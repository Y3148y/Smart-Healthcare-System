package com.aihospital.shared.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Prevents exposing passwordless demo accounts on a public interface. */
@Component
public class DemoDeploymentGuard {
    @Value("${server.address:127.0.0.1}") private String serverAddress;
    @Value("${ai.demo-auth-enabled:true}") private boolean demoAuthEnabled;

    @PostConstruct public void validate() {
        if (demoAuthEnabled && !("127.0.0.1".equals(serverAddress) || "localhost".equalsIgnoreCase(serverAddress)
                || "::1".equals(serverAddress)))
            throw new IllegalStateException("演示账号仅可绑定本机地址；公开部署前必须接入真实身份认证并关闭 AI_DEMO_AUTH_ENABLED");
    }
}
