package com.aihospital.triage.application;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class TaskAwareQueryTest {
    @Test void taskTermsAreLiteralWhileSymptomsStillRequireTheExistingSourceAuthorization(){
        assertEquals(Set.of("头痛 预防"),TaskAwareQuery.candidates(Set.of("头痛"),"头痛怎样预防"));
        assertEquals(Set.of("咳嗽"),TaskAwareQuery.candidates(Set.of("咳嗽"),"咳嗽，不想问护理"));
        assertTrue(TaskAwareQuery.candidates(Set.of(),"我朋友头痛怎么处理").isEmpty());
        assertFalse(TaskAwareQuery.candidates(Set.of("咳嗽"),"没有胸痛，咳嗽如何处理").stream().anyMatch(q->q.contains("胸痛")));
    }
    @Test void colloquialTaskSurvivesBookingNegationWithoutInventingClinicalWords(){
        assertEquals(Set.of("合成症状 平时可以怎么做"),TaskAwareQuery.candidates(Set.of("合成症状"),"我暂时不预约，想了解平时可以怎么做。"));
        assertEquals(Set.of("合成症状 日常应该如何护理"),TaskAwareQuery.candidates(Set.of("合成症状"),"日常应该如何护理？"));
        assertEquals(Set.of("合成症状"),TaskAwareQuery.candidates(Set.of("合成症状"),"不想问平时可以怎么做"));
        assertEquals(Set.of("合成症状"),TaskAwareQuery.candidates(Set.of("合成症状"),"补充普通信息"));
        assertTrue(TaskAwareQuery.candidates(Set.of(),"替朋友咨询，平时可以怎么做？").isEmpty());
        assertFalse(TaskAwareQuery.candidates(Set.of("合成症状"),"没有其他症状，平时可以怎么做？").stream().anyMatch(q->q.contains("其他症状")));
    }
}
