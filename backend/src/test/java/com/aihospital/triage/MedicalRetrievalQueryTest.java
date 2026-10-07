package com.aihospital.triage;

import com.aihospital.triage.domain.CurrentRequestIntent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MedicalRetrievalQueryTest {
    @Test void removesBookingAndDirectionClausesButKeepsSymptomText() {
        assertEquals("偏头疼两天", CurrentRequestIntent.medicalRetrievalQuery("偏头疼两天，暂时不挂号，想了解就医方向。"));
        assertEquals("胸痛", CurrentRequestIntent.medicalRetrievalQuery("胸痛，想了解就医方向。"));
        assertEquals("咳嗽两天 咳白痰", CurrentRequestIntent.medicalRetrievalQuery("咳嗽两天，咳白痰，想查看平台模拟挂号"));
        assertEquals("流鼻涕两天", CurrentRequestIntent.medicalRetrievalQuery("流鼻涕两天，暂时不挂号，只问日常注意事项。"));
    }

    @Test void keepsNegationAndDoesNotUseAnotherSpeakerOrOldSymptomsAsCurrentQuery() {
        assertEquals("我没有胸痛", CurrentRequestIntent.medicalRetrievalQuery("我没有胸痛，不挂号"));
        assertEquals("母亲以前头疼", CurrentRequestIntent.medicalRetrievalQuery("母亲以前头疼，不预约"));
    }

    @Test void preservesPureIntentInsteadOfReturningEmptyString() {
        String request = "我想预约挂号";
        assertEquals(request, CurrentRequestIntent.medicalRetrievalQuery(request));
    }
}
