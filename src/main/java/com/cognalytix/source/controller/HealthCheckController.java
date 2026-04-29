package com.cognalytix.source.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class HealthCheckController {

    @GetMapping("/health")
    public String checkHealth() {
        return "Database and Security are working properly!";
    }
}
