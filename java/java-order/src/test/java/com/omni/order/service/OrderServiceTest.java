package com.omni.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.order.client.PaymentInternalClient;
import com.omni.order.client.TicketSalesInternalClient;
import com.omni.order.client.UserInternalClient;
import com.omni.order.config.OrderSentinelConfig;
import com.omni.order.dto.CreateOrderRequest;
import com.omni.order.dto.CreateTeamOrderRequest;
import com.omni.order.dto.InternalUserRefResponse;
import com.omni.order.dto.LockSeatsRequest;
import com.omni.order.dto.OrderListItemResponse;
import com.omni.order.dto.PaymentSyncDecisionResponse;
import com.omni.order.dto.ResolvedAttendeeResponse;
import com.omni.order.dto.TicketSalesLockRequest;
import com.omni.order.dto.TicketSalesOrderRequest;
import com.omni.order.dto.TicketSalesQuoteResponse;
import com.omni.order.dto.TicketSalesSeatLockResponse;
import com.omni.order.entity.Order;
import com.omni.order.entity.OrderAttendee;
import com.omni.order.entity.OrderSeat;
import com.omni.order.entity.OrderSnapshot;
import com.omni.order.mapper.OrderMapper;
import com.omni.order.mapper.OrderAttendeeMapper;
import com.omni.order.mapper.OrderSeatMapper;
import com.omni.order.mapper.OrderSnapshotMapper;
import com.omni.order.mq.WaitlistMqProducer;
import com.omni.common.mq.message.WaitlistReleasedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderSeatMapper orderSeatMapper;

    @Mock
    private OrderSnapshotMapper orderSnapshotMapper;

    @Mock
    private PaymentInternalClient paymentInternalClient;

    @Mock
    private TicketSalesInternalClient ticketSalesInternalClient;

    @Mock
    private UserInternalClient userInternalClient;

    @Mock
    private OrderAttendeeMapper orderAttendeeMapper;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient, ticketSalesInternalClient, userInternalClient, null, orderAttendeeMapper);
    }

    @Test
    void listOrderItemsBySessionsAttachesRealNameAttendees() {
        OrderListItemResponse order = new OrderListItemResponse();
        order.setId(9001L);
        order.setSessionId(101L);

        OrderAttendee attendee = new OrderAttendee();
        attendee.setId(7001L);
        attendee.setOrderId(9001L);
        attendee.setAttendeeUserProfileId(501L);
        attendee.setRealName("Alice");
        attendee.setIdType("ID_CARD");
        attendee.setIdNoMask("110***********011");
        attendee.setStatus(1);

        when(orderMapper.selectOrderListItemsBySessions(List.of(101L), false)).thenReturn(List.of(order));
        when(orderAttendeeMapper.selectByOrderIds(List.of(9001L))).thenReturn(List.of(attendee));

        List<OrderListItemResponse> result = service.listOrderItemsBySessions(List.of(101L), false);

        assertEquals(1, result.get(0).getAttendees().size());
        assertEquals("Alice", result.get(0).getAttendees().get(0).getRealName());
    }

    @AfterEach
    void tearDown() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void createOrderOpensUserValidateCircuitAfterRuntimeFailures() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(1);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenThrow(new RuntimeException("timeout"));
        loadExceptionRatioRule(OrderSentinelConfig.USER_VALIDATE_RESOURCE);

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        BusinessException blocked = assertThrows(BusinessException.class, () -> service.createOrder(request));
        assertEquals("用户服务暂不可用，请稍后重试", blocked.getMessage());
    }

    @Test
    void createOrderOpensTicketSalesCircuitAfterRuntimeFailures() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(1);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenThrow(new RuntimeException("timeout"));
        loadExceptionRatioRule(OrderSentinelConfig.TICKET_SALES_RESOURCE);

        assertThrows(RuntimeException.class, () -> service.createOrder(request));

        BusinessException blocked = assertThrows(BusinessException.class, () -> service.createOrder(request));
        assertEquals("票务服务暂不可用，请稍后重试", blocked.getMessage());
    }

    @Test
    void createOrderRejectsWhenQuotePriceExceedsAuthorizedMax() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("980.00"));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setTicketTypeId(1L);
        quote.setUnitPrice(new BigDecimal("1280.00"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createOrder(request));

        assertTrue(exception.getMessage().contains("授权价格"));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderRejectsGrabOrderMissingAuthorizedMaxBeforeLockOrInsert() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-MISSING-AUTH");

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setUnitPrice(new BigDecimal("1280.00"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsGrabOrderMissingAuthorizedMaxBeforeLockOrInsert() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setGrabRequestId("GRAB-SEAT-MISSING-AUTH");

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setUnitPrice(new BigDecimal("1280.00"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderRejectsRealNameOrderWithoutAttendees() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(1);

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setRealNameRequired(true);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrder(request));

        assertEquals("请选择实名观演人", ex.getMessage());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderAttendeeMapper, never()).insert(any(OrderAttendee.class));
    }

    @Test
    void createOrderWritesRealNameAttendeeSnapshot() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(1);
        request.setAttendeeIds(List.of(501L));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setRealNameRequired(true);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(userInternalClient.resolveAttendees(any(), anyString())).thenReturn(Result.success(List.of(
                resolvedAttendee(501L, "Alice", "hash-a", "110***********011", "enc-a")
        )));
        when(ticketSalesInternalClient.lockStock(any(), anyString())).thenReturn(Result.success());
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(9001L);
            return 1;
        });

        service.createOrder(request);

        ArgumentCaptor<OrderAttendee> captor = ArgumentCaptor.forClass(OrderAttendee.class);
        verify(orderAttendeeMapper).insert(captor.capture());
        assertEquals(9001L, captor.getValue().getOrderId());
        assertEquals(10L, captor.getValue().getSessionId());
        assertEquals(501L, captor.getValue().getAttendeeUserProfileId());
        assertEquals("hash-a", captor.getValue().getIdNoHash());
        assertEquals("enc-a", captor.getValue().getIdNoEncrypted());
    }

    @Test
    void createOrderWithSeatsAllowsWaitlistGrabRequestIdWithoutAuthorizedPrice() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(202L);
        request.setQuantity(1);
        request.setSeatIds(List.of());
        request.setGrabRequestId("WAITLIST-10-abcdef1234567890");
        request.setAuthorizedMaxUnitPrice(null);
        request.setAttendeeIds(List.of(501L));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setTicketTypeId(202L);
        quote.setUnitPrice(new BigDecimal("100.00"));
        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(3001L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        givenResolvedAttendees(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(9001L);
            return 1;
        });
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));

        assertDoesNotThrow(() -> service.createOrderWithSeats(request));
    }

    @Test
    void duplicateGrabCreateOrderReturnsExistingOrderWithoutTicketOrInsertSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NORMAL-1");

        Order existing = new Order();
        existing.setId(81L);
        existing.setOrderNo("DM-existing-normal");
        OrderListItemResponse existingItem = teamOrderItem(81L, null, "GRAB-NORMAL-1", false);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-1")).thenReturn(existingItem);
        when(orderMapper.selectById(81L)).thenReturn(existing);

        Order result = service.createOrder(request);

        assertEquals(existing, result);
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-NORMAL-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-NORMAL-1");
        verify(orderMapper).selectById(81L);
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderRejectsDifferentRequestedTicketTypeIdBeforeLoadOrTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NORMAL-REQUESTED-MISMATCH");
        request.setRequestedTicketTypeId(2L);

        OrderListItemResponse existingItem = teamOrderItem(186L, null, "GRAB-NORMAL-REQUESTED-MISMATCH", false);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        existingItem.setRequestedTicketTypeId(3L);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-REQUESTED-MISMATCH")).thenReturn(existingItem);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrder(request));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertEquals("抢票请求与当前订单意图不一致", ex.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderRejectsMissingAuthorizedMaxBeforeTicketOrInsertSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-NORMAL-MISSING-AUTH-RETRY");

        OrderListItemResponse existingItem = teamOrderItem(181L, null, "GRAB-NORMAL-MISSING-AUTH-RETRY", false);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-MISSING-AUTH-RETRY")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderRejectsAuthorizedMaxBelowExistingUnitPriceBeforeTicketOrInsertSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("99.99"));
        request.setGrabRequestId("GRAB-NORMAL-LOW-AUTH-RETRY");

        OrderListItemResponse existingItem = teamOrderItem(182L, null, "GRAB-NORMAL-LOW-AUTH-RETRY", false);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-LOW-AUTH-RETRY")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderDerivesExistingUnitPriceFromOrderAmountWhenSnapshotPriceMissing() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NORMAL-DERIVE-PRICE");

        Order existing = new Order();
        existing.setId(193L);
        existing.setQuantity(1);
        existing.setAmount(new BigDecimal("100.00"));
        OrderListItemResponse existingItem = teamOrderItem(193L, null, "GRAB-NORMAL-DERIVE-PRICE", false);
        existingItem.setUnitPrice(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-DERIVE-PRICE")).thenReturn(existingItem);
        when(orderMapper.selectById(193L)).thenReturn(existing);

        Order result = service.createOrder(request);

        assertEquals(existing, result);
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderRejectsGrabRequestThatCollidesWithTeamGrabRequestBeforeTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("TEAM-GRAB-COLLISION");
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-COLLISION")).thenReturn(null);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-COLLISION"))
                .thenReturn(teamOrderItem(184L, "TEAM-GRAB-COLLISION", "GRAB-LEADER-184", true));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-COLLISION");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-COLLISION");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-COLLISION");
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderRejectsGrabRetryForDifferentUserBeforeLoadOrTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(3005L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-NORMAL-USER-MISMATCH");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-USER-MISMATCH"))
                .thenReturn(teamOrderItem(84L, null, "GRAB-NORMAL-USER-MISMATCH", false));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderRejectsGrabRetryForDifferentSessionBeforeLoadOrTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(202L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-NORMAL-SESSION-MISMATCH");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-SESSION-MISMATCH"))
                .thenReturn(teamOrderItem(85L, null, "GRAB-NORMAL-SESSION-MISMATCH", false));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderRejectsGrabRetryForDifferentQuantityBeforeLoadOrTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(2);
        request.setGrabRequestId("GRAB-NORMAL-QUANTITY-MISMATCH");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NORMAL-QUANTITY-MISMATCH"))
                .thenReturn(teamOrderItem(86L, null, "GRAB-NORMAL-QUANTITY-MISMATCH", false));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void duplicateGrabCreateOrderWithSeatsReturnsExistingOrderWithoutTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-SEAT-NORMAL-1");

        Order existing = new Order();
        existing.setId(82L);
        existing.setOrderNo("DM-existing-seat-normal");
        OrderListItemResponse existingItem = teamOrderItem(82L, null, "GRAB-SEAT-NORMAL-1", null);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-NORMAL-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(82L))
                .thenReturn(List.of(orderSeat(82L, 301L, null)));
        when(orderMapper.selectById(82L)).thenReturn(existing);

        Order result = service.createOrderWithSeats(request);

        assertEquals(existing, result);
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-SEAT-NORMAL-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-SEAT-NORMAL-1");
        verify(orderMapper).selectById(82L);
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderWithSeatsRejectsDifferentMatchedTicketTypeIdBeforeTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-SEAT-MATCHED-MISMATCH");
        request.setMatchedTicketTypeId(2L);
        request.setAutoDowngraded(false);

        OrderListItemResponse existingItem = teamOrderItem(187L, null, "GRAB-SEAT-MATCHED-MISMATCH", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        existingItem.setMatchedTicketTypeId(3L);
        existingItem.setAutoDowngraded(false);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-MATCHED-MISMATCH")).thenReturn(existingItem);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertEquals("抢票请求与当前订单意图不一致", ex.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderWithSeatsRejectsDifferentAutoDowngradedBeforeTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-SEAT-AUTO-MISMATCH");
        request.setMatchedTicketTypeId(2L);
        request.setAutoDowngraded(true);

        OrderListItemResponse existingItem = teamOrderItem(188L, null, "GRAB-SEAT-AUTO-MISMATCH", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        existingItem.setMatchedTicketTypeId(2L);
        existingItem.setAutoDowngraded(false);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-AUTO-MISMATCH")).thenReturn(existingItem);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertEquals("抢票请求与当前订单意图不一致", ex.getMessage());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderWithSeatsRejectsMissingAuthorizedMaxBeforeTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setGrabRequestId("GRAB-SEAT-MISSING-AUTH-RETRY");

        OrderListItemResponse existingItem = teamOrderItem(183L, null, "GRAB-SEAT-MISSING-AUTH-RETRY", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-MISSING-AUTH-RETRY")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void duplicateGrabCreateOrderWithSeatsRejectsAuthorizedMaxBelowExistingUnitPriceBeforeTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("99.99"));
        request.setGrabRequestId("GRAB-SEAT-LOW-AUTH-RETRY");

        OrderListItemResponse existingItem = teamOrderItem(189L, null, "GRAB-SEAT-LOW-AUTH-RETRY", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-LOW-AUTH-RETRY")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderReturnsExistingForNullModeNoSeatsWhenRequestedNone() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NULL-MODE-NONE");

        Order existing = new Order();
        existing.setId(190L);
        OrderListItemResponse existingItem = teamOrderItem(190L, null, "GRAB-NULL-MODE-NONE", false);
        existingItem.setSeatSelectionMode(null);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NULL-MODE-NONE")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(190L)).thenReturn(List.of());
        when(orderMapper.selectById(190L)).thenReturn(existing);

        Order result = service.createOrder(request);

        assertEquals(existing, result);
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderWithSeatsReturnsExistingForNullModeMatchingExplicitSeats() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NULL-MODE-EXPLICIT");

        Order existing = new Order();
        existing.setId(191L);
        OrderListItemResponse existingItem = teamOrderItem(191L, null, "GRAB-NULL-MODE-EXPLICIT", false);
        existingItem.setSeatSelectionMode(null);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NULL-MODE-EXPLICIT")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(191L))
                .thenReturn(List.of(orderSeat(191L, 301L, null)));
        when(orderMapper.selectById(191L)).thenReturn(existing);

        Order result = service.createOrderWithSeats(request);

        assertEquals(existing, result);
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderWithSeatsRejectsNullModeRandomRetryWithExistingSeats() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NULL-MODE-RANDOM");

        OrderListItemResponse existingItem = teamOrderItem(192L, null, "GRAB-NULL-MODE-RANDOM", false);
        existingItem.setSeatSelectionMode(null);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NULL-MODE-RANDOM")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(192L))
                .thenReturn(List.of(orderSeat(192L, 301L, null)));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderWithSeatsRejectsGrabRequestThatCollidesWithTeamGrabRequestBeforeTicketOrSeatSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setGrabRequestId("TEAM-GRAB-SEAT-COLLISION");
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-SEAT-COLLISION")).thenReturn(null);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-SEAT-COLLISION"))
                .thenReturn(teamOrderItem(185L, "TEAM-GRAB-SEAT-COLLISION", "GRAB-LEADER-185", true));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-SEAT-COLLISION");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-SEAT-COLLISION");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-SEAT-COLLISION");
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsExplicitExistingGrabRetryWhenSeatIdsOmittedBeforeTicketOrSeatSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-EXPLICIT-OMITTED-SEATS");
        OrderListItemResponse existingItem = teamOrderItem(186L, null, "GRAB-EXPLICIT-OMITTED-SEATS", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-EXPLICIT-OMITTED-SEATS")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsReturnsRandomExistingGrabRetryWhenSeatIdsOmittedWithoutTicketOrSeatSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-RANDOM-OMITTED-SEATS");
        Order existing = new Order();
        existing.setId(187L);
        OrderListItemResponse existingItem = teamOrderItem(187L, null, "GRAB-RANDOM-OMITTED-SEATS", false);
        existingItem.setSeatSelectionMode("RANDOM");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-RANDOM-OMITTED-SEATS")).thenReturn(existingItem);
        when(orderMapper.selectById(187L)).thenReturn(existing);

        Order result = service.createOrderWithSeats(request);

        assertEquals(existing, result);
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsUnknownSeatSelectionModeForOmittedSeatRetryBeforeTicketOrSeatSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-UNKNOWN-OMITTED-SEATS");
        OrderListItemResponse existingItem = teamOrderItem(188L, null, "GRAB-UNKNOWN-OMITTED-SEATS", false);
        existingItem.setSeatSelectionMode(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-UNKNOWN-OMITTED-SEATS")).thenReturn(existingItem);

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsExistingGrabRetryForDifferentSeatIdsBeforeTicketOrInsertSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(302L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-SEAT-ID-MISMATCH");
        OrderListItemResponse existingItem = teamOrderItem(89L, null, "GRAB-SEAT-ID-MISMATCH", false);
        existingItem.setSeatSelectionMode("EXPLICIT");
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-ID-MISMATCH")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(89L))
                .thenReturn(List.of(orderSeat(89L, 301L, null)));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsGrabRetryForDifferentUserBeforeLoadOrTicketSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(3005L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setGrabRequestId("GRAB-SEAT-USER-MISMATCH");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-USER-MISMATCH"))
                .thenReturn(teamOrderItem(87L, null, "GRAB-SEAT-USER-MISMATCH", false));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderWithSeatsRejectsGrabRetryForDifferentSeatQuantityBeforeLoadOrTicketSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L, 302L));
        request.setGrabRequestId("GRAB-SEAT-QUANTITY-MISMATCH");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-SEAT-QUANTITY-MISMATCH"))
                .thenReturn(teamOrderItem(88L, null, "GRAB-SEAT-QUANTITY-MISMATCH", false));

        assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrderRejectsGrabRequestThatBelongsToTeamOrderBeforeTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setGrabRequestId("GRAB-TEAM-OWNER");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-TEAM-OWNER"))
                .thenReturn(teamOrderItem(83L, "TEAM-GRAB-1", "GRAB-TEAM-OWNER", true));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-TEAM-OWNER");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-TEAM-OWNER");
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithGrabRequestLocksAndLooksUpBeforeTicketSideEffects() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NEW-NORMAL");
        request.setAttendeeIds(List.of(501L));

        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NEW-NORMAL")).thenReturn(null);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(1)));
        when(ticketSalesInternalClient.lockStock(any(), anyString())).thenReturn(Result.success());
        givenResolvedAttendees(1);

        service.createOrder(request);

        InOrder inOrder = inOrder(orderMapper, ticketSalesInternalClient);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-NEW-NORMAL");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-NEW-NORMAL");
        inOrder.verify(ticketSalesInternalClient).quote(any(), anyString());
        inOrder.verify(ticketSalesInternalClient).lockStock(any(), anyString());
    }

    @Test
    void createOrderWithSeatsGrabRequestLocksAndLooksUpBeforeTicketSideEffects() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setSeatIds(List.of(301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setGrabRequestId("GRAB-NEW-SEAT-NORMAL");
        request.setAttendeeIds(List.of(501L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L));
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-NEW-SEAT-NORMAL")).thenReturn(null);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(1)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(1);

        service.createOrderWithSeats(request);

        InOrder inOrder = inOrder(orderMapper, ticketSalesInternalClient);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-NEW-SEAT-NORMAL");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-NEW-SEAT-NORMAL");
        inOrder.verify(ticketSalesInternalClient).quote(any(), anyString());
        inOrder.verify(ticketSalesInternalClient).lockSeats(any(), anyString());
    }

    @Test
    void createTeamOrderUsesLeaderLimitAndDoesNotRelockSeats() {
        CreateTeamOrderRequest request = teamOrderRequest();

        TicketSalesQuoteResponse quote = quoteWithActivity(2);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(ticketSalesInternalClient.validateTeamSeatLock(any(), anyString())).thenReturn(Result.success(validTeamLock()));
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(81L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        Order order = service.createTeamOrderWithLockedSeats(request);

        assertEquals(2004L, order.getUserId());
        assertEquals(2, order.getQuantity());
        assertEquals(new BigDecimal("200.00"), order.getAmount());
        ArgumentCaptor<TicketSalesLockRequest> validationCaptor = ArgumentCaptor.forClass(TicketSalesLockRequest.class);
        verify(ticketSalesInternalClient).validateTeamSeatLock(validationCaptor.capture(), anyString());
        assertEquals("TEAM-GRAB-1", validationCaptor.getValue().getLockRequestId());
        assertEquals(List.of(301L, 302L), validationCaptor.getValue().getSeatIds());
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
        verify(ticketSalesInternalClient, never()).lockStock(any(), anyString());
    }

    @Test
    void createTeamOrderReturnsExistingOrderForSameTeamGrabRequestWithoutSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        Order existing = new Order();
        existing.setId(90L);
        existing.setOrderNo("DM-existing-team");
        OrderListItemResponse existingItem = teamOrderItem(90L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(90L))
                .thenReturn(List.of(orderSeat(90L, 301L, "A-1"), orderSeat(90L, 302L, "A-2")));
        when(orderMapper.selectById(90L)).thenReturn(existing);

        Order result = service.createTeamOrderWithLockedSeats(request);

        assertEquals(existing, result);
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-LEADER-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("team-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1");
        verify(orderMapper).selectById(90L);
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsTeamGrabRequestThatCollidesWithNormalGrabBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existingNormalGrab = teamOrderItem(104L, null, "TEAM-GRAB-1", false);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(existingNormalGrab);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-LEADER-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("team-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-1");
        verify(orderMapper, never()).selectOrderListItemByGrabRequestId("GRAB-LEADER-1");
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsGrabRequestThatCollidesWithExistingTeamGrabBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setTeamGrabRequestId("TEAM-GRAB-2");
        request.setGrabRequestId("GRAB-LEADER-1");
        OrderListItemResponse existingTeamGrab = teamOrderItem(105L, "GRAB-LEADER-1", "GRAB-LEADER-OLD", true);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-2")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-2")).thenReturn(null);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("GRAB-LEADER-1")).thenReturn(existingTeamGrab);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-LEADER-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-2");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("team-order:TEAM-GRAB-2");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-2");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-2");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("GRAB-LEADER-1");
        verify(orderMapper, never()).selectOrderListItemByGrabRequestId("GRAB-LEADER-1");
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentSeatIdsBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existingItem = teamOrderItem(100L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(100L))
                .thenReturn(List.of(orderSeat(100L, 301L, "A-1"), orderSeat(100L, 303L, "A-3")));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentSeatLabelBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existingItem = teamOrderItem(101L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(101L))
                .thenReturn(List.of(orderSeat(101L, 301L, "A-1"), orderSeat(101L, 302L, "A-9")));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderMissingAuthorizedMaxUnitPriceBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setAuthorizedMaxUnitPrice(null);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).acquireAdvisoryTransactionLock(anyString());
        verify(orderMapper, never()).selectTeamOrderListItemByTeamGrabRequestId(anyString());
        verify(orderMapper, never()).selectById(any());
        verify(orderSeatMapper, never()).selectLockedAndSoldSeatsByOrderId(any());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderWhenAuthorizedMaxUnitPriceBelowExistingUnitPriceBeforeTicketOrInsertSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setAuthorizedMaxUnitPrice(new BigDecimal("99.99"));
        OrderListItemResponse existingItem = teamOrderItem(103L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(103L))
                .thenReturn(List.of(orderSeat(103L, 301L, "A-1"), orderSeat(103L, 302L, "A-2")));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderReturnsExistingOrderForSameGrabRequestWithoutSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        Order existing = new Order();
        existing.setId(91L);
        existing.setOrderNo("DM-existing-grab");
        OrderListItemResponse existingItem = teamOrderItem(91L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existingItem.setUnitPrice(new BigDecimal("100.00"));
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-LEADER-1")).thenReturn(existingItem);
        when(orderSeatMapper.selectLockedAndSoldSeatsByOrderId(91L))
                .thenReturn(List.of(orderSeat(91L, 301L, "A-1"), orderSeat(91L, 302L, "A-2")));
        when(orderMapper.selectById(91L)).thenReturn(existing);

        Order result = service.createTeamOrderWithLockedSeats(request);

        assertEquals(existing, result);
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-LEADER-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("team-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-LEADER-1");
        verify(orderMapper).selectById(91L);
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderLocksAndLooksUpBeforeQuoteSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        TicketSalesQuoteResponse quote = quoteWithActivity(2);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(ticketSalesInternalClient.validateTeamSeatLock(any(), anyString())).thenReturn(Result.success(validTeamLock()));

        service.createTeamOrderWithLockedSeats(request);

        InOrder inOrder = inOrder(orderMapper, ticketSalesInternalClient);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-LEADER-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("team-order:TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("TEAM-GRAB-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-LEADER-1");
        inOrder.verify(ticketSalesInternalClient).quote(any(), anyString());
    }

    @Test
    void createTeamOrderRejectsMismatchedGrabRequestForExistingTeamOrder() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setGrabRequestId("GRAB-OTHER");
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1"))
                .thenReturn(teamOrderItem(92L, "TEAM-GRAB-1", "GRAB-LEADER-1", true));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsIdenticalTeamGrabAndGrabRequestIdsBeforeMapperOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setGrabRequestId("TEAM-GRAB-1");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals("组队抢票请求标识不能与普通抢票请求标识相同", ex.getMessage());
        verify(orderMapper, never()).acquireAdvisoryTransactionLock(anyString());
        verify(orderMapper, never()).selectTeamOrderListItemByTeamGrabRequestId(anyString());
        verify(orderMapper, never()).selectOrderListItemByGrabRequestId(anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentUserBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existing = teamOrderItem(95L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existing.setUserId(3005L);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentSessionBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existing = teamOrderItem(96L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existing.setSessionId(202L);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentQuantityBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existing = teamOrderItem(97L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existing.setQuantity(1);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsExistingTeamOrderForDifferentTeamBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existing = teamOrderItem(98L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existing.setTeamId(8002L);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsMissingTeamIdBeforeMapperOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setTeamId(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        assertEquals("组队订单缺少队伍标识", ex.getMessage());
        verify(orderMapper, never()).acquireAdvisoryTransactionLock(anyString());
        verify(orderMapper, never()).selectTeamOrderListItemByTeamGrabRequestId(anyString());
        verify(orderMapper, never()).selectOrderListItemByGrabRequestId(anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsExistingGrabOrderForDifferentUserBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        OrderListItemResponse existing = teamOrderItem(99L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        existing.setUserId(3005L);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-LEADER-1")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsExistingGrabOrderForDifferentTeamBeforeLoadOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setTeamId(8002L);
        OrderListItemResponse existing = teamOrderItem(106L, "TEAM-GRAB-1", "GRAB-LEADER-1", true);
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-LEADER-1")).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsMissingGrabRequestBeforeMapperOrTicketSideEffects() {
        CreateTeamOrderRequest request = teamOrderRequest();
        request.setGrabRequestId(" ");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals("组队订单缺少抢票请求标识", ex.getMessage());
        verify(orderMapper, never()).acquireAdvisoryTransactionLock(anyString());
        verify(orderMapper, never()).selectTeamOrderListItemByTeamGrabRequestId(anyString());
        verify(orderMapper, never()).selectOrderListItemByGrabRequestId(anyString());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(ticketSalesInternalClient, never()).validateTeamSeatLock(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderRejectsGrabRequestHitForNonTeamOrder() {
        CreateTeamOrderRequest request = teamOrderRequest();
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-LEADER-1"))
                .thenReturn(teamOrderItem(93L, null, "GRAB-LEADER-1", false));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsGrabRequestHitForDifferentTeamRequest() {
        CreateTeamOrderRequest request = teamOrderRequest();
        when(orderMapper.selectTeamOrderListItemByTeamGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("TEAM-GRAB-1")).thenReturn(null);
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-LEADER-1"))
                .thenReturn(teamOrderItem(94L, "TEAM-GRAB-OTHER", "GRAB-LEADER-1", true));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).selectById(any());
        verify(ticketSalesInternalClient, never()).quote(any(), anyString());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createTeamOrderRejectsWhenTicketLockDoesNotBelongToRequest() {
        CreateTeamOrderRequest request = teamOrderRequest();
        TicketSalesSeatLockResponse invalid = new TicketSalesSeatLockResponse();
        invalid.setValid(false);
        invalid.setLockedSeatIds(List.of(301L));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.validateTeamSeatLock(any(), anyString())).thenReturn(Result.success(invalid));

        assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(ticketSalesInternalClient, never()).lockSeats(any(), anyString());
    }

    @Test
    void createTeamOrderRejectsWhenTicketLockLabelDiffersFromRequestBeforeInsert() {
        CreateTeamOrderRequest request = teamOrderRequest();
        TicketSalesSeatLockResponse invalid = new TicketSalesSeatLockResponse();
        invalid.setValid(true);
        invalid.setLockedSeatIds(List.of(301L, 302L));
        invalid.setSeatLabels(List.of("ticket-label-1", "A-2"));

        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.validateTeamSeatLock(any(), anyString())).thenReturn(Result.success(invalid));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTeamOrderWithLockedSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createTeamOrderStoresOrderOwnedSeatLabels() {
        CreateTeamOrderRequest request = teamOrderRequest();
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.validateTeamSeatLock(any(), anyString())).thenReturn(Result.success(validTeamLock()));
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(82L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        service.createTeamOrderWithLockedSeats(request);

        ArgumentCaptor<OrderSeat> seatCaptor = ArgumentCaptor.forClass(OrderSeat.class);
        verify(orderSeatMapper, org.mockito.Mockito.times(2)).insert(seatCaptor.capture());
        assertEquals(List.of("A-1", "A-2"), seatCaptor.getAllValues().stream()
                .map(OrderSeat::getSeatLabel)
                .collect(java.util.stream.Collectors.toList()));

        ArgumentCaptor<OrderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(orderSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("A-1, A-2", snapshotCaptor.getValue().getSeatLabels());
        assertEquals(7001L, snapshotCaptor.getValue().getTeamId());
        assertEquals("TEAM-GRAB-1", snapshotCaptor.getValue().getTeamGrabRequestId());
        assertEquals(Boolean.TRUE, snapshotCaptor.getValue().getTeamOrder());
        assertEquals("TEAM", snapshotCaptor.getValue().getSeatSelectionMode());
    }

    @Test
    void createOrderWritesGrabSnapshotFields() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(2L);
        request.setQuantity(1);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("980.00"));
        request.setGrabRequestId("GRAB1");
        request.setRequestedTicketTypeId(1L);
        request.setMatchedTicketTypeId(2L);
        request.setAutoDowngraded(true);
        request.setAttendeeIds(List.of(501L));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(1);
        quote.setTicketTypeId(2L);
        quote.setUnitPrice(new BigDecimal("980.00"));
        quote.setTicketName("B");
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(ticketSalesInternalClient.lockStock(any(TicketSalesLockRequest.class), anyString())).thenReturn(Result.success());
        givenResolvedAttendees(1);

        service.createOrder(request);

        ArgumentCaptor<OrderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(orderSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("GRAB1", snapshotCaptor.getValue().getGrabRequestId());
        assertEquals(1L, snapshotCaptor.getValue().getRequestedTicketTypeId());
        assertEquals(2L, snapshotCaptor.getValue().getMatchedTicketTypeId());
        assertEquals(Boolean.TRUE, snapshotCaptor.getValue().getAutoDowngraded());
        assertEquals("NONE", snapshotCaptor.getValue().getSeatSelectionMode());
    }

    @Test
    void findOrderByGrabRequestIdReturnsOrderSummary() {
        OrderListItemResponse order = new OrderListItemResponse();
        order.setId(14L);
        order.setOrderNo("DM20260530120000ABCDEF");
        order.setStatus(1);
        order.setGrabRequestId("GRAB-20260530-1");
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-20260530-1")).thenReturn(order);

        OrderListItemResponse result = service.findOrderByGrabRequestId("GRAB-20260530-1");

        assertEquals(14L, result.getId());
        assertEquals("DM20260530120000ABCDEF", result.getOrderNo());
        assertEquals(1, result.getStatus());
        assertEquals("GRAB-20260530-1", result.getGrabRequestId());
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-20260530-1");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-20260530-1");
    }

    @Test
    void findOrderByGrabRequestIdReturnsNullWhenMissing() {
        when(orderMapper.selectOrderListItemByGrabRequestId("GRAB-20260530-missing")).thenReturn(null);

        OrderListItemResponse result = service.findOrderByGrabRequestId("GRAB-20260530-missing");

        assertNull(result);
        InOrder inOrder = inOrder(orderMapper);
        inOrder.verify(orderMapper).acquireAdvisoryTransactionLock("grab-order:GRAB-20260530-missing");
        inOrder.verify(orderMapper).selectOrderListItemByGrabRequestId("GRAB-20260530-missing");
    }

    @Test
    void createOrderWithSeatsStoresLockedSeatLabels() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setSeatIds(List.of(302L, 301L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(302L, 301L));
        lockResponse.setSeatLabels(List.of("A-2", "A-1"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(83L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        service.createOrderWithSeats(request);

        ArgumentCaptor<OrderSeat> seatCaptor = ArgumentCaptor.forClass(OrderSeat.class);
        verify(orderSeatMapper, org.mockito.Mockito.times(2)).insert(seatCaptor.capture());
        assertEquals(List.of(302L, 301L), seatCaptor.getAllValues().stream()
                .map(OrderSeat::getSessionSeatId)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("A-2", "A-1"), seatCaptor.getAllValues().stream()
                .map(OrderSeat::getSeatLabel)
                .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void createOrderWithSeatsPreservesQuoteSeatLabelsWhenLockLabelsAreMissing() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setSeatIds(List.of(301L, 302L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(2);
        quote.setSeatLabels("A-1, A-2");
        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L, 302L));
        lockResponse.setSeatLabels(List.of());
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(85L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        service.createOrderWithSeats(request);

        ArgumentCaptor<OrderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(orderSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("A-1, A-2", snapshotCaptor.getValue().getSeatLabels());

        ArgumentCaptor<OrderSeat> seatCaptor = ArgumentCaptor.forClass(OrderSeat.class);
        verify(orderSeatMapper, org.mockito.Mockito.times(2)).insert(seatCaptor.capture());
        assertTrue(seatCaptor.getAllValues().stream().allMatch(seat -> seat.getSeatLabel() == null));
    }

    @Test
    void createOrderWithSeatsRejectsLockLabelCountMismatchBeforeInsert() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setSeatIds(List.of(301L, 302L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L, 302L));
        lockResponse.setSeatLabels(List.of("A-1"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsFewerLockedSeatIdsThanQuantityBeforeInsert() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(2);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L));
        lockResponse.setSeatLabels(List.of("A-1"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsMoreLockedSeatIdsThanQuantityBeforeInsert() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(2);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L, 302L, 303L));
        lockResponse.setSeatLabels(List.of("A-1", "A-2", "A-3"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsRejectsExplicitRequestWhenLockedSeatIdsDifferBeforeInsert() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setSeatIds(List.of(301L, 302L));
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of(301L, 303L));
        lockResponse.setSeatLabels(List.of("A-1", "A-3"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrderWithSeats(request));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        verify(orderSnapshotMapper, never()).insert(any(OrderSnapshot.class));
    }

    @Test
    void createOrderWithSeatsAllowsAggregateFallbackWithoutOrderSeats() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(2);
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setAttendeeIds(List.of(501L, 502L));

        TicketSalesSeatLockResponse lockResponse = new TicketSalesSeatLockResponse();
        lockResponse.setLockedSeatIds(List.of());
        lockResponse.setSeatLabels(List.of("系统分配站区票 x2"));
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quoteWithoutLimit(2)));
        when(ticketSalesInternalClient.lockSeats(any(), anyString())).thenReturn(Result.success(lockResponse));
        givenResolvedAttendees(2);
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(84L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        service.createOrderWithSeats(request);

        verify(orderMapper).insert(any(Order.class));
        verify(orderSeatMapper, never()).insert(any(OrderSeat.class));
        ArgumentCaptor<OrderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(OrderSnapshot.class);
        verify(orderSnapshotMapper).insert(snapshotCaptor.capture());
        assertEquals("系统分配站区票 x2", snapshotCaptor.getValue().getSeatLabels());
    }

    @Test
    void createOrderDoesNotCheckQuantityWhenActivityHasNoPerUserLimit() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(10L);
        request.setTicketTypeId(20L);
        request.setQuantity(5);
        request.setAttendeeIds(List.of(501L, 502L, 503L, 504L, 505L));

        TicketSalesQuoteResponse quote = quoteWithoutLimit(5);
        when(userInternalClient.getUserRef(eq(2004L), anyString())).thenReturn(Result.success(activeUser()));
        when(ticketSalesInternalClient.quote(any(), anyString())).thenReturn(Result.success(quote));
        when(ticketSalesInternalClient.lockStock(any(TicketSalesLockRequest.class), anyString())).thenReturn(Result.success());
        givenResolvedAttendees(5);

        service.createOrder(request);

        verify(orderMapper, never()).sumEffectiveQuantityByUserAndActivity(any(), any());
        verify(orderMapper).insert(any(Order.class));
    }

    @Test
    void teamOrderMarkPaidConfirmsOnlyLockedSeatsWithTeamGrabRequestIdFence() {
        Order order = paidOrder(2101L, 101L, 1L);
        OrderSeat lockedSeat = orderSeat(2101L, 301L, "A-1");
        lockedSeat.setId(9001L);
        lockedSeat.setSessionId(101L);
        lockedSeat.setTicketTypeId(1L);
        lockedSeat.setStatus(1);
        OrderSeat soldSeat = orderSeat(2101L, 302L, "A-2");
        soldSeat.setId(9002L);
        soldSeat.setSessionId(101L);
        soldSeat.setTicketTypeId(1L);
        soldSeat.setStatus(2);
        when(orderMapper.selectById(2101L)).thenReturn(order);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(lockedSeat, soldSeat));
        when(orderSnapshotMapper.selectOne(any())).thenReturn(teamSnapshot(2101L, "TEAM-GRAB-1"));
        when(ticketSalesInternalClient.confirmSold(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.markPaid(2101L);

        ArgumentCaptor<TicketSalesOrderRequest> captor = ArgumentCaptor.forClass(TicketSalesOrderRequest.class);
        verify(ticketSalesInternalClient).confirmSold(captor.capture(), eq("test-internal-token"));
        assertEquals(List.of(301L), captor.getValue().getSeatIds());
        assertEquals("TEAM-GRAB-1", captor.getValue().getLockRequestId());
    }

    @Test
    void teamOrderMarkPaidSkipsRemoteConfirmWhenLocalSeatsAlreadySold() {
        Order order = paidOrder(2102L, 101L, 1L);
        OrderSeat soldSeat = orderSeat(2102L, 302L, "A-2");
        soldSeat.setId(9002L);
        soldSeat.setSessionId(101L);
        soldSeat.setTicketTypeId(1L);
        soldSeat.setStatus(2);
        when(orderMapper.selectById(2102L)).thenReturn(order);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(soldSeat));

        Order result = service.markPaid(2102L);

        assertEquals(OrderService.STATUS_PAID, result.getStatus());
        verify(ticketSalesInternalClient, never()).confirmSold(any(), anyString());
        verify(orderSeatMapper, never()).updateById(any());
    }

    @Test
    void markPaidLogsSegmentedLatencyForOrderFulfillment() {
        Order order = pendingOrder(2105L, 101L, 1L);
        order.setOrderNo("ORDER-2105");
        when(orderMapper.selectById(2105L)).thenReturn(order);
        when(orderMapper.updateStatusIfCurrent(2105L, OrderService.STATUS_PENDING, OrderService.STATUS_PAID))
                .thenReturn(1);
        when(orderSeatMapper.selectList(any())).thenReturn(null);
        when(ticketSalesInternalClient.confirmSold(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            service.markPaid(2105L);
        } finally {
            logger.detachAppender(appender);
        }

        String message = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("订单支付履约链路耗时"))
                .findFirst()
                .orElse("");

        assertTrue(message.contains("orderId=2105"));
        assertTrue(message.contains("orderNo=ORDER-2105"));
        assertTrue(message.contains("outcome=PAID"));
        assertTrue(message.contains("orderLoadMs="));
        assertTrue(message.contains("statusUpdateMs="));
        assertTrue(message.contains("ticketConfirmMs="));
        assertTrue(message.contains("ticketIssueMs="));
        assertTrue(message.contains("waitlistNotifyMs="));
        assertTrue(message.contains("totalMs="));
    }

    @Test
    void teamOrderCancelReleasesWithTeamGrabRequestIdFence() {
        Order order = pendingOrder(2103L, 101L, 1L);
        OrderSeat lockedSeat = orderSeat(2103L, 301L, "A-1");
        lockedSeat.setId(9003L);
        lockedSeat.setSessionId(101L);
        lockedSeat.setTicketTypeId(1L);
        lockedSeat.setStatus(1);
        when(orderMapper.selectById(2103L)).thenReturn(order);
        when(paymentInternalClient.syncOrderForCancel(2103L, "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(2103L, OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED))
                .thenReturn(1);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(lockedSeat));
        when(orderSnapshotMapper.selectOne(any())).thenReturn(teamSnapshot(2103L, "TEAM-GRAB-1"));
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.cancelOrder(2103L);

        ArgumentCaptor<TicketSalesOrderRequest> captor = ArgumentCaptor.forClass(TicketSalesOrderRequest.class);
        verify(ticketSalesInternalClient).release(captor.capture(), eq("test-internal-token"));
        assertEquals(List.of(301L), captor.getValue().getSeatIds());
        assertEquals("TEAM-GRAB-1", captor.getValue().getLockRequestId());
        assertEquals(4, lockedSeat.getStatus());
    }

    @Test
    void cancelOrderPublishesDistinctOrderCancelReleaseEvent() {
        WaitlistMqProducer producer = org.mockito.Mockito.mock(WaitlistMqProducer.class);
        service = new OrderService(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient,
                ticketSalesInternalClient, userInternalClient, producer, orderAttendeeMapper);
        Order order = pendingOrder(2104L, 101L, 1L);
        when(orderMapper.selectById(2104L)).thenReturn(order);
        when(paymentInternalClient.syncOrderForCancel(2104L, "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(2104L, OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED))
                .thenReturn(1);
        when(orderSeatMapper.selectList(any())).thenReturn(List.of());
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.cancelOrder(2104L);

        ArgumentCaptor<WaitlistReleasedMessage> captor = ArgumentCaptor.forClass(WaitlistReleasedMessage.class);
        verify(producer).sendReleasedEvent(captor.capture());
        assertEquals("ORDER_CANCEL", captor.getValue().getSource());
        assertEquals("order-cancel:2104:session:101:ticket-type:1", captor.getValue().getEventKey());
        assertEquals(2104L, captor.getValue().getSourceOrderId());
    }

    @Test
    void releaseExpiredSeatLocksMarksAttendeesCancelledWithDatabaseAllowedStatus() {
        Order order = pendingOrder(2201L, 101L, 1L);
        OrderSeat lockedSeat = orderSeat(2201L, 301L, "A-1");
        lockedSeat.setId(9101L);
        lockedSeat.setSessionId(101L);
        lockedSeat.setTicketTypeId(1L);
        lockedSeat.setStatus(1);
        lockedSeat.setLockExpireTime(java.time.LocalDateTime.now().minusMinutes(1));
        when(orderSeatMapper.selectList(any())).thenReturn(List.of(lockedSeat));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order));
        when(paymentInternalClient.syncOrderForCancel(2201L, "test-internal-token"))
                .thenReturn(Result.success(safeToCancelDecision()));
        when(orderMapper.updateStatusIfCurrent(2201L, OrderService.STATUS_PENDING, OrderService.STATUS_CANCELLED))
                .thenReturn(1);
        when(ticketSalesInternalClient.release(any(TicketSalesOrderRequest.class), eq("test-internal-token")))
                .thenReturn(Result.success());

        service.releaseExpiredSeatLocks();

        verify(orderAttendeeMapper).updateStatusByOrderId(2201L, 2);
    }

    private TicketSalesQuoteResponse quoteWithActivity(int quantity) {
        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setActivityId(100L);
        quote.setUnitPrice(new BigDecimal("100.00"));
        quote.setQuantity(quantity);
        return quote;
    }

    private TicketSalesQuoteResponse quoteWithoutLimit(int quantity) {
        TicketSalesQuoteResponse quote = new TicketSalesQuoteResponse();
        quote.setActivityId(100L);
        quote.setUnitPrice(new BigDecimal("100.00"));
        quote.setQuantity(quantity);
        return quote;
    }

    private PaymentSyncDecisionResponse safeToCancelDecision() {
        PaymentSyncDecisionResponse decision = new PaymentSyncDecisionResponse();
        decision.setPaid(false);
        decision.setSafeToCancel(true);
        return decision;
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
        order.setAmount(new BigDecimal("100.00"));
        return order;
    }

    private OrderSnapshot teamSnapshot(Long orderId, String teamGrabRequestId) {
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setOrderId(orderId);
        snapshot.setTeamOrder(true);
        snapshot.setTeamGrabRequestId(teamGrabRequestId);
        return snapshot;
    }

    private OrderListItemResponse teamOrderItem(Long id, String teamGrabRequestId, String grabRequestId, Boolean teamOrder) {
        OrderListItemResponse item = new OrderListItemResponse();
        item.setId(id);
        item.setTeamGrabRequestId(teamGrabRequestId);
        item.setGrabRequestId(grabRequestId);
        item.setTeamOrder(teamOrder);
        item.setSeatSelectionMode(Boolean.TRUE.equals(teamOrder) ? "TEAM" : "NONE");
        item.setUserId(2004L);
        item.setSessionId(101L);
        item.setTicketTypeId(1L);
        item.setQuantity(Boolean.TRUE.equals(teamOrder) ? 2 : 1);
        if (Boolean.TRUE.equals(teamOrder)) {
            item.setTeamId(7001L);
        }
        return item;
    }

    private CreateTeamOrderRequest teamOrderRequest() {
        CreateTeamOrderRequest request = new CreateTeamOrderRequest();
        request.setTeamId(7001L);
        request.setUserId(2004L);
        request.setPayerUserId(2004L);
        request.setSessionId(101L);
        request.setTicketTypeId(1L);
        request.setQuantity(2);
        request.setTeamGrabRequestId("TEAM-GRAB-1");
        request.setGrabRequestId("GRAB-LEADER-1");
        request.setMatchedStrategy("STRICT_CONTIGUOUS");
        request.setAuthorizedMaxUnitPrice(new BigDecimal("100.00"));
        request.setSeats(List.of(teamSeat(301L, "A-1"), teamSeat(302L, "A-2")));
        return request;
    }

    private OrderSeat orderSeat(Long orderId, Long sessionSeatId, String seatLabel) {
        OrderSeat seat = new OrderSeat();
        seat.setOrderId(orderId);
        seat.setSessionSeatId(sessionSeatId);
        seat.setSeatLabel(seatLabel);
        return seat;
    }

    private CreateTeamOrderRequest.TeamOrderSeatItem teamSeat(Long sessionSeatId, String seatLabel) {
        CreateTeamOrderRequest.TeamOrderSeatItem seat = new CreateTeamOrderRequest.TeamOrderSeatItem();
        seat.setSessionSeatId(sessionSeatId);
        seat.setSeatLabel(seatLabel);
        return seat;
    }

    private TicketSalesSeatLockResponse validTeamLock() {
        TicketSalesSeatLockResponse response = new TicketSalesSeatLockResponse();
        response.setValid(true);
        response.setLockedSeatIds(List.of(301L, 302L));
        response.setSeatLabels(List.of("A-1", "A-2"));
        return response;
    }

    private InternalUserRefResponse activeUser() {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(2004L);
        user.setPhone("13900000001");
        user.setRole("user");
        user.setStatus(1);
        return user;
    }

    private ResolvedAttendeeResponse resolvedAttendee(Long id, String name, String hash, String mask, String encrypted) {
        ResolvedAttendeeResponse attendee = new ResolvedAttendeeResponse();
        attendee.setId(id);
        attendee.setRealName(name);
        attendee.setIdType("ID_CARD");
        attendee.setIdNoHash(hash);
        attendee.setIdNoMask(mask);
        attendee.setIdNoEncrypted(encrypted);
        return attendee;
    }

    private void givenResolvedAttendees(int quantity) {
        java.util.List<ResolvedAttendeeResponse> attendees = new java.util.ArrayList<>();
        for (int index = 0; index < quantity; index++) {
            attendees.add(resolvedAttendee(500L + index, "Attendee" + index, "hash-" + index,
                    "110***********01" + index, "enc-" + index));
        }
        when(userInternalClient.resolveAttendees(any(), anyString())).thenReturn(Result.success(attendees));
    }

    private void loadExceptionRatioRule(String resource) {
        DegradeRule rule = new DegradeRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5);
        rule.setMinRequestAmount(1);
        rule.setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(rule));
    }
}
