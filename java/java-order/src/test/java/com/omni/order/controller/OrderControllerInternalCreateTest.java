package com.omni.order.controller;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.omni.common.result.Result;
import com.omni.order.dto.CreateOrderRequest;
import com.omni.order.dto.LockSeatsRequest;
import com.omni.order.dto.OrderListItemResponse;
import com.omni.order.dto.OrderSeatItemResponse;
import com.omni.order.dto.TicketWalletItemResponse;
import com.omni.order.entity.Order;
import com.omni.order.service.OrderService;
import com.omni.order.service.TicketWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerInternalCreateTest {

    private OrderService orderService;
    private TicketWalletService ticketWalletService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        ticketWalletService = mock(TicketWalletService.class);
        controller = new OrderController(orderService, ticketWalletService, "test-internal-token", "omni-jwt-secretomni-jwt-secretomni-jwt-secret");
    }

    @Test
    void internalCreateRejectsMissingToken() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);

        Result<Order> result = controller.createInternalOrder(request, null);

        assertEquals(403, result.getCode());
        assertEquals("无权限", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).createOrder(any());
    }

    @Test
    void internalCreateUsesExistingOrderServiceWhenTokenValid() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);
        Order order = new Order();
        order.setId(14L);
        order.setOrderNo("DM20260527124232BE5091");
        order.setAmount(new BigDecimal("160.00"));
        when(orderService.createOrder(request)).thenReturn(order);

        Result<Order> result = controller.createInternalOrder(request, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(order, result.getData());
        verify(orderService).createOrder(request);
    }

    @Test
    void internalCreateWithSeatsRejectsMissingToken() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);
        request.setSeatIds(List.of(301L));

        Result<Order> result = controller.createInternalOrderWithSeats(request, null);

        assertEquals(403, result.getCode());
        assertEquals("无权限", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).createOrderWithSeats(any());
    }

    @Test
    void internalCreateWithSeatsUsesExistingOrderServiceWhenTokenValid() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);
        request.setSeatIds(List.of(301L));
        Order order = new Order();
        order.setId(15L);
        order.setOrderNo("DM20260527124233BE5092");
        order.setAmount(new BigDecimal("160.00"));
        when(orderService.createOrderWithSeats(request)).thenReturn(order);

        Result<Order> result = controller.createInternalOrderWithSeats(request, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(order, result.getData());
        verify(orderService).createOrderWithSeats(request);
    }

    @Test
    void internalOrderSeatsReturnsLockedAndSoldSeatsForAssignment() {
        OrderSeatItemResponse locked = new OrderSeatItemResponse();
        locked.setOrderSeatId(1001L);
        locked.setSessionSeatId(301L);
        locked.setStatus(1);
        locked.setSeatLabel("A-1");
        OrderSeatItemResponse sold = new OrderSeatItemResponse();
        sold.setOrderSeatId(1002L);
        sold.setSessionSeatId(302L);
        sold.setStatus(2);
        sold.setSeatLabel("A-2");
        when(orderService.listInternalOrderSeats(16L)).thenReturn(List.of(locked, sold));

        Result<List<OrderSeatItemResponse>> result = controller.listInternalOrderSeats(16L, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(List.of(locked, sold), result.getData());
        verify(orderService).listInternalOrderSeats(16L);
    }

    @Test
    void internalListUserOrdersRequiresToken() {
        Result<List<OrderListItemResponse>> result = controller.listInternalUserOrders(2004L, 5, null);

        assertEquals(403, result.getCode());
        assertNull(result.getData());
        verify(orderService, never()).listOrderItems(any());
    }

    @Test
    void internalListUserOrdersReturnsLimitedUserOrders() {
        OrderListItemResponse first = new OrderListItemResponse();
        first.setId(101L);
        OrderListItemResponse second = new OrderListItemResponse();
        second.setId(102L);
        when(orderService.listOrderItems(2004L)).thenReturn(List.of(first, second));

        Result<List<OrderListItemResponse>> result = controller.listInternalUserOrders(2004L, 1, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(List.of(first), result.getData());
        verify(orderService).listOrderItems(2004L);
    }

    @Test
    void internalListUserTicketsRequiresToken() {
        Result<List<TicketWalletItemResponse>> result = controller.listInternalUserTickets(2004L, 5, null);

        assertEquals(403, result.getCode());
        assertNull(result.getData());
        verify(ticketWalletService, never()).listMyTickets(any());
    }

    @Test
    void internalListUserTicketsReturnsLimitedUserTickets() {
        TicketWalletItemResponse first = new TicketWalletItemResponse();
        first.setTicketId(301L);
        TicketWalletItemResponse second = new TicketWalletItemResponse();
        second.setTicketId(302L);
        when(ticketWalletService.listMyTickets(2004L)).thenReturn(List.of(first, second));

        Result<List<TicketWalletItemResponse>> result = controller.listInternalUserTickets(2004L, 1, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(List.of(first), result.getData());
        verify(ticketWalletService).listMyTickets(2004L);
    }

    @Test
    void internalCreateBlockHandlerReturnsBusyResponse() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);
        BlockException exception = new FlowException("order-internal-create");

        Result<Order> result = controller.createInternalOrderBlocked(request, "test-internal-token", exception);

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).createOrder(any());
    }

    @Test
    void internalCreateWithSeatsBlockHandlerReturnsBusyResponse() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(2004L);
        request.setSessionId(7L);
        request.setTicketTypeId(21L);
        request.setQuantity(1);
        request.setSeatIds(List.of(301L));
        BlockException exception = new FlowException("order-internal-create-with-seats");

        Result<Order> result = controller.createInternalOrderWithSeatsBlocked(request, "test-internal-token", exception);

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).createOrderWithSeats(any());
    }

    @Test
    void internalMarkPaidBlockHandlerReturnsBusyResponse() {
        BlockException exception = new FlowException("order-internal-mark-paid");

        Result<Order> result = controller.markInternalPaidBlocked(14L, "test-internal-token", exception);

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).markPaid(any());
    }

    @Test
    void internalLookupByGrabRequestRejectsMissingToken() {
        Result<com.omni.order.dto.OrderListItemResponse> result =
                controller.getInternalOrderByGrabRequest("GRAB-20260530-1", null);

        assertEquals(403, result.getCode());
        assertEquals("无权限", result.getMessage());
        assertNull(result.getData());
        verify(orderService, never()).findOrderByGrabRequestId(any());
    }

    @Test
    void internalLookupByGrabRequestReturnsOrderWhenTokenValid() {
        com.omni.order.dto.OrderListItemResponse order = new com.omni.order.dto.OrderListItemResponse();
        order.setId(14L);
        order.setOrderNo("DM20260530120000ABCDEF");
        order.setStatus(1);
        order.setGrabRequestId("GRAB-20260530-1");
        when(orderService.findOrderByGrabRequestId("GRAB-20260530-1")).thenReturn(order);

        Result<com.omni.order.dto.OrderListItemResponse> result =
                controller.getInternalOrderByGrabRequest("GRAB-20260530-1", "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(order, result.getData());
        verify(orderService).findOrderByGrabRequestId("GRAB-20260530-1");
    }

    @Test
    void internalLookupByGrabRequestReturnsNullWhenNotFound() {
        when(orderService.findOrderByGrabRequestId("GRAB-20260530-missing")).thenReturn(null);

        Result<com.omni.order.dto.OrderListItemResponse> result =
                controller.getInternalOrderByGrabRequest("GRAB-20260530-missing", "test-internal-token");

        assertEquals(200, result.getCode());
        assertNull(result.getData());
        verify(orderService).findOrderByGrabRequestId("GRAB-20260530-missing");
    }
}
