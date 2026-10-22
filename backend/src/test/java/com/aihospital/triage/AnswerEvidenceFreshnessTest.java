package com.aihospital.triage;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.triage.application.AnswerEvidenceFreshness;
import com.aihospital.triage.application.GuidanceAnswerService;
import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.shared.model.Models.*;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.observation.domain.CallLogStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerEvidenceFreshnessTest {
    private NarrationModel.Answer quoted(List<Evidence> evidence) {
        return new NarrationModel.Answer("Exact quote and bounded dialogue", "SOURCE_QUOTED", "fixture", draft(evidence).diagnostics());
    }
    @Test void sourceQuotedRequiresWholeCurrentParagraphsAndWithdrawalStillWins() {
        var local=new InMemoryKnowledgeCatalog(false);
        var catalog=new com.aihospital.knowledge.infrastructure.qdrant.HybridKnowledgeCatalog(local,
                mock(com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex.class),null);
        var doc=local.addDocument("Quoted fixture","# Scope\nOnly if X. Whole original paragraph.");local.approveDocument(doc.id());
        var evidence=sourceChunks(local,doc);var original=quoted(evidence);
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT,catalog.evidenceState(evidence));
        assertFalse(AnswerEvidenceFreshness.check(catalog,evidence,original).blocked());
        local.withdrawDocument(doc.id());
        var result=AnswerEvidenceFreshness.check(catalog,evidence,original);
        assertTrue(result.blocked());assertTrue(result.answer().diagnostics().adoptedReferenceIds().isEmpty());
        assertFalse(result.answer().text().contains("Exact quote"));
        var longDoc=local.addDocument("Long quoted fixture","Whole long paragraph. "+"Still the same paragraph. ".repeat(100));local.approveDocument(longDoc.id());
        var slices=sourceChunks(local,longDoc);assertTrue(slices.size()>1);
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT,catalog.evidenceState(slices));
        assertTrue(AnswerEvidenceFreshness.check(catalog,slices,quoted(slices)).blocked());
    }
    @Test void sourceQuotedRejectsMissingOrChangedReferenceIdentityAndCatalogFailure() {
        var catalog=new InMemoryKnowledgeCatalog(false);
        var doc=catalog.addDocument("Quote identity fixture","Whole original paragraph.");catalog.approveDocument(doc.id());
        var evidence=catalog.approvedCorpus();var original=quoted(evidence);
        assertTrue(AnswerEvidenceFreshness.check(catalog,List.of(),original).blocked());
        var untracked=mock(KnowledgeCatalog.class);when(untracked.evidenceState(anyList())).thenReturn(KnowledgeCatalog.EvidenceState.CURRENT);
        assertTrue(AnswerEvidenceFreshness.check(untracked,evidence,original).blocked());
        var changed=List.of(new Evidence("other title","source","Other original text.",1));
        assertTrue(AnswerEvidenceFreshness.check(catalog,evidence,quoted(changed)).blocked());
        when(untracked.sourceExcerptState(any())).thenThrow(new IllegalStateException("private error"));
        var unavailable=AnswerEvidenceFreshness.check(untracked,evidence,original);
        assertTrue(unavailable.blocked());assertFalse(unavailable.answer().toString().contains("private error"));
    }
    private List<Evidence> sourceChunks(InMemoryKnowledgeCatalog catalog,KnowledgeDocument doc) {
        return catalog.documentChunks(doc.id()).stream().map(c->new Evidence(doc.title(),c.source(),c.text(),1,c.sectionPath())).toList();
    }
    private NarrationModel.Answer draft(List<Evidence> evidence) {
        return new NarrationModel.Answer("WITHHELD_DRAFT", "LIVE", "fixture",
                new AnswerEvidence.Diagnostics("trace", "MATCHED", "PASSED", AnswerEvidence.references(evidence),
                        List.of("E1"), List.of(), "PASSED", null, 10L,
                        new AnswerEvidence.Generation("GENERATION", "STOP", 17, 7, 14)));
    }
    @Test void withdrawalChangesExactMembershipWithoutUsingScores() {
        var catalog = new InMemoryKnowledgeCatalog();
        var doc = catalog.addDocument("source fixture", "Exact source fixture content.");
        catalog.approveDocument(doc.id());
        var evidence = catalog.approvedCorpus().stream().filter(e -> e.title().equals(doc.title())).toList();
        assertFalse(evidence.isEmpty());
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT, catalog.evidenceState(evidence));
        var original = draft(evidence);
        assertSame(original, AnswerEvidenceFreshness.check(catalog, evidence, original).answer());
        catalog.withdrawDocument(doc.id());
        var checked = AnswerEvidenceFreshness.check(catalog, evidence, original);
        assertTrue(checked.blocked());
        assertFalse(checked.answer().text().contains("WITHHELD_DRAFT"));
        assertTrue(checked.answer().diagnostics().retrievedReferences().isEmpty());
        assertTrue(checked.answer().diagnostics().adoptedReferenceIds().isEmpty());
        assertEquals(17, checked.answer().diagnostics().generation().inputTokens());
        assertEquals("ANSWER_SOURCE_CHANGED", checked.answer().diagnostics().failure().code());
    }
    @Test void unknownAndUnavailableSourcesFailClosedButEmptyEvidenceDoesNotInventSourceCheck() {
        var catalog = mock(KnowledgeCatalog.class);
        var evidence = List.of(new Evidence("fixture", "source", "content", 1));
        when(catalog.evidenceState(evidence)).thenThrow(new IllegalStateException("PRIVATE_ERROR"));
        var checked = AnswerEvidenceFreshness.check(catalog, evidence, draft(evidence));
        assertEquals("ANSWER_SOURCE_UNAVAILABLE", checked.answer().diagnostics().failure().code());
        assertFalse(checked.answer().toString().contains("PRIVATE_ERROR"));
        assertTrue(AnswerEvidenceFreshness.check(null, evidence, draft(evidence)).blocked());
        assertFalse(AnswerEvidenceFreshness.check(null, List.of(), draft(List.of())).blocked());
    }
    @Test void headingContextAndBodyMustMatchButRelevanceScoreIsNotSourceIdentity() {
        var a = new Evidence("title", "source", "body", .1, List.of("condition"));
        assertTrue(KnowledgeCatalog.sameSource(a, new Evidence("title", "source", "body", .9, List.of("condition"))));
        assertFalse(KnowledgeCatalog.sameSource(a, new Evidence("title", "source", "body", .1)));
        assertFalse(KnowledgeCatalog.sameSource(a, new Evidence("title", "source", "changed", .1, List.of("condition"))));
    }
    @Test void guidanceRechecksAfterNarrationInsteadOfTrustingPreparedRetrieval() {
        var catalog = new InMemoryKnowledgeCatalog();
        var doc = catalog.addDocument("generation fixture", "Content approved before generation.");
        catalog.approveDocument(doc.id());
        var evidence = catalog.approvedCorpus().stream().filter(e -> e.title().equals(doc.title())).toList();
        var narration = mock(NarrationModel.class);
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(), any(), eq(true), anyString()))
                .thenAnswer(call -> { catalog.withdrawDocument(doc.id()); return draft(evidence); });
        var tools = mock(HospitalToolExecutor.class);
        var prepared = new TriageEngine.PreparedEvidence(new KnowledgeCatalog.Retrieval(evidence, true, "MATCHED"),
                new ToolTrace("medical_knowledge_retrieve", "fixture", "", "MATCHED", 1, true, ""));
        var result = new GuidanceAnswerService(tools, narration, mock(CallLogStore.class), catalog)
                .answer("fixture", "fixture", "", List.of(), stage -> {}, prepared, refs -> "fallback");
        assertEquals("VALIDATION_BLOCKED", result.modelStatus());
        assertEquals(0, result.knowledgeHits());
        assertTrue(result.answerEvidence().retrievedReferences().isEmpty());
        verifyNoInteractions(tools);
    }
    @Test void assessmentBlocksWithdrawnEvidenceAndEmergencyStillBypassesNarration() {
        var catalog = new InMemoryKnowledgeCatalog();
        var doc = catalog.addDocument("头痛合成资料", "头痛三天伴恶心，合成测试片段。");
        catalog.approveDocument(doc.id());
        var evidence = catalog.approvedCorpus().stream().filter(e -> e.title().equals(doc.title())).toList();
        var safety = new com.aihospital.triage.domain.TriageSafetyPolicy();
        var directory = new com.aihospital.catalog.infrastructure.demo.DemoDoctorDirectory();
        var calls = new com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore();
        var tools = new HospitalToolExecutor(new com.aihospital.tools.infrastructure.demo.InMemoryToolRegistry(),
                directory, catalog, safety, calls);
        var narration = mock(NarrationModel.class);
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(), any(), eq(false), anyString()))
                .thenAnswer(call -> { catalog.withdrawDocument(doc.id()); return draft(evidence); });
        var engine = new com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine(safety, directory, catalog,
                narration, calls, tools, new com.aihospital.triage.infrastructure.llm.StructuredDecisionModel("demo", "", "", "", 5));
        var result = engine.triage("fixture", "头痛三天伴恶心", "fixture", List.of());
        assertEquals("VALIDATION_BLOCKED", result.modelStatus());
        assertFalse(result.grounded());
        assertNull(result.doctor());
        assertTrue(result.candidates().stream().allMatch(c -> c.doctor() == null));
        assertTrue(result.evidence().isEmpty());
        // Emergency bypass must not be held hostage by the knowledge store or narration.
        clearInvocations(narration);
        var emergency = engine.triage("fixture", "突然剧烈胸痛，呼吸困难，出冷汗", "fixture", List.of());
        assertEquals(com.aihospital.triage.domain.Disposition.EMERGENCY, emergency.riskLevel());
        assertEquals("SAFETY_RULE", emergency.modelStatus());
        verifyNoInteractions(narration);
    }
}
