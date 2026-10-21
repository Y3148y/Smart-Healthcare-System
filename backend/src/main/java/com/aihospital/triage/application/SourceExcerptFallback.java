package com.aihospital.triage.application;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.NarrationModel;
import java.util.*;

/** Deterministic quotation, never partial acceptance of a rejected model draft. Final freshness still required. */
public final class SourceExcerptFallback {
    private SourceExcerptFallback() {}
    private static final Set<String> ELIGIBLE_ISSUES=Set.of("UNSUPPORTED_CLAIM","WRONG_REFERENCE","CONDITION_NOT_ESTABLISHED");
    private static final String INTRO="生成内容未通过依据核对，未采用。以下是检索资料原文，不是对您个人情况的判断：";
    public static NarrationModel.Answer apply(KnowledgeCatalog catalog,NarrationModel narration,
            List<Evidence> evidence,boolean grounded,NarrationModel.Answer answer) {
        var d=answer.diagnostics();
        if(!grounded||catalog==null||d==null||!"VALIDATION_BLOCKED".equals(answer.status())
                ||!"MATCHED".equals(d.retrievalStatus())||!"SUPPORT_BLOCKED".equals(d.validationStatus())
                ||d.failure()==null||!"OUTPUT_SUPPORT_REJECTED".equals(d.failure().code())
                ||d.generation()==null||!"STOP".equals(d.generation().finishReason())
                ||d.supportReview()==null||!"REJECTED".equals(d.supportReview().status())
                ||d.supportReview().issueCodes().isEmpty()||!ELIGIBLE_ISSUES.containsAll(d.supportReview().issueCodes()))return answer;
        try {
            if(catalog.evidenceState(evidence)!=KnowledgeCatalog.EvidenceState.CURRENT)return answer;
            var refs=d.retrievedReferences();var ids=new HashSet<String>();
            for(var ref:refs)if(ref==null||ref.id()==null||!ref.id().matches("E[1-9][0-9]?")||!ids.add(ref.id())
                    ||ref.excerpt()==null||!AnswerEvidence.hash(ref.excerpt()).equals(ref.contentHash()))return answer;
            var eligible=new ArrayList<AnswerEvidence.Reference>();
            for(var ref:refs) {
                var matching=evidence.stream().filter(e->e.title().equals(ref.title())&&e.source().equals(ref.source())
                        &&e.excerpt().equals(ref.excerpt())&&e.sectionPath().equals(ref.sectionPath())).toList();
                if(matching.size()!=1)return answer;
                var state=catalog.sourceExcerptState(matching.get(0));
                if(state==null||state==KnowledgeCatalog.ExcerptState.UNVERIFIABLE)return answer;
                if(state!=KnowledgeCatalog.ExcerptState.COMPLETE_PARAGRAPHS||!narration.sourceExcerptAllowed(ref.excerpt()))continue;
                eligible.add(ref);
            }
            var adopted=new ArrayList<String>();var text=new StringBuilder(INTRO);
            for(var ref:eligible) {
                String context=ref.sectionPath().isEmpty()?ref.title():ref.title()+" · "+String.join(" / ",ref.sectionPath());
                String quoted="\n\n【资料摘录 "+ref.id()+"】"+context+"\n"+ref.excerpt();
                if(text.length()+quoted.length()>1200)continue; // Skip whole chunks, never truncate conditions.
                text.append(quoted);adopted.add(ref.id());if(adopted.size()==2)break;
            }
            if(adopted.isEmpty()||!narration.sourceExcerptAllowed(text.toString()))return answer;
            var diagnostics=new AnswerEvidence.Diagnostics(d.traceId(),d.retrievalStatus(),"SOURCE_EXCERPT_VERIFIED",
                    refs,adopted,List.of(),"SOURCE_TEXT_ONLY",d.failure(),d.elapsedMs(),d.generation(),d.supportReview(),
                    new AnswerEvidence.ServiceNotice(d.serviceNotice()==null?List.of():d.serviceNotice().messages(),false));
            return new NarrationModel.Answer(text.toString(),"SOURCE_EXCERPT",answer.modelName(),diagnostics);
        } catch(RuntimeException unavailable){return answer;}
    }
}
