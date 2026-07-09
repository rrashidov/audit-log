package com.example.auditlog.service;

import com.example.auditlog.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

class ConsolidationServiceSchedulingTest {

    private ConsolidationService service;
    private ConsolidationService spy;

    @BeforeEach
    void setUp() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean warmupComplete = new AtomicBoolean(true);
        // Build real instance; pass itself as the self-proxy (no Spring context needed here).
        service = new ConsolidationService(repo, warmupComplete, null);
        spy = spy(service);
        // Prevent actual DB work — we only care about call order.
        doNothing().when(spy).runMinute();
        doNothing().when(spy).runHour();
        doNothing().when(spy).runDay();
        doNothing().when(spy).runWeek();
        doNothing().when(spy).runMonth();
        doNothing().when(spy).runYear();
        // Inject spy as self so the scheduled methods route through it.
        service = spy;
    }

    @Test
    void consolidateMinuteAndHour_callsMinuteThenHourInOrder() {
        service.consolidateMinuteAndHour();

        InOrder order = inOrder(spy);
        order.verify(spy).runMinute();
        order.verify(spy).runHour();
        verifyNoMoreInteractions(spy);
    }

    @Test
    void consolidateDayAndAbove_callsDayWeekMonthYearInOrder() {
        service.consolidateDayAndAbove();

        InOrder order = inOrder(spy);
        order.verify(spy).runDay();
        order.verify(spy).runWeek();
        order.verify(spy).runMonth();
        order.verify(spy).runYear();
        verifyNoMoreInteractions(spy);
    }

    @Test
    void consolidateMinuteAndHour_isNoOpWhenWarmupIncomplete() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean notReady = new AtomicBoolean(false);
        ConsolidationService cold = new ConsolidationService(repo, notReady, null);
        ConsolidationService coldSpy = spy(cold);
        doNothing().when(coldSpy).runMinute();
        doNothing().when(coldSpy).runHour();

        coldSpy.consolidateMinuteAndHour();

        verify(coldSpy, never()).runMinute();
        verify(coldSpy, never()).runHour();
    }

    @Test
    void consolidateDayAndAbove_isNoOpWhenWarmupIncomplete() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AtomicBoolean notReady = new AtomicBoolean(false);
        ConsolidationService cold = new ConsolidationService(repo, notReady, null);
        ConsolidationService coldSpy = spy(cold);
        doNothing().when(coldSpy).runDay();
        doNothing().when(coldSpy).runWeek();
        doNothing().when(coldSpy).runMonth();
        doNothing().when(coldSpy).runYear();

        coldSpy.consolidateDayAndAbove();

        verify(coldSpy, never()).runDay();
        verify(coldSpy, never()).runWeek();
        verify(coldSpy, never()).runMonth();
        verify(coldSpy, never()).runYear();
    }
}
