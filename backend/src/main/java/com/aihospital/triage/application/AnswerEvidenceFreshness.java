package com.aihospital.triage.application;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.NarrationModel;
import java.util.List;

/** Post-generation source check. Does not make publication and persistence atomic. */
public final class AnswerEvidenceFreshness {
    private AnswerEvidenceFreshness() {}
    public record Checked(NarrationModel.Answer answer, boolean blocked) {}

    public static Checked check(KnowledgeCatalog catalog, List<Evidence> evidence, NarrationModel.Answer answer) {
        if (evidence.isEmpty()&&!"SOURCE_QUOTED".equals(answer.status())) return new Checked(answer, false);
        KnowledgeCatalog.EvidenceState state;
        try { state = evidence.isEmpty()||catalog == null ? KnowledgeCatalog.EvidenceState.UNTRACKED : catalog.evidenceState(evidence); }
        catch (RuntimeException unavailable) { state = KnowledgeCatalog.EvidenceState.UNAVAILABLE; }
        if (state == KnowledgeCatalog.EvidenceState.CURRENT && "SOURCE_QUOTED".equals(answer.status())) {
            var d=answer.diagnostics();
            try {
                if(d==null||d.adoptedReferenceIds().isEmpty())state=KnowledgeCatalog.EvidenceState.UNTRACKED;
                else for(String id:d.adoptedReferenceIds()) {
                    var refs=d.retrievedReferences().stream().filter(r->id.equals(r.id())).toList();
                    if(refs.size()!=1){state=KnowledgeCatalog.EvidenceState.UNTRACKED;break;}
                    var r=refs.get(0);
                    var matching=evidence.stream().filter(e->e.title().equals(r.title())&&e.source().equals(r.source())
                            &&e.excerpt().equals(r.excerpt())&&e.sectionPath().equals(r.sectionPath())).toList();
                    if(matching.size()!=1||!AnswerEvidence.hash(r.excerpt()).equals(r.contentHash())
                            ||catalog.sourceExcerptState(matching.get(0))!=KnowledgeCatalog.ExcerptState.COMPLETE_PARAGRAPHS) {
                        state=KnowledgeCatalog.EvidenceState.UNTRACKED;break;
                    }
                }
            } catch(RuntimeException unavailable){state=KnowledgeCatalog.EvidenceState.UNAVAILABLE;}
        }
        if (state == KnowledgeCatalog.EvidenceState.CURRENT) return new Checked(answer, false);
        String code = "ANSWER_SOURCE_" + (state == null ? "UNTRACKED" : state.name());
        var old = answer.diagnostics();
        var diagnostics = new AnswerEvidence.Diagnostics(old == null ? null : old.traceId(), code,
                "BLOCKED", List.of(), List.of(), List.of(), "NOT_ESTABLISHED",
                new AnswerEvidence.Failure(code, "SourceState", null), old == null ? null : old.elapsedMs(),
                old == null ? null : old.generation(), old == null ? null : old.supportReview(), null);
        String text = state == KnowledgeCatalog.EvidenceState.CHANGED
                ? "本轮参考资料已发生变化，原回答暂不展示。请重新咨询以使用当前资料，或申请人工导诊。"
                : "暂时无法确认本轮参考资料仍然有效，原回答暂不展示。请稍后重试或申请人工导诊。";
        return new Checked(new NarrationModel.Answer(text, "VALIDATION_BLOCKED", answer.modelName(), diagnostics), true);
    }
}
