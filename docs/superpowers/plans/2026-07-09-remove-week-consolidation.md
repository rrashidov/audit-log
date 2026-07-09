# Remove Week-Level Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove week-level consolidation from the audit log pipeline so the chain becomes minute → hour → day → month → year, and simultaneously replace the six independent `@Scheduled` methods with two sequential ones.

**Architecture:** Delete `WEEK_CONSOLIDATION` from the enum, delete `runWeek()` and `WEEK_BUCKET` from `ConsolidationService`, repoint `runMonth()` to consume `DAY_CONSOLIDATION` rows, remove `runWeek` from `catchUp()`, and add two new sequential scheduler methods (`consolidateMinuteAndHour` and `consolidateDayAndAbove`) in place of the six old ones. Update the existing scheduling tests to match the new week-free call sequence.

**Tech Stack:** Java 17, Spring Boot (Spring Scheduling, Spring Data JPA), JUnit 5, Mockito, H2 (test).

## Global Constraints

- All times in UTC (`ZoneOffset.UTC`).
- Scheduled methods must remain on `ConsolidationService` — no new classes.
- Tests use plain unit tests with mocks (no `@SpringBootTest`).
- Test profile is `test` (set via `spring.profiles.active=test` in `src/test/resources/application.properties`).
- Greenfield — no data migration needed.

---

### Task 1: Update the scheduling tests to reflect the week-free call sequence

The test file already exists at `src/test/java/com/example/auditlog/service/ConsolidationServiceSchedulingTest.java` with week references baked in. Update it now so the tests become the failing spec that Task 2 will satisfy.

**Files:**
- Modify: `src/test/java/com/example/auditlog/service/ConsolidationServiceSchedulingTest.java`

**Interfaces:**
- Consumes: `ConsolidationService(AuditLogRepository, AtomicBoolean, ConsolidationService)` constructor; public methods `runMinute()`, `runHour()`, `runDay()`, `runMonth()`, `runYear()`, `consolidateMinuteAndHour()`, `consolidateDayAndAbove()`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace the entire test file with the week-free version**

```java
package com.example.auditlog.service;

import com.example.auditlog.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

class ConsolidationServiceSchedulingTest {

    private ConsolidationService service;
    private ConsolidationService spy;

    @BeforeEach
    void setUp() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean warmupComplete = new AtomicBoolean(true);
        service = new ConsolidationService(repo, warmupComplete, null);
        spy = spy(service);
        doNothing().when(spy).runMinute();
        doNothing().when(spy).runHour();
        doNothing().when(spy).runDay();
        doNothing().when(spy).runMonth();
        doNothing().when(spy).runYear();
        service = spy;
    }

    @Test
    void consolidateMinuteAndHour_callsMinuteThenHourInOrder() {
        service.consolidateMinuteAndHour();

        InOrder order = inOrder(spy);
        order.verify(spy).runMinute();
        order.verify(spy).runHour();
        verifyNoMoreInteractions(spy);
    }

    @Test
    void consolidateDayAndAbove_callsDayMonthYearInOrder() {
        service.consolidateDayAndAbove();

        InOrder order = inOrder(spy);
        order.verify(spy).runDay();
        order.verify(spy).runMonth();
        order.verify(spy).runYear();
        verifyNoMoreInteractions(spy);
    }

    @Test
    void consolidateMinuteAndHour_isNoOpWhenWarmupIncomplete() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean notReady = new AtomicBoolean(false);
        ConsolidationService cold = new ConsolidationService(repo, notReady, null);
        ConsolidationService coldSpy = spy(cold);
        doNothing().when(coldSpy).runMinute();
        doNothing().when(coldSpy).runHour();

        coldSpy.consolidateMinuteAndHour();

        verify(coldSpy, never()).runMinute();
        verify(coldSpy, never()).runHour();
    }

    @Test
    void consolidateDayAndAbove_isNoOpWhenWarmupIncomplete() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean notReady = new AtomicBoolean(false);
        ConsolidationService cold = new ConsolidationService(repo, notReady, null);
        ConsolidationService coldSpy = spy(cold);
        doNothing().when(coldSpy).runDay();
        doNothing().when(coldSpy).runMonth();
        doNothing().when(coldSpy).runYear();

        coldSpy.consolidateDayAndAbove();

        verify(coldSpy, never()).runDay();
        verify(coldSpy, never()).runMonth();
        verify(coldSpy, never()).runYear();
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail (methods not yet updated)**

```bash
cd /Users/I072325/Downloads/audit-log
mvn test -Dtest=ConsolidationServiceSchedulingTest -q 2>&1 | tail -20
```

Expected: compilation or test failure — `consolidateMinuteAndHour()` and `consolidateDayAndAbove()` don't exist yet, and `runWeek` stubs are gone.

- [ ] **Step 3: Commit the updated failing tests**

```bash
git add src/test/java/com/example/auditlog/service/ConsolidationServiceSchedulingTest.java
git commit -m "test(consolidation): update scheduling tests — remove week, expect day->month->year"
```

---

### Task 2: Remove week from the enum, service, and scheduling logic

**Files:**
- Modify: `src/main/java/com/example/auditlog/enums/EntryType.java`
- Modify: `src/main/java/com/example/auditlog/service/ConsolidationService.java`

**Interfaces:**
- Consumes: `EntryType` enum values `DAY_CONSOLIDATION`, `MONTH_CONSOLIDATION`, `YEAR_CONSOLIDATION`.
- Produces:
  - `consolidateMinuteAndHour()` — public, `@Scheduled(cron = "0 * * * * *")`, calls `self.runMinute()` then `self.runHour()`.
  - `consolidateDayAndAbove()` — public, `@Scheduled(cron = "0 0 0 * * *")`, calls `self.runDay()` → `self.runMonth()` → `self.runYear()`.
  - `runMonth()` — public, `@Transactional`, consumes `DAY_CONSOLIDATION` rows.

- [ ] **Step 1: Remove `WEEK_CONSOLIDATION` from `EntryType.java`**

Replace the entire file content:

```java
package com.example.auditlog.enums;

public enum EntryType {
    REGULAR,
    MINUTE_CONSOLIDATION,
    HOUR_CONSOLIDATION,
    DAY_CONSOLIDATION,
    MONTH_CONSOLIDATION,
    YEAR_CONSOLIDATION
}
```

- [ ] **Step 2: Rewrite `ConsolidationService.java` with week removed**

Replace the entire file content:

```java
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

    private static final UnaryOperator<LocalDateTime> MINUTE_BUCKET =
            t -> t.truncatedTo(ChronoUnit.MINUTES);
    private static final UnaryOperator<LocalDateTime> HOUR_BUCKET =
            t -> t.truncatedTo(ChronoUnit.HOURS);
    private static final UnaryOperator<LocalDateTime> DAY_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS);
    private static final UnaryOperator<LocalDateTime> MONTH_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
    private static final UnaryOperator<LocalDateTime> YEAR_BUCKET =
            t -> t.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);

    @Scheduled(cron = "0 * * * * *")
    public void consolidateMinuteAndHour() {
        if (!warmupComplete.get()) return;
        self.runMinute();
        self.runHour();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void consolidateDayAndAbove() {
        if (!warmupComplete.get()) return;
        self.runDay();
        self.runMonth();
        self.runYear();
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
    public void runMonth() {
        LocalDateTime cutoff = MONTH_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.DAY_CONSOLIDATION, EntryType.MONTH_CONSOLIDATION, cutoff, MONTH_BUCKET);
    }

    @Transactional
    public void runYear() {
        LocalDateTime cutoff = YEAR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
        consolidate(EntryType.MONTH_CONSOLIDATION, EntryType.YEAR_CONSOLIDATION, cutoff, YEAR_BUCKET);
    }

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
```

- [ ] **Step 3: Run the scheduling tests to verify they pass**

```bash
mvn test -Dtest=ConsolidationServiceSchedulingTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 4 tests green.

- [ ] **Step 4: Run the full test suite to check for regressions**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, no failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/auditlog/enums/EntryType.java
git add src/main/java/com/example/auditlog/service/ConsolidationService.java
git commit -m "feat(consolidation): remove week level — chain is now day->month->year

Deletes WEEK_CONSOLIDATION enum value, WEEK_BUCKET, runWeek(), and the
runWeek call in catchUp(). runMonth() now consumes DAY_CONSOLIDATION rows.
Also replaces the six independent @Scheduled wrappers with two sequential
methods: consolidateMinuteAndHour (every minute) and consolidateDayAndAbove
(midnight). Sequential execution eliminates the race that produced duplicate
rollup rows."
```

---

### Task 3: Update the superseded plan doc

The old plan at `docs/superpowers/plans/2026-07-09-fix-consolidation-scheduling.md` still references week. Mark it superseded so future readers are not confused.

**Files:**
- Modify: `docs/superpowers/plans/2026-07-09-fix-consolidation-scheduling.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add a superseded notice at the top of the old plan**

Insert the following block immediately after the `# Fix Consolidation Scheduling Implementation Plan` heading (before the `> **For agentic workers:**` line):

```markdown
> **SUPERSEDED** by `2026-07-09-remove-week-consolidation.md`, which implements
> the scheduling fix and the week removal together. Do not execute this plan.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-07-09-fix-consolidation-scheduling.md
git commit -m "docs(plans): mark fix-consolidation-scheduling plan as superseded"
```
