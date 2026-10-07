package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.AnswerEvidence.*;
import com.aihospital.triage.domain.NarrationModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract/fault tests with a synthetic reviewer; does not measure model judgement accuracy. */
class AnswerSupportReviewerTest {
    static final String PASS = "{\"claimsSupported\":true,\"conditionsPreserved\":true,\"onTopic\":true,\"noServiceClaims\":true,\"referencesCorrect\":true,\"questionsJustified\":true}";
    static String decision(String code) {
        if (code == null) return PASS;
        var fields = Map.of("UNSUPPORTED_CLAIM", "claimsSupported", "CONDITION_NOT_ESTABLISHED", "conditionsPreserved",
                "OFF_TOPIC", "onTopic", "SERVICE_CONTENT", "noServiceClaims", "WRONG_REFERENCE", "referencesCorrect",
                "QUESTION_NOT_JUSTIFIED", "questionsJustified");
        return PASS.replace("\"" + fields.get(code) + "\":true", "\"" + fields.get(code) + "\":false");
    }
    private final ObjectMapper json = new ObjectMapper();
    @Test void fixedChecksRequireEveryBooleanAndServerDerivesDecision() throws Exception {
        assertEquals("PASSED", AnswerSupportReviewer.parse(PASS, draft).status());
        for (String code : List.of("UNSUPPORTED_CLAIM", "CONDITION_NOT_ESTABLISHED", "OFF_TOPIC",
                "SERVICE_CONTENT", "WRONG_REFERENCE", "QUESTION_NOT_JUSTIFIED")) {
            var result = AnswerSupportReviewer.parse(decision(code), draft);
            assertEquals("REJECTED", result.status());
            assertEquals(List.of(code), result.issues());
        }
        for (String invalid : List.of(PASS.replace("true", "null"), PASS.replace("true", "1"),
                PASS.replace("true", "\"true\""), PASS.replace("\"claimsSupported\":true,", ""),
                PASS.replace("\"claimsSupported\":true", "\"claimsSupported\":false,\"claimsSupported\":true"),
                PASS + " {}", "```json\n" + PASS + "\n```"))
            assertThrows(Exception.class, () -> AnswerSupportReviewer.parse(invalid, draft));
    }
    private final Draft draft = new Draft(List.of(new Paragraph("资料中的一般信息。", "GENERAL_INFORMATION", List.of("E1"))), List.of(), List.of());
    private final List<Reference> refs = List.of(new Reference("E1", "资料", "source", "资料中的一般信息。", "hash", "UNKNOWN"));

    @Test void inconsistentOrUnboundedDecisionsCannotPass() {
        for (String raw : List.of("null", "{}", "{\"status\":\"PASSED\",\"issues\":null}",
                "{\"status\":\"REJECTED\",\"issues\":[]}", "{\"status\":\"PASSED\",\"issues\":[],\"extra\":true}",
                "{\"status\":\"PASSED\",\"issues\":[]} trailing",
                "{\"status\":\"REJECTED\",\"issues\":[{\"part\":\"paragraph\",\"index\":9,\"code\":\"OFF_TOPIC\"}]}",
                "{\"status\":\"REJECTED\",\"issues\":[{\"part\":\"paragraph\",\"index\":0,\"code\":\"ARBITRARY_SECRET\"}]}"))
            assertThrows(Exception.class, () -> AnswerSupportReviewer.parse(raw, draft));
    }

    @Test void reviewerGetsOnlyBoundedPatientStatementsAndExactEvidence() {
        var history = new ArrayList<NarrationModel.Turn>();
        for (int i=0;i<7;i++) {
            history.add(new NarrationModel.Turn("USER", "patient-" + i + "x".repeat(790)));
            history.add(new NarrationModel.Turn("ASSISTANT", "assistant-invented-stability"));
        }
        var review = AnswerSupportReviewer.review("current-request", history, refs, draft, false, messages -> {
            assertEquals(2,messages.size());
            String payload=((UserMessage)messages.get(1)).singleText();
            assertFalse(payload.contains("patient-0")); assertFalse(payload.contains("patient-1"));
            assertTrue(payload.contains("patient-2")); assertTrue(payload.contains("patient-6"));
            assertFalse(payload.contains("assistant-invented-stability"));
            assertTrue(payload.contains("UNKNOWN")); assertTrue(payload.contains("current-request"));
            assertTrue(((SystemMessage) messages.get(0)).text().contains(MedicalAnswerInstructions.task("current-request")));
            return Response.from(AiMessage.from(PASS),new TokenUsage(7,9),FinishReason.STOP);
        });
        assertEquals("PASSED",review.status()); assertEquals(7,review.inputTokens()); assertEquals(9,review.outputTokens());
    }

    @Test void reviewTimeoutOrMalformedReplyNeverMeansPassed() {
        var timeout=AnswerSupportReviewer.review("question",List.of(),refs,draft,false,messages->{throw new java.util.concurrent.TimeoutException("not logged");});
        assertEquals("UNAVAILABLE",timeout.status()); assertEquals("MODEL_TIMEOUT",timeout.failure().code());
        var invalid=AnswerSupportReviewer.review("question",List.of(),refs,draft,false,messages->Response.from(AiMessage.from("secret text"),null,FinishReason.STOP));
        assertEquals("UNAVAILABLE",invalid.status()); assertEquals("REVIEW_JSON_INVALID",invalid.failure().code());
        assertFalse(invalid.toString().contains("secret text"));
        var truncated=AnswerSupportReviewer.review("question",List.of(),refs,draft,false,messages->Response.from(AiMessage.from("{\"status\":\"PASSED\",\"issues\":[]}"),null,FinishReason.LENGTH));
        assertEquals("UNAVAILABLE",truncated.status());
        assertEquals("REVIEW_TRUNCATED",truncated.failure().code());
    }

    @Test void invalidResponseDiagnosticsAreSpecificAndContainNoRawContent() {
        var cases = Map.of(
                "{\"status\":\"PASSED\",\"issues\":[],\"secret\":\"patient-private\"}", "REVIEW_UNKNOWN_FIELD",
                "null", "REVIEW_DECISION_INVALID",
                "{}", "REVIEW_CHECK_INVALID",
                PASS.replace("true", "\"patient-private\""), "REVIEW_CHECK_INVALID",
                " ", "REVIEW_EMPTY_RESPONSE", "x".repeat(8001), "REVIEW_SIZE_INVALID");
        cases.forEach((raw, code) -> {
            var result = AnswerSupportReviewer.review("question", List.of(), refs, draft, false,
                    messages -> Response.from(AiMessage.from(raw), null, FinishReason.STOP));
            assertEquals("UNAVAILABLE", result.status());
            assertEquals(code, result.failure().code());
            assertFalse(result.toString().contains("patient-private"));
        });
        var empty = AnswerSupportReviewer.review("question", List.of(), refs, draft, false, messages -> null);
        assertEquals("REVIEW_EMPTY_RESPONSE", empty.failure().code());
    }

    @Test void productionAdapterEnforcesSeparateReviewAndKeepsServiceFactsOutOfModel() throws Exception {
        var issue=new AtomicReference<>("CONDITION_NOT_ESTABLISHED");
        var generated=new AtomicReference<>("您目前属于稳定表现。");
        var reviewStatus=new AtomicInteger(200); var generationCalls=new AtomicInteger(); var reviewCalls=new AtomicInteger();
        var requests=new ArrayList<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            String sent=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); requests.add(sent);
            boolean reviewing=sent.contains("<review_data>");
            if(reviewing)reviewCalls.incrementAndGet();else generationCalls.incrementAndGet();
            String content=reviewing ? decision(issue.get())
                    : json.writeValueAsString(new Draft(List.of(new Paragraph(generated.get(),"GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of()));
            byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))),"usage",Map.of("prompt_tokens",12,"completion_tokens",14)));
            exchange.sendResponseHeaders(reviewing?reviewStatus.get():200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var model=new OptionalNarrationModel();
            ReflectionTestUtils.setField(model,"mode","openai-compatible");
            ReflectionTestUtils.setField(model,"apiKey","synthetic-key");
            ReflectionTestUtils.setField(model,"model","synthetic");
            ReflectionTestUtils.setField(model,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            ReflectionTestUtils.setField(model,"enableThinking","true");
            ReflectionTestUtils.setField(model,"structuredJson",true);
            ReflectionTestUtils.setField(model,"timeoutSeconds",3);
            var services=new NarrationModel.ServiceContext(List.of(new NarrationModel.ServiceState("测试目录","AVAILABLE","synthetic-backend-slot-fact")),true);
            for(String code:List.of("CONDITION_NOT_ESTABLISHED","UNSUPPORTED_CLAIM","OFF_TOPIC","SERVICE_CONTENT","WRONG_REFERENCE")) {
                issue.set(code);
                var answer=model.answerWithEvidence("想预约","","",List.of(new Evidence("资料","source","条件性资料。",.8)),"fallback",List.of(),services,false,"MATCHED");
                assertEquals("VALIDATION_BLOCKED",answer.status());
                assertEquals("OUTPUT_SUPPORT_REJECTED",answer.diagnostics().failure().code());
                assertTrue(answer.diagnostics().adoptedReferenceIds().isEmpty());
                assertEquals(List.of(code),answer.diagnostics().supportReview().issueCodes());
                assertFalse(answer.text().contains("稳定表现"));
                assertEquals(List.of("测试目录：synthetic-backend-slot-fact"),answer.diagnostics().serviceNotice().messages());
                assertTrue(answer.diagnostics().elapsedMs()>=answer.diagnostics().supportReview().elapsedMs());
                assertEquals(false,answer.diagnostics().supportReview().thinkingEnabled());
                assertEquals(answer.diagnostics(),json.readValue(json.writeValueAsString(answer.diagnostics()),Diagnostics.class));
            }
            issue.set(null);generated.set("资料中的一般信息。");
            var accepted=model.answerWithEvidence("只问一般问题","","",List.of(new Evidence("资料","source","资料中的一般信息。",.8)),"fallback",List.of(),services,true,"MATCHED");
            assertEquals("LIVE",accepted.status()); assertNull(accepted.diagnostics().serviceNotice());
            assertEquals("MODEL_REVIEW_PASSED",accepted.diagnostics().semanticSupport());
            reviewStatus.set(429);
            var unavailable=model.answerWithEvidence("question","","",List.of(new Evidence("资料","source","资料中的一般信息。",.8)),"fallback",List.of(),null,true,"MATCHED");
            assertEquals("REVIEW_UNAVAILABLE",unavailable.diagnostics().failure().code());
            assertEquals(429,unavailable.diagnostics().supportReview().failure().httpStatus());
            assertTrue(unavailable.diagnostics().adoptedReferenceIds().isEmpty());
            assertEquals(7,generationCalls.get());assertEquals(7,reviewCalls.get()); // no hidden retries
            for(String sent:requests) {
                assertFalse(sent.contains("synthetic-backend-slot-fact"));
                if(sent.contains("<review_data>")) {
                    assertFalse(json.readTree(sent).path("enable_thinking").booleanValue());
                    assertEquals("synthetic",json.readTree(sent).path("model").asText());
                    assertFalse(json.readTree(sent).has("thinking_budget"));
                }
            }
        } finally {server.stop(0);}
    }
}
