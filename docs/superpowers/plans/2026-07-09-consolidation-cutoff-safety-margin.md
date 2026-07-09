# Consolidation Cutoff Safety Margin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subtract one granularity unit from the snapped cutoff in each `runXxx` method so that a bucket is never consolidated until one full bucket-period has elapsed since it closed.

**Architecture:** Five one-line edits to `ConsolidationService.java` — append `.minusMinutes(1)` / `.minusHours(1)` / `.minusDays(1)` / `.minusMonths(1)` / `.minusYears(1)` to each cutoff expression. New `ConsolidationServiceCutoffTest` uses `ArgumentCaptor` to verify the cutoff value passed to the repository for each method.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, H2 (test).

## Global Constraints

- All times in UTC (`ZoneOffset.UTC`).
- No injectable clock — use `ArgumentCaptor` to verify cutoff values, not a fixed clock.
- Tests use plain unit tests with mocks (no `@SpringBootTest`).
- Test profile is `test` (set via `spring.profiles.active=test` in `src/test/resources/application.properties`).

---

### Task 1: Write failing cutoff tests then implement the safety margin

**Files:**
- Create: `src/test/java/com/example/auditlog/service/ConsolidationServiceCutoffTest.java`
- Modify: `src/main/java/com/example/auditlog/service/ConsolidationService.java`

**Interfaces:**
- Consumes: `ConsolidationService(AuditLogRepository, AtomicBoolean, ConsolidationService)` constructor; `runMinute()`, `runHour()`, `runDay()`, `runMonth()`, `runYear()` — all public, `@Transactional`.
- Produces: nothing consumed by later tasks.

**How the tests work:** Each test creates a `ConsolidationService` with a mocked `AuditLogRepository`, calls `runXxx()` directly (bypassing the scheduler), then uses `ArgumentCaptor<LocalDateTime>` to capture the `cutoff` argument passed to `auditLogRepository.findByEntryTypeAndDateTimeBefore(sourceType, cutoff)`. The captured value is asserted to be strictly less than the snapped-now value for that granularity — specifically, exactly one unit behind it.

Because `runXxx()` calls `LocalDateTime.now(ZoneOffset.UTC)` internally, the test cannot know the exact wall-clock value. Instead it asserts that:
1. The captured cutoff is truncated to the correct granularity.
2. The captured cutoff is strictly before the snapped-now value computed by the test immediately after the call (i.e. `capturedCutoff.isBefore(snapNow)`), which is guaranteed if the subtraction is applied.
3. More precisely: `capturedCutoff.plus(1, unit).isEqual(snapNow) || capturedCutoff.plus(1, unit).isBefore(snapNow)` — the captured value plus one unit is ≤ the snapped-now at call time.

A simpler and fully sufficient assertion: capture the cutoff, compute `expected = BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minus(1, unit)` immediately after the call, and assert `capturedCutoff` is not after `expected` (i.e. `!capturedCutoff.isAfter(expected)`). This is robust even if a second boundary is crossed between the internal call and the test's post-call snapshot.

- [ ] **Step 1: Create the failing test file**

```java
package com.example.auditlog.service;

import com.example.auditlog.enums.EntryType;
import com.example.auditlog.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsolidationServiceCutoffTest {

    private ConsolidationService buildService(AuditLogRepository repo) {
        // self=null is fine here: these tests call runXxx() directly,
        // which does not invoke self
        return new ConsolidationService(repo, new AtomicBoolean(true), null);
    }

    private LocalDateTime capturedCutoff(AuditLogRepository repo, EntryType sourceType) {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).findByEntryTypeAndDateTimeBefore(eq(sourceType), captor.capture());
        return captor.getValue();
    }

    @Test
    void runMinute_cutoffIsOneMinuteBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runMinute();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.REGULAR);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runHour_cutoffIsOneHourBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runHour();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.MINUTE_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS).minusHours(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runDay_cutoffIsOneDayBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runDay();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.HOUR_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).minusDays(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runMonth_cutoffIsOneMonthBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runMonth();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.DAY_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).minusMonths(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runYear_cutoffIsOneYearBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runYear();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.MONTH_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).withDayOfYear(1).minusYears(1);
        assertThat(cutoff).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/I072325/Downloads/audit-log
mvn test -Dtest=ConsolidationServiceCutoffTest -q 2>&1 | tail -20
```

Expected: 5 failures — each `assertThat(cutoff).isEqualTo(expected)` fails because the current cutoff has no subtraction applied.

- [ ] **Step 3: Apply the safety margin to each `runXxx` method in `ConsolidationService.java`**

Replace the five cutoff lines (lines 91, 97, 103, 109, 115) as follows:

`runMinute` — change:
```java
LocalDateTime cutoff = MINUTE_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
```
to:
```java
LocalDateTime cutoff = MINUTE_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minusMinutes(1);
```

`runHour` — change:
```java
LocalDateTime cutoff = HOUR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
```
to:
```java
LocalDateTime cutoff = HOUR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minusHours(1);
```

`runDay` — change:
```java
LocalDateTime cutoff = DAY_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
```
to:
```java
LocalDateTime cutoff = DAY_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minusDays(1);
```

`runMonth` — change:
```java
LocalDateTime cutoff = MONTH_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
```
to:
```java
LocalDateTime cutoff = MONTH_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minusMonths(1);
```

`runYear` — change:
```java
LocalDateTime cutoff = YEAR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC));
```
to:
```java
LocalDateTime cutoff = YEAR_BUCKET.apply(LocalDateTime.now(ZoneOffset.UTC)).minusYears(1);
```

- [ ] **Step 4: Run the cutoff tests to verify they pass**

```bash
mvn test -Dtest=ConsolidationServiceCutoffTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 5 tests green.

- [ ] **Step 5: Run the full test suite to check for regressions**

```bash
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests green (previously 19, now 24).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/example/auditlog/service/ConsolidationServiceCutoffTest.java
git add src/main/java/com/example/auditlog/service/ConsolidationService.java
git commit -m "feat(consolidation): apply one-unit safety margin to all runXxx cutoffs

Each method now subtracts one unit from the snapped cutoff before querying:
runMinute subtracts 1 minute, runHour 1 hour, runDay 1 day, runMonth 1 month,
runYear 1 year. This ensures a bucket is never consolidated until a full
bucket-period has elapsed since it closed, preventing late-arriving writes
from being silently dropped."
```
