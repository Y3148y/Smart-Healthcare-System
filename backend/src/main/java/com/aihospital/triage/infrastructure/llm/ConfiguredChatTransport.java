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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded compatible HTTP transport. Streamed drafts remain private until the answer pipeline checks them. */
final class ConfiguredChatTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    enum ResponseIssue {
        OUTPUT_TRUNCATED, OUTPUT_PROVIDER_FILTERED,
        MODEL_RESPONSE_JSON_INVALID, MODEL_RESPONSE_CHOICES_INVALID, MODEL_RESPONSE_FINISH_REASON_INVALID,
        MODEL_RESPONSE_CONTENT_INVALID, MODEL_RESPONSE_UNEXPECTED_TOOL_CALLS,
        MODEL_RESPONSE_TOOL_CALLS_INVALID, MODEL_RESPONSE_TOOL_CALLS_MISSING, MODEL_RESPONSE_TOO_LARGE
    }
    /** Fixed diagnostics only; never retain provider content or reasoning in an exception. */
    static final class InvalidResponse extends IOException {
        final ResponseIssue issue;
        final FinishReason finishReason;
        final TokenUsage usage;
        InvalidResponse(ResponseIssue issue) { this(issue, null, null); }
        InvalidResponse(ResponseIssue issue, FinishReason finishReason, TokenUsage usage) {
            this.issue = issue; this.finishReason = finishReason; this.usage = usage;
        }
    }
    static InvalidResponse responseFailure(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause())
            if (cause instanceof InvalidResponse response) return response;
        return null;
    }
    /** Shared wire representation includes tool arguments and results in the input budget. */
    static List<Map<String,Object>> wireMessages(List<ChatMessage> messages){
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
            } else if (message instanceof ToolExecutionResultMessage tool) {
                turns.add(Map.of("role", "tool", "tool_call_id", tool.id(), "content", tool.text()));
            }
            else throw new IllegalArgumentException("Unsupported message type");
        }
        return turns;
    }
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
        return generate(base, key, model, thinking, maxTokens, seconds, messages, jsonObject, thinkingBudget,
                tools, false);
    }

    Response<AiMessage> generate(String base, String key, String model, boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject, Integer thinkingBudget,
            List<Map<String,Object>> tools, boolean forbidToolCalls) throws Exception {
        return generateInternal(base,key,model,thinking,maxTokens,seconds,messages,jsonObject,thinkingBudget,tools,forbidToolCalls,false);
    }

    Response<AiMessage> generateStreaming(String base, String key, String model, Boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject, Integer thinkingBudget) throws Exception {
        return generateInternal(base,key,model,thinking,maxTokens,seconds,messages,jsonObject,thinkingBudget,List.of(),true,true);
    }

    private Response<AiMessage> generateInternal(String base, String key, String model, Boolean thinking, int maxTokens,
            int seconds, List<ChatMessage> messages, boolean jsonObject, Integer thinkingBudget,
            List<Map<String,Object>> tools, boolean forbidToolCalls, boolean streaming) throws Exception {
        if (thinkingBudget != null && (!Boolean.TRUE.equals(thinking) || thinkingBudget < 1 || thinkingBudget > 32768))
            throw new IllegalArgumentException("Invalid thinking budget");
        URI endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Invalid model endpoint");
        var turns=wireMessages(messages);
        var body = new LinkedHashMap<String,Object>();
        body.put("model", model); body.put("messages", turns); body.put("temperature", .1);
        body.put("max_tokens", maxTokens); body.put("stream", streaming);
        if (streaming) body.put("stream_options", Map.of("include_usage", true));
        if(thinking!=null)body.put("enable_thinking", thinking);
        if (thinkingBudget != null) body.put("thinking_budget", thinkingBudget);
        if (jsonObject) body.put("response_format", Map.of("type", "json_object"));
        if (!tools.isEmpty()) {
            if (JSON.writeValueAsString(Map.of("messages", turns, "tools", tools)).length() > 30000)
                throw new IllegalArgumentException("Agent context exceeds limit");
            body.put("tools", tools); body.put("tool_choice", forbidToolCalls ? "none" : "auto");
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
            com.fasterxml.jackson.databind.JsonNode root;
            try { root = streaming ? decodeStream(response.body()) : JSON.readTree(response.body()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_JSON_INVALID);
            }
            if (root == null) throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CHOICES_INVALID);
            var choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1)
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CHOICES_INVALID);
            var choice = choices.get(0); var message = choice.path("message");
            boolean hasTools = message.hasNonNull("tool_calls");
            FinishReason finish = switch (choice.path("finish_reason").asText()) {
                case "stop" -> FinishReason.STOP;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                case "tool_calls" -> FinishReason.TOOL_EXECUTION;
                default -> throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_FINISH_REASON_INVALID);
            };
            var usage = root.path("usage");
            TokenUsage tokens = null;
            if (usage.path("prompt_tokens").canConvertToInt() && usage.path("completion_tokens").canConvertToInt())
                tokens = new TokenUsage(usage.path("prompt_tokens").intValue(), usage.path("completion_tokens").intValue());
            if (hasTools && (tools.isEmpty() || forbidToolCalls))
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_UNEXPECTED_TOOL_CALLS, finish, tokens);
            if (!hasTools && finish == FinishReason.TOOL_EXECUTION)
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_TOOL_CALLS_MISSING, finish, tokens);
            if (!hasTools && (!message.path("content").isTextual() || message.path("content").asText().isBlank())) {
                ResponseIssue issue = finish == FinishReason.LENGTH ? ResponseIssue.OUTPUT_TRUNCATED
                        : finish == FinishReason.CONTENT_FILTER ? ResponseIssue.OUTPUT_PROVIDER_FILTERED
                        : ResponseIssue.MODEL_RESPONSE_CONTENT_INVALID;
                throw new InvalidResponse(issue, finish, tokens);
            }
            if (hasTools) {
                var requested = message.path("tool_calls");
                if (finish != FinishReason.TOOL_EXECUTION || !requested.isArray() || requested.isEmpty() || requested.size() > 4)
                    throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_TOOL_CALLS_INVALID, finish, tokens);
                var requests = new ArrayList<ToolExecutionRequest>(); var ids = new HashSet<String>();
                for (var item : requested) {
                    var function = item.path("function"); String id = item.path("id").asText();
                    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(id) || !"function".equals(item.path("type").asText())
                            || !function.path("name").isTextual() || !function.path("arguments").isTextual()
                            || function.path("name").asText().length() > 80 || function.path("arguments").asText().length() > 2400)
                        throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_TOOL_CALLS_INVALID, finish, tokens);
                    requests.add(ToolExecutionRequest.builder().id(id).name(function.path("name").asText())
                            .arguments(function.path("arguments").asText()).build());
                }
                // Accompanying free text is not a checked patient answer and is deliberately discarded.
                return Response.from(AiMessage.from(requests), tokens, finish);
            }
            return Response.from(AiMessage.from(message.path("content").textValue()), tokens, finish);
        } finally { pending.cancel(true); }
    }

    /** No callback releases provider deltas: structured JSON and medical review require the complete draft. */
    static com.fasterxml.jackson.databind.JsonNode decodeStream(byte[] bytes) throws IOException {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CONTENT_INVALID); }
        StringBuilder content = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String finish = null;
        boolean done = false;
        com.fasterxml.jackson.databind.JsonNode usage = null;
        for (String line : (text.replace("\r\n", "\n") + "\n\n").split("\n", -1)) {
            if (!line.isEmpty()) {
                if (done && !line.startsWith(":")) throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CONTENT_INVALID);
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
                continue;
            }
            if (data.isEmpty()) continue;
            String frame = data.toString(); data.setLength(0);
            if (frame.equals("[DONE]")) { done = true; continue; }
            var item = JSON.readTree(frame);
            if (item == null || !item.isObject() || item.has("error") || !item.path("choices").isArray())
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CHOICES_INVALID);
            var choices = item.path("choices");
            if (item.hasNonNull("usage")) {
                if (usage != null || !item.path("usage").isObject()) throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CONTENT_INVALID);
                usage = item.path("usage");
            }
            if (choices.isEmpty()) continue;
            if (choices.size() != 1 || !choices.get(0).path("index").isIntegralNumber() || !choices.get(0).path("index").canConvertToInt() || choices.get(0).path("index").asInt(-1) != 0)
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CHOICES_INVALID);
            var choice = choices.get(0);
            var delta = choice.path("delta");
            if (!delta.isObject() || delta.hasNonNull("tool_calls"))
                throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_UNEXPECTED_TOOL_CALLS);
            if (delta.hasNonNull("content")) {
                if (!delta.path("content").isTextual() || (finish != null && !delta.path("content").asText().isEmpty()))
                    throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_CONTENT_INVALID);
                content.append(delta.path("content").asText());
            }
            // reasoning_content is deliberately never copied into the returned model message.
            if (choice.hasNonNull("finish_reason")) {
                if (finish != null || !choice.path("finish_reason").isTextual())
                    throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_FINISH_REASON_INVALID);
                finish = choice.path("finish_reason").asText();
            }
        }
        if (!done || finish == null) throw new InvalidResponse(ResponseIssue.MODEL_RESPONSE_FINISH_REASON_INVALID);
        var root = JSON.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", finish)
                .putObject("message").put("content", content.toString());
        if (usage != null) root.set("usage", usage);
        return root;
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
                terminal = true; subscription.cancel(); delegate.onError(new InvalidResponse(ResponseIssue.MODEL_RESPONSE_TOO_LARGE));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!terminal) { terminal = true; delegate.onError(error); } }
        public void onComplete() { if (!terminal) { terminal = true; delegate.onComplete(); } }
    }
}
