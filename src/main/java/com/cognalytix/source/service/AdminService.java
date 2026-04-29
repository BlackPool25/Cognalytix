package com.cognalytix.source.service;

import com.cognalytix.source.domain.token.RefreshTokenRepository;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.AdminSetUserPasswordRequest;
import com.cognalytix.source.dto.UserPublicDto;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserPublicDto deactivateUser(UUID targetUserId, UUID actorUserId) {
        if (targetUserId.equals(actorUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot deactivate your own account.");
        }
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(false);
        userRepository.save(user);
        return new UserPublicDto(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }

    @Transactional
    public UserPublicDto setUserPassword(UUID targetUserId, AdminSetUserPasswordRequest request) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.deleteByUser_Id(targetUserId);
        return new UserPublicDto(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }
}
