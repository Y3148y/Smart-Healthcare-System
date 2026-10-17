package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.tools.domain.AgentTool;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentPlanningBudgetTest {
    private final IntentInput input=new IntentInput(List.of(new IntentInput.SourceMessage("u1","USER","synthetic")));
    private final IntentExtraction intent=new IntentExtraction(1,List.of(IntentExtraction.Intent.GENERAL_HEALTH),List.of(),List.of(),List.of());
    @Test void initialPlanningBudgetRejectsBeforeConstructingAProviderRequest(){
        var model=new LangChainAgentModel("openai-compatible","synthetic-key","invalid://not-a-provider","synthetic",false);
        ReflectionTestUtils.setField(model,"inputTokenBudget",512);
        var result=model.decide(input,intent,Set.of(AgentTool.MEDICAL_KNOWLEDGE),Set.of(),Set.of("synthetic"),List.of(),1);
        assertEquals(AgentModel.Status.UNAVAILABLE,result.status());
        assertEquals("CONTEXT_INPUT_BUDGET_EXCEEDED",result.failureCode());assertNull(result.inputTokens());
    }
    @Test void continuationCountsCompleteToolResultsInsteadOfSilentlyClippingThem(){
        var model=new LangChainAgentModel("openai-compatible","synthetic-key","invalid://not-a-provider","synthetic",false);
        ReflectionTestUtils.setField(model,"inputTokenBudget",5000);
        var transcript=List.of(new AgentModel.Exchange(List.of(new AgentModel.ToolCall("c1","medical_knowledge_retrieve","{}")),List.of("x".repeat(8000))));
        var result=model.decide(input,intent,Set.of(),Set.of(),Set.of("synthetic"),transcript,1);
        assertEquals("CONTEXT_INPUT_BUDGET_EXCEEDED",result.failureCode());
        assertEquals(8000,transcript.get(0).results().get(0).length());assertNull(result.outputTokens());
    }
    @Test void wireBudgetIncludesToolArgumentsResultsAndSchemas(){
        var call=ToolExecutionRequest.builder().id("c1").name("medical_knowledge_retrieve").arguments("{\"query\":\"synthetic\"}").build();
        var wire=ConfiguredChatTransport.wireMessages(List.of(AiMessage.from(List.of(call)),ToolExecutionResultMessage.from(call,"whole-result")));
        String serialized=wire.toString();assertTrue(serialized.contains("synthetic"));assertTrue(serialized.contains("whole-result"));
        var payload=Map.of("messages",wire,"tools",List.of(Map.of("schema","x".repeat(2000))));
        assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(1500).checkPayload(payload,2));
        assertEquals("CONTEXT_PLANNING_CHARACTER_LIMIT",assertThrows(ContextAssembler.BudgetExceeded.class,
                ()->new ContextAssembler(65536).checkPayload(Map.of("data","x".repeat(30001)),1)).code);
    }
}
