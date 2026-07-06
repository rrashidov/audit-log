package com.example.auditlog.service;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import com.example.auditlog.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConsolidationService {

    private final AuditLogRepository auditLogRepository;
    private final AtomicBoolean warmupComplete;
    /**
     * Self-reference resolved through the Spring proxy so that calls from
     * catchUp() to the runXxx() methods are intercepted by the @Transactional
     * advice. A direct this.runXxx() call would bypass the proxy and run
     * outside any transaction. @Lazy breaks the constructor cycle and must
     * sit on the constructor parameter so Spring reads it during injection.
     */
    private final ConsolidationService self;

    public ConsolidationService(AuditLogRepository auditLogRepository,
                                AtomicBoolean warmupComplete,
                                @Lazy ConsolidationService self) {
        this.auditLogRepository = auditLogRepository;
        this.warmupComplete = warmupComplete;
        this.self = self;
    }

    /** Snap a timestamp to the start of its bucket for the given target granularity. */
    private static final UnaryOperator<LocalDateTime> MINUTE_BUCKET =
            t -> t.truncatedTo(ChronoUnit.MINUTES);
    private static final UnaryOperator<LocalDateTime> HOUR_BUCKET =
            t -> t.truncatedTo(ChronoUnit.HOURS);
    private static final UnaryOperator<LocalDateTime> DAY_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS);
    private static final UnaryOperator<LocalDateTime> WEEK_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private static final UnaryOperator<LocalDateTime> MONTH_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
    private static final UnaryOperator<LocalDateTime> YEAR_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void consolidateMinute() {
        if (!warmupComplete.get()) return;
        runMinute();
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void consolidateHour() {
        if (!warmupComplete.get()) return;
        runHour();
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void consolidateDay() {
        if (!warmupComplete.get()) return;
        runDay();
    }

    @Scheduled(cron = "0 0 0 * * MON")
    @Transactional
    public void consolidateWeek() {
        if (!warmupComplete.get()) return;
        runWeek();
    }

    @Scheduled(cron = "0 0 0 1 * *")
    @Transactional
    public void consolidateMonth() {
        if (!warmupComplete.get()) return;
        runMonth();
    }

    @Scheduled(cron = "0 0 0 1 1 *")
    @Transactional
    public void consolidateYear() {
        if (!warmupComplete.get()) return;
        runYear();
    }

    /**
     * Startup catch-up. Runs every granularity once, in dependency order, so
     * any consolidations missed during downtime are performed before the
     * regular schedule resumes. Bypasses the warmupComplete gate; callers
     * must ensure warmup has finished and must call this before flipping
     * the flag to true (so scheduled ticks can't race the catch-up).
     */
    public void catchUp() {
        log.info("Consolidation catch-up: starting");
        self.runMinute();
        self.runHour();
        self.runDay();
        self.runWeek();
        self.runMonth();
        self.runYear();
        log.info("Consolidation catch-up: complete");
    }

    @Transactional
    public void runMinute() {
        LocalDateTime cutoff = MINUTE_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.REGULAR, EntryType.MINUTE_CONSOLIDATION, cutoff, MINUTE_BUCKET);
    }

    @Transactional
    public void runHour() {
        LocalDateTime cutoff = HOUR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.MINUTE_CONSOLIDATION, EntryType.HOUR_CONSOLIDATION, cutoff, HOUR_BUCKET);
    }

    @Transactional
    public void runDay() {
        LocalDateTime cutoff = DAY_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.HOUR_CONSOLIDATION, EntryType.DAY_CONSOLIDATION, cutoff, DAY_BUCKET);
    }

    @Transactional
    public void runWeek() {
        LocalDateTime cutoff = WEEK_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.DAY_CONSOLIDATION, EntryType.WEEK_CONSOLIDATION, cutoff, WEEK_BUCKET);
    }

    @Transactional
    public void runMonth() {
        LocalDateTime cutoff = MONTH_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.WEEK_CONSOLIDATION, EntryType.MONTH_CONSOLIDATION, cutoff, MONTH_BUCKET);
    }

    @Transactional
    public void runYear() {
        LocalDateTime cutoff = YEAR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.MONTH_CONSOLIDATION, EntryType.YEAR_CONSOLIDATION, cutoff, YEAR_BUCKET);
    }

    /** Composite grouping key: rows sharing operation type and bucket-start collapse into one row. */
    private record BucketKey(OperationType operationType, LocalDateTime bucketStart) {}

    private void consolidate(EntryType sourceType,
                             EntryType targetType,
                             LocalDateTime cutoff,
                             UnaryOperator<LocalDateTime> bucketStart) {
        List<AuditLog> oldEntries = auditLogRepository.findByEntryTypeAndDateTimeBefore(sourceType, cutoff);
        if (oldEntries.isEmpty()) return;

        Map<BucketKey, List<AuditLog>> groups = oldEntries.stream()
                .collect(Collectors.groupingBy(e -> new BucketKey(
                        e.getOperationType(),
                        bucketStart.apply(e.getDateTime()))));

        List<AuditLog> consolidated = groups.entrySet().stream().map(entry -> {
            BucketKey key = entry.getKey();
            List<AuditLog> group = entry.getValue();
            return AuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .entryType(targetType)
                    .operationType(key.operationType())
                    .dateTime(key.bucketStart())
                    .count(group.stream().mapToInt(AuditLog::getCount).sum())
                    .duration((int) group.stream().mapToInt(AuditLog::getDuration).average().orElse(0))
                    .build();
        }).collect(Collectors.toList());

        auditLogRepository.saveAll(consolidated);
        auditLogRepository.deleteAll(oldEntries);

        log.debug("Consolidated {} {} entries into {} {} entries",
                oldEntries.size(), sourceType, consolidated.size(), targetType);
    }
}
