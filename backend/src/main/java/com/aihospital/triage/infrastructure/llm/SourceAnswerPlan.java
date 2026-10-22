package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** The model selects sources and dialogue acts, never patient-visible medical prose. */
final class SourceAnswerPlan {
    private static final ObjectMapper JSON=new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Plan(List<String> sourceIds,String transition,List<String> questionKeys,String gap) {}
    static final Map<String,String> QUESTIONS=Collections.unmodifiableMap(new TreeMap<>(Map.of(
            "DURATION","这种不适持续多久了？",
            "ONSET","是突然出现，还是逐渐出现的？",
            "LOCATION","最不舒服的具体部位在哪里？",
            "SEVERITY","目前对日常活动有什么影响？",
            "ASSOCIATED","还伴有哪些不适？",
            "SUBJECT","您描述的是自己的情况，还是他人的情况？",
            "CURRENT","这些不适现在仍然存在吗？")));
    private static final Map<String,String> TRANSITIONS=Map.of(
            "REFERENCE","以下是本轮检索资料的原文，请结合其中的适用条件阅读：",
            "ACKNOWLEDGE","我明白您想了解这方面的信息。下面保留资料原文：",
            "NONE","");
    private static final Map<String,String> GAPS=Map.of(
            "NONE","", "NOT_COVERED","现有资料未覆盖您问到的具体内容。",
            "APPLICABILITY_UNKNOWN","目前还不能确认这些资料是否适用于您的具体情况。");

    static final class VerifiedPlan {
        private final AnswerDraftValidator.Checked checked;
        private final List<Reference> references;
        private VerifiedPlan(AnswerDraftValidator.Checked checked,List<Reference> references) {
            var draft=checked.draft();
            var immutable=new Draft(draft.paragraphs().stream().map(p->new Paragraph(p.text(),p.kind(),List.copyOf(p.referenceIds()))).toList(),
                    List.copyOf(draft.questions()),List.copyOf(draft.uncovered()));
            this.checked=new AnswerDraftValidator.Checked(checked.text(),List.copyOf(checked.adopted()),List.copyOf(checked.uncovered()),immutable);
            this.references=List.copyOf(references);
        }
        AnswerDraftValidator.Checked checked(){return checked;}
        String text(){return checked.text();}
        Draft draft(){return checked.draft();}
        List<String> adopted(){return checked.adopted();}
        List<Reference> references(){return references;}
    }

    static VerifiedPlan check(String raw,List<Reference> references) throws Exception {
        if(raw==null||raw.length()>4000)throw new AnswerDraftValidator.InvalidDraftException("SIZE_INVALID");
        Plan plan=JSON.readValue(raw,Plan.class);
        if(plan==null||plan.sourceIds()==null||plan.sourceIds().size()>2||plan.questionKeys()==null||plan.questionKeys().size()>2
                ||plan.transition()==null||plan.gap()==null||!TRANSITIONS.containsKey(plan.transition())||!GAPS.containsKey(plan.gap())
                ||plan.sourceIds().stream().anyMatch(Objects::isNull)||plan.questionKeys().stream().anyMatch(Objects::isNull)
                ||new HashSet<>(plan.sourceIds()).size()!=plan.sourceIds().size()
                ||new HashSet<>(plan.questionKeys()).size()!=plan.questionKeys().size()
                ||!QUESTIONS.keySet().containsAll(plan.questionKeys()))throw new AnswerDraftValidator.InvalidDraftException("SOURCE_PLAN_INVALID");
        if(plan.sourceIds().isEmpty()&&"NONE".equals(plan.gap()))throw new AnswerDraftValidator.InvalidDraftException("SOURCE_PLAN_EMPTY");
        var paragraphs=new ArrayList<Paragraph>();var display=new ArrayList<String>();
        if(!plan.sourceIds().isEmpty()&&!TRANSITIONS.get(plan.transition()).isEmpty())display.add(TRANSITIONS.get(plan.transition()));
        for(String id:plan.sourceIds()) {
            var matching=references.stream().filter(r->id.equals(r.id())).toList();
            if(matching.size()!=1)throw new AnswerDraftValidator.InvalidReferenceException();
            var ref=matching.get(0);
            if(ref.excerpt()==null||!com.aihospital.triage.domain.AnswerEvidence.hash(ref.excerpt()).equals(ref.contentHash()))
                throw new AnswerDraftValidator.InvalidReferenceException();
            paragraphs.add(new Paragraph(ref.excerpt(),"GENERAL_INFORMATION",List.of(id)));
            String title=ref.title()+(ref.sectionPath().isEmpty()?"":" · "+String.join(" / ",ref.sectionPath()));
            display.add("【资料原文 "+id+"】"+title+"\n"+ref.excerpt());
        }
        String gap=GAPS.get(plan.gap());
        if(!gap.isEmpty()){paragraphs.add(new Paragraph(gap,"LIMITATION",List.of()));display.add(gap);}
        var questions=plan.questionKeys().stream().map(QUESTIONS::get).toList();display.addAll(questions);
        var draft=new Draft(paragraphs,questions,gap.isEmpty()?List.of():List.of(gap));
        var checked=AnswerDraftValidator.check(JSON.writeValueAsString(draft),references);
        String text=String.join("\n\n",display);
        if(text.length()>1200)throw new AnswerDraftValidator.InvalidDraftException("TEXT_SIZE_INVALID");
        return new VerifiedPlan(new AnswerDraftValidator.Checked(text,checked.adopted(),checked.uncovered(),checked.draft()),references);
    }
    private SourceAnswerPlan() {}
}
