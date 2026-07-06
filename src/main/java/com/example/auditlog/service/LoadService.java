package com.example.auditlog.service;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.entity.KeyValue;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import com.example.auditlog.repository.AuditLogRepository;
import com.example.auditlog.repository.KeyValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoadService {

    private final KeyValueRepository keyValueRepository;
    private final AuditLogRepository auditLogRepository;
    private final SettingsService settingsService;

    private final Random random = new Random();
    private volatile boolean running = false;

    public void startLoad() {
        running = true;
        Thread thread = new Thread(this::runLoop, "load-service");
        thread.setDaemon(true);
        thread.start();
        log.info("Load service started");
    }

    private void runLoop() {
        while (running) {
            try {
                runOneIteration();
            } catch (InterruptedException e) {
                // Honour shutdown / explicit interrupts. Restore the flag
                // (best practice) and exit the loop cleanly.
                running = false;
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error in load loop: {}", e.getMessage());
            }
        }
    }

    /**
     * Package-private for direct testing. Runs exactly one iteration of the
     * load loop: pick a random operation, perform it (audit-logged inside
     * {@link #performOperation}), then sleep the configured delay. The sleep
     * is deliberately OUTSIDE the @Transactional performOperation call — a
     * sleeping thread must not hold a DB connection or row locks.
     */
    void runOneIteration() throws InterruptedException {
        OperationType op = OperationType.values()[random.nextInt(4)];
        performOperation(op);

        long delay = settingsService.getLoadDelayMs();
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    @Transactional
    public void performOperation(OperationType op) {
        long start = System.currentTimeMillis();
        try {
            switch (op) {
                case CREATE -> performCreate();
                case READ -> performRead();
                case UPDATE -> performUpdate();
                case DELETE -> performDelete();
            }
        } catch (DataIntegrityViolationException e) {
            if (op == OperationType.CREATE) {
                // ignore duplicate key on create
            } else {
                throw e;
            }
        } finally {
            long duration = System.currentTimeMillis() - start;
            saveAuditLog(op, duration);
        }
    }

    private void performCreate() {
        KeyValue kv = new KeyValue(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        keyValueRepository.save(kv);
    }

    private void performRead() {
        keyValueRepository.findFirstByOrderById();
    }

    private void performUpdate() {
        Optional<KeyValue> first = keyValueRepository.findFirstByOrderById();
        first.ifPresent(kv -> {
            kv.setValue(UUID.randomUUID().toString());
            keyValueRepository.save(kv);
        });
    }

    private void performDelete() {
        Optional<KeyValue> first = keyValueRepository.findFirstByOrderById();
        first.ifPresent(keyValueRepository::delete);
    }

    private void saveAuditLog(OperationType op, long duration) {
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .entryType(EntryType.REGULAR)
                .operationType(op)
                .dateTime(LocalDateTime.now(ZoneOffset.UTC))
                .count(1)
                .duration((int) duration)
                .build();
        auditLogRepository.save(log);
    }
}
