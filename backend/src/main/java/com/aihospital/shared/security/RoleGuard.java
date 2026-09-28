package com.aihospital.shared.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RoleGuard {
    private final JwtService jwt = new JwtService();
    public JwtService.Claims require(String authorization, String role) {
        final JwtService.Claims claims;
        try { claims = jwt.verifyBearer(authorization); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
        if (!role.equals(claims.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权访问该功能");
        return claims;
    }
}
