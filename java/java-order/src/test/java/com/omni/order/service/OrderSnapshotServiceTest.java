package com.omni.order.service;

import com.omni.common.result.Result;
import com.omni.order.client.TicketSalesInternalClient;
import com.omni.order.client.UserInternalClient;
import com.omni.order.dto.CreateOrderRequest;
import com.omni.order.dto.InternalUserRefResponse;
import com.omni.order.dto.LockSeatsRequest;
import com.omni.order.dto.ResolvedAttendeeResponse;
import com.omni.order.dto.TicketSalesLockRequest;
import com.omni.order.dto.TicketSalesQuoteRequest;
import com.omni.order.dto.TicketSalesQuoteResponse;
import com.omni.order.dto.TicketSalesSeatLockResponse;
import com.omni.order.entity.Order;
import com.omni.order.entity.OrderSnapshot;
import com.omni.order.mapper.OrderMapper;
import com.omni.order.mapper.OrderSeatMapper;
import com.omni.order.mapper.OrderSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSnapshotServiceTest {

    @Test
    void createOrderWritesSnapshotFromTicketQuote() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderSeatMapper orderSeatMapper = mock(OrderSeatMapper.class);
        OrderSnapshotMapper snapshotMapper = mock(OrderSnapshotMapper.class);
        TicketSalesInternalClient ticketClient = mock(TicketSalesInternalClient.class);
        UserInternalClient userClient = mock(UserInternalClient.class);
        OrderService service = new OrderService(orderMapper, orderSeatMapper, snapshotMapper, null, ticketClient, userClient);
        when(ticketClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token"))).thenReturn(Result.success(quote(false)));
        when(ticketClient.lockStock(any(TicketSalesLockRequest.class), eq("test-internal-token"))).thenReturn(Result.success());
        givenValidUser(userClient, 2004L);
        givenResolvedAttendees(userClient, 2);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setQuantity(2);
        request.setAttendeeIds(List.of(501L, 502L));

        Order order = service.createOrder(request);

        ArgumentCaptor<OrderSnapshot> captor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        OrderSnapshot snapshot = captor.getValue();
        assertEquals(order.getId(), snapshot.getOrderId());
        assertEquals(2001L, snapshot.getActivityId());
        assertEquals("巡演北京站", snapshot.getActivityName());
        assertEquals("国家体育馆", snapshot.getVenueName());
        assertEquals("看台A", snapshot.getTicketName());
        assertEquals(new BigDecimal("380.00"), snapshot.getUnitPrice());
        assertEquals(2, snapshot.getQuantity());
        assertEquals(false, snapshot.getTicketTransferAllowed());
    }

    @Test
    void createOrderWithSeatsWritesSeatLabelsSnapshot() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderSeatMapper orderSeatMapper = mock(OrderSeatMapper.class);
        OrderSnapshotMapper snapshotMapper = mock(OrderSnapshotMapper.class);
        TicketSalesInternalClient ticketClient = mock(TicketSalesInternalClient.class);
        UserInternalClient userClient = mock(UserInternalClient.class);
        OrderService service = new OrderService(orderMapper, orderSeatMapper, snapshotMapper, null, ticketClient, userClient);
        when(ticketClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token"))).thenReturn(Result.success(quote(true)));
        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(501L, 502L));
        when(ticketClient.lockSeats(any(TicketSalesLockRequest.class), eq("test-internal-token"))).thenReturn(Result.success(lockResponse));
        givenValidUser(userClient, 2004L);
        givenResolvedAttendees(userClient, 2);

        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(3001L);
        request.setTicketTypeId(4001L);
        request.setSeatIds(List.of(501L, 502L));
        request.setAttendeeIds(List.of(601L, 602L));

        service.createOrderWithSeats(request);

        ArgumentCaptor<OrderSnapshot> captor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertEquals("A-1, A-2", captor.getValue().getSeatLabels());
        assertEquals(2, captor.getValue().getQuantity());
    }

    private void givenValidUser(UserInternalClient userClient, Long userId) {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(userId);
        user.setStatus(1);
        when(userClient.getUserRef(eq(userId), eq("test-internal-token"))).thenReturn(Result.success(user));
    }

    private void givenResolvedAttendees(UserInternalClient userClient, int quantity) {
        List<ResolvedAttendeeResponse> attendees = new java.util.ArrayList<>();
        for (int index = 0; index < quantity; index++) {
            ResolvedAttendeeResponse attendee = new ResolvedAttendeeResponse();
            attendee.setId(501L + index);
            attendee.setRealName("观演人" + index);
            attendee.setIdType("ID_CARD");
            attendee.setIdNoHash("hash-" + index);
            attendee.setIdNoMask("110***********0" + index);
            attendee.setIdNoEncrypted("enc-" + index);
            attendees.add(attendee);
        }
        when(userClient.resolveAttendees(any(), eq("test-internal-token"))).thenReturn(Result.success(attendees));
    }

    private TicketSalesQuoteResponse quote(boolean withSeats) {
        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setActivityId(2001L);
        quote.setActivityName("巡演北京站");
        quote.setActivityPoster("poster.jpg");
        quote.setTourId(9001L);
        quote.setStationId(9101L);
        quote.setSessionId(3001L);
        quote.setSessionTime(LocalDateTime.of(2026, 6, 22, 19, 30));
        quote.setVenueName("国家体育馆");
        quote.setTicketTypeId(4001L);
        quote.setTicketName("看台A");
        quote.setUnitPrice(new BigDecimal("380.00"));
        quote.setQuantity(withSeats ? 2 : 2);
        quote.setSeatBased(withSeats);
        quote.setSeatLabels(withSeats ? "A-1, A-2" : null);
        quote.setTicketTransferAllowed(false);
        return quote;
    }
}
