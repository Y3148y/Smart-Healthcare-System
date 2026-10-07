package com.aihospital.observation;

import com.aihospital.observation.domain.OverviewStore;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OverviewToolCountTest {
    @Autowired OverviewStore overview;
    @Autowired CallLogStore calls;
    @Test void countsActualPersistedToolsIncludingFailuresButNotWorkflowOrReview() {
        int before = overview.toolCalls();
        for (String purpose : List.of("symptom_tag_search", "medical_knowledge_retrieve", "department_search",
                "doctor_schedule_search", "分诊工作流", "回答依据核对")) {
            calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), purpose, "synthetic", "test",
                    0, 0, 1, false, List.of()));
        }
        assertEquals(before + 4, overview.toolCalls());
    }
}
