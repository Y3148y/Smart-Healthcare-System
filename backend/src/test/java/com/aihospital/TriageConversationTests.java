package com.aihospital;

import com.aihospital.shared.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TriageConversationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private final JwtService jwt = new JwtService();

    private String token(String subject) { return "Bearer " + jwt.issue(subject, "PATIENT"); }

    private String create(String auth) throws Exception {
        String body = mvc.perform(post("/api/triage/sessions").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adultConfirmed\":true,\"forSelfConfirmed\":true,\"notPregnantConfirmed\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("session").path("id").asText();
    }

    private JsonNode turn(String auth, String id, String content) throws Exception {
        String body = mvc.perform(post("/api/triage/sessions/{id}/turns", id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Message(content))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private record Message(String content) {}

    @Test
    void unsupportedPopulationCannotStartStandardTriage() throws Exception {
        mvc.perform(post("/api/triage/sessions").header("Authorization", token("minor-" + UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adultConfirmed\":false,\"forSelfConfirmed\":true,\"notPregnantConfirmed\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyIsPrivateAndSupportsFollowUpAndAssessmentVersions() throws Exception {
        String owner = token("patient-" + UUID.randomUUID());
        String other = token("another-" + UUID.randomUUID());
        String id = create(owner);

        JsonNode first = turn(owner, id, "我不舒服");
        org.junit.jupiter.api.Assertions.assertEquals("待补充信息", first.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2, first.path("messages").size());
        org.junit.jupiter.api.Assertions.assertEquals(0, first.path("assessments").size());

        JsonNode second = turn(owner, id, "头晕已经三天了，没有肢体无力和说话不清");
        org.junit.jupiter.api.Assertions.assertEquals(1, second.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals("神经内科", second.path("assessments").get(0).path("result").path("department").asText());
        JsonNode third = turn(owner, id, "今天仍然有些恶心");
        org.junit.jupiter.api.Assertions.assertEquals(2, third.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals(2, third.path("assessments").get(1).path("version").asInt());
        JsonNode firstAssessment = third.path("assessments").get(0);
        JsonNode secondAssessment = third.path("assessments").get(1);
        org.junit.jupiter.api.Assertions.assertEquals(second.path("assessments").get(0).path("result"), firstAssessment.path("result"));
        org.junit.jupiter.api.Assertions.assertFalse(firstAssessment.path("assistantMessageId").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(secondAssessment.path("assistantMessageId").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertNotEquals(firstAssessment.path("assistantMessageId").asText(), secondAssessment.path("assistantMessageId").asText());
        for (JsonNode assessment : third.path("assessments")) {
            String anchor = assessment.path("assistantMessageId").asText();
            boolean matchesOriginalAnswer = false;
            for (JsonNode message : third.path("messages"))
                if (anchor.equals(message.path("id").asText()) && "ASSISTANT".equals(message.path("role").asText())
                        && assessment.path("result").path("summary").asText().equals(message.path("content").asText()))
                    matchesOriginalAnswer = true;
            org.junit.jupiter.api.Assertions.assertTrue(matchesOriginalAnswer);
        }

        mvc.perform(get("/api/triage/sessions/{id}", id).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(6));
        mvc.perform(get("/api/triage/timeline").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].sessionId", hasItem(id)));
        mvc.perform(get("/api/triage/sessions/{id}", id).header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/triage/sessions").header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", not(hasItem(id))));
        mvc.perform(get("/api/triage/sessions/{id}/result", id).header("Authorization", other))
                .andExpect(status().isNotFound());
    }

    @Test
    void emergencySkipsFollowUpAndOrdinaryDoctorRecommendation() throws Exception {
        String owner = token("emergency-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode urgent = turn(owner, id, "突然剧烈胸痛，呼吸困难");
        org.junit.jupiter.api.Assertions.assertEquals("紧急提示", urgent.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("紧急", urgent.path("assessments").get(0).path("result").path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertTrue(urgent.path("assessments").get(0).path("result").path("doctor").isNull());
        mvc.perform(post("/api/triage/sessions/{id}/turns", id).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"还想挂号\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void mixedSymptomsReturnMultipleBookableDepartmentDoctorsAndKeepVersions() throws Exception {
        String owner = token("mixed-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode first = turn(owner, id, "头晕三天，还伴有腹痛反酸");
        org.junit.jupiter.api.Assertions.assertEquals(1, first.path("assessments").size());
        JsonNode second = turn(owner, id, "头晕反复出现，上腹部不适，没有突然肢体无力");
        JsonNode result = second.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("全科医学科", result.path("department").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.path("candidates").size());
        org.junit.jupiter.api.Assertions.assertEquals("神经内科", result.path("candidates").get(0).path("department").asText());
        org.junit.jupiter.api.Assertions.assertEquals("消化内科", result.path("candidates").get(1).path("department").asText());
        String candidateDoctor = result.path("candidates").get(0).path("doctor").path("id").asText();
        org.junit.jupiter.api.Assertions.assertFalse(candidateDoctor.isBlank());

        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("doctorId", candidateDoctor,
                                "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doctor.id").value(candidateDoctor));

        JsonNode third = turn(owner, id, "目前还是头晕和腹部不适");
        org.junit.jupiter.api.Assertions.assertEquals(3, third.path("assessments").size());
        JsonNode saved = json.readTree(mvc.perform(get("/api/triage/sessions/{id}", id).header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertEquals(3, saved.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals(candidateDoctor,
                saved.path("assessments").get(0).path("result").path("candidates").get(0).path("doctor").path("id").asText());
    }

    @Test
    void clearFirstTurnSymptomsRouteDirectlyAndMultipleSitesHaveMultipleDoctors() throws Exception {
        String owner = token("direct-" + UUID.randomUUID());
        String fracture = create(owner);
        JsonNode bone = turn(owner, fracture, "我手摔断了");
        org.junit.jupiter.api.Assertions.assertEquals(1, bone.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals("骨科", bone.path("assessments").get(0).path("result").path("department").asText());
        org.junit.jupiter.api.Assertions.assertEquals("尽快就医", bone.path("assessments").get(0).path("result").path("riskLevel").asText());

        String throat = create(owner);
        JsonNode throatGuide = turn(owner, throat, "我嗓子疼");
        org.junit.jupiter.api.Assertions.assertEquals("待补充信息", throatGuide.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, throatGuide.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertTrue(throatGuide.path("messages").get(1).path("content").asText().contains("持续多久"));
        JsonNode respiratory = turn(owner, throat, "已经三天了，没有发热和呼吸困难");
        org.junit.jupiter.api.Assertions.assertEquals("呼吸内科", respiratory.path("assessments").get(0).path("result").path("department").asText());

        String period = create(owner);
        JsonNode periodGuide = turn(owner, period, "我痛经");
        org.junit.jupiter.api.Assertions.assertEquals(0, periodGuide.path("assessments").size());
        JsonNode gynecology = turn(owner, period, "持续两天，经量和平时差不多");
        org.junit.jupiter.api.Assertions.assertEquals("妇科", gynecology.path("assessments").get(0).path("result").path("department").asText());

        JsonNode combined = turn(owner, fracture, "我嗓子疼，而且痛经");
        JsonNode candidates = combined.path("assessments").get(1).path("result").path("candidates");
        org.junit.jupiter.api.Assertions.assertEquals(3, candidates.size());
        for (JsonNode candidate : candidates)
            org.junit.jupiter.api.Assertions.assertFalse(candidate.path("doctor").path("id").asText().isBlank());
    }

    @Test
    void exposedBoneSkipsOrdinaryBooking() throws Exception {
        String owner = token("injury-" + UUID.randomUUID());
        JsonNode urgent = turn(owner, create(owner), "摔伤后骨头外露");
        org.junit.jupiter.api.Assertions.assertEquals("紧急提示", urgent.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(urgent.path("assessments").get(0).path("result").path("doctor").isNull());
    }

    @Test
    void safetyWarningDoesNotWaitForFollowUp() throws Exception {
        String owner = token("warning-" + UUID.randomUUID());
        JsonNode urgent = turn(owner, create(owner), "我突然脸肿、吞咽不了，感觉喘不上气");
        org.junit.jupiter.api.Assertions.assertEquals("紧急", urgent.path("assessments").get(0).path("result").path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertTrue(urgent.path("assessments").get(0).path("result").path("safetyTip").asText().contains("气道"));
        org.junit.jupiter.api.Assertions.assertEquals(2, urgent.path("messages").size());
    }
}
