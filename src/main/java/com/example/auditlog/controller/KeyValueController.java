package com.example.auditlog.controller;

import com.example.auditlog.entity.KeyValue;
import com.example.auditlog.repository.KeyValueRepository;
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
@RequestMapping("/keyvalue")
@RequiredArgsConstructor
public class KeyValueController {

    private final KeyValueRepository keyValueRepository;

    private static final int PAGE_SIZE = 20;

    @GetMapping
    public String list(Model model, @RequestParam(defaultValue = "0") int page) {
        Page<KeyValue> keyValues = keyValueRepository.findAll(
                PageRequest.of(page, PAGE_SIZE, Sort.by("id")));
        model.addAttribute("keyValues", keyValues);
        model.addAttribute("currentPage", page);
        return "keyvalue/list";
    }
}
