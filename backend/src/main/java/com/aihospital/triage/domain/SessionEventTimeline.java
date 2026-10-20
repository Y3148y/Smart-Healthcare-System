package com.aihospital.triage.domain;

import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.aihospital.triage.domain.IntentExtraction.*;

/** Source-anchored engineering event identity. Never deletes the original observations or clears risk. */
public final class SessionEventTimeline {
    public record Event(String id,SymptomEvent observation,String corrects,String state) {}
    public List<Event> project(SessionFacts facts) {
        var result=new ArrayList<Event>();
        for(var observation:facts.events()) {
            String id=identity(observation);
            if(result.stream().anyMatch(e->e.id().equals(id)))continue;
            var candidates=result.stream().filter(e->e.observation().symptom().equals(observation.symptom())
                    &&sharesAnchor(e.observation(),observation)&&hasExplicitCorrection(observation,e.observation()))
                    .filter(e->result.stream().noneMatch(other->e.id().equals(other.corrects()))).toList();
            String corrects=candidates.size()==1?candidates.get(0).id():null;
            result.add(new Event(id,observation,corrects,candidates.size()>1?"AMBIGUOUS_CORRECTION":"OBSERVED"));
        }
        Set<String> superseded=new HashSet<>();result.forEach(e->{if(e.corrects()!=null)superseded.add(e.corrects());});
        return result.stream().map(e->new Event(e.id(),e.observation(),e.corrects(),superseded.contains(e.id())?"SUPERSEDED":e.state())).toList();
    }
    private boolean sharesAnchor(SymptomEvent a,SymptomEvent b) {
        return a.sourceRefs().stream().anyMatch(b.sourceRefs()::contains);
    }
    private boolean hasExplicitCorrection(SymptomEvent next,SymptomEvent previous) {
        return next.sourceRefs().stream().filter(ref->!previous.sourceRefs().contains(ref))
                .anyMatch(ref->ref.quote().matches("(?s).*(更正|纠正|说错了).*"));
    }
    public String identity(SymptomEvent event) {
        var parts=new ArrayList<String>();
        parts.addAll(Arrays.asList(event.symptom(),event.bodySite(),event.onset(),event.duration(),event.trigger(),event.severity(),
                event.subject().name(),event.temporality().name(),event.assertion().name()));
        event.sourceRefs().stream().sorted(Comparator.comparing(SourceRef::messageId).thenComparing(SourceRef::quote))
                .forEach(ref->{parts.add(ref.messageId());parts.add(ref.quote());});
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String value:parts) {byte[] bytes=Objects.toString(value,"<unknown>").getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Event identity unavailable");}
    }
}
