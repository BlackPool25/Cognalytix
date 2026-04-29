package com.cognalytix.source.config;

import com.cognalytix.source.service.RefreshTokenHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtRefreshConfig {

    @Bean
    RefreshTokenHasher refreshTokenHasher(JwtProperties jwtProperties) {
        return new RefreshTokenHasher(jwtProperties.refreshStorageSecret());
    }
}
