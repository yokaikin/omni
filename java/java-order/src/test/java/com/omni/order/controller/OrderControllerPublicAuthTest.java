package com.omni.order.controller;

import com.omni.common.result.Result;
import com.omni.order.dto.TicketEntryCodeResponse;
import com.omni.order.dto.TicketTransferClaimRequest;
import com.omni.order.dto.TicketTransferClaimResponse;
import com.omni.order.dto.TicketTransferCreateResponse;
import com.omni.order.dto.TicketTransferRevokeResponse;
import com.omni.order.dto.TicketWalletItemResponse;
import com.omni.order.service.OrderService;
import com.omni.order.service.TicketWalletService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerPublicAuthTest {
    private static final String SECRET = "omni-jwt-secretomni-jwt-secretomni-jwt-secret";

    @Test
    void publicListOrdersUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.listOrders(bearer(2004L));

        verify(orderService).listOrderItems(2004L);
    }

    @Test
    void publicListOrdersRejectsMissingJwt() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        Result<List<com.omni.order.dto.OrderListItemResponse>> result = controller.listOrders(null);

        assertEquals(401, result.getCode());
        verify(orderService, never()).listOrderItems(any());
    }

    @Test
    void publicTicketWalletUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        OrderController controller = new OrderController(orderService, ticketWalletService, "internal-token", SECRET);

        Result<List<TicketWalletItemResponse>> result = controller.listMyTickets(bearer(2004L));

        assertEquals(200, result.getCode());
        verify(ticketWalletService).listMyTickets(2004L);
    }

    @Test
    void publicTicketEntryCodeUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        TicketEntryCodeResponse response = new TicketEntryCodeResponse();
        response.setTicketId(3001L);
        when(ticketWalletService.createEntryCode(2004L, 3001L)).thenReturn(response);
        OrderController controller = new OrderController(orderService, ticketWalletService, "internal-token", SECRET);

        Result<TicketEntryCodeResponse> result = controller.createTicketEntryCode(3001L, bearer(2004L));

        assertEquals(200, result.getCode());
        assertEquals(3001L, result.getData().getTicketId());
        verify(ticketWalletService).createEntryCode(2004L, 3001L);
    }

    @Test
    void publicCreateTicketTransferUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        TicketTransferCreateResponse response = new TicketTransferCreateResponse();
        response.setTicketId(3001L);
        response.setTransferCode("gift-code");
        when(ticketWalletService.createTransfer(2004L, 3001L)).thenReturn(response);
        OrderController controller = new OrderController(orderService, ticketWalletService, "internal-token", SECRET);

        Result<TicketTransferCreateResponse> result = controller.createTicketTransfer(3001L, bearer(2004L));

        assertEquals(200, result.getCode());
        assertEquals("gift-code", result.getData().getTransferCode());
        verify(ticketWalletService).createTransfer(2004L, 3001L);
    }

    @Test
    void publicClaimTicketTransferUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        TicketTransferClaimResponse response = new TicketTransferClaimResponse();
        response.setTicketId(3009L);
        when(ticketWalletService.claimTransfer(3008L, "gift-code")).thenReturn(response);
        TicketTransferClaimRequest request = new TicketTransferClaimRequest();
        request.setTransferCode("gift-code");
        OrderController controller = new OrderController(orderService, ticketWalletService, "internal-token", SECRET);

        Result<TicketTransferClaimResponse> result = controller.claimTicketTransfer(request, bearer(3008L));

        assertEquals(200, result.getCode());
        assertEquals(3009L, result.getData().getTicketId());
        verify(ticketWalletService).claimTransfer(3008L, "gift-code");
    }

    @Test
    void publicRevokeTicketTransferUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        TicketWalletService ticketWalletService = mock(TicketWalletService.class);
        TicketTransferRevokeResponse response = new TicketTransferRevokeResponse();
        response.setTicketId(3001L);
        when(ticketWalletService.revokeTransfer(2004L, 3001L)).thenReturn(response);
        OrderController controller = new OrderController(orderService, ticketWalletService, "internal-token", SECRET);

        Result<TicketTransferRevokeResponse> result = controller.revokeTicketTransfer(3001L, bearer(2004L));

        assertEquals(200, result.getCode());
        assertEquals(3001L, result.getData().getTicketId());
        verify(ticketWalletService).revokeTransfer(2004L, 3001L);
    }

    @Test
    void publicTrashOrdersUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.listTrashOrders(bearer(2004L));

        verify(orderService).listTrashOrderItems(2004L);
    }

    @Test
    void publicHideOrderUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.hideOrder(9001L, bearer(2004L));

        verify(orderService).hideOrder(9001L, 2004L);
    }

    @Test
    void publicRestoreOrderUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.restoreOrder(9001L, bearer(2004L));

        verify(orderService).restoreOrder(9001L, 2004L);
    }

    @Test
    void publicRefundOptionsUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.getRefundOptions(9001L, bearer(2004L));

        verify(orderService).getUserRefundOptions(9001L, 2004L);
    }

    @Test
    void publicCancelOrderUsesJwtUserId() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, "internal-token", SECRET);

        controller.cancelOrder(9001L, bearer(2004L));

        verify(orderService).cancelUserOrder(9001L, 2004L);
    }

    private static String bearer(Long userId) {
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claim("userId", userId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        return "Bearer " + token;
    }
}
