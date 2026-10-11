package com.aihospital.triage.application;

import com.aihospital.triage.domain.TriageRecords.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The exact source window supplied to the extractor, including assistant questions as context. */
public record IntentInput(List<SourceMessage> messages) {
    public IntentInput {
        messages = List.copyOf(messages);
        if (messages.isEmpty() || !"USER".equals(messages.get(messages.size() - 1).role()))
            throw new IllegalArgumentException("Intent window must end with the current patient message");
        var ids = new java.util.HashSet<String>();
        int characters = 0, users = 0;
        for (var message : messages) {
            if (message.messageId() == null || message.messageId().isBlank() || message.messageId().length() > 64
                    || !ids.add(message.messageId()) || !List.of("USER", "ASSISTANT").contains(message.role())
                    || message.content() == null || message.content().isBlank())
                throw new IllegalArgumentException("Invalid intent source message");
            characters += message.content().length();
            if ("USER".equals(message.role())) users++;
        }
        if (characters > 4000 || users > 5) throw new IllegalArgumentException("Intent window exceeds budget");
    }
    public record SourceMessage(String messageId, String role, String content) {}

    public static IntentInput recent(List<Message> history) {
        var selected = new ArrayList<SourceMessage>();
        int characters = 0, users = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            var message = history.get(i);
            if (!List.of("USER", "ASSISTANT").contains(message.role())) continue;
            if (message.content() == null || message.content().isBlank()) continue;
            int nextUsers = users + ("USER".equals(message.role()) ? 1 : 0);
            if (nextUsers > 5 || characters + message.content().length() > 4000) break;
            selected.add(new SourceMessage(message.id(), message.role(), message.content()));
            characters += message.content().length();
            users = nextUsers;
        }
        Collections.reverse(selected);
        while (!selected.isEmpty() && !"USER".equals(selected.get(0).role())) selected.remove(0);
        if (selected.isEmpty()) throw new IllegalArgumentException("No complete patient message fits the intent window");
        return new IntentInput(selected);
    }
}
