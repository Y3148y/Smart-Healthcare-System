package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminKnowledgeWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeCatalog catalog;
    private final JwtService jwt = new JwtService();
    private String admin() { return "Bearer " + jwt.issue("系统管理员", "ADMIN"); }

    @Test void uploadPreviewApprovalAndUnavailableIndexRemainDistinct() throws Exception {
        var file = new MockMultipartFile("file", "workflow.md", "text/markdown",
                "测试专属资料：观察轻微手腕不适的持续时间并咨询医生。".getBytes(StandardCharsets.UTF_8));
        var response = mvc.perform(multipart("/api/admin/knowledge/upload").file(file).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(response).path("id").asText();
        mvc.perform(get("/api/admin/knowledge/" + id + "/details").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("管理员录入/本地上传"))
                .andExpect(jsonPath("$.segments[0].excerpt").isNotEmpty())
                .andExpect(jsonPath("$.indexStatus").value("NOT_APPROVED"));
        assertTrue(catalog.retrieve("测试专属资料", 3, 0.28).evidence().stream().noneMatch(e -> e.title().equals("workflow.md")));
        mvc.perform(post("/api/admin/knowledge/" + id + "/approve").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/api/admin/knowledge/" + id + "/details").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indexStatus").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.indexedChunks").value(0));
        mvc.perform(post("/api/admin/knowledge/index/sync").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value("false"))
                .andExpect(jsonPath("$.status").value("NOT_CONFIGURED"));
    }

    @Test void adminEndpointsRejectPatientAndUnauthenticatedRequests() throws Exception {
        String patient = "Bearer " + jwt.issue("张三", "PATIENT");
        for (String path : new String[]{"/api/admin/knowledge/kd101/details", "/api/admin/knowledge/search/details?q=咳嗽",
                "/api/admin/knowledge/retrieval-events", "/api/admin/knowledge/runtime",
                "/api/admin/knowledge/kd101/metadata", "/api/admin/knowledge/kd101/chunks"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", patient)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/knowledge/index/sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/knowledge/index/sync").header("Authorization", patient)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/knowledge/documents").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/knowledge/documents").contentType("application/json").content("{}")
                .header("Authorization", patient)).andExpect(status().isForbidden());
    }

    @Test void debugProducesRequestLocalCandidatesAndBoundedMetadataWithoutPatientText() throws Exception {
        mvc.perform(get("/api/admin/knowledge/search/details").param("q", "咳嗽").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("LOCAL_BM25_UNRERANKED"))
                .andExpect(jsonPath("$.candidates[0].lexicalRank").isNumber())
                .andExpect(jsonPath("$.rerankStatus").value("NOT_CONFIGURED"));
        for (int i = 0; i < 105; i++) catalog.retrieve("not-persisted-query-" + i, 3, 0.28);
        var response = mvc.perform(get("/api/admin/knowledge/retrieval-events").header("Authorization", admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(100, json.readTree(response).size());
        assertFalse(response.contains("not-persisted-query"));
        assertFalse(response.contains("excerpt"));
        var runtime = mvc.perform(get("/api/admin/knowledge/runtime").header("Authorization", admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(runtime.contains("api-key")); assertFalse(runtime.contains("Authorization"));
    }

    @Test void invalidUploadsAndMissingDocumentsFailClearly() throws Exception {
        mvc.perform(multipart("/api/admin/knowledge/upload").file(new MockMultipartFile("file", "bad.pdf", "application/pdf", new byte[]{1}))
                .header("Authorization", admin())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/knowledge/upload").file(new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{(byte)0xff}))
                .header("Authorization", admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/knowledge/missing/details").header("Authorization", admin())).andExpect(status().isBadRequest());
    }
}
