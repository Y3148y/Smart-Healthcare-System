package com.aihospital.triage.application;

import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.tools.application.AgentToolGateway;
import com.aihospital.tools.domain.AgentTool;
import com.aihospital.tools.domain.AgentToolRun;
import com.aihospital.triage.domain.CurrentRequestIntent;
import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.triage.domain.SessionFacts;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import com.aihospital.triage.domain.TriageStore;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import com.aihospital.triage.domain.TriageRecords.Message;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final SessionFactStore durableFacts;
    private final SessionFactReducer factReducer = new SessionFactReducer();
    public AgentWorkflowCoordinator(AgentWorkflowSettings settings, IntentExtractor extractor, AgentModel model,
            AgentToolGateway gateway, CatalogManagementService catalogue, KnowledgeCatalog knowledge,
            TriageStore store, CallLogStore calls, TriageSafetyPolicy safety, SessionFactStore durableFacts) {
        this.settings = settings;
        this.extractor = extractor;
        this.runner = new AgentRunner(model, gateway, settings.maxSteps(), settings.planningTimeoutSeconds());
        this.catalogue = catalogue;
        this.knowledge = knowledge;
        this.store = store;
        this.calls = calls;
        this.safety = safety;
        this.durableFacts = durableFacts;
    }

    public TriageEngine.PreparedEvidence prepare(Conversation conversation, String patient, String accumulated) {
        try(var invocation=com.aihospital.shared.diagnostics.InvocationContext.open(com.aihospital.shared.diagnostics.InvocationContext.Origin.AGENT)){
            return prepareScoped(conversation,patient,accumulated);
        }
    }
    private TriageEngine.PreparedEvidence prepareScoped(Conversation conversation,String patient,String accumulated){
        if (settings.mode() == AgentWorkflowSettings.Mode.OFF) return null;
        String current = conversation.messages().stream().filter(message -> "USER".equals(message.role()))
                .reduce((left, right) -> right).map(Message::content).orElse("");
        if (safety.assess(accumulated).humanReviewRecommended() || safety.requiresBleedingClarification(accumulated)
                || CurrentRequestIntent.restricted(current)) return null;
        try {
            var input = IntentInput.recent(conversation.messages());
            var extracted = extractor.extract(input);
            audit("意图抽取", extracted.status().name() + "/" + extracted.failureCode(), extracted.inputTokens(),
                    extracted.outputTokens(), extracted.elapsedMs(), extracted.status() == IntentExtractor.Status.ACCEPTED,
                    extracted.status()==IntentExtractor.Status.NOT_CONFIGURED
                            ||extracted.failureCode()!=null&&extracted.failureCode().startsWith("INTENT_CONTEXT_")
                            ?com.aihospital.shared.model.Models.TokenUsage.local()
                            :com.aihospital.shared.model.Models.TokenUsage.reported(extracted.inputTokens(),extracted.outputTokens()));
            if (extracted.status() != IntentExtractor.Status.ACCEPTED) return null;
            durableFacts.stage(patient,conversation,input,extracted.extraction());
            // Historical observations persist, but only this authorized source window feeds tools/model.
            SessionFacts facts = factReducer.merge(SessionFacts.empty(), extracted.extraction(), input,
                    conversation.messages().size());
            if (settings.mode() != AgentWorkflowSettings.Mode.ENABLED) return null;
            return plan(conversation, patient, current, input, extracted, facts);
        } catch (RuntimeException failure) {
            try { audit("Agent执行", "WORKFLOW_FALLBACK", null, null, 0, false); }
            catch (RuntimeException ignored) { /* Preserve the original workflow if audit storage fails. */ }
            return null;
        }
    }

    private TriageEngine.PreparedEvidence plan(Conversation conversation, String patient, String current,
            IntentInput input, IntentExtractor.Result extracted, SessionFacts facts) {
        String messageId = input.messages().get(input.messages().size() - 1).messageId();
        String version = corpusVersion();
        Set<String> departments = enabledDepartments();
        var queries = new HashSet<>(facts.retrievalSymptoms());
        queries.add(current.strip());
        // Identity scope is server-only and never included in the planner prompt.
        var scope = new AgentToolRun(new AgentToolRun.Scope(patient, conversation.session().id(),
                TurnTraceContext.currentOrNewId(), conversation.messages().size(), version,
                planningTools(current, extracted.extraction(), departments), departments, queries), settings.maxToolCalls(),
                Duration.ofSeconds(settings.totalPlanningSeconds()), Duration.ofSeconds(settings.planningTimeoutSeconds()));
        java.util.function.BooleanSupplier fresh = () -> scopeIsCurrent(conversation, patient, messageId, version, departments);
        long started = System.nanoTime();
        var result = runner.run(input, extracted.extraction(), scope, Set.of(AgentTool.MEDICAL_KNOWLEDGE), fresh);
        auditPlan(result, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        if (result.status() != AgentRunner.Status.COMPLETED || !fresh.getAsBoolean()) return null;
        // Only actual tool retrieval, never model final text, reaches narration.
        return scope.snapshots().stream().filter(snapshot -> snapshot.tool().equals(AgentTool.MEDICAL_KNOWLEDGE.code())
                && snapshot.data() instanceof KnowledgeCatalog.Retrieval).reduce((left, right) -> right)
                .map(snapshot -> new TriageEngine.PreparedEvidence((KnowledgeCatalog.Retrieval) snapshot.data(), snapshot.trace()))
                .orElse(null);
    }

    private boolean scopeIsCurrent(Conversation conversation, String patient, String messageId,
            String version, Set<String> departments) {
        String sessionId = conversation.session().id();
        if (store.sessions(patient).stream().noneMatch(session -> session.id().equals(sessionId))) return false;
        var messages = store.messages(sessionId);
        return messages.size() == conversation.messages().size()
                && messages.stream().filter(message -> "USER".equals(message.role())).reduce((left, right) -> right)
                .map(message -> messageId.equals(message.id())).orElse(false)
                && corpusVersion().equals(version) && enabledDepartments().equals(departments);
    }

    private Set<String> enabledDepartments() {
        return catalogue.departments().stream().filter(department -> department.enabled())
                .map(department -> department.name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<AgentTool> planningTools(String current, IntentExtraction extraction, Set<String> departments) {
        var allowed = java.util.EnumSet.of(AgentTool.MEDICAL_KNOWLEDGE);
        if (!departments.isEmpty() && (CurrentRequestIntent.directionRequested(current)
                || CurrentRequestIntent.booking(current) == CurrentRequestIntent.BookingIntent.REQUESTED
                || extraction.intents().contains(IntentExtraction.Intent.FIND_DEPARTMENT)
                || extraction.intents().contains(IntentExtraction.Intent.FIND_DOCTOR)
                || extraction.intents().contains(IntentExtraction.Intent.BOOKING_REQUEST))) {
            allowed.add(AgentTool.DEPARTMENT);
            if (extraction.intents().contains(IntentExtraction.Intent.FIND_DOCTOR)
                    || extraction.intents().contains(IntentExtraction.Intent.BOOKING_REQUEST)
                    || CurrentRequestIntent.booking(current) == CurrentRequestIntent.BookingIntent.REQUESTED)
                allowed.add(AgentTool.SCHEDULE);
        }
        return Set.copyOf(allowed);
    }

    private String corpusVersion() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var document : knowledge.documents().stream().filter(document -> "READY".equals(document.status()))
                    .sorted(Comparator.comparing(document -> document.id())).toList()) {
                digest.update((document.id() + "\u0000" + document.updatedAt() + "\u0000" + document.body() + "\u0000")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void auditPlan(AgentRunner.RunResult result, long elapsed) {
        for (var decision : result.decisions()) {
            audit("Agent工具规划", decision.status().name()
                    + (decision.failureCode() == null ? "" : "/" + decision.failureCode()),
                    decision.inputTokens(), decision.outputTokens(),
                    decision.elapsedMs(), Set.of(AgentModel.Status.TOOLS, AgentModel.Status.DONE).contains(decision.status()));
        }
        audit("Agent执行", result.status().name(), null, null, elapsed, result.status() == AgentRunner.Status.COMPLETED);
    }

    private void audit(String purpose, String status, Integer input, Integer output, long elapsed, boolean success) {
        audit(purpose,status,input,output,elapsed,success,
                "Agent执行".equals(purpose)?com.aihospital.shared.model.Models.TokenUsage.local()
                        :com.aihospital.shared.model.Models.TokenUsage.reported(input,output));
    }
    private void audit(String purpose,String status,Integer input,Integer output,long elapsed,boolean success,
                       com.aihospital.shared.model.Models.TokenUsage usage){
        calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), purpose, "患者", status,
                input == null ? 0 : Math.max(0, input), output == null ? 0 : Math.max(0, output),
                Math.max(0, elapsed), success, List.of(),TurnTraceContext.currentId(),
                usage));
    }

    @Override @PreDestroy public void close() { runner.close(); }
}
