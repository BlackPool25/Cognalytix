package com.cognalytix.source.config;

import com.cognalytix.source.security.Peppering;
import com.cognalytix.source.service.PasswordPepperService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder(PasswordPepperService passwordPepperService) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return bcrypt.encode(Peppering.pepperedSha256Hex(rawPassword, passwordPepperService.getEffectivePepper()));
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return bcrypt.matches(
                        Peppering.pepperedSha256Hex(rawPassword, passwordPepperService.getEffectivePepper()),
                        encodedPassword);
            }
        };
    }
}
