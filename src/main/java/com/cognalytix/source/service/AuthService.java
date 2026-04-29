package com.cognalytix.source.service;

import com.cognalytix.source.config.JwtProperties;
import com.cognalytix.source.domain.token.RefreshToken;
import com.cognalytix.source.domain.token.RefreshTokenRepository;
import com.cognalytix.source.domain.user.Role;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.AuthApiResponse;
import com.cognalytix.source.dto.AuthTokensPayload;
import com.cognalytix.source.dto.ChangeOwnPasswordRequest;
import com.cognalytix.source.dto.LoginRequest;
import com.cognalytix.source.dto.MessageResponse;
import com.cognalytix.source.dto.RefreshRequest;
import com.cognalytix.source.dto.RegisterRequest;
import com.cognalytix.source.dto.UserPublicDto;
import com.cognalytix.source.security.AuthUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenHasher refreshTokenHasher;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties,
            RefreshTokenHasher refreshTokenHasher) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenHasher = refreshTokenHasher;
    }

    @Transactional
    public AuthApiResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.USER);
        user.setActive(true);
        userRepository.save(user);
        return issueSession(user, "Registration successful.");
    }

    @Transactional
    public AuthApiResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        return issueSession(user, "Signed in successfully.");
    }

    /**
     * Refresh token rotation (RTR): previous refresh row is revoked; a new opaque refresh token is minted.
     */
    @Transactional
    public AuthApiResponse refresh(RefreshRequest request) {
        String hash = refreshTokenHasher.hashOpaqueToken(request.refreshToken());
        RefreshToken stored =
                refreshTokenRepository.findByTokenHashAndRevokedFalse(hash).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid refresh token"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }
        User user = stored.getUser();
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        AuthApiResponse rotated = issueSession(user, "Access token refreshed.");
        return rotated;
    }

    @Transactional
    public AuthApiResponse logout(RefreshRequest request, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
        String hash = refreshTokenHasher.hashOpaqueToken(request.refreshToken());
        RefreshToken stored =
                refreshTokenRepository.findByTokenHashAndRevokedFalse(hash).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid refresh token"));
        if (!stored.getUser().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Refresh token does not belong to the signed-in user.");
        }
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
        return new AuthApiResponse("You have been signed out.", null, null);
    }

    @Transactional
    public MessageResponse changeOwnPassword(UUID userId, ChangeOwnPasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.deleteByUser_Id(userId);
        return new MessageResponse("Password has been updated. Sign in again on other devices; refresh tokens were cleared.");
    }

    private AuthApiResponse issueSession(User user, String message) {
        String accessToken = jwtService.createAccessToken(user);
        String plainRefresh = newRefreshTokenValue();
        RefreshToken refresh = new RefreshToken();
        refresh.setUser(user);
        refresh.setTokenHash(refreshTokenHasher.hashOpaqueToken(plainRefresh));
        refresh.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS));
        refresh.setRevoked(false);
        refreshTokenRepository.save(refresh);
        long expiresInSeconds = jwtProperties.accessTokenExpiryMs() / 1000;
        AuthTokensPayload tokens = new AuthTokensPayload(accessToken, plainRefresh, "Bearer", expiresInSeconds);
        return new AuthApiResponse(message, tokens, toPublic(user));
    }

    private static UserPublicDto toPublic(User user) {
        return new UserPublicDto(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }

    private static String newRefreshTokenValue() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
