package com.example.auditlog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
public class AppConfig {

    @Bean
    public AtomicBoolean warmupComplete() {
        return new AtomicBoolean(false);
    }
}
