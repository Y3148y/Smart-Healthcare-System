package com.aihospital.shared.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Prevents exposing passwordless demo accounts on a public interface. */
@Component
public class DemoDeploymentGuard {
    private final java.util.function.BooleanSupplier containerRuntime;
    public DemoDeploymentGuard() {
        this(() -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/.dockerenv")));
    }
    DemoDeploymentGuard(java.util.function.BooleanSupplier containerRuntime) { this.containerRuntime = containerRuntime; }
    @Value("${server.address:127.0.0.1}") private String serverAddress;
    @Value("${ai.demo-auth-enabled:true}") private boolean demoAuthEnabled;
    @Value("${ai.demo-local-container:false}") private boolean localContainerDemo;

    @PostConstruct public void validate() {
        if (localContainerDemo && !containerRuntime.getAsBoolean())
            throw new IllegalStateException("本机容器演示开关只用于Docker；宿主机仍应绑定回环地址");
        if (demoAuthEnabled && !("127.0.0.1".equals(serverAddress) || "localhost".equalsIgnoreCase(serverAddress)
                || "::1".equals(serverAddress) || localContainerDemo && "0.0.0.0".equals(serverAddress)))
            throw new IllegalStateException("演示账号仅可绑定本机地址；公开部署前必须接入真实身份认证并关闭 AI_DEMO_AUTH_ENABLED");
        if (localContainerDemo && demoAuthEnabled)
            org.slf4j.LoggerFactory.getLogger(DemoDeploymentGuard.class).warn(
                    "Local container demo enabled: publish ports to 127.0.0.1 only; application cannot verify host port exposure");
    }
}
