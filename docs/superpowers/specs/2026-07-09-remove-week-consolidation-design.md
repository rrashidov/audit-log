# Remove Week-Level Consolidation

**Date:** 2026-07-09
**Status:** Approved

## Context

The audit log consolidation pipeline rolls raw entries up through a hierarchy of granularities. The original hierarchy was:

```
REGULAR → MINUTE_CONSOLIDATION → HOUR_CONSOLIDATION → DAY_CONSOLIDATION
       → WEEK_CONSOLIDATION → MONTH_CONSOLIDATION → YEAR_CONSOLIDATION
```

Week-level consolidation adds no analytical value and complicates the pipeline. The new hierarchy is:

```
REGULAR → MINUTE_CONSOLIDATION → HOUR_CONSOLIDATION → DAY_CONSOLIDATION
       → MONTH_CONSOLIDATION → YEAR_CONSOLIDATION
```

This is a greenfield removal — no data migration required.

## Changes

### 1. `EntryType.java`

Remove `WEEK_CONSOLIDATION` from the enum. Hibernate's `ddl-auto=update` handles the schema; no SQL migration needed.

### 2. `ConsolidationService.java`

- Delete `WEEK_BUCKET` constant.
- Delete `runWeek()` method.
- Change `runMonth()` to consume `DAY_CONSOLIDATION` rows instead of `WEEK_CONSOLIDATION`.
- Remove `self.runWeek()` call from `catchUp()`.
- The planned `consolidateDayAndAbove()` method (from the in-progress scheduling fix) chains day → month → year (no week step).

### 3. `ConsolidationServiceSchedulingTest.java`

- Remove the `runWeek` stub from `setUp()`.
- Rename `consolidateDayAndAbove_callsDayWeekMonthYearInOrder` → `consolidateDayAndAbove_callsDayMonthYearInOrder`.
- Update its `InOrder` verification to expect day → month → year only.
- Remove week from the no-op warmup test for `consolidateDayAndAbove`.

### 4. In-progress plan doc (`2026-07-09-fix-consolidation-scheduling.md`)

Update Task 1 and Task 2 text so all references to `runWeek` / week are removed, and the described call sequence reflects day → month → year.

## Architecture

No new classes. No new interfaces. The self-proxy pattern and `@Transactional` boundaries on each `runXxx` method are unchanged.

The consolidation chain after this change:

| Scheduler | Cron | Sequence |
|---|---|---|
| `consolidateMinuteAndHour()` | `0 * * * * *` | runMinute → runHour |
| `consolidateDayAndAbove()` | `0 0 0 * * *` | runDay → runMonth → runYear |

## Testing

Existing unit tests in `ConsolidationServiceSchedulingTest` cover call order and warmup guard. No new test cases needed — the change is a strict subset removal.
