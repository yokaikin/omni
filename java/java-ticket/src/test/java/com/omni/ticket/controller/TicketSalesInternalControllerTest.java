package com.omni.ticket.controller;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.omni.common.result.Result;
import com.omni.ticket.dto.TicketPurchaseContextRequest;
import com.omni.ticket.dto.TicketPurchaseContextResponse;
import com.omni.ticket.dto.TicketTypeVisibleResponse;
import com.omni.ticket.dto.TicketTypesVisibleRequest;
import com.omni.ticket.dto.TicketSalesLockRequest;
import com.omni.ticket.dto.TicketSalesOrderRequest;
import com.omni.ticket.dto.TicketSalesQuoteRequest;
import com.omni.ticket.dto.TicketSalesQuoteResponse;
import com.omni.ticket.dto.TicketSalesSeatLockResponse;
import com.omni.ticket.service.TicketSalesInternalService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TicketSalesInternalControllerTest {
    private final TicketSalesInternalService service = mock(TicketSalesInternalService.class);
    private final TicketSalesInternalController controller = new TicketSalesInternalController(service, "test-internal-token");

    @Test
    void quoteRejectsMissingToken() {
        Result<TicketSalesQuoteResponse> result = controller.quote(new TicketSalesQuoteRequest(), null);

        assertEquals(403, result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void quoteDelegatesWhenTokenMatches() {
        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        TicketSalesQuoteResponse response = new TicketSalesQuoteResponse();
        response.setTicketTypeId(4001L);
        when(service.quote(request)).thenReturn(response);

        Result<TicketSalesQuoteResponse> result = controller.quote(request, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(4001L, result.getData().getTicketTypeId());
    }

    @Test
    void ticketTypesVisibleRejectsMissingToken() {
        Result<List<TicketTypeVisibleResponse>> result = controller.ticketTypesVisible(new TicketTypesVisibleRequest(), null);

        assertEquals(403, result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void ticketTypesVisibleReturnsMetadataWhenTokenMatches() {
        TicketTypesVisibleRequest request = new TicketTypesVisibleRequest();
        request.setSessionId(101L);
        request.setTicketTypeIds(List.of(1L));
        TicketTypeVisibleResponse response = new TicketTypeVisibleResponse();
        response.setTicketTypeId(1L);
        response.setName("A");
        response.setPrice(new BigDecimal("1280.00"));
        response.setRemainStock(87);
        when(service.listVisibleTicketTypes(request)).thenReturn(List.of(response));

        Result<List<TicketTypeVisibleResponse>> result = controller.ticketTypesVisible(request, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals(1L, result.getData().get(0).getTicketTypeId());
        assertEquals("A", result.getData().get(0).getName());
        verify(service).listVisibleTicketTypes(request);
    }

    @Test
    void purchaseContextRejectsMissingToken() {
        Result<TicketPurchaseContextResponse> result = controller.purchaseContext(new TicketPurchaseContextRequest(), null);

        assertEquals(403, result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void purchaseContextDelegatesWhenTokenMatches() {
        TicketPurchaseContextRequest request = new TicketPurchaseContextRequest();
        request.setSessionId(101L);
        request.setTicketTypeId(202L);
        TicketPurchaseContextResponse response = new TicketPurchaseContextResponse();
        response.setSessionId(101L);
        response.setTicketTypeId(202L);
        response.setActivityName("周末演唱会");
        response.setTicketTypeName("看台 A");
        when(service.getPurchaseContext(request)).thenReturn(response);

        Result<TicketPurchaseContextResponse> result = controller.purchaseContext(request, "test-internal-token");

        assertEquals(200, result.getCode());
        assertEquals("周末演唱会", result.getData().getActivityName());
        assertEquals("看台 A", result.getData().getTicketTypeName());
        verify(service).getPurchaseContext(request);
    }

    @Test
    void lockStockRejectsMissingToken() {
        Result<Void> result = controller.lockStock(null, null);

        assertEquals(403, result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void lockStockBlockedReturnsTooManyRequests() {
        Result<Void> result = controller.lockStockBlocked(new TicketSalesLockRequest(), "test-internal-token", mock(BlockException.class));

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verifyNoInteractions(service);
    }

    @Test
    void lockSeatsBlockedReturnsTooManyRequests() {
        Result<TicketSalesSeatLockResponse> result = controller.lockSeatsBlocked(new TicketSalesLockRequest(), "test-internal-token", mock(BlockException.class));

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verifyNoInteractions(service);
    }

    @Test
    void confirmSoldBlockedReturnsTooManyRequests() {
        Result<Void> result = controller.confirmSoldBlocked(new TicketSalesOrderRequest(), "test-internal-token", mock(BlockException.class));

        assertEquals(429, result.getCode());
        assertEquals("系统繁忙，请稍后重试", result.getMessage());
        assertNull(result.getData());
        verifyNoInteractions(service);
    }

}
