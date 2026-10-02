package com.aihospital.triage.domain;

/**
 * The triage disposition vocabulary and the rules that follow from it.
 *
 * <p>Incident D4 was a contradiction between three places that each decided "may this be
 * booked" on their own: the engine attached a doctor, the conversation reported the session
 * as 已完成分诊, and the booking entry point accepted the slot. The predicates now live here
 * so a disposition can never be bookable in one place and not in another.
 *
 * <p>未经临床审核。This is a demo disposition model, not a clinical triage standard.
 */
public final class Disposition {
    /** Stop the routine flow and point to emergency care. Never bookable. */
    public static final String EMERGENCY = "紧急";
    /** Needs offline assessment today. Never bookable in the demo: there is no emergency
     *  slot pool and no clinical review loop yet. A separate referral flow is required once
     *  real hospital integration exists. */
    public static final String URGENT = "尽快就医";
    /** Insufficient information. Never bookable. */
    public static final String PENDING = "待补充信息";
    /** Several departments fit; bookable once grounded and a slot is verified. */
    public static final String MULTI = "多科室参考";
    /** Bookable once grounded and a slot is verified. */
    public static final String ROUTINE = "普通";

    private Disposition() {}

    /**
     * A disposition may carry a doctor and consume a simulated slot only when it is not an
     * emergency, not urgent, and not awaiting information.
     */
    public static boolean isBookable(String riskLevel) {
        return !EMERGENCY.equals(riskLevel) && !URGENT.equals(riskLevel) && !PENDING.equals(riskLevel);
    }

    /**
     * The conversation status for a disposition. A session may only report 已完成分诊 when
     * the disposition itself is bookable, so the status can never imply a completed triage
     * that cannot be booked.
     */
    public static String sessionStatus(String riskLevel) {
        if (EMERGENCY.equals(riskLevel)) return "紧急提示";
        if (URGENT.equals(riskLevel)) return "建议尽快就医";
        if (PENDING.equals(riskLevel)) return PENDING;
        return "已完成分诊";
    }
}
