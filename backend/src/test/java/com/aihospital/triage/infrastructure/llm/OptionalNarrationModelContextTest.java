package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.NarrationModel.Turn;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptionalNarrationModelContextTest {
    private final OptionalNarrationModel model = new OptionalNarrationModel();

    @Test
    void secondTurnPromptContainsFirstAssistantQuestionAndEvidence() {
        List<ChatMessage> prompt = model.buildMessages("安全指令", "现在还咳嗽", "呼吸资料", List.of(
                new Turn("USER", "我咳嗽"),
                new Turn("ASSISTANT", "咳嗽持续多久？"),
                new Turn("USER", "现在还咳嗽")));
        assertEquals("安全指令" + OptionalNarrationModel.CONVERSATION_STYLE, ((SystemMessage) prompt.get(0)).text());
        assertTrue(prompt.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .anyMatch(message -> message.text().contains("<evidence>呼吸资料</evidence>")));
        assertTrue(prompt.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                .anyMatch(message -> message.text().equals("咳嗽持续多久？")));
        assertEquals("现在还咳嗽", ((UserMessage) prompt.get(prompt.size() - 1)).text());
    }

    @Test
    void windowKeepsAtMostFiveUserTurnsAndFourThousandCharacters() {
        List<Turn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new Turn("USER", "第" + i + "轮" + "症".repeat(550)));
            if (i < 7) history.add(new Turn("ASSISTANT", "第" + i + "轮追问"));
        }
        List<ChatMessage> prompt = model.buildMessages("安全指令", "最新问题", "知识", history);
        List<ChatMessage> conversation = prompt.subList(2, prompt.size());
        assertTrue(conversation.stream().filter(UserMessage.class::isInstance).count() <= 5);
        assertTrue(conversation.stream().mapToInt(message -> message instanceof UserMessage user
                ? user.text().length() : ((AiMessage) message).text().length()).sum() <= 4000);
        assertFalse(conversation.stream().anyMatch(message -> message.toString().contains("第0轮")));
        assertTrue(conversation.stream().anyMatch(message -> message.toString().contains("第7轮")));
    }
}
