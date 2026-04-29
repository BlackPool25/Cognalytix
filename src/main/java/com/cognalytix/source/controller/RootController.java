package com.cognalytix.source.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "cognalytix-backend",
                "health", "/actuator/health",
                "register", "POST /api/auth/register",
                "login", "POST /api/auth/login"
        );
    }
}
