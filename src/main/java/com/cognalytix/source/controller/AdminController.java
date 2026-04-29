package com.cognalytix.source.controller;

import com.cognalytix.source.dto.AdminSetUserPasswordRequest;
import com.cognalytix.source.dto.AdminUserActionResponse;
import com.cognalytix.source.dto.ChangeServerPepperRequest;
import com.cognalytix.source.dto.MessageResponse;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.AdminService;
import com.cognalytix.source.service.ServerSecurityAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("@adminAuth.allow(authentication)")
public class AdminController {

    private final AdminService adminService;
    private final ServerSecurityAdminService serverSecurityAdminService;

    public AdminController(AdminService adminService, ServerSecurityAdminService serverSecurityAdminService) {
        this.adminService = adminService;
        this.serverSecurityAdminService = serverSecurityAdminService;
    }

    @PutMapping("/security/password-pepper")
    public MessageResponse rotatePasswordPepper(
            @Valid @RequestBody ChangeServerPepperRequest request,
            Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return serverSecurityAdminService.rotatePasswordPepper(principal.getId(), request);
    }

    @PutMapping("/users/{id}/deactivate")
    public AdminUserActionResponse deactivate(@PathVariable("id") UUID userId, Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        var user = adminService.deactivateUser(userId, principal.getId());
        return new AdminUserActionResponse("User account has been deactivated.", user);
    }

    @PutMapping("/users/{id}/password")
    public AdminUserActionResponse setUserPassword(
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminSetUserPasswordRequest request) {
        var user = adminService.setUserPassword(userId, request);
        return new AdminUserActionResponse("Password has been reset for this user.", user);
    }
}
