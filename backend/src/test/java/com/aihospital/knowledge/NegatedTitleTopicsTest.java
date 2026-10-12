package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.NegatedTitleTopics;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NegatedTitleTopicsTest {
    private final List<Evidence> corpus = List.of(
            new Evidence("甲主题：说明", "source", "synthetic", 0),
            new Evidence("乙主题：说明", "source", "synthetic", 0));

    @Test void excludesOnlyAnExactlyDeniedApprovedTitleTopic() {
        assertEquals(Set.of("乙主题：说明"), NegatedTitleTopics.excludedTitles("甲主题，没有乙主题", corpus));
        assertEquals(Set.of("乙主题：说明"), NegatedTitleTopics.excludedTitles("甲主题，不伴乙主题", corpus));
        assertEquals(Set.of(), NegatedTitleTopics.excludedTitles("甲主题，有乙主题", corpus));
        assertEquals(Set.of(), NegatedTitleTopics.excludedTitles("甲主题，不可能乙主题", corpus));
        assertEquals(Set.of(), NegatedTitleTopics.excludedTitles("甲主题，没有乙主题，但后来乙主题", corpus));
        assertEquals(Set.of(), NegatedTitleTopics.excludedTitles("甲主题，没有别的情况", corpus));
        assertEquals(Set.of("咳嗽：病程、一般照护与就医条件"),
                NegatedTitleTopics.excludedTitles("流鼻涕，没有咳嗽", List.of(
                        new Evidence("咳嗽：病程、一般照护与就医条件", "source", "synthetic", 0))));
    }
}
