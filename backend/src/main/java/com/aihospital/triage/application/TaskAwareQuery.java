package com.aihospital.triage.application;
import java.util.*;
import java.util.regex.Pattern;
/** Query terms come only from already-authorized symptoms plus literal nonclinical task words. */
public final class TaskAwareQuery {
    private static final Pattern TASK=Pattern.compile("预防|缓解|护理|诱因|持续|区别|定义|处理|注意事项");
    public static Set<String> candidates(Set<String> symptoms,String current) {
        var result=new HashSet<>(symptoms);
        if(current==null||current.isBlank())return Set.copyOf(result);
        var terms=new LinkedHashSet<String>();var matcher=TASK.matcher(current);
        while(matcher.find()&&terms.size()<4) {
            String prefix=current.substring(Math.max(0,matcher.start()-4),matcher.start());
            if(!prefix.matches("(?s).*(不要|不想|不需要|不问|不是).*$"))terms.add(matcher.group());
        }
        if(!terms.isEmpty())for(String symptom:symptoms) {
            String query=symptom+" "+String.join(" ",terms);
            if(query.length()<=2200)result.add(query);
        }
        return Set.copyOf(result);
    }
}
