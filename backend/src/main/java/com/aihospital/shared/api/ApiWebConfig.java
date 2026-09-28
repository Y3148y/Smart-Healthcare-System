package com.aihospital.shared.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiWebConfig implements WebMvcConfigurer {
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:5188", "http://127.0.0.1:5188")
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
    }
}
