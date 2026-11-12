package com.omni.ticket.service;

import com.omni.exception.BusinessException;
import com.omni.ticket.dto.TeamSeatLockReleaseRequest;
import com.omni.ticket.dto.TeamSeatLockRequest;
import com.omni.ticket.dto.TeamSeatLockResponse;
import com.omni.ticket.dto.TeamSeatLockValidationRequest;
import com.omni.ticket.dto.TeamSeatLockValidationResponse;
import com.omni.ticket.dto.TicketSalesLockRequest;
import com.omni.ticket.dto.TicketSalesOrderRequest;
import com.omni.ticket.dto.TicketPurchaseContextRequest;
import com.omni.ticket.dto.TicketPurchaseContextResponse;
import com.omni.ticket.dto.TicketSalesQuoteRequest;
import com.omni.ticket.dto.TicketSalesQuoteResponse;
import com.omni.ticket.dto.TicketSalesReleaseResponse;
import com.omni.ticket.dto.TicketSalesSeatLockResponse;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.entity.SessionSeat;
import com.omni.ticket.entity.SeatBlock;
import com.omni.ticket.entity.TicketGroup;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SeatBlockMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.TicketGroupMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.VenueMapper;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.Venue;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

class TicketSalesInternalServiceTest {

    @Test
    void quoteRejectsUnknownTicketType() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setQuantity(2);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.quote(request));

        assertEquals("票档不存在", exception.getMessage());
    }

    @Test
    void quoteReturnsBackendPriceAndQuantity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(4001L);
        ticketType.setSessionId(3001L);
        ticketType.setName("看台A");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(1);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(true);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setQuantity(2);

        TicketSalesQuoteResponse response = service.quote(request);

        assertEquals(new BigDecimal("380.00"), response.getUnitPrice());
        assertEquals("看台A", response.getTicketName());
        assertEquals(2, response.getQuantity());
        assertEquals(false, response.getSeatBased());
    }

    @Test
    void quoteRejectsWhenSessionNotSellable() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = ticketType(4001L, "A区票", new BigDecimal("380.00"));
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(false);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setQuantity(1);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.quote(request));

        assertEquals("当前场次不可售", exception.getMessage());
        verify(sessionMapper, never()).selectById(3001L);
    }

    @Test
    void purchaseContextReturnsReadableSnapshotForUnsellableTicketType() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(4001L);
        ticketType.setSessionId(3001L);
        ticketType.setName("看台 A");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(0);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);

        Session session = new Session();
        session.setId(3001L);
        session.setActivityId(2001L);
        session.setVenueId(1001L);
        session.setStartTime(LocalDateTime.of(2026, 7, 18, 19, 30));
        when(sessionMapper.selectById(3001L)).thenReturn(session);

        Activity activity = new Activity();
        activity.setId(2001L);
        activity.setName("周末演唱会");
        activity.setPoster("/poster.jpg");
        when(activityMapper.selectById(2001L)).thenReturn(activity);

        Venue venue = new Venue();
        venue.setId(1001L);
        venue.setName("万象体育馆");
        when(venueMapper.selectById(1001L)).thenReturn(venue);

        TicketPurchaseContextRequest request = new TicketPurchaseContextRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);

        TicketPurchaseContextResponse response = service.getPurchaseContext(request);

        assertEquals(3001L, response.getSessionId());
        assertEquals(4001L, response.getTicketTypeId());
        assertEquals(2001L, response.getActivityId());
        assertEquals("周末演唱会", response.getActivityName());
        assertEquals("/poster.jpg", response.getActivityPoster());
        assertEquals("看台 A", response.getTicketTypeName());
        assertEquals("万象体育馆", response.getVenueName());
        assertEquals(LocalDateTime.of(2026, 7, 18, 19, 30), response.getSessionTime());
        verify(sessionSeatMapper, never()).selectSessionSellable(3001L);
    }

    @Test
    void listVisibleTicketTypesHidesAllWhenSessionNotSellable() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(false);
        when(ticketTypeMapper.selectBatchIds(List.of(4001L))).thenReturn(List.of(ticketType(4001L, "A区票", new BigDecimal("380.00"))));

        com.omni.ticket.dto.TicketTypesVisibleRequest request = new com.omni.ticket.dto.TicketTypesVisibleRequest();
        request.setSessionId(3001L);
        request.setTicketTypeIds(List.of(4001L));

        assertTrue(service.listVisibleTicketTypes(request).isEmpty());
    }

    @Test
    void listVisibleTicketTypesCapsRemainStockAtTotalStock() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        TicketType ticketType = ticketType(4001L, "A", new BigDecimal("380.00"));
        ticketType.setTotalStock(300);
        ticketType.setRemainStock(440);
        when(ticketTypeMapper.selectBatchIds(List.of(4001L))).thenReturn(List.of(ticketType));

        com.omni.ticket.dto.TicketTypesVisibleRequest request = new com.omni.ticket.dto.TicketTypesVisibleRequest();
        request.setSessionId(3001L);
        request.setTicketTypeIds(List.of(4001L));

        List<com.omni.ticket.dto.TicketTypeVisibleResponse> result = service.listVisibleTicketTypes(request);

        assertEquals(300, result.get(0).getTotalStock());
        assertEquals(300, result.get(0).getRemainStock());
    }

    @Test
    void listVisibleTicketTypesUsesSessionSeatStockForSeatedTicketTypes() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        TicketType ticketType = ticketType(4001L, "普通票", new BigDecimal("380.00"));
        ticketType.setTotalStock(200);
        ticketType.setRemainStock(200);
        when(ticketTypeMapper.selectBatchIds(List.of(4001L))).thenReturn(List.of(ticketType));
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(3001L))
                .thenReturn(List.of(stockSnapshot(4001L, 200, 199)));

        com.omni.ticket.dto.TicketTypesVisibleRequest request = new com.omni.ticket.dto.TicketTypesVisibleRequest();
        request.setSessionId(3001L);
        request.setTicketTypeIds(List.of(4001L));

        List<com.omni.ticket.dto.TicketTypeVisibleResponse> result = service.listVisibleTicketTypes(request);

        assertEquals(200, result.get(0).getTotalStock());
        assertEquals(199, result.get(0).getRemainStock());
    }

    @Test
    void lockStockDecreasesTicketTypeStock() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, mock(SessionSeatMapper.class));
        when(ticketTypeMapper.decreaseRemainStockIfEnough(4001L, 2)).thenReturn(1);

        TicketSalesLockRequest request = lockRequest(null, 2);

        service.lockStock(request);

        verify(ticketTypeMapper).decreaseRemainStockIfEnough(4001L, 2);
    }

    @Test
    void lockStockRejectsWhenSessionNotSellableBeforeStockDecrease() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockStock(lockRequest(null, 2)));

        assertEquals("当前场次不可售", exception.getMessage());
        verify(ticketTypeMapper, never()).decreaseRemainStockIfEnough(4001L, 2);
    }

    @Test
    void lockSeatsLocksEachSeat() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.lockSeat(eq(501L), eq(3001L), eq(4001L), any())).thenReturn(1);
        when(sessionSeatMapper.lockSeat(eq(502L), eq(3001L), eq(4001L), any())).thenReturn(1);
        when(sessionSeatMapper.selectSeatLabelsByIds(List.of(502L, 501L))).thenReturn(List.of("A-2", "A-1"));

        TicketSalesSeatLockResponse response = service.lockSeats(lockRequest(List.of(502L, 501L), 2));

        assertEquals(List.of(502L, 501L), response.getLockedSeatIds());
        assertEquals(List.of("A-2", "A-1"), response.getSeatLabels());
        verify(sessionSeatMapper).selectSeatLabelsByIds(List.of(502L, 501L));
    }

    @Test
    void lockSeatsRejectsWhenSessionNotSellableBeforeSeatLock() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockSeats(lockRequest(List.of(501L), 1)));

        assertEquals("当前场次不可售", exception.getMessage());
        verify(sessionSeatMapper, never()).lockSeat(any(), any(), any(), any());
    }

    @Test
    void lockSeatsRandomlyAllocatesRealSeatsWhenSeatIdsMissing() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.selectAvailableSeatsForFinder(3001L, 4001L))
                .thenReturn(List.of(seat(601L, 50L, 7001L, 1, 1), seat(602L, 50L, 7001L, 1, 2)));
        when(sessionSeatMapper.lockSeat(eq(601L), eq(3001L), eq(4001L), any())).thenReturn(1);
        when(sessionSeatMapper.lockSeat(eq(602L), eq(3001L), eq(4001L), any())).thenReturn(1);
        when(sessionSeatMapper.selectSeatLabelsByIds(List.of(601L, 602L))).thenReturn(List.of("A区1排1座", "A区1排2座"));

        TicketSalesLockRequest request = lockRequest(null, 2);
        request.setAllocateRandom(true);

        TicketSalesSeatLockResponse response = service.lockSeats(request);

        assertEquals(List.of(601L, 602L), response.getLockedSeatIds());
        assertEquals(List.of("A区1排1座", "A区1排2座"), response.getSeatLabels());
    }

    @Test
    void lockSeatsFallsBackToTicketStockForBoundSeatlessStandingTicketType() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        SeatBlockMapper seatBlockMapper = mock(SeatBlockMapper.class);
        TicketGroupMapper ticketGroupMapper = mock(TicketGroupMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper, seatBlockMapper, ticketGroupMapper);
        TicketType ticketType = ticketType(4001L, "站区票", new BigDecimal("280.00"));
        ticketType.setSeatBlockId(7001L);
        SeatBlock standingBlock = new SeatBlock();
        standingBlock.setId(7001L);
        standingBlock.setOwnerType("session");
        standingBlock.setOwnerId(3001L);
        standingBlock.setBlockType("standingBlock");
        standingBlock.setTicketGroupKey("standing-1");
        standingBlock.setCapacity(300);
        standingBlock.setStatus(1);
        TicketGroup standingGroup = new TicketGroup();
        standingGroup.setName("站区票");
        standingGroup.setActivityPrice(new BigDecimal("280.00"));
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectRandomAvailableSeatIds(3001L, 4001L, 2)).thenReturn(List.of());
        when(seatBlockMapper.selectSalesBindingById(7001L)).thenReturn(standingBlock);
        when(ticketGroupMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(standingGroup);
        when(ticketTypeMapper.decreaseRemainStockIfEnough(4001L, 2)).thenReturn(1);

        TicketSalesLockRequest request = lockRequest(null, 2);
        request.setAllocateRandom(true);

        TicketSalesSeatLockResponse response = service.lockSeats(request);

        assertEquals(List.of(), response.getLockedSeatIds());
        assertEquals(List.of("系统分配站区票 x2"), response.getSeatLabels());
        verify(ticketTypeMapper).decreaseRemainStockIfEnough(4001L, 2);
        verify(seatBlockMapper, never()).selectById(7001L);
        verify(sessionSeatMapper, never()).lockSeat(any(), any(), any(), any());
    }

    @Test
    void lockSeatsRejectsSeatlessStandingTicketTypeWithoutExplicitSeatBlockBinding() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        SeatBlockMapper seatBlockMapper = mock(SeatBlockMapper.class);
        TicketGroupMapper ticketGroupMapper = mock(TicketGroupMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper, seatBlockMapper, ticketGroupMapper);
        TicketType ticketType = ticketType(4001L, "站区票", new BigDecimal("280.00"));
        SeatBlock standingBlock = new SeatBlock();
        standingBlock.setId(7001L);
        standingBlock.setTicketGroupKey("standing-1");
        TicketGroup standingGroup = new TicketGroup();
        standingGroup.setName("站区票");
        standingGroup.setActivityPrice(new BigDecimal("280.00"));
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectRandomAvailableSeatIds(3001L, 4001L, 2)).thenReturn(List.of());

        TicketSalesLockRequest request = lockRequest(null, 2);
        request.setAllocateRandom(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockSeats(request));

        assertEquals("票档库存不足", exception.getMessage());
        verify(seatBlockMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(ticketGroupMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(ticketTypeMapper, never()).decreaseRemainStockIfEnough(4001L, 2);
    }

    @Test
    void lockSeatsDoesNotFallbackForSeatedTicketTypeWhenRealSeatsAreInsufficient() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        SeatBlockMapper seatBlockMapper = mock(SeatBlockMapper.class);
        TicketGroupMapper ticketGroupMapper = mock(TicketGroupMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper, seatBlockMapper, ticketGroupMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A区票", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectRandomAvailableSeatIds(3001L, 4001L, 2)).thenReturn(List.of(601L));
        when(seatBlockMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        TicketSalesLockRequest request = lockRequest(null, 2);
        request.setAllocateRandom(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockSeats(request));

        assertEquals("票档库存不足", exception.getMessage());
        verify(ticketTypeMapper, never()).decreaseRemainStockIfEnough(4001L, 2);
        verify(sessionSeatMapper, never()).lockSeat(any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsPrefersStrictContiguousSeats() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(List.of(
                seat(501L, 10L, null, 1, 1),
                seat(502L, 10L, null, 1, 2),
                seat(503L, 10L, null, 1, 3),
                seat(601L, 10L, null, 2, 1)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 502L, 503L)), eq("team-lock-1"), any()))
                .thenReturn(3);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 502L, 503L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, null, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, null, 1, 2, "A-2"),
                        lockedSeat(503L, 10L, null, 1, 3, "A-3")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_TICKET_TYPE")));

        assertEquals(List.of(501L, 502L, 503L), response.getLockedSeatIds());
        assertEquals(List.of("A-1", "A-2", "A-3"), response.getSeatLabels());
        assertEquals("STRICT_CONTIGUOUS", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsRetryReturnsExistingSeatsWithoutRelocking() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectLockedByRequestId(3001L, 4001L, "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, 900L, 1, 2, "A-2"),
                        lockedSeat(503L, 10L, 900L, 1, 3, "A-3")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_TICKET_TYPE")));

        assertEquals(List.of(501L, 502L, 503L), response.getLockedSeatIds());
        assertEquals(List.of("A-1", "A-2", "A-3"), response.getSeatLabels());
        assertEquals("STRICT_CONTIGUOUS", response.getMatchedStrategy());
        verify(sessionSeatMapper, never()).selectAvailableForTeamLock(any(), any(), any());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsAcquiresRequestLockBeforeReadingExistingLock() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(sessionSeatMapper.selectLockedByRequestId(3001L, 4001L, "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, 900L, 1, 2, "A-2")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(2, "STRICT_CONTIGUOUS", List.of()));

        assertEquals(List.of(501L, 502L), response.getLockedSeatIds());
        InOrder inOrder = inOrder(sessionSeatMapper);
        inOrder.verify(sessionSeatMapper).acquireTeamLockRequestLock("team-lock-1");
        inOrder.verify(sessionSeatMapper).selectLockedByRequestId(3001L, 4001L, "team-lock-1");
        verify(sessionSeatMapper, never()).selectAvailableForTeamLock(any(), any(), any());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsRetryInfersExistingStrategyWithStablePriority() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(sessionSeatMapper.selectLockedByRequestId(3001L, 4001L, "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, 900L, 1, 2, "A-2")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(2, "SAME_TICKET_TYPE", List.of()));

        assertEquals(List.of(501L, 502L), response.getLockedSeatIds());
        assertEquals("STRICT_CONTIGUOUS", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsRetryReturnsExistingSeatsWhenSessionIsNoLongerSellable() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(false);
        when(sessionSeatMapper.selectLockedByRequestId(3001L, 4001L, "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, 900L, 1, 2, "A-2")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(2, "STRICT_CONTIGUOUS", List.of()));

        assertEquals(List.of(501L, 502L), response.getLockedSeatIds());
        assertEquals(List.of("A-1", "A-2"), response.getSeatLabels());
        verify(ticketTypeMapper, never()).selectById(any());
        verify(sessionSeatMapper, never()).selectAvailableForTeamLock(any(), any(), any());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsFallsBackToSameTicketTypeWhenAllowed() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(List.of(
                seat(501L, 10L, null, 1, 1),
                seat(503L, 10L, null, 1, 3),
                seat(601L, 20L, null, 4, 8)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 503L, 601L)), eq("team-lock-1"), any()))
                .thenReturn(3);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 503L, 601L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, null, 1, 1, "A-1"),
                        lockedSeat(503L, 10L, null, 1, 3, "A-3"),
                        lockedSeat(601L, 20L, null, 4, 8, "B-8")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_TICKET_TYPE")));

        assertEquals(List.of(501L, 503L, 601L), response.getLockedSeatIds());
        assertEquals("SAME_TICKET_TYPE", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsFallsBackToSameTicketTypeForFourSeatsWhenOnlyThreeAreContiguous() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 40)).thenReturn(List.of(
                seat(501L, 10L, 900L, 1, 1),
                seat(502L, 10L, 900L, 1, 2),
                seat(503L, 10L, 900L, 1, 3),
                seat(601L, 20L, 901L, 4, 8)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 502L, 503L, 601L)), eq("team-lock-1"), any()))
                .thenReturn(4);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 502L, 503L, 601L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, 900L, 1, 2, "A-2"),
                        lockedSeat(503L, 10L, 900L, 1, 3, "A-3"),
                        lockedSeat(601L, 20L, 901L, 4, 8, "B-8")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(4, "STRICT_CONTIGUOUS", List.of("SAME_TICKET_TYPE")));

        assertEquals(List.of(501L, 502L, 503L, 601L), response.getLockedSeatIds());
        assertEquals("SAME_TICKET_TYPE", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsSameTicketTypeUsesBlockRowSeatOrderingBeforeSection() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 20)).thenReturn(List.of(
                seat(502L, 1L, 20L, 1, 1),
                seat(501L, 99L, 10L, 1, 1),
                seat(503L, 99L, 10L, 1, 2)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 503L)), eq("team-lock-1"), any()))
                .thenReturn(2);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 503L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 99L, 10L, 1, 1, "Block-10-1"),
                        lockedSeat(503L, 99L, 10L, 1, 2, "Block-10-2")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(2, "SAME_TICKET_TYPE", List.of()));

        assertEquals(List.of(501L, 503L), response.getLockedSeatIds());
        assertEquals("SAME_TICKET_TYPE", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsMatchesSameBlockSeats() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 20)).thenReturn(List.of(
                seat(501L, 10L, 900L, 1, 1),
                seat(601L, 20L, 900L, 4, 8),
                seat(701L, 30L, 901L, 2, 3)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 601L)), eq("team-lock-1"), any()))
                .thenReturn(2);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 601L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, 900L, 1, 1, "Block-A-1"),
                        lockedSeat(601L, 20L, 900L, 4, 8, "Block-A-8")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(2, "SAME_BLOCK", List.of()));

        assertEquals(List.of(501L, 601L), response.getLockedSeatIds());
        assertEquals(List.of("Block-A-1", "Block-A-8"), response.getSeatLabels());
        assertEquals("SAME_BLOCK", response.getMatchedStrategy());
    }

    @Test
    void lockTeamSeatsExpandsCandidateWindowUntilStrategyMatches() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        List<SessionSeat> firstWindow = sparseTeamSeats(30);
        List<SessionSeat> expandedWindow = new ArrayList<>(firstWindow);
        expandedWindow.add(seat(901L, 90L, 990L, 1, 1));
        expandedWindow.add(seat(902L, 90L, 990L, 1, 2));
        expandedWindow.add(seat(903L, 90L, 990L, 1, 3));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(firstWindow);
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 60)).thenReturn(expandedWindow);
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(901L, 902L, 903L)), eq("team-lock-1"), any()))
                .thenReturn(3);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(901L, 902L, 903L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(901L, 90L, 990L, 1, 1, "Late-1"),
                        lockedSeat(902L, 90L, 990L, 1, 2, "Late-2"),
                        lockedSeat(903L, 90L, 990L, 1, 3, "Late-3")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_BLOCK")));

        assertEquals(List.of(901L, 902L, 903L), response.getLockedSeatIds());
        assertEquals("STRICT_CONTIGUOUS", response.getMatchedStrategy());
        verify(sessionSeatMapper).selectAvailableForTeamLock(3001L, 4001L, 30);
        verify(sessionSeatMapper).selectAvailableForTeamLock(3001L, 4001L, 60);
    }

    @Test
    void lockTeamSeatsExpandsPrimaryStrategyBeforeUsingSameTicketTypeFallback() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        List<SessionSeat> firstWindow = sparseTeamSeats(30);
        List<SessionSeat> expandedWindow = new ArrayList<>(firstWindow);
        expandedWindow.add(seat(901L, 90L, 990L, 1, 1));
        expandedWindow.add(seat(902L, 90L, 990L, 1, 2));
        expandedWindow.add(seat(903L, 90L, 990L, 1, 3));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(firstWindow);
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 60)).thenReturn(expandedWindow);
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(901L, 902L, 903L)), eq("team-lock-1"), any()))
                .thenReturn(3);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(901L, 902L, 903L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(901L, 90L, 990L, 1, 1, "Late-1"),
                        lockedSeat(902L, 90L, 990L, 1, 2, "Late-2"),
                        lockedSeat(903L, 90L, 990L, 1, 3, "Late-3")));

        TeamSeatLockResponse response = service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_TICKET_TYPE")));

        assertEquals(List.of(901L, 902L, 903L), response.getLockedSeatIds());
        assertEquals("STRICT_CONTIGUOUS", response.getMatchedStrategy());
        verify(sessionSeatMapper).selectAvailableForTeamLock(3001L, 4001L, 30);
        verify(sessionSeatMapper).selectAvailableForTeamLock(3001L, 4001L, 60);
    }

    @Test
    void lockTeamSeatsDoesNotSatisfySameBlockWithSameSectionAndDifferentBlocks() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 20)).thenReturn(List.of(
                seat(501L, 10L, 900L, 1, 1),
                seat(502L, 10L, 901L, 1, 2)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.lockTeamSeats(teamLockRequest(2, "SAME_BLOCK", List.of())));

        assertEquals("没有可满足数量的组队锁座策略", exception.getMessage());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsRejectsWhenTeamSeatUpdateCountDoesNotMatchQuantity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(List.of(
                seat(501L, 10L, null, 1, 1),
                seat(502L, 10L, null, 1, 2),
                seat(503L, 10L, null, 1, 3)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 502L, 503L)), eq("team-lock-1"), any()))
                .thenReturn(2);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of())));

        assertEquals("组队锁座状态已变化，请重试", exception.getMessage());
        verify(sessionSeatMapper, never()).selectLockedByRequest(any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsRejectsPastExpireTimeBeforeSelectingCandidates() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        TeamSeatLockRequest request = teamLockRequest(3, "STRICT_CONTIGUOUS", List.of());
        request.setLockExpireTime(LocalDateTime.now().minusSeconds(1));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockTeamSeats(request));

        assertEquals("组队锁座过期时间必须晚于当前时间", exception.getMessage());
        verify(sessionSeatMapper, never()).selectAvailableForTeamLock(any(), any(), any());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void lockTeamSeatsRejectsWhenLockedReadDoesNotMatchQuantity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(List.of(
                seat(501L, 10L, null, 1, 1),
                seat(502L, 10L, null, 1, 2),
                seat(503L, 10L, null, 1, 3)));
        when(sessionSeatMapper.lockTeamSeatIds(eq(3001L), eq(4001L), eq(List.of(501L, 502L, 503L)), eq("team-lock-1"), any()))
                .thenReturn(3);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 502L, 503L), "team-lock-1"))
                .thenReturn(List.of(
                        lockedSeat(501L, 10L, null, 1, 1, "A-1"),
                        lockedSeat(502L, 10L, null, 1, 2, "A-2")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of())));

        assertEquals("组队锁座归属校验失败", exception.getMessage());
    }

    @Test
    void lockTeamSeatsRejectsWhenNoStrategyCanSatisfyQuantity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, sessionSeatMapper);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType(4001L, "A", new BigDecimal("380.00")));
        when(sessionSeatMapper.selectAvailableForTeamLock(3001L, 4001L, 30)).thenReturn(List.of(
                seat(501L, 10L, null, 1, 1),
                seat(503L, 20L, null, 1, 3)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.lockTeamSeats(teamLockRequest(3, "STRICT_CONTIGUOUS", List.of("SAME_BLOCK"))));

        assertEquals("没有可满足数量的组队锁座策略", exception.getMessage());
        verify(sessionSeatMapper, never()).lockTeamSeatIds(any(), any(), any(), any(), any());
    }

    @Test
    void validateTeamSeatLockRequiresSameLockRequestId() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.selectLockedByRequest(3001L, 4001L, List.of(501L, 502L), "team-lock-1"))
                .thenReturn(List.of(lockedSeat(501L, 10L, null, 1, 1, "A-1")));

        TeamSeatLockValidationRequest request = new TeamSeatLockValidationRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));
        request.setLockRequestId("team-lock-1");

        TeamSeatLockValidationResponse response = service.validateTeamSeatLock(request);

        assertEquals(false, response.getValid());
        assertEquals(List.of(501L), response.getSeatIds());
        assertEquals(List.of("A-1"), response.getSeatLabels());
    }

    @Test
    void releaseTeamSeatLockDelegatesByRequestAndSeats() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(2);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");
        request.setSeatIds(List.of(501L, 502L));

        assertEquals(true, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper, never()).countTeamSeatLocksByRequest(any(), any());
    }

    @Test
    void releaseTeamSeatLockReturnsFalseWhenOnlySomeRequestedSeatsRemainLocked() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(1);
        when(sessionSeatMapper.countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(1);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");
        request.setSeatIds(List.of(501L, 502L));

        assertEquals(false, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L));
    }

    @Test
    void releaseTeamSeatLockTreatsPartialReleaseWithNoRemainingRequestedLocksAsSuccess() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(1);
        when(sessionSeatMapper.countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(0);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");
        request.setSeatIds(List.of(501L, 502L));

        assertEquals(true, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L));
    }

    @Test
    void releaseTeamSeatLockTreatsZeroReleaseWithNoRemainingRequestedLocksAsSuccess() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(0);
        when(sessionSeatMapper.countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L))).thenReturn(0);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");
        request.setSeatIds(List.of(501L, 502L));

        assertEquals(true, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).countTeamSeatLocksByRequest("team-lock-1", List.of(501L, 502L));
    }

    @Test
    void releaseTeamSeatLockWithoutSeatIdsReleasesAllSeatsForRequest() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequestId("team-lock-1")).thenReturn(2);
        when(sessionSeatMapper.countTeamSeatLocksByRequestId("team-lock-1")).thenReturn(0);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");

        assertEquals(true, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).releaseTeamSeatLockByRequestId("team-lock-1");
        verify(sessionSeatMapper).countTeamSeatLocksByRequestId("team-lock-1");
        verify(sessionSeatMapper, never()).releaseTeamSeatLockByRequest(any(), any());
    }

    @Test
    void releaseTeamSeatLockWithoutSeatIdsTreatsNoRemainingLocksAsSuccess() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequestId("team-lock-1")).thenReturn(0);
        when(sessionSeatMapper.countTeamSeatLocksByRequestId("team-lock-1")).thenReturn(0);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");

        assertEquals(true, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).releaseTeamSeatLockByRequestId("team-lock-1");
        verify(sessionSeatMapper).countTeamSeatLocksByRequestId("team-lock-1");
        verify(sessionSeatMapper, never()).releaseTeamSeatLockByRequest(any(), any());
    }

    @Test
    void releaseTeamSeatLockWithoutSeatIdsReturnsFalseWhenRemainingLocksExist() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseTeamSeatLockByRequestId("team-lock-1")).thenReturn(0);
        when(sessionSeatMapper.countTeamSeatLocksByRequestId("team-lock-1")).thenReturn(1);
        TeamSeatLockReleaseRequest request = new TeamSeatLockReleaseRequest();
        request.setLockRequestId("team-lock-1");

        assertEquals(false, service.releaseTeamSeatLock(request));
        verify(sessionSeatMapper).releaseTeamSeatLockByRequestId("team-lock-1");
        verify(sessionSeatMapper).countTeamSeatLocksByRequestId("team-lock-1");
        verify(sessionSeatMapper, never()).releaseTeamSeatLockByRequest(any(), any());
    }

    @Test
    void lockTeamSeatsMethodHasTransactionalRollbackForException() throws Exception {
        Method method = TicketSalesInternalService.class.getDeclaredMethod("lockTeamSeats", TeamSeatLockRequest.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertArrayEquals(new Class<?>[] {Exception.class}, transactional.rollbackFor());
    }

    @Test
    void lockStockThrowsWhenInsufficientStock() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        TicketSalesInternalService service = service(ticketTypeMapper, mock(SessionSeatMapper.class));
        when(ticketTypeMapper.decreaseRemainStockIfEnough(4001L, 2)).thenReturn(0);

        TicketSalesLockRequest request = lockRequest(null, 2);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockStock(request));

        assertEquals("票档库存不足", exception.getMessage());
    }

    @Test
    void confirmSoldMarksSeats() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.markSeatSold(501L, 3001L, 4001L, 88L, null)).thenReturn(1);
        when(sessionSeatMapper.markSeatSold(502L, 3001L, 4001L, 88L, null)).thenReturn(1);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(88L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));

        service.confirmSold(request);

        verify(sessionSeatMapper).markSeatSold(501L, 3001L, 4001L, 88L, null);
        verify(sessionSeatMapper).markSeatSold(502L, 3001L, 4001L, 88L, null);
    }

    @Test
    void confirmSoldTreatsAlreadySoldSameOrderMapperOneAsNoOpSuccess() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.markSeatSold(501L, 3001L, 4001L, 88L, null)).thenReturn(1);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(88L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L));

        service.confirmSold(request);

        verify(sessionSeatMapper).markSeatSold(501L, 3001L, 4001L, 88L, null);
    }

    @Test
    void confirmSoldUsesLockRequestIdFenceAndFailsOnPartialSeatUpdate() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.markSeatSold(501L, 3001L, 4001L, 88L, "TEAM-GRAB-1")).thenReturn(1);
        when(sessionSeatMapper.markSeatSold(502L, 3001L, 4001L, 88L, "TEAM-GRAB-1")).thenReturn(0);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(88L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));
        request.setLockRequestId("TEAM-GRAB-1");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.confirmSold(request));

        assertEquals(409, exception.getCode());
        verify(sessionSeatMapper).markSeatSold(501L, 3001L, 4001L, 88L, "TEAM-GRAB-1");
        verify(sessionSeatMapper).markSeatSold(502L, 3001L, 4001L, 88L, "TEAM-GRAB-1");
    }

    @Test
    void releaseUsesLockRequestIdFenceAndFailsOnPartialSeatUpdate() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseLockedSeat(501L, 3001L, 4001L, "TEAM-GRAB-1")).thenReturn(1);
        when(sessionSeatMapper.releaseLockedSeat(502L, 3001L, 4001L, "TEAM-GRAB-1")).thenReturn(0);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));
        request.setLockRequestId("TEAM-GRAB-1");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.release(request));

        assertEquals(409, exception.getCode());
        verify(sessionSeatMapper).releaseLockedSeat(501L, 3001L, 4001L, "TEAM-GRAB-1");
        verify(sessionSeatMapper).releaseLockedSeat(502L, 3001L, 4001L, "TEAM-GRAB-1");
    }

    @Test
    void releaseReturnsReleasedSeatDetails() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseLockedSeat(501L, 3001L, 4001L, null)).thenReturn(1);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L));
        request.setQuantity(1);

        TicketSalesReleaseResponse response = service.release(request);

        assertEquals(3001L, response.getSessionId());
        assertEquals(4001L, response.getTicketTypeId());
        assertEquals(1, response.getQuantity());
        assertEquals(1, response.getRestoredQuantity());
        assertEquals(List.of(501L), response.getSeatIds());
    }

    @Test
    void releaseTreatsAlreadyAvailableSeatAsIdempotentSuccess() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.releaseLockedSeat(501L, 3001L, 4001L, null)).thenReturn(0);
        when(sessionSeatMapper.countAvailableBySeatIds(3001L, 4001L, List.of(501L))).thenReturn(1);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L));
        request.setQuantity(1);

        TicketSalesReleaseResponse response = service.release(request);

        assertEquals(1, response.getQuantity());
        assertEquals(1, response.getRestoredQuantity());
        assertEquals(List.of(501L), response.getSeatIds());
    }

    @Test
    void refundReturnsZeroRestoredQuantityWhenRefundedSeatsCannotResell() {
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = service(mock(TicketTypeMapper.class), sessionSeatMapper);
        when(sessionSeatMapper.restoreSoldSeat(501L, 3001L)).thenReturn(0);

        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L));
        request.setQuantity(1);

        TicketSalesReleaseResponse response = service.refund(request);

        assertEquals(0, response.getRestoredQuantity());
        assertEquals(List.of(501L), response.getSeatIds());
    }

    @Test
    void quoteReturnsTourStationAndSeatLabels() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(4001L);
        ticketType.setSessionId(3001L);
        ticketType.setName("看台A");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(1);
        when(ticketTypeMapper.selectById(4001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(true);

        Session session = new Session();
        session.setId(3001L);
        session.setActivityId(2001L);
        session.setVenueId(1001L);
        session.setStartTime(LocalDateTime.now().plusDays(30));
        when(sessionMapper.selectById(3001L)).thenReturn(session);

        Activity activity = new Activity();
        activity.setId(2001L);
        activity.setName("巡演北京站");
        activity.setPoster("poster.jpg");
        activity.setTourId(9001L);
        activity.setStationId(9101L);
        when(activityMapper.selectById(2001L)).thenReturn(activity);

        Venue venue = new Venue();
        venue.setId(1001L);
        venue.setName("国家体育馆");
        when(venueMapper.selectById(1001L)).thenReturn(venue);
        when(sessionSeatMapper.selectSeatLabelsByIds(List.of(501L, 502L))).thenReturn(List.of("A-1", "A-2"));

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));

        TicketSalesQuoteResponse response = service.quote(request);

        assertEquals(9001L, response.getTourId());
        assertEquals(9101L, response.getStationId());
        assertEquals("A-1, A-2", response.getSeatLabels());
        assertEquals("国家体育馆", response.getVenueName());
    }

    @Test
    void quoteDoesNotExposeActivityPurchaseLimit() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(3001L);
        ticketType.setSessionId(2001L);
        ticketType.setName("看台");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(1);
        when(ticketTypeMapper.selectById(3001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(2001L)).thenReturn(true);

        Session session = new Session();
        session.setId(2001L);
        session.setActivityId(1001L);
        session.setStartTime(LocalDateTime.now().plusDays(30));
        when(sessionMapper.selectById(2001L)).thenReturn(session);

        Activity activity = new Activity();
        activity.setId(1001L);
        activity.setName("南京站");
        when(activityMapper.selectById(1001L)).thenReturn(activity);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(2001L);
        request.setTicketTypeId(3001L);
        request.setQuantity(1);

        TicketSalesQuoteResponse response = service.quote(request);

        assertEquals(Boolean.TRUE, response.getRealNameRequired());
    }

    @Test
    void quoteReturnsRealNameRequiredFromActivity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(3001L);
        ticketType.setSessionId(2001L);
        ticketType.setName("A");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(1);
        when(ticketTypeMapper.selectById(3001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(2001L)).thenReturn(true);

        Session session = new Session();
        session.setId(2001L);
        session.setActivityId(1001L);
        session.setStartTime(LocalDateTime.now().plusDays(30));
        when(sessionMapper.selectById(2001L)).thenReturn(session);

        Activity activity = new Activity();
        activity.setId(1001L);
        activity.setRealNameRequired(true);
        when(activityMapper.selectById(1001L)).thenReturn(activity);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(2001L);
        request.setTicketTypeId(3001L);
        request.setQuantity(1);

        TicketSalesQuoteResponse response = service.quote(request);

        assertEquals(true, response.getRealNameRequired());
    }

    @Test
    void quoteReturnsTicketTransferAllowedFromActivity() {
        TicketTypeMapper ticketTypeMapper = mock(TicketTypeMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        VenueMapper venueMapper = mock(VenueMapper.class);
        SessionSeatMapper sessionSeatMapper = mock(SessionSeatMapper.class);
        TicketSalesInternalService service = new TicketSalesInternalService(
                ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper);

        TicketType ticketType = new TicketType();
        ticketType.setId(3001L);
        ticketType.setSessionId(2001L);
        ticketType.setName("A");
        ticketType.setPrice(new BigDecimal("380.00"));
        ticketType.setStatus(1);
        when(ticketTypeMapper.selectById(3001L)).thenReturn(ticketType);
        when(sessionSeatMapper.selectSessionSellable(2001L)).thenReturn(true);

        Session session = new Session();
        session.setId(2001L);
        session.setActivityId(1001L);
        session.setStartTime(LocalDateTime.now().plusDays(30));
        when(sessionMapper.selectById(2001L)).thenReturn(session);

        Activity activity = new Activity();
        activity.setId(1001L);
        activity.setTicketTransferAllowed(false);
        when(activityMapper.selectById(1001L)).thenReturn(activity);

        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(2001L);
        request.setTicketTypeId(3001L);
        request.setQuantity(1);

        TicketSalesQuoteResponse response = service.quote(request);

        assertEquals(false, response.getTicketTransferAllowed());
    }

    private TicketSalesInternalService service(TicketTypeMapper ticketTypeMapper, SessionSeatMapper sessionSeatMapper) {
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(true);
        return new TicketSalesInternalService(
                ticketTypeMapper, mock(SessionMapper.class), mock(ActivityMapper.class),
                mock(VenueMapper.class), sessionSeatMapper);
    }

    private TicketSalesInternalService service(TicketTypeMapper ticketTypeMapper,
                                               SessionSeatMapper sessionSeatMapper,
                                               SeatBlockMapper seatBlockMapper,
                                               TicketGroupMapper ticketGroupMapper) {
        when(sessionSeatMapper.selectSessionSellable(3001L)).thenReturn(true);
        return new TicketSalesInternalService(
                ticketTypeMapper, mock(SessionMapper.class), mock(ActivityMapper.class),
                mock(VenueMapper.class), sessionSeatMapper, seatBlockMapper, ticketGroupMapper);
    }

    private TicketType ticketType(Long id, String name, BigDecimal price) {
        TicketType ticketType = new TicketType();
        ticketType.setId(id);
        ticketType.setSessionId(3001L);
        ticketType.setName(name);
        ticketType.setPrice(price);
        ticketType.setStatus(1);
        return ticketType;
    }

    private TicketSalesLockRequest lockRequest(List<Long> seatIds, Integer quantity) {
        TicketSalesLockRequest request = new TicketSalesLockRequest();
        request.setOrderId(88L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(seatIds);
        request.setQuantity(quantity);
        request.setLockExpireTime(LocalDateTime.now().plusMinutes(15));
        return request;
    }

    private TeamSeatLockRequest teamLockRequest(Integer quantity, String strategy, List<String> fallbacks) {
        TeamSeatLockRequest request = new TeamSeatLockRequest();
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setQuantity(quantity);
        request.setStrategy(strategy);
        request.setFallbacks(fallbacks);
        request.setLockRequestId("team-lock-1");
        request.setLockExpireTime(LocalDateTime.now().plusMinutes(15));
        return request;
    }

    private SessionSeat seat(Long id, Long layoutSectionId, Long seatBlockId, Integer rowNo, Integer seatNo) {
        SessionSeat seat = new SessionSeat();
        seat.setId(id);
        seat.setLayoutSectionId(layoutSectionId);
        seat.setSeatBlockId(seatBlockId);
        seat.setRowNo(rowNo);
        seat.setSeatNo(seatNo);
        return seat;
    }

    private SessionSeat lockedSeat(Long id, Long layoutSectionId, Long seatBlockId, Integer rowNo, Integer seatNo, String label) {
        SessionSeat seat = seat(id, layoutSectionId, seatBlockId, rowNo, seatNo);
        seat.setSeatLabel(label);
        seat.setStatus(2);
        seat.setLockRequestId("team-lock-1");
        return seat;
    }

    private TicketTypeSeatStockSnapshot stockSnapshot(Long ticketTypeId, Integer totalStock, Integer remainStock) {
        TicketTypeSeatStockSnapshot snapshot = new TicketTypeSeatStockSnapshot();
        snapshot.setTicketTypeId(ticketTypeId);
        snapshot.setTotalStock(totalStock);
        snapshot.setRemainStock(remainStock);
        return snapshot;
    }

    private List<SessionSeat> sparseTeamSeats(int count) {
        List<SessionSeat> seats = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            seats.add(seat(700L + index, 50L + index, 800L + index, 1, 1));
        }
        return seats;
    }
}
