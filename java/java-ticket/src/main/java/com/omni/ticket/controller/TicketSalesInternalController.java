package com.omni.ticket.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.omni.common.result.Result;
import com.omni.ticket.config.TicketSentinelConfig;
import com.omni.ticket.dto.TicketPurchaseContextRequest;
import com.omni.ticket.dto.TicketPurchaseContextResponse;
import com.omni.ticket.dto.TicketTypeVisibleResponse;
import com.omni.ticket.dto.TicketTypesVisibleRequest;
import com.omni.ticket.dto.TicketSalesLockRequest;
import com.omni.ticket.dto.TicketSalesOrderRequest;
import com.omni.ticket.dto.TicketSalesQuoteRequest;
import com.omni.ticket.dto.TicketSalesQuoteResponse;
import com.omni.ticket.dto.TicketSalesReleaseResponse;
import com.omni.ticket.dto.TicketSalesSeatLockResponse;
import com.omni.ticket.service.TicketSalesInternalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ticket/internal/sales")
public class TicketSalesInternalController {
    private final TicketSalesInternalService service;
    private final String internalApiToken;

    public TicketSalesInternalController(TicketSalesInternalService service,
                                         @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this.service = service;
        this.internalApiToken = internalApiToken;
    }

    @PostMapping("/quote")
    public Result<TicketSalesQuoteResponse> quote(@RequestBody TicketSalesQuoteRequest request,
                                                   @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(service.quote(request));
    }

    @PostMapping("/ticket-types-visible")
    public Result<List<TicketTypeVisibleResponse>> ticketTypesVisible(@RequestBody TicketTypesVisibleRequest request,
                                                                       @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "forbidden");
        }
        return Result.success(service.listVisibleTicketTypes(request));
    }

    @PostMapping("/purchase-context")
    public Result<TicketPurchaseContextResponse> purchaseContext(@RequestBody TicketPurchaseContextRequest request,
                                                                  @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "forbidden");
        }
        return Result.success(service.getPurchaseContext(request));
    }

    @PostMapping("/lock-stock")
    @SentinelResource(value = TicketSentinelConfig.SALES_LOCK_STOCK, blockHandler = "lockStockBlocked")
    public Result<Void> lockStock(@RequestBody TicketSalesLockRequest request,
                                  @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        service.lockStock(request);
        return Result.success();
    }

    public Result<Void> lockStockBlocked(TicketSalesLockRequest request, String token, BlockException exception) {
        return Result.fail(429, "系统繁忙，请稍后重试");
    }

    @PostMapping("/lock-seats")
    @SentinelResource(value = TicketSentinelConfig.SALES_LOCK_SEATS, blockHandler = "lockSeatsBlocked")
    public Result<TicketSalesSeatLockResponse> lockSeats(@RequestBody TicketSalesLockRequest request,
                                                          @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(service.lockSeats(request));
    }

    public Result<TicketSalesSeatLockResponse> lockSeatsBlocked(TicketSalesLockRequest request, String token, BlockException exception) {
        return Result.fail(429, "系统繁忙，请稍后重试");
    }

    @PostMapping("/confirm-sold")
    @SentinelResource(value = TicketSentinelConfig.SALES_CONFIRM_SOLD, blockHandler = "confirmSoldBlocked")
    public Result<Void> confirmSold(@RequestBody TicketSalesOrderRequest request,
                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        service.confirmSold(request);
        return Result.success();
    }

    public Result<Void> confirmSoldBlocked(TicketSalesOrderRequest request, String token, BlockException exception) {
        return Result.fail(429, "系统繁忙，请稍后重试");
    }

    @PostMapping("/release")
    public Result<TicketSalesReleaseResponse> release(@RequestBody TicketSalesOrderRequest request,
                                                      @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(service.release(request));
    }

    @PostMapping("/refund")
    public Result<TicketSalesReleaseResponse> refund(@RequestBody TicketSalesOrderRequest request,
                                                     @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(service.refund(request));
    }

    private boolean isValidInternalToken(String token) {
        return StringUtils.hasText(internalApiToken) && internalApiToken.equals(token);
    }
}
