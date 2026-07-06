package com.example.auditlog;

import com.example.auditlog.service.ConsolidationService;
import com.example.auditlog.service.LoadService;
import com.example.auditlog.service.WarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class StartupRunner implements ApplicationRunner {

    private final WarmupService warmupService;
    private final ConsolidationService consolidationService;
    private final LoadService loadService;
    private final AtomicBoolean warmupComplete;

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
