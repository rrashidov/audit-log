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
