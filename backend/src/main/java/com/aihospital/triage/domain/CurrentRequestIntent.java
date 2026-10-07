package com.aihospital.triage.domain;

import java.util.List;
import java.util.regex.Pattern;

/** Current-turn conversational intent, independent of medical symptoms and safety assessment. */
public final class CurrentRequestIntent {
    private CurrentRequestIntent() {}
    private static final Pattern RESTRICTED=Pattern.compile(
        "(开药|开处方|处方|开方|买药|确诊|诊断一下|能治吗|怎么治疗|用什么药|是不是.{0,10}(病|炎|感染)|(?:胃|肠|肺|肝|肾|胆|胰|心|脑|血|甲|乳)[^，。？！,.?!]{0,6}(病|炎|感染|癌|结石|息肉))");
    // Only explicit withdrawal of a request is ignored. This does not negate symptoms.
    private static final Pattern WITHDRAWN=Pattern.compile("(?:不用|不要|不需要|无需)(?:再|给我)?(?:开处方|开药|开方|买药)(?:了)?");
    public enum BookingIntent { REQUESTED, DECLINED, UNSPECIFIED }
    // Consume a declined phrase as a whole so its booking word cannot re-match as positive.
    // This is a bounded lexical policy, not a general language understanding guarantee.
    private static final Pattern BOOKING_INTENT=Pattern.compile(
            "(?<declined>(?:不想|不要|不需要|不用|无需|不打算|不考虑|取消|暂停|不)"
            + "(?:(?:再|先|现在|目前|暂时|给我|帮我|替我|去|进行|办理|考虑|想要|想)){0,3}(?:挂号|预约))"
            + "|(?<general>(?:只|仅)(?:是)?(?:想|要)?(?:问|了解|咨询|知道)[^，。？！,.?!]{0,12}"
            + "(?:日常注意事项|注意事项|健康知识|一般问题|科普))"
            + "|(?<requested>挂号|预约)");
    private static final Pattern DIRECTION=Pattern.compile("(?:(?:想|要|希望|请问|了解|咨询|查看|看看){0,3})?(?:挂什么科|看什么科|哪个科|就诊方向|就医方向)");
    private static final Pattern QUERY_WRAPPER=Pattern.compile("(?:现在|暂时|目前|只|仅|想|要|希望|请问|查看|看看|了解|咨询|问一下|问问|平台|模拟|一下)+");

    /** Last explicit booking choice in this message wins; prior rounds are not consulted. */
    public static BookingIntent booking(String request) {
        if(request==null || request.isBlank()) return BookingIntent.UNSPECIFIED;
        var matcher=BOOKING_INTENT.matcher(request);
        BookingIntent intent=BookingIntent.UNSPECIFIED;
        while(matcher.find()) intent=matcher.group("requested")!=null ? BookingIntent.REQUESTED : BookingIntent.DECLINED;
        return intent;
    }
    public static boolean directionRequested(String request) {
        return request!=null && DIRECTION.matcher(request).find();
    }
    /**
     * Remove explicit service-intent wording from the retrieval query while retaining the user's
     * symptom clauses verbatim. Safety assessment and booking logic must continue using raw text.
     */
    public static String medicalRetrievalQuery(String request) {
        if (request == null || request.isBlank()) return request == null ? "" : request;
        StringBuilder symptoms = new StringBuilder();
        for (String clause : request.split("(?<=[，,。？！?!；;\\n])|(?=[，,。？！?!；;\\n])")) {
            String cleaned = BOOKING_INTENT.matcher(clause).replaceAll(" ");
            cleaned = DIRECTION.matcher(cleaned).replaceAll(" ");
            cleaned = QUERY_WRAPPER.matcher(cleaned).replaceAll(" ");
            cleaned = cleaned.replaceAll("[，,。？！?!；;\\s]+", " ").strip();
            if (!cleaned.isBlank()) {
                if (!symptoms.isEmpty()) symptoms.append(' ');
                symptoms.append(cleaned);
            }
        }
        // Do not turn a pure booking utterance into a false empty medical query.
        String query = symptoms.toString();
        return query.isBlank() || query.matches("我\\s*(?:想|要|希望)?") ? request : query;
    }
    public static boolean restricted(String request){
        return request!=null&&!request.isBlank()&&RESTRICTED.matcher(WITHDRAWN.matcher(request).replaceAll("")).find();
    }
    public static String latest(List<NarrationModel.Turn> history,String fallback){
        if(history!=null)for(int i=history.size()-1;i>=0;i--){var turn=history.get(i);if(turn!=null&&"USER".equals(turn.role())&&turn.content()!=null)return turn.content();}
        return fallback;
    }
}
