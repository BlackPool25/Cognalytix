package com.cognalytix.source.domain.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "security_settings")
public class SecuritySettings {

    public static final short SINGLETON_KEY = 1;

    @Id
    @Column(name = "singleton_key", nullable = false)
    private Short singletonKey = SINGLETON_KEY;

    @Column(name = "password_pepper", nullable = false, length = 8192)
    private String passwordPepper;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        updatedAt = now;
    }
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Short getSingletonKey() {
        return singletonKey;
    }

    public String getPasswordPepper() {
        return passwordPepper;
    }

    public void setPasswordPepper(String passwordPepper) {
        this.passwordPepper = passwordPepper;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
