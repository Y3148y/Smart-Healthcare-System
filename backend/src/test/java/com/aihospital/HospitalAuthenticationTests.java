package com.aihospital;

import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HospitalAuthenticationTests {
    @Autowired MockMvc mvc;
    private final JwtService jwt = new JwtService();

    @Test
    void demoLoginAcceptsKnownAccountsAndRejectsUnknownAccount() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhangsan\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("PATIENT"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"lisi\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("李四"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"unknown\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminApiRequiresValidAdminToken() throws Exception {
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + jwt.issue("张三", "PATIENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + jwt.issue("系统管理员", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void patientApiRejectsTamperedToken() throws Exception {
        String token = jwt.issue("张三", "PATIENT");
        mvc.perform(get("/api/appointments").header("Authorization", "Bearer " + token + "tampered"))
                .andExpect(status().isUnauthorized());
    }
}
