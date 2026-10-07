package com.aihospital.review;

import com.aihospital.review.application.ReviewAccessPolicy;
import com.aihospital.review.application.HumanReviewService;
import com.aihospital.review.domain.ReviewAccessAudit;
import com.aihospital.shared.security.JwtService;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.api.AdminHumanReviewController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewAccessPolicyTest {
    @Test void emptyDefaultAndExactMatchingCannotGrantWildcardOrSubstringAccess() {
        assertFalse(new ReviewAccessPolicy("").mayRead("admin"));
        var policy = new ReviewAccessPolicy("reviewer, second,reviewer");
        assertTrue(policy.mayRead("reviewer")); assertTrue(policy.mayRead("second"));
        assertFalse(policy.mayRead("reviewer-other")); assertFalse(policy.mayRead(null));
        assertFalse(new ReviewAccessPolicy("*").mayRead("admin"));
    }
    @Test void auditFailurePreventsAuthorizedSummaryReturning() {
        var service = mock(HumanReviewService.class);
        var audit = mock(ReviewAccessAudit.class);
        doThrow(new IllegalStateException("storage unavailable")).when(audit).record("reviewer", "request", "READ");
        var controller = new AdminHumanReviewController(new RoleGuard(), service, new ReviewAccessPolicy("reviewer"), audit);
        assertThrows(IllegalStateException.class, () -> controller.summary("request",
                "Bearer " + new JwtService().issue("reviewer", "ADMIN")));
        verify(service).summary("request"); verify(audit).record("reviewer", "request", "READ");
    }
    @Test void sameSubjectPatientRoleCannotUseAdministratorGrant() {
        var service = mock(HumanReviewService.class);
        var audit = mock(ReviewAccessAudit.class);
        var controller = new AdminHumanReviewController(new RoleGuard(), service, new ReviewAccessPolicy("reviewer"), audit);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.summary("request",
                "Bearer " + new JwtService().issue("reviewer", "PATIENT")));
        verifyNoInteractions(service, audit);
    }
}
