package com.aihospital.triage.application;

import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
// Async SSE workers mutate mock headers while Boot's default print handler iterates them.
// Disable diagnostic printing only; the response/failure/concurrency assertions remain below.
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class TriageStreamFailureTest {
    @Autowired MockMvc mvc;
    @MockitoBean TriageConversationService service;

    @Test void unexpectedFailureUsesBoundedPatientMessageAndDoesNotLeakException() throws Exception {
        String sessionId = "synthetic-session";
        when(service.send(eq(sessionId), eq("synthetic-patient"), eq("test"), isNull(), any()))
                .thenThrow(new IllegalStateException("private database host and patient payload"));
        String path = "/api/triage/sessions/" + sessionId + "/turns/stream";
        var started = mvc.perform(post(path)
                        .header("Authorization", "Bearer " + new JwtService().issue("synthetic-patient", "PATIENT"))
                        .contentType("application/json").content("{\"content\":\"test\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        String body = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(body.contains("event:failure"));
        assertTrue(body.contains("本次处理未完成，请刷新会话核对保存状态"));
        assertFalse(body.contains("private database host"));
        assertFalse(body.contains("synthetic-patient"));
        verify(service).send(eq(sessionId), eq("synthetic-patient"), eq("test"), isNull(), any());
    }

    @Test void saturatedProgressWorkersRejectNewRequestWithoutQueueingOrLeakingDetails() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic test timeout");
            throw new IllegalStateException("private saturated worker detail");
        }).when(service).send(anyString(), anyString(), anyString(), isNull(), any());

        List<MvcResult> running = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                running.add(mvc.perform(post("/api/triage/sessions/saturated-" + index + "/turns/stream")
                                .header("Authorization", "Bearer " + new JwtService().issue("patient-" + index, "PATIENT"))
                                .contentType("application/json").content("{\"content\":\"test\"}"))
                        .andExpect(request().asyncStarted()).andReturn());
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS), "all bounded workers should be occupied");
            var rejected = mvc.perform(post("/api/triage/sessions/saturated-5/turns/stream")
                            .header("Authorization", "Bearer " + new JwtService().issue("patient-5", "PATIENT"))
                            .contentType("application/json").content("{\"content\":\"test\"}"))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
            assertTrue(rejected.contains("问诊服务繁忙，请稍后重试"));
            assertFalse(rejected.contains("private saturated worker detail"));
        } finally {
            release.countDown();
        }
        for (var request : running) {
            request.getAsyncResult(10_000);
            mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        }
    }
}
