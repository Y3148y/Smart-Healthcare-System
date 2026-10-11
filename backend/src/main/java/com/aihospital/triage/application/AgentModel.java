package com.aihospital.triage.application;

import com.aihospital.tools.domain.AgentTool;
import com.aihospital.triage.domain.IntentExtraction;
import java.util.List;
import java.util.Set;

/** Proposal port only. No external model adapter or write authority is implied. */
public interface AgentModel {
    record ToolCall(String id, String name, String arguments) {}
    record Exchange(List<ToolCall> calls, List<String> results) {
        public Exchange {
            calls = List.copyOf(calls); results = List.copyOf(results);
            if (calls.size() != results.size()) throw new IllegalArgumentException("Unpaired tool results");
        }
    }
    enum Status { TOOLS, DONE, INVALID, UNAVAILABLE, NOT_CONFIGURED }
    record Step(Status status, List<ToolCall> calls, Integer inputTokens, Integer outputTokens,
                long elapsedMs, String failureCode) {
        public Step { calls = List.copyOf(calls); }
        public Step(Status status, List<ToolCall> calls, Integer inputTokens, Integer outputTokens, long elapsedMs) {
            this(status, calls, inputTokens, outputTokens, elapsedMs, null);
        }
    }
    Step decide(IntentInput input, IntentExtraction intent, Set<AgentTool> allowed,
                Set<String> departments, Set<String> queries, List<Exchange> transcript, int timeoutSeconds);
}
