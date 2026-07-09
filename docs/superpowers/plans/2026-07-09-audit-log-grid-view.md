# Audit Log Grid View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/auditlog/grid` page that renders all audit log records as a colored canvas grid (defragmenter-style), grouped and colored by EntryType.

**Architecture:** A single JPQL `GROUP BY entryType` query in the repository feeds a new `AuditLogController.grid()` method that builds a model and returns a new Thymeleaf template. The template injects the counts as `window.GRID_DATA` JSON; a plain JS `<script>` block reads it and draws colored squares on a `<canvas>` element. No new libraries.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Thymeleaf, H2 (tests), Bootstrap 5.3.2 (CDN), plain canvas JS.

## Global Constraints

- No new Maven dependencies.
- All tests use `@SpringBootTest` with H2 in-memory DB (see `src/test/resources/application.properties`).
- Follow existing code style: Lombok, `@RequiredArgsConstructor`, AssertJ assertions.
- Canvas width: 720px. Square size: 10px. Gap: 2px. Step: 12px. Free space rows: 3.
- Type display order (coarsest first): `YEAR_CONSOLIDATION`, `MONTH_CONSOLIDATION`, `DAY_CONSOLIDATION`, `HOUR_CONSOLIDATION`, `MINUTE_CONSOLIDATION`, `REGULAR`.
- Colors: `YEAR_CONSOLIDATION=#e879f9`, `MONTH_CONSOLIDATION=#f87171`, `DAY_CONSOLIDATION=#a78bfa`, `HOUR_CONSOLIDATION=#f59e0b`, `MINUTE_CONSOLIDATION=#60a5fa`, `REGULAR=#4ade80`.
- Free space color: `#2a2a3e`, border: `#3a3a5e`.

---

### Task 1: Add `countByEntryType` repository query and test it

**Files:**
- Modify: `src/main/java/com/example/auditlog/repository/AuditLogRepository.java`
- Create: `src/test/java/com/example/auditlog/repository/AuditLogRepositoryGridTest.java`

**Interfaces:**
- Produces: `List<Object[]> countByEntryType()` — each element is `[EntryType, Long]`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/auditlog/repository/AuditLogRepositoryGridTest.java`:

```java
package com.example.auditlog.repository;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditLogRepositoryGridTest {

    @Autowired
    AuditLogRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
    }

    @Test
    void countByEntryType_returnsCorrectCountsPerType() {
        repo.save(auditLog(EntryType.REGULAR));
        repo.save(auditLog(EntryType.REGULAR));
        repo.save(auditLog(EntryType.MINUTE_CONSOLIDATION));
        repo.save(auditLog(EntryType.HOUR_CONSOLIDATION));

        List<Object[]> results = repo.countByEntryType();

        Map<EntryType, Long> counts = results.stream()
                .collect(Collectors.toMap(
                        row -> (EntryType) row[0],
                        row -> (Long) row[1]
                ));

        assertThat(counts.get(EntryType.REGULAR)).isEqualTo(2L);
        assertThat(counts.get(EntryType.MINUTE_CONSOLIDATION)).isEqualTo(1L);
        assertThat(counts.get(EntryType.HOUR_CONSOLIDATION)).isEqualTo(1L);
        assertThat(counts).doesNotContainKey(EntryType.DAY_CONSOLIDATION);
    }

    @Test
    void countByEntryType_emptyTable_returnsEmptyList() {
        List<Object[]> results = repo.countByEntryType();
        assertThat(results).isEmpty();
    }

    private AuditLog auditLog(EntryType type) {
        return AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .entryType(type)
                .operationType(OperationType.CREATE)
                .dateTime(LocalDateTime.now())
                .count(1)
                .duration(10)
                .build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -pl . -Dtest=AuditLogRepositoryGridTest -q
```

Expected: FAIL — `countByEntryType()` does not exist yet.

- [ ] **Step 3: Add the query to `AuditLogRepository`**

Add to `src/main/java/com/example/auditlog/repository/AuditLogRepository.java`:

```java
import org.springframework.data.jpa.repository.Query;

@Query("SELECT a.entryType, COUNT(a) FROM AuditLog a GROUP BY a.entryType")
List<Object[]> countByEntryType();
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -pl . -Dtest=AuditLogRepositoryGridTest -q
```

Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/auditlog/repository/AuditLogRepository.java \
        src/test/java/com/example/auditlog/repository/AuditLogRepositoryGridTest.java
git commit -m "feat(grid): add countByEntryType query to AuditLogRepository"
```

---

### Task 2: Add `GET /auditlog/grid` controller endpoint

**Files:**
- Modify: `src/main/java/com/example/auditlog/controller/AuditLogController.java`

**Interfaces:**
- Consumes: `AuditLogRepository.countByEntryType()` → `List<Object[]>`
- Produces: `GET /auditlog/grid` → Thymeleaf view `auditlog/grid` with model attributes: `counts` (`Map<String,Long>`), `totalRecords` (`long`), `scale` (`long`), `typeOrder` (`List<String>`), `colors` (`Map<String,String>`)

Note: `counts` and `colors` are keyed by the `EntryType` name string (e.g. `"REGULAR"`) so Thymeleaf can serialize them to JSON cleanly.

- [ ] **Step 1: Add the `grid()` method to `AuditLogController`**

Open `src/main/java/com/example/auditlog/controller/AuditLogController.java` and add:

```java
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

private static final int CANVAS_WIDTH = 720;
private static final int SQUARE_STEP = 12;   // 10px square + 2px gap
private static final int FREE_ROWS = 3;

private static final List<String> TYPE_ORDER = List.of(
        "YEAR_CONSOLIDATION",
        "MONTH_CONSOLIDATION",
        "DAY_CONSOLIDATION",
        "HOUR_CONSOLIDATION",
        "MINUTE_CONSOLIDATION",
        "REGULAR"
);

private static final Map<String, String> TYPE_COLORS = Map.of(
        "YEAR_CONSOLIDATION",  "#e879f9",
        "MONTH_CONSOLIDATION", "#f87171",
        "DAY_CONSOLIDATION",   "#a78bfa",
        "HOUR_CONSOLIDATION",  "#f59e0b",
        "MINUTE_CONSOLIDATION","#60a5fa",
        "REGULAR",             "#4ade80"
);

@GetMapping("/grid")
public String grid(Model model) {
    List<Object[]> rows = auditLogRepository.countByEntryType();

    Map<String, Long> counts = new LinkedHashMap<>();
    for (String type : TYPE_ORDER) {
        counts.put(type, 0L);
    }
    for (Object[] row : rows) {
        counts.put(((EntryType) row[0]).name(), (Long) row[1]);
    }

    long totalRecords = counts.values().stream().mapToLong(Long::longValue).sum();

    int cols = CANVAS_WIDTH / SQUARE_STEP;
    // Canvas height will be computed in JS; server needs dataCells for scale only.
    // Use a generous row estimate (50 rows) to keep scale reasonable.
    int estimatedRows = 50;
    int dataCells = (estimatedRows * cols) - (FREE_ROWS * cols);
    long scale = Math.max(1L, (long) Math.ceil((double) totalRecords / dataCells));

    model.addAttribute("counts", counts);
    model.addAttribute("totalRecords", totalRecords);
    model.addAttribute("scale", scale);
    model.addAttribute("typeOrder", TYPE_ORDER);
    model.addAttribute("colors", TYPE_COLORS);
    return "auditlog/grid";
}
```

- [ ] **Step 2: Start the application and verify the endpoint responds**

```bash
./mvnw spring-boot:run -q &
sleep 8
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auditlog/grid
```

Expected: `500` (template not found yet) or `200` if template exists — either confirms the route is wired. Then stop the server:

```bash
pkill -f "spring-boot:run"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/auditlog/controller/AuditLogController.java
git commit -m "feat(grid): add GET /auditlog/grid controller endpoint"
```

---

### Task 3: Create `auditlog/grid.html` template

**Files:**
- Create: `src/main/resources/templates/auditlog/grid.html`

**Interfaces:**
- Consumes model: `counts` (`Map<String,Long>`), `totalRecords` (`long`), `scale` (`long`), `typeOrder` (`List<String>`), `colors` (`Map<String,String>`)

- [ ] **Step 1: Create the template**

Create `src/main/resources/templates/auditlog/grid.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Audit Log Grid</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark bg-dark">
    <div class="container">
        <a class="navbar-brand" href="/">Audit Log App</a>
        <div class="navbar-nav">
            <a class="nav-link" href="/keyvalue">KeyValue</a>
            <a class="nav-link" href="/auditlog">Audit Log</a>
            <a class="nav-link active" href="/auditlog/grid">Grid</a>
            <a class="nav-link" href="/settings">Settings</a>
        </div>
    </div>
</nav>

<div class="container mt-4">
    <h2>Audit Log Grid</h2>
    <p class="text-muted">Each square represents <span th:text="${scale}">1</span> record(s), colored by entry type. Coarsest consolidations first, regular entries last.</p>

    <!-- Legend -->
    <div class="d-flex flex-wrap gap-3 mb-3">
        <div th:each="type : ${typeOrder}" class="d-flex align-items-center gap-2">
            <div style="width:14px;height:14px;border-radius:2px;"
                 th:style="|width:14px;height:14px;border-radius:2px;background:${colors[type]};|"></div>
            <span class="small" th:text="${type}"></span>
        </div>
        <div class="d-flex align-items-center gap-2">
            <div style="width:14px;height:14px;border-radius:2px;background:#2a2a3e;border:1px solid #3a3a5e;"></div>
            <span class="small">Free space</span>
        </div>
    </div>

    <!-- Canvas -->
    <div style="background:#1e1e2e;border-radius:8px;padding:12px;display:inline-block;">
        <canvas id="grid"></canvas>
    </div>

    <p class="text-muted mt-2 small">
        Total records: <span th:text="${totalRecords}">0</span>
        &nbsp;|&nbsp;
        Scale: 1 square = <span th:text="${scale}">1</span> record(s)
    </p>
</div>

<script th:inline="javascript">
    /*<![CDATA[*/
    window.GRID_DATA = {
        counts: /*[[${counts}]]*/ {},
        scale:  /*[[${scale}]]*/ 1,
        typeOrder: /*[[${typeOrder}]]*/ [],
        colors: /*[[${colors}]]*/ {}
    };
    /*]]>*/
</script>
<script>
    (function () {
        const { counts, scale, typeOrder, colors } = window.GRID_DATA;

        const SQ = 10, GAP = 2, STEP = 12;
        const FREE_ROWS = 3;
        const CANVAS_W = 720;

        const cols = Math.floor(CANVAS_W / STEP);

        // squares per type: max(1, ceil(count/scale)), 0 if count is 0
        const squaresPerType = {};
        let totalSquares = 0;
        typeOrder.forEach(t => {
            const c = counts[t] || 0;
            squaresPerType[t] = c > 0 ? Math.max(1, Math.ceil(c / scale)) : 0;
            totalSquares += squaresPerType[t];
        });

        const dataRows = Math.ceil(totalSquares / cols);
        const canvasH = (dataRows + FREE_ROWS) * STEP;

        const canvas = document.getElementById('grid');
        canvas.width = CANVAS_W;
        canvas.height = canvasH;
        const ctx = canvas.getContext('2d');

        let cell = 0;
        typeOrder.forEach(t => {
            ctx.fillStyle = colors[t];
            for (let i = 0; i < squaresPerType[t]; i++, cell++) {
                const col = cell % cols;
                const row = Math.floor(cell / cols);
                ctx.fillRect(col * STEP, row * STEP, SQ, SQ);
            }
        });

        // free space
        const totalCells = (dataRows + FREE_ROWS) * cols;
        for (let s = cell; s < totalCells; s++) {
            const col = s % cols;
            const row = Math.floor(s / cols);
            ctx.fillStyle = '#2a2a3e';
            ctx.strokeStyle = '#3a3a5e';
            ctx.lineWidth = 0.5;
            ctx.fillRect(col * STEP, row * STEP, SQ, SQ);
            ctx.strokeRect(col * STEP + 0.5, row * STEP + 0.5, SQ - 1, SQ - 1);
        }
    })();
</script>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

- [ ] **Step 2: Start the application and verify the page renders**

```bash
./mvnw spring-boot:run -q &
sleep 8
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auditlog/grid
```

Expected: `200`. Then open `http://localhost:8080/auditlog/grid` in a browser and confirm the canvas renders with colored squares and free-space rows at the bottom. Stop the server:

```bash
pkill -f "spring-boot:run"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/auditlog/grid.html
git commit -m "feat(grid): add auditlog/grid.html Thymeleaf template"
```

---

### Task 4: Add "Grid" navbar link to all existing templates

**Files:**
- Modify: `src/main/resources/templates/auditlog/list.html`
- Modify: `src/main/resources/templates/keyvalue/list.html`
- Modify: `src/main/resources/templates/settings/view.html`

**Interfaces:** None — purely visual.

- [ ] **Step 1: Update `auditlog/list.html` navbar**

In `src/main/resources/templates/auditlog/list.html`, find the `<div class="navbar-nav">` block and add the Grid link between "Audit Log" and "Settings":

```html
<div class="navbar-nav">
    <a class="nav-link" href="/keyvalue">KeyValue</a>
    <a class="nav-link active" href="/auditlog">Audit Log</a>
    <a class="nav-link" href="/auditlog/grid">Grid</a>
    <a class="nav-link" href="/settings">Settings</a>
</div>
```

- [ ] **Step 2: Update `keyvalue/list.html` navbar**

In `src/main/resources/templates/keyvalue/list.html`, find the `<div class="navbar-nav">` block and add the Grid link:

```html
<div class="navbar-nav">
    <a class="nav-link active" href="/keyvalue">KeyValue</a>
    <a class="nav-link" href="/auditlog">Audit Log</a>
    <a class="nav-link" href="/auditlog/grid">Grid</a>
    <a class="nav-link" href="/settings">Settings</a>
</div>
```

- [ ] **Step 3: Update `settings/view.html` navbar**

In `src/main/resources/templates/settings/view.html`, find the `<div class="navbar-nav">` block and add the Grid link:

```html
<div class="navbar-nav">
    <a class="nav-link" href="/keyvalue">KeyValue</a>
    <a class="nav-link" href="/auditlog">Audit Log</a>
    <a class="nav-link" href="/auditlog/grid">Grid</a>
    <a class="nav-link active" href="/settings">Settings</a>
</div>
```

- [ ] **Step 4: Verify all nav links work**

```bash
./mvnw spring-boot:run -q &
sleep 8
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/keyvalue
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auditlog
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/settings
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auditlog/grid
pkill -f "spring-boot:run"
```

Expected: all four return `200`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/auditlog/list.html \
        src/main/resources/templates/keyvalue/list.html \
        src/main/resources/templates/settings/view.html
git commit -m "feat(grid): add Grid nav link to all templates"
```

---

### Task 5: Run full test suite and verify nothing is broken

- [ ] **Step 1: Run all tests**

```bash
./mvnw test -q
```

Expected: all tests pass, including the new `AuditLogRepositoryGridTest`.

- [ ] **Step 2: Commit if any test-only fixes were needed**

If step 1 required any fixes, commit them:

```bash
git add -p
git commit -m "fix(grid): address test failures after grid view addition"
```

If all tests passed without changes, no commit needed.
