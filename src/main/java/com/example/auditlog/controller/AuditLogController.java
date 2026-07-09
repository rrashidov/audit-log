package com.example.auditlog.controller;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import com.example.auditlog.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/auditlog")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    private static final int PAGE_SIZE = 20;

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

    @GetMapping
    public String list(
            Model model,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) EntryType entryType,
            @RequestParam(required = false) OperationType operationType) {

        PageRequest pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "dateTime"));
        Page<AuditLog> auditLogs;

        if (entryType != null && operationType != null) {
            auditLogs = auditLogRepository.findByEntryTypeAndOperationType(entryType, operationType, pageable);
        } else if (entryType != null) {
            auditLogs = auditLogRepository.findByEntryType(entryType, pageable);
        } else if (operationType != null) {
            auditLogs = auditLogRepository.findByOperationType(operationType, pageable);
        } else {
            auditLogs = auditLogRepository.findAll(pageable);
        }

        model.addAttribute("auditLogs", auditLogs);
        model.addAttribute("currentPage", page);
        model.addAttribute("selectedEntryType", entryType);
        model.addAttribute("selectedOperationType", operationType);
        model.addAttribute("entryTypes", EntryType.values());
        model.addAttribute("operationTypes", OperationType.values());
        return "auditlog/list";
    }

    @GetMapping("/grid")
    public String grid(Model model) {
        List<Object[]> rows = auditLogRepository.countGroupedByEntryType();

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
        // Fixed upper bound for scale computation: the JS canvas grows dynamically but will never exceed this row count given the scale formula.
        int estimatedRows = 50;
        int dataCells = (estimatedRows * cols) - (FREE_ROWS * cols);
        long scale = Math.max(1L, (long) Math.ceil((double) totalRecords / dataCells));

        Map<String, String> colors = new LinkedHashMap<>(TYPE_COLORS);

        model.addAttribute("counts", counts);
        model.addAttribute("totalRecords", totalRecords);
        model.addAttribute("scale", scale);
        model.addAttribute("typeOrder", TYPE_ORDER);
        model.addAttribute("colors", colors);
        return "auditlog/grid";
    }
}
