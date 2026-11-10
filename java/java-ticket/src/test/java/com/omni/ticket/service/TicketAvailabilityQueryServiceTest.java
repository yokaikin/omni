package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.omni.ticket.ai.TicketIntent;
import com.omni.ticket.dto.ActivityVO;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.VenueMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TicketAvailabilityQueryServiceTest {

    @Mock ActivityMapper activityMapper;
    @Mock SessionMapper sessionMapper;
    @Mock TicketTypeMapper ticketTypeMapper;
    @Mock VenueMapper venueMapper;
    @Mock SessionSeatMapper sessionSeatMapper;
    @Mock SeatAdjacencyEvaluator seatAdjacencyEvaluator;

    @Test
    void usesLiveTicketTypeStockAndPriceInsteadOfElasticsearchActivitySummary() {
        Activity activity = activity(10L, "真实演唱会");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "看台", "380.00", 5);
        Venue venue = venue(30L, "东京巨蛋", "东京");
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(20L)).thenReturn(List.of());

        ActivityVO staleCandidate = new ActivityVO();
        staleCandidate.setId(10L);
        staleCandidate.setName("旧索引价格");
        staleCandidate.setMinPrice(new BigDecimal("1.00"));

        TicketIntent intent = TicketIntent.builder().keyword("演唱会").peopleCount(2).build();
        List<TicketFinderResult> results = new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(staleCandidate), intent);

        assertEquals(1, results.size());
        assertEquals(new BigDecimal("380.00"), results.get(0).getPrice());
        assertEquals(5, results.get(0).getAvailableQuantity());
        assertEquals("on_sale", results.get(0).getSaleStatus());
        assertTrue(results.get(0).getActivityName().contains("真实"));
    }

    @Test
    void dropsCandidateWhenRealtimeStockNoLongerMeetsPeopleCount() {
        Activity activity = activity(10L, "演唱会");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "看台", "380.00", 1);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(20L)).thenReturn(List.of());

        TicketIntent intent = TicketIntent.builder().keyword("演唱会").peopleCount(2).build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
    }

    @Test
    void dropsCandidateWhenLivePriceOrSaleStatusDoesNotMatchIntent() {
        Activity activity = activity(10L, "演唱会");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "看台", "800.00", 5);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));

        TicketIntent intent = TicketIntent.builder()
                .keyword("演唱会")
                .maxPrice(new BigDecimal("500.00"))
                .saleStatus("sold_out")
                .build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
    }

    @Test
    void requiresReadOnlyAdjacentSeatEvaluationForSeatedTicket() {
        Activity activity = activity(10L, "演唱会");
        activity.setSeatMapVisibility("published");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "内场", "680.00", 5);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(20L)).thenReturn(List.of());
        when(sessionSeatMapper.selectAvailableSeatsForFinder(20L, 40L)).thenReturn(List.of());
        when(seatAdjacencyEvaluator.hasAdjacentSeats(any(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(false);

        TicketIntent intent = TicketIntent.builder()
                .keyword("演唱会")
                .peopleCount(2)
                .needAdjacentSeats(true)
                .build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
        org.mockito.Mockito.verify(seatAdjacencyEvaluator)
                .hasAdjacentSeats(any(), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void doesNotReturnResultsWhenAdjacentSeatRequestHasNoPeopleCount() {
        TicketIntent intent = TicketIntent.builder()
                .keyword("演唱会")
                .needAdjacentSeats(true)
                .build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
        verifyNoInteractions(activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator);
    }

    @Test
    void evaluatesAdjacentSeatsEvenWhenTicketTypeHasSeatBlock() {
        Activity activity = activity(10L, "演唱会");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "内场", "680.00", 5);
        ticketType.setSeatBlockId(88L);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(20L)).thenReturn(List.of());
        when(sessionSeatMapper.selectAvailableSeatsForFinder(20L, 40L)).thenReturn(List.of());
        when(seatAdjacencyEvaluator.hasAdjacentSeats(any(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(false);

        TicketIntent intent = TicketIntent.builder()
                .keyword("演唱会")
                .peopleCount(2)
                .needAdjacentSeats(true)
                .build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
        org.mockito.Mockito.verify(seatAdjacencyEvaluator)
                .hasAdjacentSeats(any(), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void supportSeatRequiresPublishedSeatMapAndLiveSeatSnapshot() {
        Activity activity = activity(10L, "演唱会");
        activity.setSeatMapVisibility("published");
        Session session = session(20L, 10L, 30L);
        TicketType ticketType = ticketType(40L, 20L, "内场", "680.00", 5);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(20L)).thenReturn(List.of());

        TicketIntent intent = TicketIntent.builder().keyword("演唱会").isSupportSeat(true).build();

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), intent).isEmpty());
    }

    @Test
    void excludesAlreadyStartedSessionsEvenWithoutUserDateConstraint() {
        Activity activity = activity(10L, "演唱会");
        Session session = session(20L, 10L, 30L);
        session.setStartTime(LocalDateTime.of(2026, 9, 1, 19, 30));
        TicketType ticketType = ticketType(40L, 20L, "看台", "380.00", 5);
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(30L, "场馆", "东京")));

        assertTrue(new TicketAvailabilityQueryService(
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, java.time.Clock.systemUTC()).findAvailable(
                List.of(candidate(10L)), TicketIntent.builder().keyword("演唱会").build()).isEmpty());
    }

    private ActivityVO candidate(Long id) {
        ActivityVO candidate = new ActivityVO();
        candidate.setId(id);
        return candidate;
    }

    private Activity activity(Long id, String name) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setName(name);
        activity.setStatus(1);
        activity.setPublishStatus("published");
        return activity;
    }

    private Session session(Long id, Long activityId, Long venueId) {
        Session session = new Session();
        session.setId(id);
        session.setActivityId(activityId);
        session.setVenueId(venueId);
        session.setStartTime(LocalDateTime.now().plusDays(30));
        session.setStatus(1);
        return session;
    }

    private TicketType ticketType(Long id, Long sessionId, String name, String price, int remainStock) {
        TicketType ticketType = new TicketType();
        ticketType.setId(id);
        ticketType.setSessionId(sessionId);
        ticketType.setName(name);
        ticketType.setPrice(new BigDecimal(price));
        ticketType.setRemainStock(remainStock);
        ticketType.setStatus(1);
        return ticketType;
    }

    private Venue venue(Long id, String name, String city) {
        Venue venue = new Venue();
        venue.setId(id);
        venue.setName(name);
        venue.setCity(city);
        venue.setStatus(1);
        return venue;
    }
}
