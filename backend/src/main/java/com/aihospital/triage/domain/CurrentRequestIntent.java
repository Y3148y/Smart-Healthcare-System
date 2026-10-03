package com.aihospital.triage.domain;

import java.util.List;
import java.util.regex.Pattern;

/** Lexical compliance gate for the current request, not a medical symptom classifier. */
public final class CurrentRequestIntent {
    private CurrentRequestIntent() {}
    private static final Pattern RESTRICTED=Pattern.compile(
        "(开药|开处方|处方|开方|买药|确诊|诊断一下|能治吗|怎么治疗|用什么药|是不是.{0,10}(病|炎|感染)|(?:胃|肠|肺|肝|肾|胆|胰|心|脑|血|甲|乳)[^，。？！,.?!]{0,6}(病|炎|感染|癌|结石|息肉))");
    // Only explicit withdrawal of a request is ignored. This does not negate symptoms.
    private static final Pattern WITHDRAWN=Pattern.compile("(?:不用|不要|不需要|无需)(?:再|给我)?(?:开处方|开药|开方|买药)(?:了)?");
    public static boolean restricted(String request){
        return request!=null&&!request.isBlank()&&RESTRICTED.matcher(WITHDRAWN.matcher(request).replaceAll("")).find();
    }
    public static String latest(List<NarrationModel.Turn> history,String fallback){
        if(history!=null)for(int i=history.size()-1;i>=0;i--){var turn=history.get(i);if(turn!=null&&"USER".equals(turn.role())&&turn.content()!=null)return turn.content();}
        return fallback;
    }
}
