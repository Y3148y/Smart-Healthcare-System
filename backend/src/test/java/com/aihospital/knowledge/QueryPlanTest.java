package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.QueryPlan;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class QueryPlanTest {
    @Test void preservesNegationSubjectTimeAndCurrentIntentWithoutMergingHistory() {
        String current = "我现在不挂号，母亲以前头疼，我没有胸痛";
        var plan = QueryPlan.shadow(current, List.of(new QueryPlan.Context("m1", "之前咳嗽")));
        assertEquals(current, plan.currentQuestion());
        assertEquals("m1", plan.patientContext().get(0).messageId());
        assertFalse(plan.currentQuestion().contains("咳嗽"));
    }
    @Test void retainsLatestFiveStatementsAndOriginalIds() {
        var entries = IntStream.range(0, 8).mapToObj(i -> new QueryPlan.Context("m" + i, "自述" + i)).toList();
        var plan = QueryPlan.shadow("当前问题", entries);
        assertEquals(5, plan.patientContext().size());
        assertEquals("m3", plan.patientContext().get(0).messageId());
    }
    @Test void overBudgetDropsWholeOldStatementsRatherThanClippingNegations() {
        var plan = QueryPlan.shadow("当前问题", List.of(
            new QueryPlan.Context("old", "没有胸痛".repeat(1100)), new QueryPlan.Context("new", "当前没有胸痛")));
        assertEquals(List.of(new QueryPlan.Context("new", "当前没有胸痛")), plan.patientContext());
        assertThrows(IllegalArgumentException.class, () -> QueryPlan.shadow(" ", List.of()));
    }
}
