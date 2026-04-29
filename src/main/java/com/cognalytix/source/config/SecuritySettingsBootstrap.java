package com.cognalytix.source.config;

import com.cognalytix.source.domain.settings.SecuritySettings;
import com.cognalytix.source.domain.settings.SecuritySettingsRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code security_settings} from {@code app.security.password-pepper} when the table is empty.
 */
@Component
@Order(0)
public class SecuritySettingsBootstrap implements ApplicationRunner {

    private final SecuritySettingsRepository securitySettingsRepository;
    private final AppSecurityProperties appSecurityProperties;

    public SecuritySettingsBootstrap(
            SecuritySettingsRepository securitySettingsRepository,
            AppSecurityProperties appSecurityProperties) {
        this.securitySettingsRepository = securitySettingsRepository;
        this.appSecurityProperties = appSecurityProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (securitySettingsRepository.findBySingletonKey(SecuritySettings.SINGLETON_KEY).isPresent()) {
            return;
        }
        SecuritySettings row = new SecuritySettings();
        row.setPasswordPepper(appSecurityProperties.passwordPepper());
        securitySettingsRepository.save(row);
    }
}
