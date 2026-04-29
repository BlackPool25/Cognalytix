package com.cognalytix.source.domain.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SecuritySettingsRepository extends JpaRepository<SecuritySettings, Short> {

    Optional<SecuritySettings> findBySingletonKey(short singletonKey);
}
