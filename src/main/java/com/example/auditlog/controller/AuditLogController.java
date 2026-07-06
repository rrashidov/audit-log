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

@Controller
@RequestMapping("/auditlog")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    private static final int PAGE_SIZE = 20;

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
}
