# Audit Log Grid View — Design

**Date:** 2026-07-09
**Status:** Approved

## Summary

Add a visual grid view to the audit log UI, inspired by the Windows 98 disk defragmenter. Each colored square represents one or more audit log records, grouped and colored by `EntryType`. The view gives an instant at-a-glance picture of how many records of each type exist in the system.

## Motivation

The existing `/auditlog` page is a paginated table — useful for inspecting individual records but gives no sense of the overall composition of the log. The grid view answers "how many of each type are there?" in a single glance without reading numbers.

## Non-goals

- Filtering by date range or operation type — this view covers all records, always.
- Tooltips or interactivity on individual squares.
- Real-time live updates — the grid is a snapshot loaded on page request.
- Replacing the existing table view — the grid is an addition, not a replacement.

## Visual design

- Dark background canvas (`#1e1e2e`), matching the mockup.
- Square size: 10px, gap: 2px (step = 12px).
- Grid fills the canvas width (~720px), rows fill downward.
- **Layout order (top-left → bottom-right):**
  1. YEAR_CONSOLIDATION (pink `#e879f9`)
  2. MONTH_CONSOLIDATION (red `#f87171`)
  3. DAY_CONSOLIDATION (purple `#a78bfa`)
  4. HOUR_CONSOLIDATION (amber `#f59e0b`)
  5. MINUTE_CONSOLIDATION (blue `#60a5fa`)
  6. REGULAR (green `#4ade80`)
  7. Free space (dark `#2a2a3e` with subtle border) — 3 trailing rows
- Color legend above the canvas, one chip per type.
- Scale label below: "1 square = N records".

## Adaptive scale

The server computes a single global scale factor `S = ceil(totalRecords / dataCells)`, where `dataCells = totalCells - (3 × cols)` (reserving 3 rows for free space).

Each type gets `max(1, ceil(typeCount / S))` squares — so even a single record of a rare type (e.g. 4 YEAR_CONSOLIDATION records) is guaranteed at least one square. Types with zero records get zero squares.

This means the total square count may slightly exceed `dataCells` in edge cases (when many types each round up), but in practice the rounding is negligible.

## Architecture

### New endpoint: `GET /auditlog/grid`

Added to `AuditLogController`. Returns the Thymeleaf view `auditlog/grid`.

The controller queries record counts grouped by `EntryType` using a single JPQL query:

```java
@Query("SELECT a.entryType, COUNT(a) FROM AuditLog a GROUP BY a.entryType")
List<Object[]> countByEntryType();
```

Added to `AuditLogRepository`.

The controller builds a model with:
- `counts` — `Map<EntryType, Long>` with all six types (zero for absent types)
- `totalRecords` — sum of all counts
- `scale` — computed scale factor S
- `typeOrder` — the six `EntryType` values in display order (coarsest first)
- `colors` — `Map<EntryType, String>` hex color per type

### New template: `templates/auditlog/grid.html`

Same navbar shell as `auditlog/list.html`. Contains:
- Legend row (color chip + label per type, plus free space chip)
- `<canvas id="grid">` element
- Scale label paragraph
- `<script>` block that reads the model data injected as a JSON variable and renders the canvas

The rendering logic (in plain JS, no libraries):
1. Read `window.GRID_DATA` (a JSON object injected by Thymeleaf with counts, scale, typeOrder, colors).
2. Compute `cols`, `dataCells`, `squaresPerType` (per-type `max(1, ceil(count/scale))`).
3. Draw squares left-to-right, top-to-bottom in type order.
4. Fill remaining cells with free-space squares.

### Navbar link

Add a "Grid" link to all existing templates (`auditlog/list.html`, `keyvalue/list.html`, `settings/view.html`, `auditlog/grid.html`) pointing to `/auditlog/grid`.

## Data flow

1. Browser requests `GET /auditlog/grid`.
2. `AuditLogController.grid()` runs one `GROUP BY entryType` query.
3. Controller builds the model and returns the `auditlog/grid` view.
4. Thymeleaf renders the page, injecting counts into a `<script>` block as `window.GRID_DATA = {...}`.
5. The browser JS reads `GRID_DATA`, computes square layout, draws the canvas.

## Error handling

- Empty database (zero records): `totalRecords = 0`, scale = 1, all squares are free space. Canvas renders as all dark. No special case needed.
- DB query failure: bubbles to Spring's default error page, consistent with the rest of the app.

## Testing

- `AuditLogRepositoryTest` (or existing test slice): verify `countByEntryType()` returns correct counts for a seeded dataset with a mix of entry types.
- No controller test needed beyond what the existing pattern covers — the controller logic is a straightforward query + model build.
