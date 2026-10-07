package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.OpenAiHttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import javax.net.ssl.SSLException;
import static org.junit.jupiter.api.Assertions.*;

class ModelFailureDiagnosticsTest {
    @Test void outerIoInterruptionSurvivesGenericInnerIoWithoutGuessingTimeout() throws Exception {
        var interrupted = new java.io.InterruptedIOException("synthetic-secret timeout");
        interrupted.initCause(new java.io.IOException("synthetic-secret canceled"));
        interrupted.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "okhttp3.internal.connection.RealCall", "timeoutExit", "RealCall.kt", 1)});
        var failure = ModelFailureDiagnostics.classify(new RuntimeException(interrupted));
        assertEquals("MODEL_IO_INTERRUPTED", failure.code());
        assertEquals(List.of("RuntimeException", "InterruptedIOException", "IOException"), failure.causeChain());
        assertTrue(failure.locations().contains("HTTP_CLIENT_TIMEOUT_EXIT"));
        assertFalse(new ObjectMapper().writeValueAsString(failure).contains("synthetic-secret"));
        assertFalse(ModelFailureDiagnostics.patientMessage(failure).contains("超时"));
    }
    @Test void unknownClassNamesAndStackPayloadsAreNotPersistedAndOldFailureIsCompatible() throws Exception {
        var error = new RuntimeException("secret") {};
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("secret.patient", "secret", "secret", 1)});
        var failure = ModelFailureDiagnostics.classify(error);
        assertEquals("RuntimeException", failure.causeType());
        assertTrue(failure.locations().isEmpty());
        assertFalse(new ObjectMapper().writeValueAsString(failure).contains("secret"));
        var old = new ObjectMapper().readValue("{\"code\":\"MODEL_IO_ERROR\",\"causeType\":\"IOException\",\"httpStatus\":null}", AnswerEvidence.Failure.class);
        assertTrue(old.causeChain().isEmpty()); assertTrue(old.locations().isEmpty());
    }
    @Test void wrappedTimeoutIsNotGuessedFromMessage() {
        var failure=ModelFailureDiagnostics.classify(new RuntimeException(new RuntimeException(new SocketTimeoutException("sensitive body"))));
        assertEquals("MODEL_TIMEOUT",failure.code());
        assertEquals("SocketTimeoutException",failure.causeType());
        assertTrue(ModelFailureDiagnostics.patientMessage(failure).contains("超时"));
        assertEquals("MODEL_UNKNOWN_ERROR",ModelFailureDiagnostics.classify(new RuntimeException("timeout token confidential")).code());
    }
    @ParameterizedTest @CsvSource({"401,MODEL_AUTH_FAILED","403,MODEL_FORBIDDEN","429,MODEL_RATE_LIMITED","408,PROVIDER_TIMEOUT","504,PROVIDER_TIMEOUT","400,PROVIDER_HTTP_ERROR","500,PROVIDER_HTTP_ERROR"})
    void providerStatusIsRecordedWithoutResponseBody(int status,String code) throws Exception {
        var failure=ModelFailureDiagnostics.classify(new RuntimeException(new OpenAiHttpException(status,"synthetic-secret-provider-body")));
        assertEquals(code,failure.code());assertEquals(status,failure.httpStatus());
        assertFalse(new ObjectMapper().writeValueAsString(failure).contains("synthetic-secret"));
        assertFalse(ModelFailureDiagnostics.patientMessage(failure).matches("(?s).*(持续多久|观察|适合等待|没有危险信号).*"));
    }
    @Test void distinguishesConnectionDnsTlsConfigurationAndInterrupted() {
        assertEquals("MODEL_IO_ERROR",ModelFailureDiagnostics.classify(new java.io.IOException("timeout or credential must not be parsed")).code());
        assertEquals("MODEL_CONNECTION_FAILED",ModelFailureDiagnostics.classify(new ConnectException()).code());
        assertEquals("MODEL_DNS_FAILED",ModelFailureDiagnostics.classify(new UnknownHostException()).code());
        assertEquals("MODEL_TLS_FAILED",ModelFailureDiagnostics.classify(new SSLException("sensitive")).code());
        assertEquals("MODEL_CONFIGURATION_ERROR",ModelFailureDiagnostics.classify(new IllegalArgumentException()).code());
        assertEquals("MODEL_INTERRUPTED",ModelFailureDiagnostics.classify(new InterruptedException()).code());
    }
    @Test void causeCyclesTerminate() {
        var a=new RuntimeException("sensitive");var b=new RuntimeException(a);a.initCause(b);
        assertEquals("MODEL_UNKNOWN_ERROR",ModelFailureDiagnostics.classify(a).code());
    }
    @Test void legacyDiagnosticsDoNotInventFailureOrDuration() throws Exception {
        var json=new ObjectMapper();
        var old=new AnswerEvidence.Diagnostics("old","MATCHED","MODEL_UNAVAILABLE",List.of(),List.of(),List.of(),"NOT_VERIFIED");
        assertNull(old.failure());assertNull(old.elapsedMs());
        var decoded=json.readValue("{\"traceId\":\"old\",\"retrievalStatus\":\"MATCHED\",\"validationStatus\":\"MODEL_UNAVAILABLE\",\"semanticSupport\":\"NOT_VERIFIED\"}",AnswerEvidence.Diagnostics.class);
        assertNull(decoded.failure());assertNull(decoded.elapsedMs());
        assertNull(decoded.generation());
    }
}
