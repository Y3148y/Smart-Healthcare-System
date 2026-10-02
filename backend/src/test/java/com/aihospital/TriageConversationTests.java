package com.aihospital;

import com.aihospital.shared.security.JwtService;
import com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TriageConversationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private final JwtService jwt = new JwtService();

    private String token(String subject) { return "Bearer " + jwt.issue(subject, "PATIENT"); }

    /**
     * D6. Whether the suite runs against the demo narration or a live provider is a profile
     * choice, so assertions about the provider label must ask the active profile instead of
     * assuming demo. Mode-independent behaviour is asserted unconditionally elsewhere.
     */
    @Autowired org.springframework.core.env.Environment env;

    private boolean demoProfileActive() { return "demo".equals(env.getProperty("ai.mode", "demo")); }

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
        mvc.perform(post("/api/appointments").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of(
                        "doctorId", "d1", "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
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

        String multiSession = create(owner);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, multiSession, "我嗓子疼，而且痛经").path("assessments").size());
        JsonNode multiSite = turn(owner, multiSession, "都持续三天了，没有发热和呼吸困难，经量和平时差不多");
        JsonNode multiRisk = multiSite.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("多科室参考", multiRisk.path("riskLevel").asText());
        JsonNode candidates = multiRisk.path("candidates");
        org.junit.jupiter.api.Assertions.assertTrue(candidates.size() >= 2);
        for (JsonNode candidate : candidates)
            org.junit.jupiter.api.Assertions.assertFalse(candidate.path("doctor").path("id").asText().isBlank());
    }

    @Test
    void urgentDispositionBlocksBookingAndReportsItsOwnSessionStatus() throws Exception {
        String owner = token("urgent-blocking-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode bone = turn(owner, id, "我手摔断了");
        JsonNode result = bone.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("尽快就医", result.path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertEquals("建议尽快就医", bone.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(result.path("doctor").isNull()
                || result.path("doctor").path("id").asText().isBlank());
        for (JsonNode candidate : result.path("candidates"))
            org.junit.jupiter.api.Assertions.assertTrue(candidate.path("doctor").isNull()
                    || candidate.path("doctor").path("id").asText().isBlank());
        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("doctorId", "d1",
                                "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
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

    /** D2: facial swelling on its own must produce a safety signal without any airway phrase. */
    @Test
    void dentalFacialSwellingAloneIsFlaggedUrgentAndNotBookable() throws Exception {
        String owner = token("face-alone-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode first = turn(owner, id, "智齿发炎，我的脸都肿起来了");
        org.junit.jupiter.api.Assertions.assertEquals(1, first.path("assessments").size(), first.toString());
        JsonNode result = first.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("尽快就医", result.path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertEquals("建议尽快就医", first.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(result.path("doctor").isNull()
                || result.path("doctor").path("id").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertTrue(result.path("safetyAssessment").path("humanReviewRecommended").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(result.path("safetyAssessment").path("stopRoutineFlow").asBoolean());
    }

    @Test
    void patientCanRequestHumanReviewAndAdminCanProcessQueue() throws Exception {
        String owner = token("human-" + UUID.randomUUID());
        String id = create(owner);
        turn(owner, id, "我头晕三天并且恶心，没有胸痛");
        String created = mvc.perform(post("/api/triage/sessions/{id}/human-review", id)
                        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"希望人工确认就医方向\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String requestId = json.readTree(created).path("id").asText();
        mvc.perform(get("/api/admin/human-reviews").header("Authorization", owner))
                .andExpect(status().isForbidden());
        String admin = "Bearer " + jwt.issue("系统管理员", "ADMIN");
        mvc.perform(get("/api/admin/human-reviews").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(requestId)));
        mvc.perform(patch("/api/admin/human-reviews/{id}", requestId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/triage/sessions/{id}", id).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.humanReview.status").value("ACCEPTED"));
    }

    @Test
    void mcpToolsUseRealDataAndRejectMissingMetadata() throws Exception {
        String admin = "Bearer " + jwt.issue("系统管理员", "ADMIN");
        String metadata = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"},"
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
        mvc.perform(post("/mcp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + metadata + "}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/mcp").header("Authorization", admin)
                        .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call")
                        .header("Mcp-Name", "doctor_schedule_search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                                + metadata + ",\"name\":\"doctor_schedule_search\",\"arguments\":{\"department\":\"骨科\"}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent[0].department").value("骨科"));
    }

    @Test
    void knowledgeApprovalIsAdminOnlyAndPublishesPendingDocument() throws Exception {
        String admin = "Bearer " + jwt.issue("系统管理员", "ADMIN");
        String patient = token("knowledge-" + UUID.randomUUID());
        String title = "新增骨折审核资料" + UUID.randomUUID().toString().substring(0, 8);
        String created = mvc.perform(post("/api/admin/knowledge").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("title", title,
                                "body", "膝关节骨折与骨折部位需要由医生检查评估。"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(created).path("id").asText();
        mvc.perform(post("/api/admin/knowledge/{id}/approve", id).header("Authorization", patient))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/knowledge/{id}/approve", id).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/api/admin/knowledge/search").header("Authorization", admin)
                        .param("q", "膝关节骨折"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].title", hasItem(title)));
    }

    /**
     * Pins the user-facing refusal verbatim.  The expected text is intentionally
     * duplicated here instead of referencing {@code RuleBasedTriageEngine.PRESCRIPTION_REFUSAL}:
     * a shared constant would make the assertion tautological, so any change to the
     * refusal wording would silently pass.  A compliance refusal is a guarantee, not
     * narration, so its text and status must not drift.
     */
    private static final String EXPECTED_PRESCRIPTION_REFUSAL =
            "本演示系统不提供诊断、处方或药物建议，亦不能自动生成治疗方案。"
            + "你希望判断就医方向或生成预约，请补充最主要的不适、持续时间和变化；"
            + "如果需要人工协助，可在会话页选择“需要人工导诊？提交申请”（演示系统仅记录申请，不保证实时响应）。";

    @Test
    void prescriptionRequestIsRefusedVerbatimAndOffersNoDepartmentOrBooking() throws Exception {
        String owner = token("prescription-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode conversation = turn(owner, id, "我最近老胃疼，想吃点药开点处方");
        JsonNode reply = conversation.path("messages").path(conversation.path("messages").size() - 1);

        org.junit.jupiter.api.Assertions.assertEquals(EXPECTED_PRESCRIPTION_REFUSAL,
                reply.path("content").asText(), "拒答文案必须逐字一致，不得由模型改写");
        org.junit.jupiter.api.Assertions.assertEquals("POLICY_REFUSAL",
                reply.path("provenance").path("modelStatus").asText(), "拒答必须来自确定性策略而非模型");
        org.junit.jupiter.api.Assertions.assertEquals(0, reply.path("provenance").path("localToolCalls").asInt(),
                "拒答不得触发任何本地工具调用");
        org.junit.jupiter.api.Assertions.assertEquals(0, conversation.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals("待补充信息", conversation.path("session").path("status").asText());

        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "doctorId", "d1", "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
    }

    @Test
    void complianceRefusalIsAuditedAsComplianceAndNeverAsOrdinaryGuidance() throws Exception {
        String owner = token("refusal-audit-" + UUID.randomUUID());
        turn(owner, create(owner), "我最近老胃疼，想吃点药开点处方");

        String admin = "Bearer " + jwt.issue("系统管理员", "ADMIN");
        String body = mvc.perform(get("/api/admin/calls").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode compliance = null;
        for (JsonNode entry : json.readTree(body)) {
            if ("合规拒答".equals(entry.path("purpose").asText())) { compliance = entry; break; }
            org.junit.jupiter.api.Assertions.assertFalse(
                    "预问诊引导".equals(entry.path("purpose").asText())
                            && "POLICY_REFUSAL".equals(entry.path("model").asText()),
                    "合规拒答不得被记成普通追问引导，否则审计链断裂：" + entry);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(compliance, "应存在合规拒答记录");
        org.junit.jupiter.api.Assertions.assertEquals("POLICY_REFUSAL", compliance.path("model").asText());
        org.junit.jupiter.api.Assertions.assertTrue(compliance.path("success").asBoolean(),
                "合规拒答是策略成功执行，不是失败");
        org.junit.jupiter.api.Assertions.assertEquals(0, compliance.path("tools").size());
    }

    @Test
    void diagnosisIntentPatternDoesNotSwallowOrdinaryTriageQuestions() {
        RuleBasedTriageEngine engine = new RuleBasedTriageEngine(null, null, null, null, null, null, null);
        for (String benign : new String[]{"我头痛三天，想挂号", "帮我看看化验单", "我肚子疼，拉肚子"}) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    engine.requiresHumanHandover(benign), "不应误判为问诊诉求：" + benign);
        }
        for (String asking : new String[]{"开点处方", "帮我看看是不是胃癌", "这个能治吗", "用什么药",
                "这是不是慢性胃炎"}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    engine.requiresHumanHandover(asking), "应识别为问诊诉求：" + asking);
        }
    }

    @Test
    void unmappedNasalSymptomsStayInConversationWithoutBooking() throws Exception {
        String owner = token("nasal-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode first = turn(owner, id, "流鼻涕");
        String firstReply = first.path("messages").get(1).path("content").asText();
        org.junit.jupiter.api.Assertions.assertFalse(firstReply.isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(firstReply.contains("知识库没有检索到"));
        org.junit.jupiter.api.Assertions.assertEquals(0, first.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertTrue(first.path("messages").get(1).path("provenance").path("knowledgeHits").asInt() > 0);
        // D6: the mode-independent contract is that the reply is produced by a configured
        // narration path rather than by an ungrounded fallback. The literal provider label is a
        // property of the active profile, so it is asserted only when demo mode is active.
        String firstStatus = first.path("messages").get(1).path("provenance").path("modelStatus").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals("DEMO_UNGROUNDED", firstStatus);
        if (demoProfileActive())
            org.junit.jupiter.api.Assertions.assertEquals("DEMO", firstStatus);

        JsonNode second = turn(owner, id, "流鼻涕啊，有什么好说的");
        String secondReply = second.path("messages").get(3).path("content").asText();
        org.junit.jupiter.api.Assertions.assertFalse(secondReply.isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(secondReply.contains("知识库没有检索到"));
        org.junit.jupiter.api.Assertions.assertEquals(0, second.path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals(1, second.path("messages").get(3).path("provenance").path("localToolCalls").asInt());
    }

    @Test
    void explicitBookingForUnmappedNasalSymptomsDoesNotInventBookableDirection() throws Exception {
        String owner = token("nasal-booking-" + UUID.randomUUID());
        String id = create(owner);
        org.junit.jupiter.api.Assertions.assertEquals(0, turn(owner, id, "我流鼻涕").path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, id, "没有发热，鼻塞从昨晚开始").path("assessments").size());
        JsonNode bookedDirection = turn(owner, id, "太难受了，我要挂号");
        org.junit.jupiter.api.Assertions.assertEquals("待补充信息", bookedDirection.path("session").path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, bookedDirection.path("assessments").size());
        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("doctorId", "d1",
                                "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownOrNegatedNasalSymptomDoesNotCreateBookingRecommendation() throws Exception {
        String owner = token("nasal-negated-" + UUID.randomUUID());
        String id = create(owner);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, id, "我没有流鼻涕，只是想挂号").path("assessments").size());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, id, "我要挂号").path("assessments").size());
    }

    @Test
    void suspectedFoodReactionWithGeneralizedRashStopsRoutineBooking() throws Exception {
        String owner = token("allergy-warning-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode conversation = turn(owner, id, "我食物过敏了，现在全身好多红肿，好痒，想挂号");
        org.junit.jupiter.api.Assertions.assertEquals("紧急提示", conversation.path("session").path("status").asText());
        JsonNode result = conversation.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("紧急", result.path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertEquals("SAFETY_RULE", result.path("modelStatus").asText());
        org.junit.jupiter.api.Assertions.assertTrue(result.path("doctor").isNull());
        org.junit.jupiter.api.Assertions.assertEquals("ER-ALLERGY-001",
                result.path("safetyAssessment").path("signals").get(0).path("ruleCode").asText());
        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("doctorId", "doc-general",
                                "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
    }

    @Test
    void negatedOrLocalRashDoesNotTriggerGeneralizedFoodReactionRule() throws Exception {
        String owner = token("allergy-negative-" + UUID.randomUUID());
        String local = create(owner);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, local, "不是食物过敏，只是胳膊上一小块红点").path("assessments").size());
        String negated = create(owner);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                turn(owner, negated, "我食物过敏了，但没有全身红疹").path("assessments").size());
    }

    /**
     * D3, ruling Q2 recut to A. The pregnancy exclusion is a self-attestation captured once at
     * session creation, so this positive case must go through the real conversation send path:
     * the session was created with notPregnantConfirmed=true and only later contradicts it.
     * An assess()-only test cannot prove this, because the interesting part is that send()
     * performs no pregnancy recheck.
     */
    @Test
    void pregnancyRedFlagInLaterTurnStillStopsRoutineBooking() throws Exception {
        String owner = token("pregnancy-positive-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode conversation = turn(owner, id, "我怀孕八周突然剧烈腹痛");

        org.junit.jupiter.api.Assertions.assertEquals("紧急提示", conversation.path("session").path("status").asText());
        JsonNode result = conversation.path("assessments").get(0).path("result");
        org.junit.jupiter.api.Assertions.assertEquals("紧急", result.path("riskLevel").asText());
        org.junit.jupiter.api.Assertions.assertEquals("SAFETY_RULE", result.path("modelStatus").asText());
        org.junit.jupiter.api.Assertions.assertTrue(result.path("doctor").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(ruleCodes(result).contains("ER-PREGNANCY-001"),
                "孕产急症信号必须命中，实际规则码: " + ruleCodes(result));
        mvc.perform(post("/api/appointments").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("doctorId", "doc-general",
                                "sessionId", id, "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
    }

    /**
     * D10. The comma used to be an invisible barrier between a site and its symptom, because
     * {@code assess} split on punctuation before any rule ran. Evaluation is now two-step, so
     * this phrasing escalates instead of degrading. This test was previously pinning the
     * degraded behaviour on purpose; the pin is retired here and the matrix lives in
     * RuleBasedTriageEngineTest.
     */
    @Test
    void commaSeparatedPregnancyRedFlagEscalatesToEmergency() {
        var assessment = new com.aihospital.triage.domain.TriageSafetyPolicy()
                .assess("我怀孕八周，突然剧烈腹痛");
        org.junit.jupiter.api.Assertions.assertEquals("EMERGENCY", assessment.acuity());
        org.junit.jupiter.api.Assertions.assertTrue(assessment.stopRoutineFlow());
        org.junit.jupiter.api.Assertions.assertTrue(assessment.signals().stream()
                .anyMatch(signal -> "ER-PREGNANCY-001".equals(signal.ruleCode())),
                "必须命中孕产急症规则，实际: "
                        + assessment.signals().stream().map(s -> s.ruleCode()).toList());
    }

    /**
     * D3 negative case. Asserted on the rule code rather than on acuity: a plain "not urgent"
     * assertion would be satisfied by any unrelated safety rule and would therefore pass even
     * if ER-PREGNANCY-001 had fired spuriously.
     */
    @Test
    void negatedPregnancyStatementDoesNotTriggerPregnancyRule() throws Exception {
        String owner = token("pregnancy-negative-" + UUID.randomUUID());
        String id = create(owner);
        JsonNode conversation = turn(owner, id, "我没有怀孕，昨天开始轻微腹痛，今天还在痛，想咨询一下");

        for (JsonNode assessment : conversation.path("assessments"))
            org.junit.jupiter.api.Assertions.assertFalse(
                    ruleCodes(assessment.path("result")).contains("ER-PREGNANCY-001"),
                    "否定孕产状态不得触发 ER-PREGNANCY-001，实际规则码: "
                            + ruleCodes(assessment.path("result")));
    }

    private static java.util.List<String> ruleCodes(JsonNode result) {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (JsonNode signal : result.path("safetyAssessment").path("signals"))
            codes.add(signal.path("ruleCode").asText());
        return codes;
    }
}
