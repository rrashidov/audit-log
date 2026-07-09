# Consolidation Cutoff Safety Margin Design

**Date:** 2026-07-09
**Status:** Approved

## Context

The audit log consolidation pipeline runs `runXxx()` methods on a schedule. Each method computes a `cutoff` by snapping `now()` to the start of the current bucket (e.g. at 08:14, `runHour` computes cutoff `08:00`), then queries all source rows with `dateTime < cutoff` and consolidates them.

The problem: the bucket that just closed (hour 07 in the example) is eligible immediately. If any writes to that bucket are delayed — clock skew, late arrivals, in-flight transactions — those rows miss the consolidation window and are never picked up correctly.

## Change

Subtract one unit of the respective granularity from the snapped cutoff before using it as the filter predicate. This introduces a one-full-bucket safety margin: a bucket is not consolidated until at least one full bucket-period has elapsed since it closed.

**Example:** at 08:14, `runHour` computes `08:00`, subtracts one hour → `07:00`. Only `MINUTE_CONSOLIDATION` rows with `dateTime < 07:00` (i.e. hour 06 and earlier) are consolidated. Hours 07 and 08 are left untouched.

## Scope

One file: `src/main/java/com/example/auditlog/service/ConsolidationService.java`.

Five cutoff lines, one subtraction each:

| Method | Cutoff expression |
|---|---|
| `runMinute` | `MINUTE_BUCKET.apply(now).minusMinutes(1)` |
| `runHour` | `HOUR_BUCKET.apply(now).minusHours(1)` |
| `runDay` | `DAY_BUCKET.apply(now).minusDays(1)` |
| `runMonth` | `MONTH_BUCKET.apply(now).minusMonths(1)` |
| `runYear` | `YEAR_BUCKET.apply(now).minusYears(1)` |

No changes to `consolidate()`, the scheduler methods, `catchUp()`, or any other file.

## Multi-bucket grouping

The `consolidate()` method already groups source rows by `bucketStart.apply(dateTime)` before producing output rows. When the query returns rows spanning multiple buckets (e.g. hours 03, 04, 05, 06), each bucket collapses into its own output row. No change needed here.

## Testing

New tests in `ConsolidationServiceCutoffTest` verify that for each `runXxx` method, the cutoff passed to `auditLogRepository.findByEntryTypeAndDateTimeBefore` is exactly one unit behind the snapped-now value. Tests use a mocked `AuditLogRepository` and a fixed `now` (injected via a clock or by capturing the argument with Mockito `ArgumentCaptor`).
