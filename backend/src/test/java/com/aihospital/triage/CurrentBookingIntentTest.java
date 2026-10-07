package com.aihospital.triage;

import com.aihospital.triage.domain.CurrentRequestIntent;
import com.aihospital.triage.domain.CurrentRequestIntent.BookingIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CurrentBookingIntentTest {
    @ParameterizedTest @ValueSource(strings={"我要挂号", "帮我预约", "现在想预约", "想查看平台模拟挂号", "挂号", "怎么预约"})
    void explicitBookingRequestsAreRecognized(String request) {
        assertEquals(BookingIntent.REQUESTED,CurrentRequestIntent.booking(request));
    }
    @ParameterizedTest @ValueSource(strings={"不挂号", "暂时不预约", "我不想挂号", "不要帮我预约", "无需挂号", "先不预约", "不需要再给我挂号", "不打算去挂号", "暂停预约", "只问日常注意事项", "只想了解健康知识"})
    void declinedOrGeneralOnlyMessagesDoNotBecomeRequests(String request) {
        assertEquals(BookingIntent.DECLINED,CurrentRequestIntent.booking(request));
    }
    @Test void lastExplicitChoiceWinsWithinTheCurrentMessage() {
        assertEquals(BookingIntent.REQUESTED,CurrentRequestIntent.booking("刚才不挂号，现在我要预约"));
        assertEquals(BookingIntent.DECLINED,CurrentRequestIntent.booking("刚才要预约，现在不挂号了"));
        assertEquals(BookingIntent.REQUESTED,CurrentRequestIntent.booking("只问日常注意事项，但现在帮我挂号"));
        assertEquals(BookingIntent.DECLINED,CurrentRequestIntent.booking("我想挂号，算了只问注意事项"));
    }
    @Test void directionIsSeparateFromBookingPreference() {
        assertEquals(BookingIntent.UNSPECIFIED,CurrentRequestIntent.booking("咳嗽，该看什么科"));
        assertTrue(CurrentRequestIntent.directionRequested("咳嗽，该看什么科"));
        assertEquals(BookingIntent.DECLINED,CurrentRequestIntent.booking("不挂号，只想知道挂什么科"));
        assertTrue(CurrentRequestIntent.directionRequested("不挂号，只想知道挂什么科"));
        assertEquals(BookingIntent.UNSPECIFIED,CurrentRequestIntent.booking(null));
        assertEquals(BookingIntent.UNSPECIFIED,CurrentRequestIntent.booking("咳嗽两天"));
    }
}
