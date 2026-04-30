package com.cognalytix.source.service;

import com.cognalytix.source.domain.settings.SecuritySettings;
import com.cognalytix.source.domain.settings.SecuritySettingsRepository;
import com.cognalytix.source.domain.token.RefreshTokenRepository;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.auth.ChangeServerPepperRequest;
import com.cognalytix.source.dto.MessageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ServerSecurityAdminService {

    private final UserRepository userRepository;
    private final SecuritySettingsRepository securitySettingsRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public ServerSecurityAdminService(
            UserRepository userRepository,
            SecuritySettingsRepository securitySettingsRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.securitySettingsRepository = securitySettingsRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Rotates the server pepper. Verifies the acting admin's password with the <em>current</em> pepper, persists the new
     * pepper, re-encodes <strong>only this admin's</strong> password for the new pepper, and revokes all refresh tokens.
     * <p>
     * All other users remain hashed with the old digest formula and <strong>cannot sign in</strong> until an admin
     * assigns them a new password via {@code PUT /api/admin/users/{id}/password}.
     */
    @Transactional
    public MessageResponse rotatePasswordPepper(UUID adminUserId, ChangeServerPepperRequest request) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!admin.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
        String adminPlain = request.adminPassword();
        if (!passwordEncoder.matches(adminPlain, admin.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin password is incorrect");
        }

        SecuritySettings settings = securitySettingsRepository
                .findBySingletonKey(SecuritySettings.SINGLETON_KEY)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Security settings not bootstrapped"));
        String trimmed = request.newPepper().trim();
        settings.setPasswordPepper(trimmed);
        securitySettingsRepository.saveAndFlush(settings);

        admin.setPasswordHash(passwordEncoder.encode(adminPlain));
        userRepository.save(admin);

        refreshTokenRepository.deleteAll();

        return new MessageResponse(
                "Server password pepper has been rotated. Your account was re-keyed to the new pepper. "
                        + "All refresh sessions were revoked. "
                        + "Other users cannot sign in until an admin sets a new password for each account "
                        + "(their stored hashes still reflect the previous pepper).");
    }
}
