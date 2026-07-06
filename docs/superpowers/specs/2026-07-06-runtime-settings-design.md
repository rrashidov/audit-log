# Runtime settings via UI — design

**Status:** approved
**Date:** 2026-07-06

## Summary

Introduce two runtime-editable settings, persisted in the database and exposed on a new `/settings` page:

- `load.delay.ms` — sleep (milliseconds) inserted after each operation in `LoadService.runLoop`. Takes effect on the next loop iteration.
- `warmup.target` — number of `KeyValue` rows to reach on application start. Takes effect on the next application restart.

The mechanism is a small, generic key/value settings facility (`app_setting` table + `SettingsService`), sized to grow if future settings are added.

## Motivation

`LoadService` currently runs a tight `while (running)` loop with no throttle. There is no way to slow it down or vary the target row count without editing configuration and rebuilding the image. Operators want to tune both from the UI at runtime.

## Non-goals

- Live resizing of the `key_value` table when `warmup.target` changes at runtime. The warmup target is read once at startup.
- Per-operation delays (different sleep per `OperationType`). A single delay applies uniformly.
- Authentication / authorization on the settings page. Consistent with the rest of the app, which is unauthenticated.
- Structured audit-logging of settings changes.

## Data model

New entity `AppSetting` mapped to a new table `app_setting`:

| column     | type          | notes                                            |
| ---------- | ------------- | ------------------------------------------------ |
| `key_name` | VARCHAR(64) PK | Column named `key_name` because `KEY` is reserved in MySQL. Java field remains `key`. |
| `value`    | VARCHAR(256) NOT NULL | Stringly-typed. Callers parse. |

Two initial keys:

- `load.delay.ms` — non-negative integer, milliseconds
- `warmup.target` — non-negative integer, row count

Schema is created automatically by `spring.jpa.hibernate.ddl-auto=update` on first boot. No migration script.

## Components

### `entity/AppSetting.java`

Standard JPA entity with `@Id` on `key` mapped to `key_name`, plus `value`. Lombok `@Data @NoArgsConstructor @AllArgsConstructor`.

### `repository/AppSettingRepository.java`

`JpaRepository<AppSetting, String>`. No custom queries.

### `service/SettingsService.java`

- Constants `KEY_LOAD_DELAY_MS = "load.delay.ms"` and `KEY_WARMUP_TARGET = "warmup.target"`.
- Reads:
  - `long getLoadDelayMs()` — returns cached value; on cache miss calls `getOrSeed`.
  - `long getWarmupTarget()` — same shape.
- Writes:
  - `void setLoadDelayMs(long)` — validates `>= 0`, upserts row, updates cache.
  - `void setWarmupTarget(long)` — same shape.
- Seeding: `getOrSeed(key, defaultValue)` returns the stored value if the row exists; otherwise persists a new row with `defaultValue` and returns it. Runs under `@Transactional`.
- Defaults are wired from `application.properties` via `@Value`:
  - `${app.warmup.target:1000000}` — preserves the existing `WARMUP_TARGET` env-var contract in `docker-compose.yml`.
  - `${app.load.delay.ms:0}` — new; optional env override for fresh installs.
- Caching: `volatile Long` fields for each setting. Written on `set*` and on cache-miss read. Sufficient because writes are rare and single-source (UI); reads are frequent (load loop).
- Parse failure on read: catches `NumberFormatException`, logs a warning, returns the seeded default without overwriting the row. Keeps the load loop alive if the row is corrupted by manual edit.

### Changes to existing components

**`service/WarmupService.java`**

- Remove the `@Value("${app.warmup.target:1000000}")` field.
- Inject `SettingsService`.
- Read `long warmupTarget = settingsService.getWarmupTarget();` at the top of `warmup()`. Rest of the method unchanged.

**`service/LoadService.java`**

- Inject `SettingsService`.
- In `runLoop`, after `performOperation(op)`:
  ```java
  long delay = settingsService.getLoadDelayMs();
  if (delay > 0) Thread.sleep(delay);
  ```
- Add `InterruptedException` handling: restore the interrupt flag and break out of the loop. The sleep is outside the `@Transactional` `performOperation` — it must not hold a DB connection or locks.

### `controller/SettingsController.java`

- `GET /settings` — renders `settings/view` with `loadDelayMs` and `warmupTarget` model attributes.
- `POST /settings` — accepts `loadDelayMs` and `warmupTarget` as `long` params, calls the two setters, redirects to `/settings` with a `saved=true` flash attribute. On `IllegalArgumentException`, redirects with `error=<message>` flash attribute instead.

### `templates/settings/view.html`

- Same shell as `keyvalue/list.html` and `auditlog/list.html`: navbar, Bootstrap 5.3.2 CDN, `container mt-4`.
- Green alert on `saved`, red alert on `error`.
- Single `<form method="post" action="/settings">` with two `<input type="number" min="0">` fields and a Save button.
- Help text under `warmup.target`: "Takes effect on next application restart."
- Help text under `load.delay.ms`: "Sleep between load operations. 0 = no throttle. Takes effect immediately."

### Navbar update

Add a `Settings` link to all three templates (`keyvalue/list.html`, `auditlog/list.html`, `settings/view.html`) with the standard `active`-class toggling based on the current page.

## Data flow

**Startup (`ApplicationRunner`):**

1. `WarmupService.warmup()` calls `settingsService.getWarmupTarget()`.
2. Cache is empty → `getOrSeed(KEY_WARMUP_TARGET, warmupTargetDefault)` runs.
   - Fresh DB: row absent → insert row with the env-var default → return default.
   - Subsequent boots: row present → return stored value (env-var default is ignored).
3. Warmup proceeds with the resolved target.
4. Consolidation catch-up and load service start as before.

**Load loop:**

1. `performOperation` runs (audit-logged as today).
2. `settingsService.getLoadDelayMs()` returns the cached value.
3. If `> 0`, `Thread.sleep(delay)`.
4. Loop.

**UI save:**

1. `POST /settings` → `SettingsService.setLoadDelayMs` and `setWarmupTarget`.
2. Each setter: validate `>= 0`, `repo.save` (upsert on PK), update the volatile cache.
3. Redirect to `GET /settings` with flash attribute.
4. Load loop sees the new delay on its next iteration.

## Error handling

- **Negative values:** `IllegalArgumentException` from the setter, caught by the controller, shown as red alert.
- **Non-numeric form input:** blocked by browser (`type="number"`); server-side would 400 via Spring's parameter binding. Acceptable — same behaviour as the rest of the app.
- **DB write failure:** JPA exception bubbles to Spring's default error page. Consistent with the rest of the app.
- **Corrupt value in the DB row:** `NumberFormatException` on read is caught in `SettingsService`, logged, and the default is returned. The load loop keeps running.
- **DB unavailable at boot:** `WarmupService`'s existing behaviour (fails on `count()`) preempts any settings read. No new failure mode.

## Concurrency

- One writer path (Spring MVC, `@Transactional` setter) → serialised by Spring's per-request handling.
- Many readers (load loop every tick; warmup once at boot).
- `volatile Long` caches are sufficient for the read-mostly pattern. Stale reads across a save/read race are acceptable — worst case, one extra iteration runs with the old delay.

## Testing

Add a small set of focused tests. The project currently has none, so we're seeding rather than extending.

1. **`SettingsServiceTest`** (`@SpringBootTest` with H2, or `@DataJpaTest` + service slice):
   - `getOrSeed` writes the env default row when the key is missing and returns it.
   - `getOrSeed` returns the stored value when a row exists (env default ignored).
   - `setLoadDelayMs` persists and updates the cache; the subsequent read returns the new value.
   - Negative values throw `IllegalArgumentException`.
   - A row with a non-numeric value causes the reader to return the default and log a warning; no exception surfaces.

2. **`SettingsControllerTest`** (`@WebMvcTest(SettingsController.class)` + mocked `SettingsService`):
   - `GET /settings` renders both attributes.
   - `POST /settings` with valid values calls both setters and redirects with `saved=true`.
   - `POST /settings` with a negative value redirects with `error` flash.

3. **`LoadServiceDelayTest`** — extract the loop body into a package-private method, mock `SettingsService.getLoadDelayMs()` to return `50`, run one iteration, assert elapsed wall-clock ≥ ~50 ms. Kept minimal to avoid timing flakiness.

## Configuration

`application.properties` gains one line:

```
app.load.delay.ms=${LOAD_DELAY_MS:0}
```

`docker-compose.yml` may optionally propagate `LOAD_DELAY_MS`; not required.

## Migration & backward compatibility

- Fresh installs: `app_setting` table is created by `ddl-auto=update`. Both settings seeded on first read from env / property defaults. `WARMUP_TARGET` env var continues to work as the seed value.
- Existing installs: first boot after this change creates the table and seeds the two rows from the current env / property values. From that point, UI edits take precedence.

## Out-of-scope follow-ups (not in this spec)

- A "reset to default" button on the settings page.
- Live warmup top-up when `warmup.target` is raised at runtime.
- Additional settings (consolidation cadence, load parallelism, per-op delays).
- Change history for settings via `AuditLog`.
