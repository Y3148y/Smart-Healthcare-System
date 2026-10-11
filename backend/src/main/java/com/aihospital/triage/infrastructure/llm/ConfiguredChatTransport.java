package com.aihospital.triage.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded compatible HTTP transport with LangChain4j messages; no streaming or reasoning retention. */
final class ConfiguredChatTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    Response<AiMessage> generate(String base, String key, String model, boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages) throws Exception {
        return generate(base, key, model, thinking, maxTokens, seconds, messages, false);
    }

    Response<AiMessage> generate(String base, String key, String model, boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject) throws Exception {
        return generate(base, key, model, thinking, maxTokens, seconds, messages, jsonObject, null);
    }

    Response<AiMessage> generate(String base, String key, String model, boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject, Integer thinkingBudget) throws Exception {
        return generate(base, key, model, thinking, maxTokens, seconds, messages, jsonObject, thinkingBudget, List.of());
    }

    Response<AiMessage> generate(String base, String key, String model, boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject, Integer thinkingBudget,
            List<Map<String,Object>> tools) throws Exception {
        if (thinkingBudget != null && (!thinking || thinkingBudget < 1 || thinkingBudget > 32768))
            throw new IllegalArgumentException("Invalid thinking budget");
        URI endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Invalid model endpoint");
        var turns = new ArrayList<Map<String,Object>>();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage system) turns.add(Map.of("role","system","content",system.text()));
            else if (message instanceof UserMessage user) turns.add(Map.of("role","user","content",user.singleText()));
            else if (message instanceof AiMessage assistant) {
                var turn = new LinkedHashMap<String,Object>(); turn.put("role", "assistant");
                turn.put("content", assistant.text());
                if (assistant.hasToolExecutionRequests()) turn.put("tool_calls", assistant.toolExecutionRequests().stream()
                        .map(call -> Map.of("id", call.id(), "type", "function", "function",
                                Map.of("name", call.name(), "arguments", call.arguments()))).toList());
                turns.add(turn);
            } else if (message instanceof ToolExecutionResultMessage tool && !tools.isEmpty()) {
                turns.add(Map.of("role", "tool", "tool_call_id", tool.id(), "content", tool.text()));
            }
            else throw new IllegalArgumentException("Unsupported message type");
        }
        var body = new LinkedHashMap<String,Object>();
        body.put("model", model); body.put("messages", turns); body.put("temperature", .1);
        body.put("max_tokens", maxTokens); body.put("stream", false);
        body.put("enable_thinking", thinking);
        if (thinkingBudget != null) body.put("thinking_budget", thinkingBudget);
        if (jsonObject) body.put("response_format", Map.of("type", "json_object"));
        if (!tools.isEmpty()) {
            if (JSON.writeValueAsString(Map.of("messages", turns, "tools", tools)).length() > 30000)
                throw new IllegalArgumentException("Agent context exceeds limit");
            body.put("tools", tools); body.put("tool_choice", "auto");
        }
        // The application never retains or replays reasoning_content, in either test arm.
        body.put("preserve_thinking", false);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(seconds))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var pending = http.sendAsync(request, info -> new LimitedBodySubscriber(256 * 1024));
        try {
            // Includes body receipt, rather than timing only response headers. No retries.
            var response = pending.get(seconds, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new OpenAiHttpException(response.statusCode(), "");
            var root = JSON.readTree(response.body());
            var choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1) throw new IOException("Invalid model response");
            var choice = choices.get(0); var message = choice.path("message");
            boolean hasTools = message.hasNonNull("tool_calls");
            if ((hasTools && tools.isEmpty()) || (!hasTools && !message.path("content").isTextual()))
                throw new IOException("Invalid model content");
            FinishReason finish = switch (choice.path("finish_reason").asText()) {
                case "stop" -> FinishReason.STOP;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                case "tool_calls" -> FinishReason.TOOL_EXECUTION;
                default -> throw new IOException("Invalid model finish reason");
            };
            var usage = root.path("usage");
            TokenUsage tokens = null;
            if (usage.path("prompt_tokens").canConvertToInt() && usage.path("completion_tokens").canConvertToInt())
                tokens = new TokenUsage(usage.path("prompt_tokens").intValue(), usage.path("completion_tokens").intValue());
            if (hasTools) {
                var requested = message.path("tool_calls");
                if (finish != FinishReason.TOOL_EXECUTION || !requested.isArray() || requested.isEmpty() || requested.size() > 4)
                    throw new IOException("Invalid tool response");
                var requests = new ArrayList<ToolExecutionRequest>(); var ids = new HashSet<String>();
                for (var item : requested) {
                    var function = item.path("function"); String id = item.path("id").asText();
                    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(id) || !"function".equals(item.path("type").asText())
                            || !function.path("name").isTextual() || !function.path("arguments").isTextual()
                            || function.path("name").asText().length() > 80 || function.path("arguments").asText().length() > 2400)
                        throw new IOException("Invalid tool request");
                    requests.add(ToolExecutionRequest.builder().id(id).name(function.path("name").asText())
                            .arguments(function.path("arguments").asText()).build());
                }
                // Accompanying free text is not a checked patient answer and is deliberately discarded.
                return Response.from(AiMessage.from(requests), tokens, finish);
            }
            if (finish == FinishReason.TOOL_EXECUTION) throw new IOException("Missing tool calls");
            return Response.from(AiMessage.from(message.path("content").textValue()), tokens, finish);
        } finally { pending.cancel(true); }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maximum;
        private Flow.Subscription subscription;
        private long size;
        private boolean terminal;
        LimitedBodySubscriber(int maximum) { this.maximum = maximum; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            if (terminal) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > maximum) {
                terminal = true; subscription.cancel(); delegate.onError(new IOException("Model response exceeds limit"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!terminal) { terminal = true; delegate.onError(error); } }
        public void onComplete() { if (!terminal) { terminal = true; delegate.onComplete(); } }
    }
}
