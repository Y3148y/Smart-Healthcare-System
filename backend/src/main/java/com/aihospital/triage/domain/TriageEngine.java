package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.shared.model.Models.SafetyAssessment;
import java.util.List;

/** Application boundary for a replaceable triage implementation. */
public interface TriageEngine {
    record Guidance(String text, String modelStatus, int knowledgeHits, int localToolCalls, int toolFailures,
                    AnswerEvidence.Diagnostics answerEvidence) {
        public Guidance(String text, String modelStatus, int knowledgeHits, int localToolCalls, int toolFailures) {
            this(text, modelStatus, knowledgeHits, localToolCalls, toolFailures, null);
        }
    }
    SafetyAssessment assessSafety(String symptoms);
    boolean requiresImmediateCare(String symptoms);
    /**
     * True when the text carries an emergency or urgent signal. Such a text must not be turned
     * into a clarification question, and must still be reported when retrieval finds nothing:
     * an ungrounded result must never downgrade a flagged disposition to 待补充信息.
     */
    default boolean requiresReview(String symptoms) { return assessSafety(symptoms).humanReviewRecommended(); }
    /** True when the dialogue needs a material fact before creating a booking recommendation. */
    boolean needsClarification(String symptoms);
    /** Current request controls intent; accumulated symptoms still control safety and routing. */
    default boolean needsClarification(String symptoms,String currentRequest){return needsClarification(symptoms);}
    /** A safe, non-diagnostic medical explanation plus the most relevant next question. */
    Guidance clarificationPrompt(String symptoms, List<NarrationModel.Turn> history);
    /** Server-side request classification; absent prefetch alone never implies SERVICE_ONLY. */
    enum QueryPurpose { UNDETERMINED, SERVICE_ONLY }
    record PreparedEvidence(java.util.Optional<com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval> retrieval,
                            java.util.Optional<com.aihospital.shared.model.Models.ToolTrace> trace,
                            List<NarrationModel.ServiceState> services, QueryPurpose purpose) {
        public PreparedEvidence(java.util.Optional<com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval> retrieval,
                java.util.Optional<com.aihospital.shared.model.Models.ToolTrace> trace,
                List<NarrationModel.ServiceState> services) {
            this(retrieval, trace, services, QueryPurpose.UNDETERMINED);
        }
        public PreparedEvidence(com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval retrieval,
                com.aihospital.shared.model.Models.ToolTrace trace) {
            this(java.util.Optional.of(retrieval), java.util.Optional.of(trace), List.of());
        }
        public PreparedEvidence(com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval retrieval,
                com.aihospital.shared.model.Models.ToolTrace trace, List<NarrationModel.ServiceState> services) {
            this(java.util.Optional.of(retrieval), java.util.Optional.of(trace), services);
        }
        public PreparedEvidence {
            retrieval = retrieval == null ? java.util.Optional.empty() : retrieval;
            trace = trace == null ? java.util.Optional.empty() : trace;
            services = services == null ? List.of() : List.copyOf(services);
            if (purpose == null) throw new IllegalArgumentException("Prepared query purpose required");
            if (retrieval.isPresent() != trace.isPresent())
                throw new IllegalArgumentException("Prepared retrieval and trace must be present together");
            if (purpose == QueryPurpose.SERVICE_ONLY && retrieval.isPresent())
                throw new IllegalArgumentException("Service-only request cannot carry medical evidence");
        }
        public static PreparedEvidence serviceOnly(List<NarrationModel.ServiceState> services) {
            return new PreparedEvidence(java.util.Optional.empty(), java.util.Optional.empty(), services, QueryPurpose.SERVICE_ONLY);
        }
    }
    default Guidance clarificationPrompt(String symptoms, List<NarrationModel.Turn> history,
            java.util.function.Consumer<TriageProgress> progress, PreparedEvidence prepared) {
        return clarificationPrompt(symptoms, history, progress);
    }
    default Guidance clarificationPrompt(String symptoms,List<NarrationModel.Turn> history,
            java.util.function.Consumer<TriageProgress> progress){return clarificationPrompt(symptoms,history);}
    TriageResult triage(String sessionId, String symptoms, String patient, List<NarrationModel.Turn> history);
    default TriageResult triage(String sessionId,String symptoms,String patient,List<NarrationModel.Turn> history,
            java.util.function.Consumer<TriageProgress> progress){return triage(sessionId,symptoms,patient,history);}
}
