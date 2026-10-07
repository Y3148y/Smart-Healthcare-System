package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence.Failure;
import dev.ai4j.openai4j.OpenAiHttpException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** Classification from exception types/status only. Do not inspect provider bodies or messages. */
final class ModelFailureDiagnostics {
    private ModelFailureDiagnostics() {}
    static Failure classify(Throwable error) {
        Failure result = classifyType(error);
        var chain = new java.util.ArrayList<String>();
        var locations = new java.util.LinkedHashSet<String>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause()) {
            // Fixed labels only: never arbitrary class names, messages or full stacks.
            chain.add(typeLabel(cause));
            for (StackTraceElement frame : cause.getStackTrace()) {
                String owner = frame.getClassName();
                if (owner.equals("okhttp3.internal.connection.RealCall") && frame.getMethodName().equals("timeoutExit"))
                    locations.add("HTTP_CLIENT_TIMEOUT_EXIT");
                else if (owner.startsWith("okhttp3.")) locations.add("HTTP_TRANSPORT");
                else if (owner.startsWith("retrofit2.")) locations.add("HTTP_ADAPTER");
                else if (owner.startsWith("com.fasterxml.jackson.")) locations.add("JSON_DECODING");
                else if (owner.startsWith("dev.ai4j.openai4j.")) locations.add("PROVIDER_SDK");
            }
        }
        return new Failure(result.code(), result.causeType(), result.httpStatus(), chain, List.copyOf(locations));
    }
    private static String typeLabel(Throwable cause) {
        if (cause instanceof OpenAiHttpException) return "OpenAiHttpException";
        if (cause instanceof SocketTimeoutException) return "SocketTimeoutException";
        if (cause instanceof java.io.InterruptedIOException) return "InterruptedIOException";
        if (cause instanceof HttpTimeoutException) return "HttpTimeoutException";
        if (cause instanceof TimeoutException) return "TimeoutException";
        if (cause instanceof InterruptedException) return "InterruptedException";
        if (cause instanceof UnknownHostException) return "UnknownHostException";
        if (cause instanceof ConnectException) return "ConnectException";
        if (cause instanceof SSLException) return "SSLException";
        if (cause instanceof java.io.IOException) return "IOException";
        if (cause instanceof IllegalArgumentException) return "IllegalArgumentException";
        if (cause instanceof RuntimeException) return "RuntimeException";
        return "OtherThrowable";
    }
    private static Failure classifyType(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable last = error;
        Throwable io = null;
        for (Throwable cause = error; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause()) {
            last = cause;
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException || cause instanceof TimeoutException)
                return failure("MODEL_TIMEOUT", cause, null);
            if (cause instanceof InterruptedException) return failure("MODEL_INTERRUPTED", cause, null);
            if (cause instanceof OpenAiHttpException http) {
                String code = switch (http.code()) {
                    case 401 -> "MODEL_AUTH_FAILED";
                    case 403 -> "MODEL_FORBIDDEN";
                    case 429 -> "MODEL_RATE_LIMITED";
                    case 408, 504 -> "PROVIDER_TIMEOUT";
                    default -> "PROVIDER_HTTP_ERROR";
                };
                return failure(code, cause, http.code());
            }
            if (cause instanceof UnknownHostException) return failure("MODEL_DNS_FAILED", cause, null);
            if (cause instanceof ConnectException) return failure("MODEL_CONNECTION_FAILED", cause, null);
            if (cause instanceof SSLException) return failure("MODEL_TLS_FAILED", cause, null);
            if (cause instanceof java.io.InterruptedIOException) io = cause;
            else if (io == null && cause instanceof java.io.IOException) io = cause;
        }
        // Preserve an outer interruption even if its inner cause is generic IO.
        // Interruption alone is not proof of timeout.
        if (io instanceof java.io.InterruptedIOException) return failure("MODEL_IO_INTERRUPTED", io, null);
        if (io != null) return failure("MODEL_IO_ERROR", io, null);
        if (last instanceof IllegalArgumentException) return failure("MODEL_CONFIGURATION_ERROR", last, null);
        if (last instanceof java.io.IOException) return failure("MODEL_IO_ERROR", last, null);
        return failure("MODEL_UNKNOWN_ERROR", last, null);
    }
    private static Failure failure(String code, Throwable cause, Integer status) {
        return new Failure(code, cause == null ? "Unknown" : typeLabel(cause), status);
    }
    static String patientMessage(Failure failure) {
        if ("OUTPUT_SUPPORT_REJECTED".equals(failure.code())) return "这次生成内容未通过问题与资料依据核对，相关内容未采用。您可以继续说明需要了解的问题，或申请人工导诊。";
        if ("REVIEW_UNAVAILABLE".equals(failure.code())) return "这次回答的依据核对暂未完成，生成内容尚未采用。可以稍后重试或申请人工导诊。";
        if (failure.code().startsWith("OUTPUT_")) return "这次AI回答未通过校验，未采用生成内容。"
                + "可以稍后重试或申请人工导诊；本次失败不能作为病情判断或具体护理建议。";
        String lead = switch (failure.code()) {
            case "MODEL_TIMEOUT", "PROVIDER_TIMEOUT" -> "这次AI回答等待超时，未能完成。";
            case "OUTPUT_SCHEMA_INVALID", "OUTPUT_REFERENCE_INVALID", "OUTPUT_CONTENT_BLOCKED" -> "这次AI回答未通过校验，未采用生成内容。";
            default -> "这次AI回答暂未完成。";
        };
        return lead + "可以稍后重试或申请人工导诊；本次失败不能作为病情判断或具体护理建议。";
    }
}
