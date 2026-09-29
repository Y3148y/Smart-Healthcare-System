package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.NarrationModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * LangChain4j boundary for an OpenAI-compatible model.  Demo mode deliberately
 * stays deterministic, while a configured deployment can enrich only the
 * human-readable explanation; the structured triage and safety rules remain
 * server controlled.
 */
@Component
public class OptionalNarrationModel implements NarrationModel {
    private static final Logger log = LoggerFactory.getLogger(OptionalNarrationModel.class);
    @Value("${ai.mode:demo}") private String mode;
    @Value("${ai.api-key:}") private String apiKey;
    @Value("${ai.base-url:}") private String baseUrl;
    @Value("${ai.model:}") private String model;
    @Value("${ai.timeout-seconds:35}") private int timeoutSeconds;

    @Override public RuntimeStatus runtimeStatus() {
        boolean configured = "openai-compatible".equalsIgnoreCase(mode) && !apiKey.isBlank() && !model.isBlank();
        return configured
                ? new RuntimeStatus(true, "openai-compatible", model, "模型配置已加载；每次回答的实际成功或降级状态请查看调用观测。")
                : new RuntimeStatus(false, mode, model, "未加载完整模型配置，当前将使用规则与本地知识资料兜底回答。");
    }

    @Override public Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback) {
        if (!"openai-compatible".equalsIgnoreCase(mode) || apiKey.isBlank() || model.isBlank())
            return new Answer(fallback, "DEMO", "");
        try {
            var builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(model)
                    .temperature(0.1)
                    .maxTokens(256)
                    .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                    .maxRetries(0);
            if (!baseUrl.isBlank()) builder.baseUrl(baseUrl);
            String response = builder.build().generate("你是医院预问诊助手。先回答患者已描述的问题；信息足够时直接给出就医方向，不要机械地每次都追问。"
                    + "只有缺失的信息会改变就医方向或安全判断时，才追问最多两个关键问题。"
                    + "目前系统建议的主就医方向是" + department + "，同时识别的相关科室有" + candidateDepartments + "。有多个症状时逐项回应，不要只保留最后一个症状。"
                    + "这些仅是挂号参考而非诊断。不要开药、不要推断具体疾病；若患者提到严重危险信号，提醒立即急诊。"
                    + "请用简洁自然的中文回答，避免套话。参考知识：" + evidence + "。患者原话：" + symptom);
            if (response == null || response.isBlank()) return new Answer(fallback, "FALLBACK", model);
            return new Answer(removeMedicationDirections(response), "LIVE", model);
        } catch (Exception ex) {
            log.warn("LLM narration unavailable; using symptom-specific fallback: {}", ex.toString());
            return new Answer(fallback, "FALLBACK", model);
        }
    }

    @Override public Answer guide(String symptom, String candidateDepartments, String evidence, String fallback) {
        if (!"openai-compatible".equalsIgnoreCase(mode) || apiKey.isBlank() || model.isBlank())
            return new Answer(fallback, "DEMO", "");
        try {
            // The early pre-consultation turn only needs a short acknowledgement and one question.
            // Output is bounded, but compatible providers may have a cold-start delay; do not
            // silently force every such turn to a template before the provider can answer.
            var builder = OpenAiChatModel.builder().apiKey(apiKey).modelName(model).temperature(0.1)
                    .maxTokens(120).timeout(Duration.ofSeconds(Math.max(8, timeoutSeconds))).maxRetries(0);
            if (!baseUrl.isBlank()) builder.baseUrl(baseUrl);
            String response = builder.build().generate("你是医院预问诊助手，正在进行多轮对话的早期信息收集。"
                    + "请先用一两句回答患者已经提到的症状可注意什么，再只问一个最能影响风险判断或挂号方向的问题。"
                    + "不要诊断、开药或催促所有患者补充完整病史；没有危险信号时明确说明还未生成预约推荐。"
                    + "如存在危险信号，只提示立即急诊。可参考方向：" + candidateDepartments
                    + "。知识依据：" + evidence + "。患者原话：" + symptom);
            if (response == null || response.isBlank()) return new Answer(fallback, "FALLBACK", model);
            return new Answer(removeMedicationDirections(response), "LIVE", model);
        } catch (Exception ex) {
            log.warn("LLM conversation unavailable; using guided fallback: {}", ex.toString());
            return new Answer(fallback, "FALLBACK", model);
        }
    }

    private String removeMedicationDirections(String response) {
        String safe = Arrays.stream(response.trim().split("(?<=[。！？!?])"))
                .filter(sentence -> !sentence.matches("(?s).*(处方|用药|服用|吃药|退烧药|抗生素|止痛药|剂量).*"))
                .reduce("", String::concat).trim();
        return safe.isBlank() ? "线上预问诊仅提供就医方向参考，具体用药请由线下医生评估后决定。" : safe;
    }
}
