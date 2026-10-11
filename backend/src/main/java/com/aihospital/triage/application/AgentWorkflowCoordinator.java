package com.aihospital.triage.application;

import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.tools.application.AgentToolGateway;
import com.aihospital.tools.domain.*;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only agent integration for the existing guidance branch, not reservation or risk delegation. */
@Service
public final class AgentWorkflowCoordinator implements AutoCloseable {
    private final AgentWorkflowSettings settings;
    private final IntentExtractor extractor;
    private final AgentRunner runner;
    private final CatalogManagementService catalogue;
    private final KnowledgeCatalog knowledge;
    private final TriageStore store;
    private final CallLogStore calls;
    private final TriageSafetyPolicy safety;
    public AgentWorkflowCoordinator(AgentWorkflowSettings settings, IntentExtractor extractor, AgentModel model,
            AgentToolGateway gateway, CatalogManagementService catalogue, KnowledgeCatalog knowledge,
            TriageStore store, CallLogStore calls, TriageSafetyPolicy safety) {
        this.settings=settings;this.extractor=extractor;this.runner=new AgentRunner(model,gateway,settings.maxSteps(),settings.planningTimeoutSeconds());
        this.catalogue=catalogue;this.knowledge=knowledge;this.store=store;this.calls=calls;this.safety=safety;
    }

    public TriageEngine.PreparedEvidence prepare(Conversation conversation, String patient, String accumulated) {
        if (settings.mode()==AgentWorkflowSettings.Mode.OFF) return null;
        String current = conversation.messages().stream().filter(m->"USER".equals(m.role()))
                .reduce((a,b)->b).map(Message::content).orElse("");
        if (safety.assess(accumulated).humanReviewRecommended() || safety.requiresBleedingClarification(accumulated)
                || CurrentRequestIntent.restricted(current)) return null;
        try {
            var input=IntentInput.recent(conversation.messages());
            var extracted=extractor.extract(input);
            audit("意图抽取", extracted.status().name()+"/"+extracted.failureCode(), extracted.inputTokens(),
                    extracted.outputTokens(),extracted.elapsedMs(),extracted.status()==IntentExtractor.Status.ACCEPTED);
            if(settings.mode()!=AgentWorkflowSettings.Mode.ENABLED || extracted.status()!=IntentExtractor.Status.ACCEPTED) return null;
            String messageId=input.messages().get(input.messages().size()-1).messageId();
            String version=corpusVersion();
            var departments=catalogue.departments().stream().filter(d->d.enabled()).map(d->d.name())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var queries=new HashSet<String>();queries.add(current.strip());
            extracted.extraction().events().stream().filter(e->e.subject()==IntentExtraction.Subject.SELF
                    && e.temporality()==IntentExtraction.Temporality.CURRENT && e.assertion()==IntentExtraction.Assertion.AFFIRMED)
                    .map(e->e.symptom().strip()).forEach(queries::add);
            // Only recent window + validated extraction + tool data are sent to the model, not this identity scope.
            var scope=new AgentToolRun(new AgentToolRun.Scope(patient,conversation.session().id(),
                    TurnTraceContext.currentOrNewId(),conversation.messages().size(),version,Set.of(AgentTool.values()),departments,queries),
                    settings.maxToolCalls(),Duration.ofSeconds(settings.totalPlanningSeconds()),Duration.ofSeconds(settings.planningTimeoutSeconds()));
            java.util.function.BooleanSupplier fresh=()->store.sessions(patient).stream().anyMatch(s->s.id().equals(conversation.session().id()))
                    && store.messages(conversation.session().id()).size()==conversation.messages().size()
                    && store.messages(conversation.session().id()).stream().filter(m->"USER".equals(m.role()))
                    .reduce((a,b)->b).map(m->messageId.equals(m.id())).orElse(false)
                    && corpusVersion().equals(version) && catalogue.departments().stream().filter(d->d.enabled())
                    .map(d->d.name()).collect(java.util.stream.Collectors.toSet()).equals(departments);
            long planningStarted=System.nanoTime();
            var result=runner.run(input,extracted.extraction(),scope,Set.of(AgentTool.MEDICAL_KNOWLEDGE),fresh);
            for(var decision:result.decisions()) audit("Agent工具规划",decision.status().name(),decision.inputTokens(),
                    decision.outputTokens(),decision.elapsedMs(),Set.of(AgentModel.Status.TOOLS,AgentModel.Status.DONE).contains(decision.status()));
            audit("Agent执行",result.status().name(),null,null,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-planningStarted),
                    result.status()==AgentRunner.Status.COMPLETED);
            if(result.status()!=AgentRunner.Status.COMPLETED || !fresh.getAsBoolean()) return null;
            // No model final text is forwarded. Only the actual executor's typed retrieval can reach narration.
            return scope.snapshots().stream().filter(s->s.tool().equals(AgentTool.MEDICAL_KNOWLEDGE.code())
                    && s.data() instanceof KnowledgeCatalog.Retrieval).reduce((a,b)->b)
                    .map(s->new TriageEngine.PreparedEvidence((KnowledgeCatalog.Retrieval)s.data(),s.trace())).orElse(null);
        } catch(RuntimeException failure) {
            try {audit("Agent执行","WORKFLOW_FALLBACK",null,null,0,false);} catch(RuntimeException ignored) {}
            return null; // Existing safe workflow remains the sole fallback and still checks all evidence.
        }
    }

    private String corpusVersion() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var doc:knowledge.documents().stream().filter(d->"READY".equals(d.status()))
                    .sorted(Comparator.comparing(d->d.id())).toList()) {
                digest.update((doc.id()+"\u0000"+doc.updatedAt()+"\u0000"+doc.body()+"\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private void audit(String purpose,String status,Integer in,Integer out,long elapsed,boolean success) {
        calls.record(new CallLog(UUID.randomUUID().toString(),LocalDateTime.now(),purpose,"患者",status,
                in==null?0:Math.max(0,in),out==null?0:Math.max(0,out),Math.max(0,elapsed),success,List.of()));
    }
    @Override @PreDestroy public void close(){runner.close();}
}
