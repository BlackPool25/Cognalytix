package com.cognalytix.source.controller;

import com.cognalytix.source.dto.AuthApiResponse;
import com.cognalytix.source.dto.ChangeOwnPasswordRequest;
import com.cognalytix.source.dto.LoginRequest;
import com.cognalytix.source.dto.MessageResponse;
import com.cognalytix.source.dto.RefreshRequest;
import com.cognalytix.source.dto.RegisterRequest;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthApiResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthApiResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthApiResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public AuthApiResponse logout(@Valid @RequestBody RefreshRequest request, Authentication authentication) {
        return authService.logout(request, authentication);
    }

    @PutMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public MessageResponse changePassword(@Valid @RequestBody ChangeOwnPasswordRequest request, Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return authService.changeOwnPassword(principal.getId(), request);
    }
}
