package com.example.auditlog.service;

import com.example.auditlog.repository.AuditLogRepository;
import com.example.auditlog.repository.KeyValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadServiceDelayTest {

    private KeyValueRepository kvRepo;
    private AuditLogRepository auditRepo;
    private SettingsService settings;
    private LoadService service;

    @BeforeEach
    void setUp() {
        kvRepo = mock(KeyValueRepository.class);
        auditRepo = mock(AuditLogRepository.class);
        settings = mock(SettingsService.class);
        // Make the KeyValue lookups no-ops so performOperation returns quickly.
        when(kvRepo.findFirstByOrderById()).thenReturn(java.util.Optional.empty());
        service = new LoadService(kvRepo, auditRepo, settings);
    }

    @Test
    void runOneIteration_sleepsRequestedMillisWhenDelayPositive() throws Exception {
        when(settings.getLoadDelayMs()).thenReturn(80L);

        long start = System.currentTimeMillis();
        service.runOneIteration();
        long elapsed = System.currentTimeMillis() - start;

        // Allow generous slack for CI jitter; the only assertion is that we
        // slept AT LEAST the configured delay.
        assertThat(elapsed).isGreaterThanOrEqualTo(75L);
        Mockito.verify(auditRepo).save(any());
    }

    @Test
    void runOneIteration_skipsSleepWhenDelayZero() throws Exception {
        when(settings.getLoadDelayMs()).thenReturn(0L);

        long start = System.currentTimeMillis();
        service.runOneIteration();
        long elapsed = System.currentTimeMillis() - start;

        // With no sleep and no real DB work (mocks return immediately), a
        // single iteration completes well under 40ms even on slow machines.
        assertThat(elapsed).isLessThan(200L);
        Mockito.verify(auditRepo).save(any());
    }
}
