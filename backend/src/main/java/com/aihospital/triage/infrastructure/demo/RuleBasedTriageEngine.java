package com.aihospital.triage.infrastructure.demo;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Demo routing policy. The LLM may explain the result but cannot replace safety decisions. */
@Component
public class RuleBasedTriageEngine implements TriageEngine {
    private final TriageSafetyPolicy safety;
    private final DoctorDirectory doctors;
    private final KnowledgeCatalog knowledge;
    private final NarrationModel narration;
    private final CallLogStore calls;
    private final AtomicInteger callIds = new AtomicInteger(100);

    public RuleBasedTriageEngine(TriageSafetyPolicy safety, DoctorDirectory doctors,
                                 KnowledgeCatalog knowledge, NarrationModel narration, CallLogStore calls) {
        this.safety = safety;
        this.doctors = doctors;
        this.knowledge = knowledge;
        this.narration = narration;
        this.calls = calls;
    }

    @Override public boolean requiresImmediateCare(String symptoms) { return safety.requiresImmediateCare(symptoms); }

    /**
     * A known symptom alone is useful for a conversation, but normally is not enough
     * to attach a simulated appointment.  We wait for a duration/change/associated
     * symptom, unless the user explicitly asks for a booking direction or a possible
     * fracture needs prompt offline assessment.
     */
    @Override public boolean needsClarification(String symptoms) {
        if (requiresImmediateCare(symptoms)) return false;
        List<DepartmentCandidate> candidates = candidatesFor(symptoms);
        if (candidates.isEmpty()) return true;
        if (possibleFracture(symptoms) || hasBookingIntent(symptoms)) return false;
        return !(hasTimeCourse(symptoms) && hasClinicalQualifier(symptoms));
    }

    @Override public String clarificationPrompt(String text) {
        List<DepartmentCandidate> candidates = candidatesFor(text);
        String departments = candidates.stream().map(DepartmentCandidate::department)
                .reduce("", (left, right) -> left.isBlank() ? right : left + "、" + right);
        String fallback = guidedFallback(text, candidates);
        List<Evidence> evidence = knowledge.search(safety.removeNegatedRedFlags(text) + " " + departments).stream().limit(2).toList();
        NarrationModel.Answer answer = narration.guide(text, departments,
                evidence.stream().map(Evidence::excerpt).reduce("", (left, right) -> left + " " + right), fallback);
        calls.record(new CallLog("call" + callIds.incrementAndGet(), LocalDateTime.now(), "预问诊引导", "患者",
                answer.modelName().isBlank() ? answer.status() : answer.modelName() + "/" + answer.status(),
                0, 0, 0, true, List.of(new ToolTrace("medical_knowledge_retrieve", "医学知识库检索", departments,
                "为多轮预问诊提供一般健康信息", 58))));
        return answer.text();
    }

    @Override public TriageResult triage(String sessionId, String text, String user) {
        boolean urgent = safety.requiresImmediateCare(text);
        List<DepartmentCandidate> candidates = urgent ? List.of() : candidatesFor(text);
        String department = urgent ? "急诊科" : candidates.size() > 1 || candidates.isEmpty()
                ? "全科医学科" : candidates.get(0).department();
        List<ToolTrace> trace = List.of(
                new ToolTrace("symptom_tag_search", "症状标签检索", text, "识别到与" + department + "相关的症状标签", 38),
                new ToolTrace("medical_knowledge_retrieve", "医学知识库检索", department, "命中 2 条经审核知识片段", 91),
                new ToolTrace("department_search", "科室查询", department, "确认推荐科室存在", 24),
                new ToolTrace("doctor_schedule_search", "医生出诊排班查询", department,
                        urgent ? "紧急情况不查询普通号源" : "返回 1 个可约号源", 45));
        String retrievalQuery = safety.removeNegatedRedFlags(text) + " " + department + (urgent ? " 急诊 红旗症状" : "");
        List<Evidence> evidence = knowledge.search(retrievalQuery).stream().limit(2).toList();
        Doctor doctor = urgent ? null : doctors.doctors(department).stream().findFirst().orElse(null);
        boolean possibleFracture = possibleFracture(text);
        String safetyTip = urgent ? safety.emergencyAdvice(text)
                : possibleFracture ? "如果怀疑骨折，请尽快到线下医疗机构评估；若骨头外露、伤口大量出血或肢体明显变形，应立即急诊。模拟预约不能代替及时就医。"
                : candidates.size() > 1 ? "症状涉及多个科室方向，建议先咨询全科或人工导诊；本建议不构成诊断、处方或治疗意见。"
                : "本建议仅用于辅助分诊和挂号参考，不构成诊断、处方或治疗意见。";
        String fallback = fallbackAnswer(department, urgent, candidates);
        NarrationModel.Answer answer = urgent ? new NarrationModel.Answer(fallback, "SAFETY_RULE", "")
                : narration.explain(text, department,
                        candidates.stream().map(DepartmentCandidate::department)
                                .reduce("", (left, right) -> left.isBlank() ? right : left + "、" + right),
                        evidence.stream().map(Evidence::excerpt).reduce("", (left, right) -> left + " " + right), fallback);
        int confidence = urgent ? 100 : "全科医学科".equals(department) ? 55 : 72;
        String risk = urgent ? "紧急" : possibleFracture ? "尽快就医" : candidates.size() > 1
                ? "多科室参考" : confidence < 60 ? "待补充信息" : "普通";
        TriageResult result = new TriageResult(sessionId, risk, confidence, department, doctor, answer.text(),
                safetyTip, evidence, trace, candidates, answer.status(), answer.modelName(), LocalDateTime.now());
        calls.record(new CallLog("call" + callIds.incrementAndGet(), LocalDateTime.now(), "分诊Agent", user,
                answer.modelName().isBlank() ? answer.status() : answer.modelName() + "/" + answer.status(),
                0, 0, 0, true, trace));
        return result;
    }

    private List<DepartmentCandidate> candidatesFor(String text) {
        List<DepartmentCandidate> found = new ArrayList<>();
        if (text.matches("(?s).*(头晕|眩晕|头痛|头昏|站不稳).*"))
            found.add(new DepartmentCandidate("神经内科", "描述中有头晕、头痛或平衡相关不适", null));
        boolean menstrual = text.matches("(?s).*(痛经|经期腹痛|月经.{0,6}(痛|疼)|姨妈.{0,6}(痛|疼)).*");
        if (text.matches("(?s).*(胃|反酸|腹泻|便秘|消化不良).*") || text.contains("腹痛") && !menstrual)
            found.add(new DepartmentCandidate("消化内科", "描述中有腹部或消化道相关不适", null));
        if (text.matches("(?s).*(咳|痰|喘|胸闷|嗓子疼|嗓子痛|喉咙疼|喉咙痛|咽痛|咽喉痛).*"))
            found.add(new DepartmentCandidate("呼吸内科", "描述中有咳嗽、咽喉或呼吸道相关不适", null));
        if (text.matches("(?s).*(肩颈痛|颈肩痛|关节痛|腰痛|扭伤|骨折|摔断|摔伤|骨头断|手.{0,3}断了|脚.{0,3}断了).*"))
            found.add(new DepartmentCandidate("骨科", "描述中有外伤、疑似骨折或骨关节不适，需线下评估", null));
        if (menstrual) found.add(new DepartmentCandidate("妇科", "描述中有经期疼痛相关不适", null));
        return found;
    }

    private boolean hasBookingIntent(String text) {
        return text.matches("(?s).*(挂什么科|看什么科|哪个科|挂号|预约|就诊方向).*" );
    }

    private boolean hasTimeCourse(String text) {
        return text.matches("(?s).*(今天|昨天|前天|刚刚|近日|最近|反复|持续|加重|缓解|突然|[0-9一二两三四五六七八九十半]+\\s*(小时|天|周|个月|年)).*");
    }

    private boolean hasClinicalQualifier(String text) {
        return text.matches("(?s).*(发热|体温|咳痰|痰|肿|麻|无力|外伤|摔|扭|经量|周期|恶心|呕吐|反酸|腹泻|便秘|出汗|无|没有|否认|不伴|影响|夜间).*" );
    }

    private boolean possibleFracture(String text) {
        return text != null && text.matches("(?s).*(骨折|摔断|骨头断|手摔断|脚摔断).*" );
    }

    private String guidedFallback(String text, List<DepartmentCandidate> candidates) {
        if (candidates.isEmpty())
            return "我还不能据此判断合适的就医方向，也不会直接生成预约建议。请先说说最不舒服的部位、从什么时候开始，以及有没有明显加重或伴随不适。";
        if (candidates.size() > 1)
            return "你提到的症状可能涉及" + candidates.stream().map(DepartmentCandidate::department).reduce("", (a, b) -> a + "、" + b)
                    + "等不同方向。线上无法判断它们是否相关；请先告诉我哪一种最困扰你、持续多久、近期是否明显加重。确认后我会分别给出可预约方向。";
        String department = candidates.get(0).department();
        if ("呼吸内科".equals(department))
            return "咽痛、咳嗽等症状常见于呼吸道不适，但线上不能据此诊断。为了判断是否适合普通门诊，请问症状持续多久了，是否发热、咳痰或逐渐加重？目前暂不生成预约建议。";
        if ("神经内科".equals(department))
            return "头晕或头痛有多种原因，线上不能仅凭一句话确定原因。请问是突然开始还是反复出现、持续多久，是否伴随走路不稳或恶心？如出现说话不清或肢体无力，请立即急诊。";
        if ("消化内科".equals(department))
            return "胃部或腹部不适可能与消化系统有关，但暂不能下结论。请问最明显的位置、持续多久，以及有没有呕吐、腹泻、反酸或黑便？确认后再生成预约方向。";
        if ("妇科".equals(department))
            return "经期疼痛较常见，但线上不能判断具体原因。请问疼痛是否与平时不同、持续多久，以及经量或周期是否有明显变化？确认后我再给出预约参考。";
        if ("骨科".equals(department))
            return "外伤或关节疼痛通常需要结合受伤方式和活动情况评估。请问何时受伤、疼痛或肿胀是否加重、能否正常活动？若明显变形、出血不止或骨头外露，请立即急诊。";
        return "我会先帮你梳理症状，再决定是否生成预约建议。请补充持续时间和最明显的伴随不适。";
    }

    private String fallbackAnswer(String department, boolean urgent, List<DepartmentCandidate> candidates) {
        if (urgent) return "你描述的症状可能需要紧急评估。请立即拨打当地急救电话或前往急诊，不要等待普通门诊预约。";
        if (candidates.size() > 1) return "你提到了多个不同方向的不适，系统分别列出相关科室和模拟医生供参考；不能仅凭聊天判断它们是否有关。请优先处理更严重或突然出现的问题，必要时先由全科或人工导诊协调。";
        if ("骨科".equals(department)) return "你描述了外伤或疑似骨折，建议尽快到线下医疗机构由骨科或急诊评估，不要等待模拟预约来确认是否骨折。";
        if ("妇科".equals(department)) return "你描述了经期疼痛，可考虑妇科或全科评估；如果疼痛明显加重或与平时不同，请及时线下就医。";
        if ("神经内科".equals(department)) return "头晕、想吐可能有多种原因，单凭这句话无法判断。先考虑神经内科或全科评估：是突然开始还是反复发作？有没有走路不稳、肢体无力、说话不清或剧烈头痛？如有这些新出现的症状，请立即急诊。";
        if ("消化内科".equals(department)) return "你描述的胃部或腹部不适可先咨询消化内科，但现在无法判断原因。请补充持续多久、疼痛位置，以及有没有反复呕吐、呕血或黑便；症状明显加重时应及时就医。";
        if ("呼吸内科".equals(department)) return "你描述的咳嗽或胸闷可先咨询呼吸内科。请补充持续时间、是否发热或咳痰；若出现明显呼吸困难或持续胸痛，请立即急诊。";
        return "目前的信息不足以可靠推荐专科，建议先由全科医学科评估。请补充最不舒服的部位、持续时间，以及是否有发热、胸痛、呼吸困难或意识变化。";
    }
}
