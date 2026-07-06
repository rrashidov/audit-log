package com.example.auditlog.service;

import com.example.auditlog.repository.KeyValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarmupService {

    private final KeyValueRepository keyValueRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.warmup.target:1000000}")
    private long warmupTarget;

    private static final int BATCH_SIZE = 1000;

    public void warmup() {
        long existing = keyValueRepository.count();
        long needed = warmupTarget - existing;

        if (needed <= 0) {
            log.info("Warmup complete: {} entities already exist (target: {})", existing, warmupTarget);
            return;
        }

        log.info("Warmup started: {} entities exist, need to create {} more to reach target {}", existing, needed, warmupTarget);

        long created = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        while (created < needed) {
            batch.clear();
            int batchCount = (int) Math.min(BATCH_SIZE, needed - created);
            for (int i = 0; i < batchCount; i++) {
                batch.add(new Object[]{UUID.randomUUID().toString(), UUID.randomUUID().toString()});
            }
            jdbcTemplate.batchUpdate("INSERT IGNORE INTO key_value (id, value) VALUES (?, ?)", batch);
            created += batchCount;

            if (created % 10000 == 0 || created == needed) {
                double progress = (double) created / needed * 100;
                log.info("Warmup progress: {}/{} ({} %)", created, needed, String.format("%.1f", progress));
            }
        }

        log.info("Warmup complete: created {} entities, total: {}", created, warmupTarget);
    }
}
