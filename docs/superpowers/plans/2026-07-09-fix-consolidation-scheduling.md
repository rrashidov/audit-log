# Fix Consolidation Scheduling Implementation Plan

> **SUPERSEDED** by `2026-07-09-remove-week-consolidation.md`, which implements
> the scheduling fix and the week removal together. Do not execute this plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the audit log consolidation pipeline so that higher-level rollups (hour, day, week, month, year) never produce duplicate rows by replacing independent scheduled methods with a single sequential scheduler that runs each level in dependency order within one invocation.

**Architecture:** Replace the six independent `@Scheduled` methods with two: one that fires every minute and runs `runMinute` then `runHour` sequentially, and one that fires at midnight and runs `runDay` → `runWeek` → `runMonth` → `runYear` sequentially. Sequential execution within a single scheduled invocation guarantees each level sees the output of the level below it before running, eliminating the race that produces duplicate hour/day/week/month/year rows.

**Tech Stack:** Java 17, Spring Boot (Spring Scheduling, Spring Data JPA), JUnit 5, Mockito, H2 (test), AssertJ.

## Global Constraints

- All times in UTC (`ZoneOffset.UTC`).
- Scheduled methods must remain on `ConsolidationService` — no new classes.
- Tests use plain unit tests with mocks (no `@SpringBootTest`) to keep them fast; follow the pattern in `LoadServiceDelayTest`.
- Test profile is `test` (set via `spring.profiles.active=test` in `src/test/resources/application.properties`).

---

### Task 1: Write failing tests for the sequential scheduler behaviour

**Files:**
- Create: `src/test/java/com/example/auditlog/service/ConsolidationServiceSchedulingTest.java`

**Interfaces:**
- Consumes: `ConsolidationService(AuditLogRepository, AtomicBoolean, ConsolidationService)` constructor; public methods `runMinute()`, `runHour()`, `runDay()`, `runWeek()`, `runMonth()`, `runYear()`.
- Produces: nothing consumed by later tasks.

These tests verify that:
1. `consolidateMinuteAndHour()` calls `runMinute` first, then `runHour`, never `runDay`/`runWeek`/`runMonth`/`runYear`.
2. `consolidateDayAndAbove()` calls `runDay`, `runWeek`, `runMonth`, `runYear` in order, never `runMinute`/`runHour`.
3. Both methods are no-ops when `warmupComplete` is false.

Because `runMinute()` etc. are `@Transactional` and we need to verify call order, use a Mockito spy on a real `ConsolidationService` instance whose repository is mocked. The spy lets us stub the `runXxx` methods to no-ops while still recording invocations.

- [ ] **Step 1: Create the test file**

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
        // Build real instance; pass itself as the self-proxy (no Spring context needed here).
        service = new ConsolidationService(repo, warmupComplete, null);
        spy = spy(service);
        // Prevent actual DB work — we only care about call order.
        doNothing().when(spy).runMinute();
        doNothing().when(spy).runHour();
        doNothing().when(spy).runDay();
        doNothing().when(spy).runWeek();
        doNothing().when(spy).runMonth();
        doNothing().when(spy).runYear();
        // Inject spy as self so the scheduled methods route through it.
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
    void consolidateDayAndAbove_callsDayWeekMonthYearInOrder() {
        service.consolidateDayAndAbove();

        InOrder order = inOrder(spy);
        order.verify(spy).runDay();
        order.verify(spy).runWeek();
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
        doNothing().when(coldSpy).runWeek();
        doNothing().when(coldSpy).runMonth();
        doNothing().when(coldSpy).runYear();

        coldSpy.consolidateDayAndAbove();

        verify(coldSpy, never()).runDay();
        verify(coldSpy, never()).runWeek();
        verify(coldSpy, never()).runMonth();
        verify(coldSpy, never()).runYear();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail with "method not found"**

```bash
cd /Users/I072325/Downloads/audit-log
mvn test -pl . -Dtest=ConsolidationServiceSchedulingTest -q 2>&1 | tail -20
```

Expected: compilation error — `consolidateMinuteAndHour()` and `consolidateDayAndAbove()` do not exist yet.

- [ ] **Step 3: Commit the failing tests**

```bash
git add src/test/java/com/example/auditlog/service/ConsolidationServiceSchedulingTest.java
git commit -m "test(consolidation): failing tests for sequential scheduler methods"
```

---

### Task 2: Replace the six independent scheduled methods with two sequential ones

**Files:**
- Modify: `src/main/java/com/example/auditlog/service/ConsolidationService.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `consolidateMinuteAndHour()` (public, `@Scheduled(cron = "0 * * * * *")`) and `consolidateDayAndAbove()` (public, `@Scheduled(cron = "0 0 0 * * *")`). Both call their `runXxx()` methods via `self` so `@Transactional` is honoured on each.

Remove `consolidateMinute`, `consolidateHour`, `consolidateDay`, `consolidateWeek`, `consolidateMonth`, `consolidateYear`. Add two replacement methods that call through `self` so each `runXxx` still runs in its own transaction (the self-proxy pattern already in use in `catchUp`).

- [ ] **Step 1: Remove the six old scheduled methods and add the two new ones**

Replace lines 62–102 in `ConsolidationService.java` (the six `@Scheduled` wrappers) with:

```java
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
    self.runWeek();
    self.runMonth();
    self.runYear();
}
```

The complete replaced block — old:

```java
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
```

New (replaces all of the above):

```java
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
    self.runWeek();
    self.runMonth();
    self.runYear();
}
```

Note: `@Transactional` is removed from the wrappers — each `runXxx` already carries its own `@Transactional` and is called through `self` (the proxy), so the advice fires correctly. The wrapper itself does not need a transaction boundary.

- [ ] **Step 2: Run the new scheduling tests to verify they pass**

```bash
mvn test -pl . -Dtest=ConsolidationServiceSchedulingTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 4 tests green.

- [ ] **Step 3: Run the full test suite to check for regressions**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, no failures.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/auditlog/service/ConsolidationService.java
git commit -m "fix(consolidation): replace 6 independent scheduled methods with 2 sequential ones

consolidateMinuteAndHour fires every minute and calls runMinute then runHour
in order, so hour consolidation always sees the minute rows just committed.
consolidateDayAndAbove fires at midnight and chains day->week->month->year.
Also fixes the copy-paste cron bug on consolidateHour (was 0 * * * * *,
should have been 0 0 * * * *)."
```
