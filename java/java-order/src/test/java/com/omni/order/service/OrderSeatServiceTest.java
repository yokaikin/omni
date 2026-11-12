package com.omni.order.service;

import com.omni.common.result.Result;
import com.omni.exception.BusinessException;
import com.omni.order.client.PaymentInternalClient;
import com.omni.order.client.TicketSalesInternalClient;
import com.omni.order.client.UserInternalClient;
import com.omni.order.dto.InternalUserRefResponse;
import com.omni.order.dto.LockSeatsRequest;
import com.omni.order.dto.CreateOrderRequest;
import com.omni.order.dto.PaymentSyncDecisionResponse;
import com.omni.order.dto.ResolvedAttendeeResponse;
import com.omni.order.dto.TicketSalesLockRequest;
import com.omni.order.dto.TicketSalesOrderRequest;
import com.omni.order.dto.TicketSalesQuoteRequest;
import com.omni.order.dto.TicketSalesQuoteResponse;
import com.omni.order.dto.TicketSalesSeatLockResponse;
import com.omni.order.entity.Order;
import com.omni.order.entity.OrderSeat;
import com.omni.order.mapper.OrderMapper;
import com.omni.order.mapper.OrderSeatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSeatServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderSeatMapper orderSeatMapper;

    @Mock
    private PaymentInternalClient paymentInternalClient;

    @Mock
    private TicketSalesInternalClient ticketSalesInternalClient;

    @Mock
    private UserInternalClient userInternalClient;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderMapper, orderSeatMapper, paymentInternalClient, ticketSalesInternalClient, userInternalClient);
    }

    private PaymentSyncDecisionResponse safeToCancelDecision() {
        PaymentSyncDecisionResponse decision = new PaymentSyncDecisionResponse();
        decision.setPaid(false);
        decision.setSafeToCancel(true);
        return decision;
    }

    @Test
    void createOrderWithSeatsCreatesPendingOrderAndLocksSeats() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setSeatIds(Arrays.asList(11L, 12L));
        request.setUnitPrice(new BigDecimal("280.00"));
        request.setAttendeeIds(Arrays.asList(501L, 502L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));

        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setUnitPrice(new BigDecimal("280.00"));
        quote.setTicketName("看台A");
        quote.setSeatBased(true);
        quote.setQuantity(2);
        when(ticketSalesInternalClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(quote));
        givenResolvedAttendees(2);

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(Arrays.asList(11L, 12L));
        when(ticketSalesInternalClient.lockSeats(any(TicketSalesLockRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(lockResponse));

        Order order = service.createOrderWithSeats(request);

        assertEquals(2, order.getQuantity());
        assertEquals(new BigDecimal("560.00"), order.getAmount());
        assertEquals(OrderService.STATUS_PENDING, order.getStatus());
        verify(orderMapper).insert(any());
    }

    @Test
    void createOrderUsesBackendTicketPriceAndLocksTicketStock() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(2);
        request.setUnitPrice(new BigDecimal("1.00"));
        request.setAttendeeIds(Arrays.asList(501L, 502L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));

        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setUnitPrice(new BigDecimal("180.00"));
        quote.setQuantity(2);
        when(ticketSalesInternalClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(quote));
        givenResolvedAttendees(2);

        when(ticketSalesInternalClient.lockStock(any(TicketSalesLockRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        Order order = service.createOrder(request);

        assertEquals(2, order.getQuantity());
        assertEquals(new BigDecimal("360.00"), order.getAmount());
        verify(ticketSalesInternalClient).lockStock(any(TicketSalesLockRequest.class), eq("test-internal-token"));
        verify(orderMapper).insert(order);
    }

    @Test
    void createOrderRejectsWhenTicketStockInsufficient() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(2);
        request.setAttendeeIds(Arrays.asList(501L, 502L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));

        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setUnitPrice(new BigDecimal("180.00"));
        quote.setQuantity(2);
        when(ticketSalesInternalClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(quote));
        givenResolvedAttendees(2);

        when(ticketSalesInternalClient.lockStock(any(TicketSalesLockRequest.class), eq("test-internal-token")))
                .thenReturn(Result.fail(400, "票档库存不足"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createOrder(request));

        assertEquals("票档库存不足", error.getMessage());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void createOrderRejectsMissingUserBeforeTicketLock() {
        UserInternalClient userClient = mock(UserInternalClient.class);
        OrderService svc = new OrderService(orderMapper, orderSeatMapper, paymentInternalClient, ticketSalesInternalClient, userClient);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(99999L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(2);

        when(userClient.getUserRef(eq(99999L), anyString())).thenReturn(Result.fail(404, "用户不存在"));

        BusinessException error = assertThrows(BusinessException.class, () -> svc.createOrder(request));

        assertEquals("用户不存在", error.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), any());
        verify(ticketSalesInternalClient, never()).lockStock(any(), any());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void createOrderRejectsDisabledUserBeforeTicketLock() {
        UserInternalClient userClient = mock(UserInternalClient.class);
        OrderService svc = new OrderService(orderMapper, orderSeatMapper, paymentInternalClient, ticketSalesInternalClient, userClient);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(2);

        InternalUserRefResponse disabledUser = new InternalUserRefResponse();
        disabledUser.setId(2004L);
        disabledUser.setStatus(0);
        when(userClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(disabledUser));

        BusinessException error = assertThrows(BusinessException.class, () -> svc.createOrder(request));

        assertEquals("用户状态不可用", error.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), any());
        verify(ticketSalesInternalClient, never()).lockStock(any(), any());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void createOrderWithSeatsWithoutSeatIdsRandomlyLocksRealSeats() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(3);
        request.setAttendeeIds(Arrays.asList(501L, 502L, 503L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));

        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setUnitPrice(new BigDecimal("180.00"));
        quote.setQuantity(3);
        when(ticketSalesInternalClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(quote));
        givenResolvedAttendees(3);

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(3001L, 3002L, 3003L));
        lockResponse.setSeatLabels(List.of("A区1排1座", "A区1排2座", "A区1排3座"));
        when(ticketSalesInternalClient.lockSeats(any(TicketSalesLockRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(lockResponse));

        Order order = service.createOrderWithSeats(request);

        assertEquals(3, order.getQuantity());
        assertEquals(new BigDecimal("540.00"), order.getAmount());
        assertEquals(OrderService.STATUS_PENDING, order.getStatus());
        verify(ticketSalesInternalClient, never()).lockStock(any(), any());
        verify(ticketSalesInternalClient).lockSeats(argThat(lock -> Boolean.TRUE.equals(lock.getAllocateRandom()) && lock.getQuantity() == 3), eq("test-internal-token"));
        verify(orderSeatMapper, times(3)).insert(any());
    }

    @Test
    void createOrderWithSeatsRejectsStandingTicketWhenStockInsufficient() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(3);
        request.setAttendeeIds(Arrays.asList(501L, 502L, 503L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));

        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setUnitPrice(new BigDecimal("180.00"));
        quote.setQuantity(3);
        when(ticketSalesInternalClient.quote(any(TicketSalesQuoteRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success(quote));
        givenResolvedAttendees(3);

        when(ticketSalesInternalClient.lockSeats(any(TicketSalesLockRequest.class), eq("test-internal-token")))
                .thenReturn(Result.fail(400, "票档库存不足"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals("票档库存不足", error.getMessage());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void cancelOrderReleasesLockedSeats() {
        Order order = pendingOrder(1001L, 101L, 2001L);
        OrderSeat orderSeat = lockedOrderSeat(9001L, order.getId(), 3001L, 101L, 2001L);
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(paymentInternalClient.syncOrderForCancel(order.getId(), "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED)).thenReturn(1);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(orderSeat));
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.cancelOrder(order.getId());

        assertEquals(OrderService.STATUS_CANCELLED, order.getStatus());
        assertEquals(4, orderSeat.getStatus());
        verify(orderMapper).updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED);
        verify(ticketSalesInternalClient).release(any(TicketSalesOrderRequest.class), eq("test-internal-token"));
    }

    @Test
    void cancelOrderRestoresStandingTicketStockWhenNoSeatsWereLocked() {
        Order order = pendingOrder(1006L, 106L, 2006L);
        order.setQuantity(3);
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(paymentInternalClient.syncOrderForCancel(order.getId(), "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED)).thenReturn(1);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.cancelOrder(order.getId());

        assertEquals(OrderService.STATUS_CANCELLED, order.getStatus());
        verify(ticketSalesInternalClient).release(any(TicketSalesOrderRequest.class), eq("test-internal-token"));
    }

    @Test
    void cancelOrderRejectsWhenPaymentServiceSaysPaid() {
        PaymentInternalClient paymentClient = mock(PaymentInternalClient.class);
        TicketSalesInternalClient ticketClient = mock(TicketSalesInternalClient.class);
        OrderService service = new OrderService(orderMapper, orderSeatMapper, paymentClient, ticketClient);
        Order order = new Order();
        order.setId(88L);
        order.setOrderNo("DM88");
        order.setStatus(OrderService.STATUS_PENDING);
        when(orderMapper.selectById(88L)).thenReturn(order);
        PaymentSyncDecisionResponse decision = new PaymentSyncDecisionResponse();
        decision.setPaid(true);
        decision.setSafeToCancel(false);
        decision.setMessage("支付成功");
        when(paymentClient.syncOrderForCancel(88L, "test-internal-token")).thenReturn(Result.success(decision));

        BusinessException error = assertThrows(BusinessException.class, () -> service.cancelOrder(88L));

        assertEquals("订单已支付，不能取消", error.getMessage());
        verify(orderMapper, never()).updateById(any());
    }

    @Test
    void markPaidDoesNotSellSeatsWhenStatusChangedConcurrently() {
        Order order = pendingOrder(1005L, 105L, 2005L);
        Order cancelled = pendingOrder(1005L, 105L, 2005L);
        cancelled.setStatus(OrderService.STATUS_CANCELLED);
        when(orderMapper.selectById(1005L)).thenReturn(order, cancelled);
        when(orderMapper.updateStatusIfCurrent(1005L, OrderService.STATUS_PENDING, OrderService.STATUS_PAID)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.markPaid(1005L));

        assertEquals("订单状态已变化，不能标记为已支付", error.getMessage());
        verify(ticketSalesInternalClient, never()).confirmSold(any(), any());
    }

    @Test
    void markPaidReconfirmsLockedSeatsWhenOrderAlreadyPaid() {
        Order order = paidOrder(1006L, 106L, 2006L);
        OrderSeat lockedSeat = lockedOrderSeat(9006L, order.getId(), 3006L, 106L, 2006L);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        service.setTicketWalletService(ticketWalletService);
        when(orderMapper.selectById(1006L)).thenReturn(order);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(lockedSeat));
        when(ticketSalesInternalClient.confirmSold(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        Order result = service.markPaid(1006L);

        assertEquals(OrderService.STATUS_PAID, result.getStatus());
        assertEquals(2, lockedSeat.getStatus());
        ArgumentCaptor<TicketSalesOrderRequest> captor = ArgumentCaptor.forClass(TicketSalesOrderRequest.class);
        verify(ticketSalesInternalClient).confirmSold(captor.capture(), eq("test-internal-token"));
        assertEquals(List.of(3006L), captor.getValue().getSeatIds());
        verify(ticketWalletService).issueForPaidOrder(order);
    }

    @Test
    void releaseExpiredSeatLocksMakesExpiredLockedSeatsAvailable() {
        Order order = pendingOrder(1002L, 102L, 2002L);
        OrderSeat expiredSeat = lockedOrderSeat(9002L, order.getId(), 3002L, 102L, 2002L);
        expiredSeat.setLockExpireTime(LocalDateTime.now().minusMinutes(1));
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(expiredSeat));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order));
        when(paymentInternalClient.syncOrderForCancel(order.getId(), "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED)).thenReturn(1);
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        int released = service.releaseExpiredSeatLocks();

        assertEquals(1, released);
        assertEquals(OrderService.STATUS_CANCELLED, order.getStatus());
        assertEquals(4, expiredSeat.getStatus());
        verify(orderMapper).updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED);
        verify(ticketSalesInternalClient).release(any(TicketSalesOrderRequest.class), eq("test-internal-token"));
    }

    @Test
    void releaseExpiredSeatLocksCancelsExpiredPendingStandingOrderAndRestoresStock() {
        Order order = pendingOrder(1007L, 107L, 2007L);
        order.setQuantity(3);
        order.setCreateTime(LocalDateTime.now().minusMinutes(16));
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(paymentInternalClient.syncOrderForCancel(order.getId(), "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(order.getId(), OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED)).thenReturn(1);
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        int released = service.releaseExpiredSeatLocks();

        assertEquals(1, released);
        assertEquals(OrderService.STATUS_CANCELLED, order.getStatus());
        ArgumentCaptor<TicketSalesOrderRequest> captor = ArgumentCaptor.forClass(TicketSalesOrderRequest.class);
        verify(ticketSalesInternalClient).release(captor.capture(), eq("test-internal-token"));
        assertEquals(107L, captor.getValue().getSessionId());
        assertEquals(2007L, captor.getValue().getTicketTypeId());
        assertEquals(3, captor.getValue().getQuantity());
        assertEquals(null, captor.getValue().getSeatIds());
    }

    @Test
    void releaseExpiredSeatLocksKeepsPendingStandingOrderWhenPaymentSyncSaysPaid() {
        Order order = pendingOrder(1008L, 108L, 2008L);
        order.setCreateTime(LocalDateTime.now().minusMinutes(16));
        PaymentSyncDecisionResponse decision = new PaymentSyncDecisionResponse();
        decision.setPaid(true);
        decision.setSafeToCancel(false);
        decision.setMessage("支付成功");
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(paymentInternalClient.syncOrderForCancel(order.getId(), "test-internal-token"))
                .thenReturn(Result.success(decision));

        int released = service.releaseExpiredSeatLocks();

        assertEquals(0, released);
        verify(orderMapper, never()).updateStatusIfCurrent(any(), any(), any());
        verify(ticketSalesInternalClient, never()).release(any(), any());
    }

    @Test
    void releaseExpiredSeatLocksKeepsPaidStandingOrder() {
        Order order = paidOrder(1008L, 108L, 2008L);
        order.setCreateTime(LocalDateTime.now().minusMinutes(16));
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());

        int released = service.releaseExpiredSeatLocks();

        assertEquals(0, released);
        verify(paymentInternalClient, never()).syncOrderForCancel(any(), any());
        verify(ticketSalesInternalClient, never()).release(any(), any());
    }

    @Test
    void markRefundedRestoresSoldSeats() {
        Order order = paidOrder(1003L, 103L, 2003L);
        OrderSeat soldSeat = soldOrderSeat(9003L, order.getId(), 3003L, 103L, 2003L);
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(soldSeat));
        when(ticketSalesInternalClient.refund(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.markRefunded(order.getId());

        ArgumentCaptor<OrderSeat> captor = ArgumentCaptor.forClass(OrderSeat.class);
        verify(orderSeatMapper, times(1)).updateById(captor.capture());
        assertEquals(3, captor.getValue().getStatus());
        verify(ticketSalesInternalClient).refund(any(TicketSalesOrderRequest.class), eq("test-internal-token"));
    }

    @Test
    void markRefundedDoesNothingWhenNoOrderSeats() {
        Order order = paidOrder(1004L, 104L, 2004L);
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());
        when(ticketSalesInternalClient.refund(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.markRefunded(order.getId());

        verify(ticketSalesInternalClient).refund(any(TicketSalesOrderRequest.class), eq("test-internal-token"));
    }

    @Test
    void createOrderMapsUserServiceExceptionBeforeTicketLock() {
        UserInternalClient userClient = mock(UserInternalClient.class);
        OrderService svc = new OrderService(orderMapper, orderSeatMapper, paymentInternalClient, ticketSalesInternalClient, userClient);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1001L);
        request.setQuantity(2);

        when(userClient.getUserRef(eq(2004L), anyString())).thenThrow(new RuntimeException("timeout"));

        BusinessException error = assertThrows(BusinessException.class, () -> svc.createOrder(request));

        assertEquals("用户服务无响应", error.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), any());
        verify(ticketSalesInternalClient, never()).lockStock(any(), any());
        verify(orderMapper, never()).insert(any());
    }

    private InternalUserRefResponse activeUser() {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(2004L);
        user.setStatus(1);
        return user;
    }

    private ResolvedAttendeeResponse resolvedAttendee(Long id, String hash) {
        ResolvedAttendeeResponse attendee = new ResolvedAttendeeResponse();
        attendee.setId(id);
        attendee.setRealName("观演人" + id);
        attendee.setIdType("ID_CARD");
        attendee.setIdNoHash(hash);
        attendee.setIdNoMask("110***********" + (id % 100));
        attendee.setIdNoEncrypted("enc-" + hash);
        return attendee;
    }

    private void givenResolvedAttendees(int quantity) {
        List<ResolvedAttendeeResponse> attendees = new java.util.ArrayList<>();
        for (int index = 0; index < quantity; index++) {
            attendees.add(resolvedAttendee(501L + index, "hash-" + index));
        }
        when(userInternalClient.resolveAttendees(any(), anyString())).thenReturn(Result.success(attendees));
    }

    private Order pendingOrder(Long id, Long sessionId, Long ticketTypeId) {
        Order order = baseOrder(id, sessionId, ticketTypeId);
        order.setStatus(OrderService.STATUS_PENDING);
        return order;
    }

    private Order paidOrder(Long id, Long sessionId, Long ticketTypeId) {
        Order order = baseOrder(id, sessionId, ticketTypeId);
        order.setStatus(OrderService.STATUS_PAID);
        return order;
    }

    private Order baseOrder(Long id, Long sessionId, Long ticketTypeId) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo("DM" + id);
        order.setUserId(2004L);
        order.setSessionId(sessionId);
        order.setTicketTypeId(ticketTypeId);
        order.setQuantity(1);
        order.setAmount(new BigDecimal("280.00"));
        return order;
    }

    private OrderSeat lockedOrderSeat(Long id, Long orderId, Long sessionSeatId, Long sessionId, Long ticketTypeId) {
        OrderSeat seat = orderSeat(id, orderId, sessionSeatId, sessionId, ticketTypeId);
        seat.setStatus(1);
        seat.setLockExpireTime(LocalDateTime.now().plusMinutes(15));
        return seat;
    }

    private OrderSeat soldOrderSeat(Long id, Long orderId, Long sessionSeatId, Long sessionId, Long ticketTypeId) {
        OrderSeat seat = orderSeat(id, orderId, sessionSeatId, sessionId, ticketTypeId);
        seat.setStatus(2);
        return seat;
    }

    private OrderSeat orderSeat(Long id, Long orderId, Long sessionSeatId, Long sessionId, Long ticketTypeId) {
        OrderSeat seat = new OrderSeat();
        seat.setId(id);
        seat.setOrderId(orderId);
        seat.setSessionSeatId(sessionSeatId);
        seat.setSessionId(sessionId);
        seat.setTicketTypeId(ticketTypeId);
        return seat;
    }
}
