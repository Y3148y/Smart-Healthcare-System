package com.aihospital.triage;

import com.aihospital.triage.application.IntentInput;
import com.aihospital.triage.domain.TriageRecords.Message;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IntentInputTest {
    private Message message(String id, String role, String content) {
        return new Message(id, role, content, LocalDateTime.now(), null);
    }
    @Test void secondTurnContainsFirstAssistantQuestionAndSourceIds() {
        var result = IntentInput.recent(List.of(message("u1", "USER", "我头疼"),
                message("a1", "ASSISTANT", "持续多久了？"), message("u2", "USER", "两天")));
        assertEquals(List.of("u1", "a1", "u2"), result.messages().stream().map(IntentInput.SourceMessage::messageId).toList());
        assertEquals("持续多久了？", result.messages().get(1).content());
    }
    @Test void dropsEarliestRoundsAtFiveUsersWithoutLeavingOrphanAssistant() {
        var messages = new ArrayList<Message>();
        for (int i = 1; i <= 7; i++) {
            messages.add(message("u" + i, "USER", "问题" + i));
            if (i < 7) messages.add(message("a" + i, "ASSISTANT", "追问" + i));
        }
        var result = IntentInput.recent(messages);
        assertEquals("u3", result.messages().get(0).messageId());
        assertEquals("u7", result.messages().get(result.messages().size() - 1).messageId());
        assertEquals(5, result.messages().stream().filter(m -> m.role().equals("USER")).count());
    }
    @Test void keepsCurrentMessageWholeAndBoundsCharacters() {
        String current = "新".repeat(2000);
        var result = IntentInput.recent(List.of(message("u1", "USER", "旧".repeat(1500)),
                message("a1", "ASSISTANT", "问".repeat(1000)), message("u2", "USER", current)));
        assertEquals(1, result.messages().size());
        assertEquals(current, result.messages().get(0).content());
    }
    @Test void rejectsDuplicateIdsOversizedSourcesAndAssistantAsCurrentRequest() {
        assertThrows(IllegalArgumentException.class, () -> new IntentInput(List.of(
                new IntentInput.SourceMessage("u1", "USER", "甲"), new IntentInput.SourceMessage("u1", "USER", "乙"))));
        assertThrows(IllegalArgumentException.class, () -> new IntentInput(List.of(
                new IntentInput.SourceMessage("u1", "USER", "甲".repeat(4001)))));
        assertThrows(IllegalArgumentException.class, () -> IntentInput.recent(List.of(message("a1", "ASSISTANT", "追问"))));
    }
}
