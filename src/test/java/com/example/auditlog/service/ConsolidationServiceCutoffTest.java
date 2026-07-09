package com.example.auditlog.service;

import com.example.auditlog.enums.EntryType;
import com.example.auditlog.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsolidationServiceCutoffTest {

    private ConsolidationService buildService(AuditLogRepository repo) {
        // self=null is fine here: these tests call runXxx() directly,
        // which does not invoke self
        return new ConsolidationService(repo, new AtomicBoolean(true), null);
    }

    private LocalDateTime capturedCutoff(AuditLogRepository repo, EntryType sourceType) {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).findByEntryTypeAndDateTimeBefore(eq(sourceType), captor.capture());
        return captor.getValue();
    }

    @Test
    void runMinute_cutoffIsOneMinuteBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runMinute();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.REGULAR);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runHour_cutoffIsOneHourBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runHour();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.MINUTE_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS).minusHours(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runDay_cutoffIsOneDayBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runDay();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.HOUR_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).minusDays(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runMonth_cutoffIsOneMonthBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runMonth();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.DAY_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).minusMonths(1);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    void runYear_cutoffIsOneYearBehindSnappedNow() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findByEntryTypeAndDateTimeBefore(any(), any())).thenReturn(List.of());

        buildService(repo).runYear();

        LocalDateTime cutoff = capturedCutoff(repo, EntryType.MONTH_CONSOLIDATION);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).withDayOfYear(1).minusYears(1);
        assertThat(cutoff).isEqualTo(expected);
    }
}
