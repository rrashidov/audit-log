package com.example.auditlog;

import com.example.auditlog.service.ConsolidationService;
import com.example.auditlog.service.LoadService;
import com.example.auditlog.service.WarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class AuditLogApplication implements ApplicationRunner {

    private final WarmupService warmupService;
    private final ConsolidationService consolidationService;
    private final LoadService loadService;
    private final AtomicBoolean warmupComplete;

    public static void main(String[] args) {
        SpringApplication.run(AuditLogApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        warmupService.warmup();
        // Catch up any consolidations missed during downtime before the
        // scheduler is allowed to fire. Runs in dependency order
        // (minute -> ... -> year) so each level sees its source rows ready.
        consolidationService.catchUp();
        warmupComplete.set(true);
        log.info("Warmup and catch-up complete, starting load service");
        loadService.startLoad();
    }
}
