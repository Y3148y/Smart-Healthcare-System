package com.aihospital.identity.api;

import com.aihospital.identity.application.DemoLoginService;
import com.aihospital.shared.model.Models.LoginRequest;
import com.aihospital.shared.model.Models.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final DemoLoginService login;
    public AuthController(DemoLoginService login) { this.login = login; }
    @PostMapping("/login") public LoginResponse login(@RequestBody @Valid LoginRequest body) {
        return login.login(body);
    }
}
