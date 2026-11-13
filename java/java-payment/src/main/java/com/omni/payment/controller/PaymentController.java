package com.omni.payment.controller;

import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.payment.client.OrderClient;
import com.omni.payment.dto.AlipayCallbackAcceptanceResponse;
import com.omni.payment.dto.MockPayRequest;
import com.omni.payment.dto.MockPayResponse;
import com.omni.payment.dto.OrderInfoResponse;
import com.omni.payment.dto.PaymentRecordResponse;
import com.omni.payment.service.MockPaymentService;
import com.omni.payment.service.PaymentService;
import io.jsonwebtoken.Claims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PaymentService paymentService;
    private final MockPaymentService mockPaymentService;
    private final OrderClient orderClient;
    private final String internalApiToken;
    private final String jwtSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${omni.payment.mock.enabled:false}")
    private boolean mockPaymentEnabled;

    @Autowired
    public PaymentController(PaymentService paymentService,
                             MockPaymentService mockPaymentService,
                             OrderClient orderClient,
                             @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken,
                             @Value("${jwt.secret:${JWT_SECRET:}}") String jwtSecret) {
        this.paymentService = paymentService;
        this.mockPaymentService = mockPaymentService;
        this.orderClient = orderClient;
        this.internalApiToken = internalApiToken;
        this.jwtSecret = jwtSecret;
    }

    PaymentController(PaymentService paymentService, MockPaymentService mockPaymentService) {
        this(paymentService, mockPaymentService, null, "", "");
    }

    @PostMapping("/pay")
    public Result<Void> mockPay(@RequestBody Map<String, Object> body) {
        return Result.fail(400, "请使用支付宝支付页面支付");
    }

    @PostMapping("/mock/pay")
    public Result<MockPayResponse> mockPayForDemo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) MockPayRequest request) {
        if (!mockPaymentEnabled) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前环境未启用本地支付确认");
        }
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付参数不能为空");
        }
        Long userId = requireAuthUserId(authorization);
        return Result.success(mockPaymentService.pay(request.getOrderId(), userId));
    }

    @PostMapping("/callback")
    public Result<Void> callback(@RequestBody Map<String, Object> body) {
        return Result.fail(400, "请使用正式支付回调接口");
    }

    @GetMapping("/record/{orderId}")
    public Result<PaymentRecordResponse> getPaymentRecord(
            @PathVariable Long orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserIdOrNull(authorization);
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (orderClient == null || !StringUtils.hasText(internalApiToken)) {
            return Result.fail(ResultCode.INTERNAL_ERROR);
        }
        Result<OrderInfoResponse> orderResult = orderClient.getOrder(orderId, internalApiToken);
        if (orderResult == null || orderResult.getCode() != ResultCode.SUCCESS.getCode()
                || orderResult.getData() == null) {
            return Result.fail(ResultCode.NOT_FOUND);
        }
        if (!userId.equals(orderResult.getData().getUserId())) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(PaymentRecordResponse.from(paymentService.getPaymentByOrderId(orderId)));
    }

    @GetMapping("/internal/alipay-notify-status/{orderId}")
    public Result<AlipayCallbackAcceptanceResponse> getAlipayNotifyStatus(
            @PathVariable Long orderId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        com.omni.payment.entity.Payment payment = paymentService.getPaymentByOrderId(orderId);
        AlipayCallbackAcceptanceResponse response = new AlipayCallbackAcceptanceResponse();
        response.setOrderId(payment.getOrderId());
        response.setPaymentStatus(payment.getStatus());
        response.setTradeNo(payment.getTradeNo());
        response.setNotifyTime(payment.getNotifyTime());
        response.setSignedNotificationReceived(hasSignedNotification(payment.getRawNotify()));
        return Result.success(response);
    }

    private Long requireAuthUserId(String authorization) {
        Long userId = requireAuthUserIdOrNull(authorization);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private Long requireAuthUserIdOrNull(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)
                || !StringUtils.hasText(jwtSecret)) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(authorization.substring(BEARER_PREFIX.length()))
                    .getBody();
            Object userId = claims.get("userId");
            return userId == null ? Long.valueOf(claims.getSubject()) : Long.valueOf(String.valueOf(userId));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean hasSignedNotification(String rawNotify) {
        if (!StringUtils.hasText(rawNotify)) {
            return false;
        }
        try {
            JsonNode fields = objectMapper.readTree(rawNotify);
            return fields.hasNonNull("notify_id") && fields.hasNonNull("sign");
        } catch (Exception e) {
            return false;
        }
    }
}
