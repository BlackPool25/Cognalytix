package com.cognalytix.source.service;

import com.cognalytix.source.config.AppSecurityProperties;
import com.cognalytix.source.domain.settings.SecuritySettings;
import com.cognalytix.source.domain.settings.SecuritySettingsRepository;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective server pepper: DB singleton when present, otherwise {@code app.security.password-pepper}.
 */
@Service
public class PasswordPepperService {

    private final SecuritySettingsRepository securitySettingsRepository;
    private final AppSecurityProperties appSecurityProperties;

    public PasswordPepperService(
            SecuritySettingsRepository securitySettingsRepository,
            AppSecurityProperties appSecurityProperties) {
        this.securitySettingsRepository = securitySettingsRepository;
        this.appSecurityProperties = appSecurityProperties;
    }

    public String getEffectivePepper() {
        return securitySettingsRepository
                .findBySingletonKey(SecuritySettings.SINGLETON_KEY)
                .map(SecuritySettings::getPasswordPepper)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(appSecurityProperties.passwordPepper());
    }
}
