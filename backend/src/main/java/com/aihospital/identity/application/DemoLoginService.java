package com.aihospital.identity.application;

import com.aihospital.shared.security.JwtService;
import com.aihospital.shared.model.Models.LoginRequest;
import com.aihospital.shared.model.Models.LoginResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoLoginService {
    private final JwtService jwt = new JwtService();
    @Value("${ai.demo-auth-enabled:true}") private boolean demoAuthEnabled;
    public LoginResponse login(LoginRequest body) {
        if (!demoAuthEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "演示账号已禁用；请先配置医院身份认证服务");
        String username = body.username() == null ? "" : body.username().trim();
        boolean admin = "admin".equalsIgnoreCase(username);
        boolean patient = "zhangsan".equalsIgnoreCase(username) || "lisi".equalsIgnoreCase(username);
        if ((!admin && !patient) || body.password() == null || body.password().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        String name = admin ? "系统管理员" : "lisi".equalsIgnoreCase(username) ? "李四" : "张三";
        String role = admin ? "ADMIN" : "PATIENT";
        return new LoginResponse(jwt.issue(name, role), role, name);
    }
}
