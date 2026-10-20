package com.aihospital.triage.application;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class TaskAwareQueryTest {
    @Test void taskTermsAreLiteralWhileSymptomsStillRequireTheExistingSourceAuthorization(){
        assertEquals(Set.of("头痛","头痛 预防"),TaskAwareQuery.candidates(Set.of("头痛"),"头痛怎样预防"));
        assertEquals(Set.of("咳嗽"),TaskAwareQuery.candidates(Set.of("咳嗽"),"咳嗽，不想问护理"));
        assertTrue(TaskAwareQuery.candidates(Set.of(),"我朋友头痛怎么处理").isEmpty());
        assertFalse(TaskAwareQuery.candidates(Set.of("咳嗽"),"没有胸痛，咳嗽如何处理").stream().anyMatch(q->q.contains("胸痛")));
    }
}
