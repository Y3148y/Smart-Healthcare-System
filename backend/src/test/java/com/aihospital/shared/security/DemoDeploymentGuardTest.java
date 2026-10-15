package com.aihospital.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class DemoDeploymentGuardTest {
    private DemoDeploymentGuard guard(String address, boolean enabled, boolean local, boolean container) {
        var guard = new DemoDeploymentGuard(() -> container);
        ReflectionTestUtils.setField(guard, "serverAddress", address);
        ReflectionTestUtils.setField(guard, "demoAuthEnabled", enabled);
        ReflectionTestUtils.setField(guard, "localContainerDemo", local);
        return guard;
    }
    @Test void normalLoopbackDemoStillWorksWithoutExceptionFlag() {
        for (String address : new String[]{"127.0.0.1", "localhost", "::1"})
            assertDoesNotThrow(() -> guard(address, true, false, false).validate());
    }
    @Test void wildcardBindingRemainsBlockedByDefaultEvenInDocker() {
        assertThrows(IllegalStateException.class, () -> guard("0.0.0.0", true, false, true).validate());
    }
    @Test void explicitlyAcknowledgedDockerDemoCanBindWildcard() {
        assertDoesNotThrow(() -> guard("0.0.0.0", true, true, true).validate());
    }
    @Test void flagDoesNotAuthorizeHostOrArbitraryInterface() {
        assertThrows(IllegalStateException.class, () -> guard("0.0.0.0", true, true, false).validate());
        assertThrows(IllegalStateException.class, () -> guard("192.0.2.1", true, true, true).validate());
        assertThrows(IllegalStateException.class, () -> guard("::", true, true, true).validate());
    }
    @Test void disablingDemoAuthRetainsOriginalNonLoopbackBehavior() {
        assertDoesNotThrow(() -> guard("0.0.0.0", false, false, false).validate());
    }
}
