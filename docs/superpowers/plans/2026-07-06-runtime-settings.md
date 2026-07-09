# Runtime Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/settings` page that lets an operator edit two DB-persisted runtime settings — `load.delay.ms` (throttle for `LoadService.runLoop`, live) and `warmup.target` (target row count for `WarmupService`, applied at next restart).

**Architecture:** A generic key/value `app_setting` table with two initial rows, fronted by a `SettingsService` that seeds missing keys from `@Value` defaults on first read ("seed once, then DB wins") and caches each value in a `volatile Long` for cheap hot-path reads. `LoadService.runLoop` sleeps `getLoadDelayMs()` after each op (skipped when 0); `WarmupService.warmup()` reads `getWarmupTarget()` at boot instead of `@Value`. A Thymeleaf `/settings` page with a single form and PRG flash messages controls both.

**Tech Stack:** Spring Boot 3.2.4, Java 21, JPA + Hibernate (`ddl-auto=update`), MySQL 8 (prod) / H2 in-memory (tests), Lombok, Thymeleaf, Bootstrap 5.3.2 CDN. Testing with `spring-boot-starter-test` (JUnit 5 + Mockito + MockMvc); H2 to be added as a test-scope dependency in Task 1.

## Global Constraints

- All new Java files under package `com.example.auditlog.*` matching the existing per-role subpackages (`entity`, `repository`, `service`, `controller`).
- Column names must not use MySQL reserved words: settings PK column is `key_name`, not `key`.
- Time zone: DB and app run in UTC (`TZ=UTC` in Dockerfile, `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`). No time-of-day logic in this feature, but preserve existing UTC posture.
- Persistence policy: `spring.jpa.hibernate.ddl-auto=update` — no migration scripts; schema is created by JPA at boot.
- Seeding policy: on cache miss, if the DB row is absent, insert it using the `@Value`-provided default (`${app.warmup.target:1000000}`, `${app.load.delay.ms:0}`). After that first insert, the DB is the source of truth — env-var/property changes are ignored on subsequent boots.
- Validation: both settings are non-negative `long` values. Negatives throw `IllegalArgumentException` from the service; the controller converts that to a redirect with a red-alert flash. No upper bound.
- UI copy (must be verbatim, per spec):
  - Under `load.delay.ms`: `Sleep between load operations. 0 = no throttle. Takes effect immediately.`
  - Under `warmup.target`: `Takes effect on next application restart.`
- Navbar consistency: every top-level page template (`keyvalue/list.html`, `auditlog/list.html`, `settings/view.html`) shows the same three-link navbar (`KeyValue`, `Audit Log`, `Settings`) with the current page marked `active`.
- Delay semantics in `LoadService`: sleep is applied AFTER `performOperation`, OUTSIDE the `@Transactional` boundary, and skipped when the value is `0` (so delay=0 is behaviourally identical to today's tight loop).
- Setting keys are the literal strings `load.delay.ms` and `warmup.target`, exposed as `public static final String` constants on `SettingsService`.
- The project has no existing tests. Task 1 bootstraps the test source tree and adds H2 as a test-scope dependency; subsequent tasks add tests under `src/test/java/`.
- Repo is not under git today. Where steps say "commit", they mean "record a save-point" — if `git init` has not been run, initialise it in Task 1; otherwise use normal commits.

---

## File Structure

New files:

- `src/main/java/com/example/auditlog/entity/AppSetting.java` — JPA entity for the `app_setting` table.
- `src/main/java/com/example/auditlog/repository/AppSettingRepository.java` — `JpaRepository<AppSetting, String>`.
- `src/main/java/com/example/auditlog/service/SettingsService.java` — typed read/write/seed API over `AppSetting`, with volatile-Long caches.
- `src/main/java/com/example/auditlog/controller/SettingsController.java` — `GET`/`POST /settings`.
- `src/main/resources/templates/settings/view.html` — Thymeleaf page.
- `src/test/java/com/example/auditlog/service/SettingsServiceTest.java`
- `src/test/java/com/example/auditlog/controller/SettingsControllerTest.java`
- `src/test/java/com/example/auditlog/service/LoadServiceDelayTest.java`
- `src/test/resources/application.properties` — H2 datasource for tests.

Modified files:

- `pom.xml` — add H2 as a test-scope dependency.
- `src/main/resources/application.properties` — add `app.load.delay.ms=${LOAD_DELAY_MS:0}`.
- `src/main/java/com/example/auditlog/service/WarmupService.java` — remove `@Value` field for `warmupTarget`, inject `SettingsService`, read target at start of `warmup()`.
- `src/main/java/com/example/auditlog/service/LoadService.java` — inject `SettingsService`; add sleep-after-op logic in `runLoop`; handle `InterruptedException`; extract the loop body into a package-private method to make it unit-testable.
- `src/main/resources/templates/keyvalue/list.html` — add Settings link to navbar.
- `src/main/resources/templates/auditlog/list.html` — add Settings link to navbar.

---

## Task 1: Test infrastructure bootstrap

**Files:**
- Modify: `pom.xml` (add H2 dependency)
- Create: `src/test/resources/application.properties`
- Create: `src/test/java/com/example/auditlog/AuditLogApplicationTests.java` (smoke test to verify the test harness loads)

**Interfaces:**
- Consumes: nothing.
- Produces: a working `mvn test` command; H2 available under test scope; `spring.datasource.*` overridden for tests so no MySQL is needed.

- [ ] **Step 1: If the repo is not a git repository, initialise it**

Run:
```bash
cd /Users/I072325/Downloads/audit-log
git rev-parse --is-inside-work-tree 2>/dev/null || (git init && git add -A && git commit -m "chore: initial import")
```
Expected: either "true" (already a repo) or a fresh initial commit is created. Subsequent commit steps below assume a working `git commit`.

- [ ] **Step 2: Add H2 as a test-scope dependency in `pom.xml`**

Insert the following inside `<dependencies>`, immediately after the `spring-boot-starter-test` block:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Create test-scoped `application.properties`**

Create `src/test/resources/application.properties` with exactly this content:

```properties
spring.datasource.url=jdbc:h2:mem:auditlog;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.jpa.show-sql=false
app.warmup.target=10
app.load.delay.ms=0
```

Rationale: `MODE=MySQL` makes H2 accept the same SQL Hibernate emits for MySQL. `NON_KEYWORDS=VALUE` is required because H2 2.x treats `VALUE` as reserved even under `MODE=MySQL`, and the existing `KeyValue` entity maps a column literally named `value`. `create-drop` gives every `@SpringBootTest` a clean schema. `app.warmup.target=10` keeps the smoke test's warmup phase near-instant. The Hibernate dialect is intentionally NOT set — Hibernate 6.x auto-detects H2, and setting it explicitly produces the `HHH90000025` deprecation warning.

- [ ] **Step 4: Create smoke test to prove the harness loads**

Create `src/test/java/com/example/auditlog/AuditLogApplicationTests.java`:

```java
package com.example.auditlog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuditLogApplicationTests {

    @Test
    void contextLoads() {
        // Passes if the Spring context comes up. The ApplicationRunner in
        // AuditLogApplication runs warmup + catch-up on H2 with target=10.
    }
}
```

- [ ] **Step 5: Run tests**

Run:
```bash
cd /Users/I072325/Downloads/audit-log && mvn -q test
```
Expected: BUILD SUCCESS, one test executed.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/test
git commit -m "test: bootstrap test harness with H2 and smoke test"
```

---

## Task 2: `AppSetting` entity and repository

**Files:**
- Create: `src/main/java/com/example/auditlog/entity/AppSetting.java`
- Create: `src/main/java/com/example/auditlog/repository/AppSettingRepository.java`
- Test: `src/test/java/com/example/auditlog/repository/AppSettingRepositoryTest.java`

**Interfaces:**
- Consumes: nothing new; uses `spring-boot-starter-data-jpa` and Lombok already on the classpath.
- Produces:
  - `AppSetting(String key, String value)` — constructor and getters/setters via Lombok `@Data @AllArgsConstructor @NoArgsConstructor`. The Java field is `key`; the DB column is `key_name`.
  - `AppSettingRepository extends JpaRepository<AppSetting, String>` — standard CRUD by `key`.

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/example/auditlog/repository/AppSettingRepositoryTest.java`:

```java
package com.example.auditlog.repository;

import com.example.auditlog.entity.AppSetting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// Uses @SpringBootTest (not @DataJpaTest) so the datasource wired via
// src/test/resources/application.properties is honoured — including
// NON_KEYWORDS=VALUE, without which H2 rejects the "value" column as a
// reserved keyword. Matches the pattern SettingsServiceTest uses in Task 3.
@SpringBootTest
class AppSettingRepositoryTest {

    @Autowired
    AppSettingRepository repo;

    @Test
    void saveAndFindByKey() {
        repo.deleteAll();
        repo.save(new AppSetting("load.delay.ms", "250"));

        Optional<AppSetting> found = repo.findById("load.delay.ms");

        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("250");
    }

    @Test
    void saveWithExistingKeyUpserts() {
        repo.deleteAll();
        repo.save(new AppSetting("warmup.target", "1000"));
        repo.save(new AppSetting("warmup.target", "2000"));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById("warmup.target")).map(AppSetting::getValue).contains("2000");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
mvn -q -Dtest=AppSettingRepositoryTest test
```
Expected: FAIL — compilation error, `AppSetting` and `AppSettingRepository` do not exist.

- [ ] **Step 3: Create the entity**

Create `src/main/java/com/example/auditlog/entity/AppSetting.java`:

```java
package com.example.auditlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    // Column is named "key_name" because KEY is a reserved word in MySQL.
    // The Java field stays "key" for readability at call sites.
    @Id
    @Column(name = "key_name", length = 64)
    private String key;

    @Column(name = "value", nullable = false, length = 256)
    private String value;
}
```

- [ ] **Step 4: Create the repository**

Create `src/main/java/com/example/auditlog/repository/AppSettingRepository.java`:

```java
package com.example.auditlog.repository;

import com.example.auditlog.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
mvn -q -Dtest=AppSettingRepositoryTest test
```
Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/auditlog/entity/AppSetting.java \
        src/main/java/com/example/auditlog/repository/AppSettingRepository.java \
        src/test/java/com/example/auditlog/repository/AppSettingRepositoryTest.java
git commit -m "feat(settings): add AppSetting entity and repository"
```

---

## Task 3: `SettingsService` — read with seeding and cache

**Files:**
- Create: `src/main/java/com/example/auditlog/service/SettingsService.java`
- Test: `src/test/java/com/example/auditlog/service/SettingsServiceTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository` (Task 2), `@Value` bindings for `${app.warmup.target:1000000}` and `${app.load.delay.ms:0}`.
- Produces:
  - `public static final String KEY_LOAD_DELAY_MS = "load.delay.ms";`
  - `public static final String KEY_WARMUP_TARGET = "warmup.target";`
  - `long getLoadDelayMs()` — cache-first, seeds from `app.load.delay.ms` on miss.
  - `long getWarmupTarget()` — cache-first, seeds from `app.warmup.target` on miss.
  - Package-private `long getOrSeed(String key, long defaultValue)` — used internally, exposed at package scope so unit tests can drive it directly.
  - Package-private `void resetCacheForTests()` — clears both volatile caches so tests can rely on the cache-miss path deterministically. Not called from production code. Documented with a comment on the method.

This task adds only the read side. Writes come in Task 4 so each behaves as its own reviewable slice.

- [ ] **Step 1: Write failing tests for the read/seed path**

Create `src/test/java/com/example/auditlog/service/SettingsServiceTest.java`:

```java
package com.example.auditlog.service;

import com.example.auditlog.entity.AppSetting;
import com.example.auditlog.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.warmup.target=123",
        "app.load.delay.ms=45"
})
class SettingsServiceTest {

    @Autowired SettingsService service;
    @Autowired AppSettingRepository repo;

    @Test
    void getLoadDelayMs_seedsFromPropertyWhenRowMissing() {
        repo.deleteAll();
        service.resetCacheForTests();

        long value = service.getLoadDelayMs();

        assertThat(value).isEqualTo(45L);
        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .isPresent()
                .map(AppSetting::getValue)
                .contains("45");
    }

    @Test
    void getWarmupTarget_returnsStoredValueWhenRowPresent() {
        repo.deleteAll();
        service.resetCacheForTests();
        repo.save(new AppSetting(SettingsService.KEY_WARMUP_TARGET, "999"));

        long value = service.getWarmupTarget();

        assertThat(value).isEqualTo(999L);
    }

    @Test
    void getLoadDelayMs_returnsSeededDefaultWhenStoredValueIsCorrupt() {
        repo.deleteAll();
        service.resetCacheForTests();
        repo.save(new AppSetting(SettingsService.KEY_LOAD_DELAY_MS, "not-a-number"));

        long value = service.getLoadDelayMs();

        // Falls back to the property default; does not throw, does not
        // overwrite the corrupt row.
        assertThat(value).isEqualTo(45L);
        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .map(AppSetting::getValue)
                .contains("not-a-number");
    }
}
```

Note: `SettingsService` caches per-instance, and `@SpringBootTest` shares the context across all test methods. Every test starts by clearing both the DB (`repo.deleteAll()`) and the cache (`service.resetCacheForTests()`) so each method exercises the cache-miss path deterministically, regardless of execution order.

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
mvn -q -Dtest=SettingsServiceTest test
```
Expected: FAIL — `SettingsService` does not exist.

- [ ] **Step 3: Create `SettingsService` (read side only)**

Create `src/main/java/com/example/auditlog/service/SettingsService.java`:

```java
package com.example.auditlog.service;

import com.example.auditlog.entity.AppSetting;
import com.example.auditlog.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    public static final String KEY_LOAD_DELAY_MS = "load.delay.ms";
    public static final String KEY_WARMUP_TARGET = "warmup.target";

    private final AppSettingRepository repo;

    @Value("${app.warmup.target:1000000}")
    private long warmupTargetDefault;

    @Value("${app.load.delay.ms:0}")
    private long loadDelayMsDefault;

    // volatile Long caches: one writer (UI in Task 4), many readers (load loop).
    // Null sentinel means "not yet read"; any actual value is boxed.
    private volatile Long cachedLoadDelayMs;
    private volatile Long cachedWarmupTarget;

    public long getLoadDelayMs() {
        Long v = cachedLoadDelayMs;
        if (v == null) {
            v = getOrSeed(KEY_LOAD_DELAY_MS, loadDelayMsDefault);
            cachedLoadDelayMs = v;
        }
        return v;
    }

    public long getWarmupTarget() {
        Long v = cachedWarmupTarget;
        if (v == null) {
            v = getOrSeed(KEY_WARMUP_TARGET, warmupTargetDefault);
            cachedWarmupTarget = v;
        }
        return v;
    }

    /**
     * Returns the stored value for {@code key} parsed as a long, or writes
     * and returns {@code defaultValue} if the row is absent. If a row exists
     * but its value cannot be parsed as a long, logs a warning and returns
     * {@code defaultValue} WITHOUT overwriting the row — an operator can
     * inspect and fix the row manually.
     *
     * Package-private for direct testing.
     */
    @Transactional
    long getOrSeed(String key, long defaultValue) {
        return repo.findById(key)
                .map(s -> parseOrDefault(key, s.getValue(), defaultValue))
                .orElseGet(() -> {
                    repo.save(new AppSetting(key, Long.toString(defaultValue)));
                    return defaultValue;
                });
    }

    private long parseOrDefault(String key, String raw, long defaultValue) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' has non-numeric value '{}'; falling back to default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Clears both volatile caches so the next read hits the DB. Not called
     * from production code; exists solely for tests that need deterministic
     * cache-miss behaviour within a shared Spring context.
     */
    void resetCacheForTests() {
        cachedLoadDelayMs = null;
        cachedWarmupTarget = null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
mvn -q -Dtest=SettingsServiceTest test
```
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/auditlog/service/SettingsService.java \
        src/test/java/com/example/auditlog/service/SettingsServiceTest.java
git commit -m "feat(settings): SettingsService reads with seed-once fallback"
```

---

## Task 4: `SettingsService` — writes and cache invalidation

**Files:**
- Modify: `src/main/java/com/example/auditlog/service/SettingsService.java`
- Modify: `src/test/java/com/example/auditlog/service/SettingsServiceTest.java` (add write-side tests)

**Interfaces:**
- Consumes: entity/repository (Task 2), read-side plumbing (Task 3).
- Produces:
  - `void setLoadDelayMs(long ms)` — rejects negatives with `IllegalArgumentException`, upserts, updates cache.
  - `void setWarmupTarget(long target)` — rejects negatives with `IllegalArgumentException`, upserts, updates cache.

- [ ] **Step 1: Append write-side failing tests**

Append these methods to `SettingsServiceTest`:

```java
    @org.junit.jupiter.api.Test
    void setLoadDelayMs_persistsAndUpdatesCache() {
        repo.deleteAll();
        service.resetCacheForTests();

        service.setLoadDelayMs(750L);

        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .map(AppSetting::getValue)
                .contains("750");
        // Next read must return the new value without needing a cache miss.
        assertThat(service.getLoadDelayMs()).isEqualTo(750L);
    }

    @org.junit.jupiter.api.Test
    void setWarmupTarget_persistsAndUpdatesCache() {
        repo.deleteAll();
        service.resetCacheForTests();

        service.setWarmupTarget(5000L);

        assertThat(repo.findById(SettingsService.KEY_WARMUP_TARGET))
                .map(AppSetting::getValue)
                .contains("5000");
        assertThat(service.getWarmupTarget()).isEqualTo(5000L);
    }

    @org.junit.jupiter.api.Test
    void setLoadDelayMs_rejectsNegative() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.setLoadDelayMs(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @org.junit.jupiter.api.Test
    void setWarmupTarget_rejectsNegative() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.setWarmupTarget(-42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run:
```bash
mvn -q -Dtest=SettingsServiceTest test
```
Expected: FAIL — the four new tests fail because the setter methods don't exist yet.

- [ ] **Step 3: Add the setters to `SettingsService`**

Add the following methods to `SettingsService` (before the `parseOrDefault` helper):

```java
    @Transactional
    public void setLoadDelayMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("load.delay.ms must be >= 0");
        }
        repo.save(new AppSetting(KEY_LOAD_DELAY_MS, Long.toString(ms)));
        cachedLoadDelayMs = ms;
    }

    @Transactional
    public void setWarmupTarget(long target) {
        if (target < 0) {
            throw new IllegalArgumentException("warmup.target must be >= 0");
        }
        repo.save(new AppSetting(KEY_WARMUP_TARGET, Long.toString(target)));
        cachedWarmupTarget = target;
    }
```

- [ ] **Step 4: Run to verify they pass**

Run:
```bash
mvn -q -Dtest=SettingsServiceTest test
```
Expected: BUILD SUCCESS, 7 tests pass (3 from Task 3 + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/auditlog/service/SettingsService.java \
        src/test/java/com/example/auditlog/service/SettingsServiceTest.java
git commit -m "feat(settings): SettingsService setters with validation and cache update"
```

---

## Task 5: Wire `WarmupService` to `SettingsService`

**Files:**
- Modify: `src/main/java/com/example/auditlog/service/WarmupService.java`

**Interfaces:**
- Consumes: `SettingsService.getWarmupTarget()` (Task 3).
- Produces: `WarmupService` no longer reads `${app.warmup.target}` directly; the default flows through the DB seed instead.

- [ ] **Step 1: Modify `WarmupService`**

Replace the current contents of `src/main/java/com/example/auditlog/service/WarmupService.java` with:

```java
package com.example.auditlog.service;

import com.example.auditlog.repository.KeyValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarmupService {

    private final KeyValueRepository keyValueRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SettingsService settingsService;

    private static final int BATCH_SIZE = 1000;

    public void warmup() {
        long warmupTarget = settingsService.getWarmupTarget();
        long existing = keyValueRepository.count();
        long needed = warmupTarget - existing;

        if (needed <= 0) {
            log.info("Warmup complete: {} entities already exist (target: {})", existing, warmupTarget);
            return;
        }

        log.info("Warmup started: {} entities exist, need to create {} more to reach target {}", existing, needed, warmupTarget);

        long created = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        while (created < needed) {
            batch.clear();
            int batchCount = (int) Math.min(BATCH_SIZE, needed - created);
            for (int i = 0; i < batchCount; i++) {
                batch.add(new Object[]{UUID.randomUUID().toString(), UUID.randomUUID().toString()});
            }
            jdbcTemplate.batchUpdate("INSERT IGNORE INTO key_value (id, value) VALUES (?, ?)", batch);
            created += batchCount;

            if (created % 10000 == 0 || created == needed) {
                double progress = (double) created / needed * 100;
                log.info("Warmup progress: {}/{} ({} %)", created, needed, String.format("%.1f", progress));
            }
        }

        log.info("Warmup complete: created {} entities, total: {}", created, warmupTarget);
    }
}
```

Changes from the previous version: import for `@Value` removed; `@Value("${app.warmup.target:1000000}")` field removed; `SettingsService` injected via the Lombok constructor; `warmup()` now reads `warmupTarget` from `settingsService`. Body of the loop unchanged.

- [ ] **Step 2: Run the smoke test (which now exercises the seeded path end-to-end)**

Run:
```bash
mvn -q -Dtest=AuditLogApplicationTests test
```
Expected: BUILD SUCCESS. On the H2 test datasource, first-boot warmup seeds `warmup.target=10` (from `src/test/resources/application.properties`) into `app_setting`, creates 10 rows in `key_value`, and the context comes up.

INSERT IGNORE note: H2 in MySQL mode accepts `INSERT IGNORE INTO ... (...) VALUES (...)`. If this step fails with a syntax error, the fallback is to add `spring.datasource.url=...;MODE=MySQL;INIT=...` — but on H2 2.x with `MODE=MySQL` this should just work. Do NOT loosen `INSERT IGNORE` in production code to satisfy tests.

- [ ] **Step 3: Also re-run the whole suite so we haven't regressed anything**

Run:
```bash
mvn -q test
```
Expected: BUILD SUCCESS, 10 tests pass (2 repo + 7 service + 1 smoke).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/auditlog/service/WarmupService.java
git commit -m "refactor(warmup): read warmup target from SettingsService"
```

---

## Task 6: Wire `LoadService` to the delay setting

**Files:**
- Modify: `src/main/java/com/example/auditlog/service/LoadService.java`
- Test: `src/test/java/com/example/auditlog/service/LoadServiceDelayTest.java`

**Interfaces:**
- Consumes: `SettingsService.getLoadDelayMs()` (Task 3).
- Produces:
  - `LoadService.runOneIteration()` — package-private, executes exactly one loop body iteration (pick op, perform, sleep). Unit-testable without spawning the daemon thread.
  - `runLoop` now calls `runOneIteration` in a `while (running)`.

- [ ] **Step 1: Write the failing delay test**

Create `src/test/java/com/example/auditlog/service/LoadServiceDelayTest.java`:

```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run:
```bash
mvn -q -Dtest=LoadServiceDelayTest test
```
Expected: FAIL — `LoadService` does not have a 3-arg constructor and does not have `runOneIteration`.

- [ ] **Step 3: Modify `LoadService`**

Replace the current contents of `src/main/java/com/example/auditlog/service/LoadService.java` with:

```java
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
```

Changes vs. before:
- Constructor gains `SettingsService settingsService` via Lombok.
- `runLoop` calls `runOneIteration()` and catches `InterruptedException` explicitly, restoring the interrupt flag and breaking out.
- New package-private `runOneIteration()` holds the previous loop-body logic plus the post-op sleep.

- [ ] **Step 4: Run the delay tests to verify they pass**

Run:
```bash
mvn -q -Dtest=LoadServiceDelayTest test
```
Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 5: Run the whole suite**

Run:
```bash
mvn -q test
```
Expected: BUILD SUCCESS, 12 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/auditlog/service/LoadService.java \
        src/test/java/com/example/auditlog/service/LoadServiceDelayTest.java
git commit -m "feat(load): throttle load loop by settings-controlled delay"
```

---

## Task 7: `SettingsController` and property wiring

**Files:**
- Create: `src/main/java/com/example/auditlog/controller/SettingsController.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/example/auditlog/controller/SettingsControllerTest.java`

**Interfaces:**
- Consumes: `SettingsService` (Tasks 3–4).
- Produces:
  - `GET /settings` — returns view name `settings/view`, model attributes `loadDelayMs` (Long) and `warmupTarget` (Long). Also passes flash attributes `saved` (Boolean) and `error` (String) through when present.
  - `POST /settings` — form-encoded, params `loadDelayMs` and `warmupTarget` (long). On success, redirects to `/settings` with flash `saved=true`. On `IllegalArgumentException`, redirects to `/settings` with flash `error=<exception message>`.

- [ ] **Step 1: Add `app.load.delay.ms` to `application.properties`**

Append this line to `src/main/resources/application.properties`:

```properties
app.load.delay.ms=${LOAD_DELAY_MS:0}
```

- [ ] **Step 2: Write failing controller tests**

Create `src/test/java/com/example/auditlog/controller/SettingsControllerTest.java`:

```java
package com.example.auditlog.controller;

import com.example.auditlog.service.ConsolidationService;
import com.example.auditlog.service.LoadService;
import com.example.auditlog.service.SettingsService;
import com.example.auditlog.service.WarmupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SettingsService settings;
    // AuditLogApplication implements ApplicationRunner and requires these
    // services via @RequiredArgsConstructor; @WebMvcTest excludes @Service
    // beans and non-web @Configuration from the slice, so they must be mocked.
    @MockBean WarmupService warmupService;
    @MockBean ConsolidationService consolidationService;
    @MockBean LoadService loadService;
    @MockBean AtomicBoolean warmupComplete;

    @Test
    void get_rendersBothValues() throws Exception {
        when(settings.getLoadDelayMs()).thenReturn(120L);
        when(settings.getWarmupTarget()).thenReturn(2500L);

        mvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/view"))
                .andExpect(model().attribute("loadDelayMs", 120L))
                .andExpect(model().attribute("warmupTarget", 2500L));
    }

    @Test
    void post_valid_savesAndFlashesSaved() throws Exception {
        mvc.perform(post("/settings")
                        .param("loadDelayMs", "50")
                        .param("warmupTarget", "1000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("saved", equalTo(true)));

        verify(settings).setLoadDelayMs(50L);
        verify(settings).setWarmupTarget(1000L);
    }

    @Test
    void post_negative_flashesError() throws Exception {
        doThrow(new IllegalArgumentException("load.delay.ms must be >= 0"))
                .when(settings).setLoadDelayMs(eq(-1L));

        mvc.perform(post("/settings")
                        .param("loadDelayMs", "-1")
                        .param("warmupTarget", "1000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("error",
                        equalTo("load.delay.ms must be >= 0")));
    }
}
```

Note: the four mocks beyond `SettingsService` are load-bearing for `@WebMvcTest` to bring up the context. `AuditLogApplication` is annotated `@SpringBootApplication` and implements `ApplicationRunner` with `@RequiredArgsConstructor` — Spring resolves the main class during context loading and needs those dependencies satisfied even in a web-slice test.

Additionally, `@WebMvcTest` performs actual view resolution when a `get()` in `MockMvc` returns a view name. If `templates/settings/view.html` does not exist yet, Thymeleaf throws `TemplateInputException`. Create a minimal stub template as part of this task:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head><title>Settings</title></head>
<body>
<span th:text="${loadDelayMs}"></span>
<span th:text="${warmupTarget}"></span>
</body>
</html>
```

at `src/main/resources/templates/settings/view.html`. Task 8 replaces this stub with the full page.

- [ ] **Step 3: Run to verify they fail**

Run:
```bash
mvn -q -Dtest=SettingsControllerTest test
```
Expected: FAIL — `SettingsController` does not exist.

- [ ] **Step 4: Create the controller**

Create `src/main/java/com/example/auditlog/controller/SettingsController.java`:

```java
package com.example.auditlog.controller;

import com.example.auditlog.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public String view(Model model) {
        model.addAttribute("loadDelayMs", settingsService.getLoadDelayMs());
        model.addAttribute("warmupTarget", settingsService.getWarmupTarget());
        return "settings/view";
    }

    @PostMapping
    public String save(@RequestParam long loadDelayMs,
                       @RequestParam long warmupTarget,
                       RedirectAttributes ra) {
        try {
            settingsService.setLoadDelayMs(loadDelayMs);
            settingsService.setWarmupTarget(warmupTarget);
            ra.addFlashAttribute("saved", true);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }
}
```

- [ ] **Step 5: Run to verify tests pass**

Run:
```bash
mvn -q -Dtest=SettingsControllerTest test
```
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.properties \
        src/main/java/com/example/auditlog/controller/SettingsController.java \
        src/test/java/com/example/auditlog/controller/SettingsControllerTest.java \
        src/main/resources/templates/settings/view.html
git commit -m "feat(settings): controller + property default for load delay"
```

---

## Task 8: Settings page template and navbar updates

**Files:**
- Create: `src/main/resources/templates/settings/view.html`
- Modify: `src/main/resources/templates/keyvalue/list.html`
- Modify: `src/main/resources/templates/auditlog/list.html`

**Interfaces:**
- Consumes: model attributes from `SettingsController` (Task 7): `loadDelayMs`, `warmupTarget`, plus flash `saved` and `error`.
- Produces: full HTML page at `/settings`; consistent three-link navbar on all three pages.

This task has no automated tests. Verification is manual — start the app locally (or via Docker) and load `/settings`.

Task 7 left a minimal stub at `src/main/resources/templates/settings/view.html` so `@WebMvcTest` could render the view. Step 1 replaces that stub with the full page — an overwrite, not a new file.

- [ ] **Step 1: Create the settings template**

Overwrite `src/main/resources/templates/settings/view.html` with:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Settings</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark bg-dark">
    <div class="container">
        <a class="navbar-brand" href="/">Audit Log App</a>
        <div class="navbar-nav">
            <a class="nav-link" href="/keyvalue">KeyValue</a>
            <a class="nav-link" href="/auditlog">Audit Log</a>
            <a class="nav-link active" href="/settings">Settings</a>
        </div>
    </div>
</nav>

<div class="container mt-4">
    <h2>Settings</h2>

    <div th:if="${saved}" class="alert alert-success" role="alert">
        Settings saved.
    </div>
    <div th:if="${error}" class="alert alert-danger" role="alert" th:text="${error}">
        error
    </div>

    <form method="post" action="/settings" class="mt-3">
        <div class="mb-3">
            <label for="loadDelayMs" class="form-label">Load delay (ms)</label>
            <input type="number" min="0" class="form-control" id="loadDelayMs" name="loadDelayMs"
                   th:value="${loadDelayMs}" required>
            <div class="form-text">Sleep between load operations. 0 = no throttle. Takes effect immediately.</div>
        </div>
        <div class="mb-3">
            <label for="warmupTarget" class="form-label">Warmup target (rows)</label>
            <input type="number" min="0" class="form-control" id="warmupTarget" name="warmupTarget"
                   th:value="${warmupTarget}" required>
            <div class="form-text">Takes effect on next application restart.</div>
        </div>
        <button type="submit" class="btn btn-primary">Save</button>
        <a href="/settings" class="btn btn-secondary">Cancel</a>
    </form>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

- [ ] **Step 2: Add Settings link to the KeyValue navbar**

In `src/main/resources/templates/keyvalue/list.html`, replace the `<div class="navbar-nav">` block with:

```html
        <div class="navbar-nav">
            <a class="nav-link active" href="/keyvalue">KeyValue</a>
            <a class="nav-link" href="/auditlog">Audit Log</a>
            <a class="nav-link" href="/settings">Settings</a>
        </div>
```

- [ ] **Step 3: Add Settings link to the AuditLog navbar**

In `src/main/resources/templates/auditlog/list.html`, replace the `<div class="navbar-nav">` block with:

```html
        <div class="navbar-nav">
            <a class="nav-link" href="/keyvalue">KeyValue</a>
            <a class="nav-link active" href="/auditlog">Audit Log</a>
            <a class="nav-link" href="/settings">Settings</a>
        </div>
```

- [ ] **Step 4: Manual verification**

Run:
```bash
cd /Users/I072325/Downloads/audit-log && make run
# wait for warmup to finish; tail logs if desired: make logs
```

Then in a browser:
1. Open `http://localhost:8080/` — should redirect to `/keyvalue`; navbar shows three links; `KeyValue` marked active.
2. Click `Audit Log` — navbar shows `Audit Log` active.
3. Click `Settings` — page loads with the two number inputs pre-filled (0 and 1000000 on a fresh DB; whatever was saved on subsequent visits).
4. Change `Load delay (ms)` to `500`, click Save. Page reloads with green "Settings saved." banner. `Load delay` input shows `500`.
5. Watch the audit log tab — the rate of new REGULAR entries should visibly slow.
6. Set `Load delay (ms)` to `-5`, click Save. Page reloads with red banner containing "load.delay.ms must be >= 0".

Cleanup:
```bash
make stop
```

- [ ] **Step 5: Full test suite**

Run:
```bash
mvn -q test
```
Expected: BUILD SUCCESS, 15 tests pass (nothing regressed).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates
git commit -m "feat(settings): /settings page and shared navbar link"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
| --- | --- |
| Data model (`app_setting` table, `key_name` PK) | Task 2 |
| `AppSetting` entity | Task 2 |
| `AppSettingRepository` | Task 2 |
| `SettingsService` reads + seeding + parse-failure fallback | Task 3 |
| `SettingsService` writes + validation + cache invalidation | Task 4 |
| `WarmupService` reads target from settings | Task 5 |
| `LoadService` sleeps by `getLoadDelayMs()`, skipped when 0, outside `@Transactional`, `InterruptedException` handled | Task 6 |
| `SettingsController` GET/POST with flash attributes | Task 7 |
| `application.properties` gains `app.load.delay.ms` | Task 7 |
| `templates/settings/view.html` with alerts, form, verbatim help text | Task 8 |
| Navbar consistency on all three templates | Task 8 |
| Tests: `SettingsServiceTest` (5 required cases) | Tasks 3 + 4 (all 5 cases covered) |
| Tests: `SettingsControllerTest` (3 required cases) | Task 7 (all 3 cases) |
| Tests: `LoadServiceDelayTest` (delay applied) | Task 6 (2 cases including zero-skip) |
| Configuration: `app.load.delay.ms=${LOAD_DELAY_MS:0}` | Task 7 |
| Migration / backward compat: `ddl-auto=update` creates table | Task 2 (no schema migration); Task 5 (WarmupService no longer reads env directly, so old env var still seeds via `@Value` on `SettingsService`) |

No gaps found.

**Placeholder scan:** no "TBD", "TODO", or hand-wavy language remains. All code blocks are complete. All commands have expected output. Ambiguity notes in Task 5 (H2 `INSERT IGNORE`) and Task 3 (cache carry-over) are calibration guidance for the implementer, not placeholders.

**Type consistency check:**
- `SettingsService.KEY_LOAD_DELAY_MS` / `KEY_WARMUP_TARGET` — used identically in Tasks 3, 4, service, and tests.
- `getLoadDelayMs()` / `getWarmupTarget()` returning `long` — consumed with matching types by `WarmupService` (Task 5), `LoadService.runOneIteration` (Task 6), and `SettingsController` (Task 7). Model attributes are boxed `Long` (as returned by `Model.addAttribute` autoboxing); the controller test asserts against `120L`/`2500L` which MockMvc's model-matcher handles as `Long`.
- `setLoadDelayMs(long)` / `setWarmupTarget(long)` throw `IllegalArgumentException` with messages containing `">= 0"` — the service test's `hasMessageContaining(">= 0")` matches, and the controller test's exact string `"load.delay.ms must be >= 0"` matches the exception message emitted by the setter.
- `LoadService` constructor: 3 args `(KeyValueRepository, AuditLogRepository, SettingsService)` — matches the Lombok `@RequiredArgsConstructor` field order (final fields declared top-to-bottom) and the test's `new LoadService(kvRepo, auditRepo, settings)` in Task 6.
- `WarmupService` constructor: 3 args `(KeyValueRepository, JdbcTemplate, SettingsService)` — Task 5 shows the field order; nothing else constructs `WarmupService` directly.
- `runOneIteration()` — declared package-private in Task 6, called by both `runLoop` and the two Task 6 tests.

Consistent throughout.

---

Plan complete and saved to `docs/superpowers/plans/2026-07-06-runtime-settings.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
