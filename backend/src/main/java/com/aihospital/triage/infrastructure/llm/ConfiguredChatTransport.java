package com.aihospital.triage.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Optional provider parameters unavailable in the pinned SDK; not an Agent or streaming path. */
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
        if (thinkingBudget != null && (!thinking || thinkingBudget < 1 || thinkingBudget > 32768))
            throw new IllegalArgumentException("Invalid thinking budget");
        URI endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Invalid model endpoint");
        var turns = new ArrayList<Map<String,String>>();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage system) turns.add(Map.of("role","system","content",system.text()));
            else if (message instanceof UserMessage user) turns.add(Map.of("role","user","content",user.singleText()));
            else if (message instanceof AiMessage assistant) turns.add(Map.of("role","assistant","content",assistant.text()));
            else throw new IllegalArgumentException("Unsupported message type");
        }
        var body = new LinkedHashMap<String,Object>();
        body.put("model", model); body.put("messages", turns); body.put("temperature", .1);
        body.put("max_tokens", maxTokens); body.put("stream", false);
        body.put("enable_thinking", thinking);
        if (thinkingBudget != null) body.put("thinking_budget", thinkingBudget);
        if (jsonObject) body.put("response_format", Map.of("type", "json_object"));
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
            if (message.hasNonNull("tool_calls") || !message.path("content").isTextual())
                throw new IOException("Invalid model content");
            FinishReason finish = switch (choice.path("finish_reason").asText()) {
                case "stop" -> FinishReason.STOP;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                default -> throw new IOException("Invalid model finish reason");
            };
            var usage = root.path("usage");
            TokenUsage tokens = null;
            if (usage.path("prompt_tokens").canConvertToInt() && usage.path("completion_tokens").canConvertToInt())
                tokens = new TokenUsage(usage.path("prompt_tokens").intValue(), usage.path("completion_tokens").intValue());
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
