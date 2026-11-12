package com.omni.order.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.mq.MqPublishSupport;
import com.omni.exception.BusinessException;
import com.omni.order.client.PaymentInternalClient;
import com.omni.order.client.TicketSalesInternalClient;
import com.omni.order.client.UserInternalClient;
import com.omni.order.mq.WaitlistMqProducer;
import com.omni.order.config.OrderSentinelConfig;
import com.omni.order.dto.CreateOrderRequest;
import com.omni.order.dto.CreateTeamOrderRequest;
import com.omni.order.dto.LockSeatsRequest;
import com.omni.order.dto.MarkPartialRefundedRequest;
import com.omni.order.dto.OrderListItemResponse;
import com.omni.order.dto.OrderAttendeeResponse;
import com.omni.order.dto.OrderSeatItemResponse;
import com.omni.order.dto.PaymentSyncDecisionResponse;
import com.omni.order.dto.RefundOptionsResponse;
import com.omni.order.dto.RefundSeatOptionResponse;
import com.omni.order.dto.ResolveAttendeesRequest;
import com.omni.order.dto.ResolvedAttendeeResponse;
import com.omni.order.dto.SessionSeatUsageItemResponse;
import com.omni.order.dto.SessionSeatUsageResponse;
import com.omni.order.dto.TicketSalesLockRequest;
import com.omni.order.dto.TicketSalesOrderRequest;
import com.omni.order.dto.TicketSalesQuoteRequest;
import com.omni.order.dto.TicketSalesQuoteResponse;
import com.omni.order.dto.InternalUserRefResponse;
import com.omni.order.dto.TicketReleasedEvent;
import com.omni.order.dto.TicketSalesReleaseResponse;
import com.omni.order.dto.TicketSalesSeatLockResponse;
import com.omni.common.mq.message.WaitlistReleasedMessage;
import com.omni.order.entity.Order;
import com.omni.order.entity.OrderAttendee;
import com.omni.order.entity.OrderSeat;
import com.omni.order.entity.OrderSnapshot;
import com.omni.order.mapper.OrderAttendeeMapper;
import com.omni.order.mapper.OrderMapper;
import com.omni.order.mapper.OrderSeatMapper;
import com.omni.order.mapper.OrderSnapshotMapper;
import io.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_PAID = 2;
    public static final int STATUS_CANCELLED = 3;
    public static final int STATUS_REFUNDED = 4;
    private static final int ORDER_SEAT_LOCKED = 1;
    private static final int ORDER_SEAT_SOLD = 2;
    private static final int ORDER_SEAT_REFUNDED = 3;
    private static final int ORDER_SEAT_RELEASED = 4;
    private static final int ORDER_ATTENDEE_ACTIVE = 1;
    private static final int ORDER_ATTENDEE_CANCELLED = 2;
    private static final int ORDER_ATTENDEE_REFUNDED = 3;
    private static final String SEAT_SELECTION_NONE = "NONE";
    private static final String SEAT_SELECTION_EXPLICIT = "EXPLICIT";
    private static final String SEAT_SELECTION_RANDOM = "RANDOM";
    private static final String SEAT_SELECTION_TEAM = "TEAM";

    private final OrderMapper orderMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final OrderSnapshotMapper orderSnapshotMapper;
    private final PaymentInternalClient paymentInternalClient;
    private final TicketSalesInternalClient ticketSalesInternalClient;
    private final UserInternalClient userInternalClient;
    private final WaitlistMqProducer waitlistProducer;
    private final String internalApiToken;

    @Value("${omni.order.seat-lock-minutes:15}")
    private long seatLockMinutes = 15;
    private OrderAttendeeMapper orderAttendeeMapper;
    private TicketWalletService ticketWalletService;

    public OrderService(OrderMapper orderMapper) {
        this(orderMapper, null, null, null, null, null, null, (String) null);
    }

    public OrderService(OrderMapper orderMapper, OrderSeatMapper orderSeatMapper) {
        this(orderMapper, orderSeatMapper, null, null, null, null, null, (String) null);
    }

    @Autowired
    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        OrderSnapshotMapper orderSnapshotMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient,
                        UserInternalClient userInternalClient,
                        WaitlistMqProducer waitlistProducer,
                        @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this.orderMapper = orderMapper;
        this.orderSeatMapper = orderSeatMapper;
        this.orderSnapshotMapper = orderSnapshotMapper;
        this.paymentInternalClient = paymentInternalClient;
        this.ticketSalesInternalClient = ticketSalesInternalClient;
        this.userInternalClient = userInternalClient;
        this.waitlistProducer = waitlistProducer;
        this.internalApiToken = internalApiToken;
    }

    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        OrderSnapshotMapper orderSnapshotMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient,
                        UserInternalClient userInternalClient) {
        this(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient, ticketSalesInternalClient, userInternalClient, null, "test-internal-token");
    }

    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        OrderSnapshotMapper orderSnapshotMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient,
                        UserInternalClient userInternalClient,
                        WaitlistMqProducer waitlistProducer) {
        this(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient, ticketSalesInternalClient, userInternalClient, waitlistProducer, "test-internal-token");
    }

    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        OrderSnapshotMapper orderSnapshotMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient,
                        UserInternalClient userInternalClient,
                        WaitlistMqProducer waitlistProducer,
                        OrderAttendeeMapper orderAttendeeMapper) {
        this(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient, ticketSalesInternalClient, userInternalClient, waitlistProducer, "test-internal-token");
        this.orderAttendeeMapper = orderAttendeeMapper;
    }

    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient,
                        UserInternalClient userInternalClient) {
        this(orderMapper, orderSeatMapper, null, paymentInternalClient, ticketSalesInternalClient, userInternalClient, null, "test-internal-token");
    }

    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        OrderSnapshotMapper orderSnapshotMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient) {
        this(orderMapper, orderSeatMapper, orderSnapshotMapper, paymentInternalClient, ticketSalesInternalClient, null, null, "test-internal-token");
    }

    @Deprecated
    public OrderService(OrderMapper orderMapper,
                        OrderSeatMapper orderSeatMapper,
                        PaymentInternalClient paymentInternalClient,
                        TicketSalesInternalClient ticketSalesInternalClient) {
        this(orderMapper, orderSeatMapper, null, paymentInternalClient, ticketSalesInternalClient, null, null, "test-internal-token");
    }

    @Autowired(required = false)
    public void setOrderAttendeeMapper(OrderAttendeeMapper orderAttendeeMapper) {
        this.orderAttendeeMapper = orderAttendeeMapper;
    }

    @Autowired(required = false)
    public void setTicketWalletService(TicketWalletService ticketWalletService) {
        this.ticketWalletService = ticketWalletService;
    }

    @GlobalTransactional(name = "omni-create-order", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(CreateOrderRequest request) {
        OrderCreationTiming timing = OrderCreationTiming.start();
        Order order = null;
        Integer quantity = request != null ? request.getQuantity() : null;
        try {
            quantity = requirePositiveQuantity(request.getQuantity());
            Order existingGrabOrder = resolveExistingNormalGrabOrder(request, quantity);
            timing.mark("existingLookup");
            if (existingGrabOrder != null) {
                order = existingGrabOrder;
                return existingGrabOrder;
            }
            validateUserExists(request.getUserId());
            timing.mark("user");
            TicketSalesQuoteResponse quote = quoteTickets(request.getSessionId(), request.getTicketTypeId(), null, quantity);
            timing.mark("quote");
            validateAuthorizedPrice(request.getAuthorizedMaxUnitPrice(), quote, request.getGrabRequestId());
            timing.mark("limit");
            List<ResolvedAttendeeResponse> attendees = resolveOrderAttendees(request.getUserId(), request.getAttendeeIds(), quote, quantity);
            timing.mark("attendee");
            order = buildPendingOrder(request.getUserId(), request.getSessionId(), request.getTicketTypeId(), quantity, quote.getUnitPrice());
            lockStockForOrder(order);
            timing.mark("lockStock");
            orderMapper.insert(order);
            writeSnapshot(order, quote, request.getGrabRequestId(), request.getRequestedTicketTypeId(),
                    request.getMatchedTicketTypeId(), request.getAutoDowngraded(), SEAT_SELECTION_NONE);
            writeOrderAttendees(order, attendees, Collections.emptyList());
            timing.mark("persist");
            log.info("订单创建成功: orderNo={}, userId={}, amount={}", order.getOrderNo(), request.getUserId(), order.getAmount());
            return order;
        } finally {
            log.info("订单创建耗时: {}", timing.summary("createOrder", request != null ? request.getGrabRequestId() : null,
                    order != null ? order.getId() : null,
                    request != null ? request.getUserId() : null,
                    request != null ? request.getSessionId() : null,
                    request != null ? request.getTicketTypeId() : null,
                    quantity));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Order markPaid(Long id) {
        OrderFulfillmentLatencyTrace latencyTrace = new OrderFulfillmentLatencyTrace(id);
        String outcome = "FAILED";
        try {
            Order order = latencyTrace.measureOrderLoad(() -> orderMapper.selectById(id));
            latencyTrace.setOrder(order);
            if (order == null) {
                outcome = "NOT_FOUND";
                throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
            }
            if (order.getStatus() == STATUS_PAID) {
                latencyTrace.measureTicketConfirm(() -> confirmTicketsSold(order));
                latencyTrace.measureTicketIssue(() -> issueElectronicTickets(order));
                outcome = "ALREADY_PAID";
                return order;
            }
            if (order.getStatus() != STATUS_PENDING) {
                outcome = "INVALID_STATUS";
                throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不允许支付");
            }
            int updated = latencyTrace.measureStatusUpdate(() -> orderMapper.updateStatusIfCurrent(id, STATUS_PENDING, STATUS_PAID));
            if (updated != 1) {
                Order latest = latencyTrace.measureOrderLoad(() -> orderMapper.selectById(id));
                latencyTrace.setOrder(latest);
                if (latest != null && latest.getStatus() == STATUS_PAID) {
                    outcome = "CONCURRENT_PAID";
                    return latest;
                }
                outcome = "CONFLICT";
                throw new BusinessException(ResultCode.CONFLICT, "订单状态已变化，不能标记为已支付");
            }
            order.setStatus(STATUS_PAID);
            order.setUpdateTime(LocalDateTime.now());
            latencyTrace.measureTicketConfirm(() -> confirmTicketsSold(order));
            latencyTrace.measureTicketIssue(() -> issueElectronicTickets(order));
            latencyTrace.measureWaitlistNotify(() -> notifyWaitlistPaid(order.getId()));
            outcome = "PAID";
            log.info("订单已标记为已支付: id={}, orderNo={}", id, order.getOrderNo());
            return order;
        } finally {
            latencyTrace.log(outcome);
        }
    }

    @GlobalTransactional(name = "omni-create-order-with-seats", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Order createOrderWithSeats(LockSeatsRequest request) {
        OrderCreationTiming timing = OrderCreationTiming.start();
        Order order = null;
        Integer quantityForLog = request != null ? request.getQuantity() : null;
        try {
            boolean hasSeatIds = request.getSeatIds() != null && !request.getSeatIds().isEmpty();
            int quantity = hasSeatIds ? request.getSeatIds().size() : requirePositiveQuantity(request.getQuantity());
            quantityForLog = quantity;
            String seatSelectionMode = hasSeatIds ? SEAT_SELECTION_EXPLICIT : SEAT_SELECTION_RANDOM;
            Order existingGrabOrder = resolveExistingNormalGrabOrder(request, quantity, seatSelectionMode);
            timing.mark("existingLookup");
            if (existingGrabOrder != null) {
                order = existingGrabOrder;
                return existingGrabOrder;
            }
            validateUserExists(request.getUserId());
            timing.mark("user");
            TicketSalesQuoteResponse quote = quoteTickets(request.getSessionId(), request.getTicketTypeId(), request.getSeatIds(), quantity);
            timing.mark("quote");
            validateAuthorizedPrice(request.getAuthorizedMaxUnitPrice(), quote, request.getGrabRequestId());
            timing.mark("limit");
            List<ResolvedAttendeeResponse> attendees = resolveOrderAttendees(request.getUserId(), request.getAttendeeIds(), quote, quantity);
            timing.mark("attendee");
            TicketSalesLockRequest lockRequest = new TicketSalesLockRequest();
            lockRequest.setOrderId(0L);
            lockRequest.setSessionId(request.getSessionId());
            lockRequest.setTicketTypeId(request.getTicketTypeId());
            lockRequest.setSeatIds(request.getSeatIds());
            lockRequest.setQuantity(quantity);
            lockRequest.setLockExpireTime(LocalDateTime.now().plusMinutes(seatLockMinutes));
            lockRequest.setAllocateRandom(!hasSeatIds);
            TicketSalesSeatLockResponse lockResponse = lockSeats(lockRequest);
            timing.mark("lockSeats");
            List<Long> lockedSeatIds = lockResponse.getLockedSeatIds();
            List<String> lockSeatLabels = lockResponse.getSeatLabels();
            boolean hasLockedSeatIds = lockedSeatIds != null && !lockedSeatIds.isEmpty();
            boolean hasAggregateSeatLabels = lockSeatLabels != null && !lockSeatLabels.isEmpty();
            Map<Long, String> lockedSeatLabelsById = Collections.emptyMap();
            if (hasLockedSeatIds) {
                if (lockedSeatIds.size() != quantity) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "锁定座位数量与购买数量不一致");
                }
                if (hasSeatIds && !sameSeatIds(request.getSeatIds(), lockedSeatIds)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "锁定座位与所选座位不一致");
                }
                lockedSeatLabelsById = buildSeatLabelMap(lockedSeatIds, lockSeatLabels,
                        "锁定座位标签与座位数量不一致");
                if (!lockedSeatLabelsById.isEmpty()) {
                    quote.setSeatLabels(String.join(", ", lockSeatLabels));
                }
            } else if (hasAggregateSeatLabels) {
                quote.setSeatLabels(String.join(", ", lockSeatLabels));
            } else {
                throw new BusinessException(ResultCode.BAD_REQUEST, "锁座结果缺少座位或座位标签");
            }
            order = buildPendingOrder(
                    request.getUserId(),
                    request.getSessionId(),
                    request.getTicketTypeId(),
                    quantity,
                    quote.getUnitPrice());
            orderMapper.insert(order);
            writeSnapshot(order, quote, request.getGrabRequestId(), request.getRequestedTicketTypeId(),
                    request.getMatchedTicketTypeId(), request.getAutoDowngraded(), seatSelectionMode);
            List<Long> orderSeatIds = new ArrayList<>();
            if (lockedSeatIds != null && !lockedSeatIds.isEmpty() && orderSeatMapper != null) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime expireTime = now.plusMinutes(seatLockMinutes);
                for (Long seatId : lockedSeatIds) {
                    OrderSeat orderSeat = new OrderSeat();
                    orderSeat.setOrderId(order.getId());
                    orderSeat.setSessionSeatId(seatId);
                    orderSeat.setSessionId(request.getSessionId());
                    orderSeat.setTicketTypeId(request.getTicketTypeId());
                    orderSeat.setStatus(1);
                    orderSeat.setSeatLabel(lockedSeatLabelsById.get(seatId));
                    orderSeat.setLockExpireTime(expireTime);
                    orderSeat.setCreateTime(now);
                    orderSeat.setUpdateTime(now);
                    orderSeatMapper.insert(orderSeat);
                    orderSeatIds.add(orderSeat.getId());
                }
            }
            writeOrderAttendees(order, attendees, orderSeatIds);
            timing.mark("persist");
            return order;
        } finally {
            log.info("订单创建耗时: {}", timing.summary("createOrderWithSeats", request != null ? request.getGrabRequestId() : null,
                    order != null ? order.getId() : null,
                    request != null ? request.getUserId() : null,
                    request != null ? request.getSessionId() : null,
                    request != null ? request.getTicketTypeId() : null,
                    quantityForLog));
        }
    }

    @GlobalTransactional(name = "omni-create-team-order-with-locked-seats", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Order createTeamOrderWithLockedSeats(CreateTeamOrderRequest request) {
        TeamOrderSeatPayload payload = validateAndParseTeamOrderRequest(request);
        lockTeamGrabNamespaces(request.getGrabRequestId(), request.getTeamGrabRequestId());
        orderMapper.acquireAdvisoryTransactionLock("team-order:" + request.getTeamGrabRequestId());

        OrderListItemResponse existingTeamOrder = orderMapper.selectTeamOrderListItemByTeamGrabRequestId(request.getTeamGrabRequestId());
        OrderListItemResponse teamGrabAsNormalOrder = orderMapper.selectOrderListItemByGrabRequestId(request.getTeamGrabRequestId());
        if (teamGrabAsNormalOrder != null && !sameTeamOrderPayload(request, teamGrabAsNormalOrder)) {
            throw new BusinessException(ResultCode.CONFLICT, "组队抢票请求与普通抢票请求冲突");
        }
        OrderListItemResponse grabAsTeamGrabOrder = orderMapper.selectTeamOrderListItemByTeamGrabRequestId(request.getGrabRequestId());
        if (grabAsTeamGrabOrder != null) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与组队抢票请求冲突");
        }
        if (existingTeamOrder != null) {
            validateTeamOrderRetryMatchesGrabRequest(request, existingTeamOrder, payload);
            return loadExistingOrder(existingTeamOrder);
        }
        if (StringUtils.hasText(request.getGrabRequestId())) {
            OrderListItemResponse existingGrabOrder = orderMapper.selectOrderListItemByGrabRequestId(request.getGrabRequestId());
            if (existingGrabOrder != null) {
                validateGrabRetryMatchesTeamRequest(request, existingGrabOrder, payload);
                return loadExistingOrder(existingGrabOrder);
            }
        }

        validateUserExists(request.getUserId());
        TicketSalesQuoteResponse quote = quoteTickets(request.getSessionId(), request.getTicketTypeId(), payload.seatIds, payload.quantity);
        validateTeamAuthorizedPrice(request.getAuthorizedMaxUnitPrice(), quote);
        validateTeamSeatLock(request, payload);

        Order order = buildPendingOrder(request.getUserId(), request.getSessionId(), request.getTicketTypeId(), payload.quantity, quote.getUnitPrice());
        orderMapper.insert(order);

        if (orderSeatMapper != null) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireTime = now.plusMinutes(seatLockMinutes);
            for (Long seatId : payload.seatIds) {
                OrderSeat orderSeat = new OrderSeat();
                orderSeat.setOrderId(order.getId());
                orderSeat.setSessionSeatId(seatId);
                orderSeat.setSessionId(request.getSessionId());
                orderSeat.setTicketTypeId(request.getTicketTypeId());
                orderSeat.setStatus(ORDER_SEAT_LOCKED);
                orderSeat.setSeatLabel(payload.seatLabelsById.get(seatId));
                orderSeat.setLockExpireTime(expireTime);
                orderSeat.setCreateTime(now);
                orderSeat.setUpdateTime(now);
                orderSeatMapper.insert(orderSeat);
            }
        }

        quote.setSeatLabels(String.join(", ", payload.seatLabelsById.values()));
        writeSnapshot(order, quote, request.getGrabRequestId(), null, request.getTicketTypeId(), false,
                SEAT_SELECTION_TEAM, request.getTeamId(), request.getTeamGrabRequestId(), true);
        return order;
    }

    @GlobalTransactional(name = "omni-mark-refunded", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Order markRefunded(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() == STATUS_REFUNDED) {
            return order;
        }
        if (order.getStatus() == STATUS_PAID) {
            order.setStatus(STATUS_REFUNDED);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
            TicketReleasedEvent event = refundTicketsStrict(order);
            markOrderAttendeesStatus(order.getId(), ORDER_ATTENDEE_REFUNDED);
            invalidateElectronicTicketsForRefundedOrder(order);
            publishWaitlistReleaseEvent(event);
            return order;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不允许退款");
    }

    public RefundOptionsResponse getRefundOptions(Long orderId) {
        Order order = getOrderDetail(orderId);
        if (order.getStatus() != STATUS_PAID) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可退款");
        }
        List<OrderSeat> seats = orderSeatMapper.selectRefundableSeatsByOrderId(orderId);
        int refunded = safeCount(orderSeatMapper.countRefundedSeatsByOrderId(orderId));
        int refundable = seats == null || seats.isEmpty() ? order.getQuantity() - refunded : seats.size();

        RefundOptionsResponse response = new RefundOptionsResponse();
        response.setOrderId(orderId);
        response.setTotalQuantity(order.getQuantity());
        response.setRefundedQuantity(refunded);
        response.setRefundableQuantity(Math.max(refundable, 0));
        response.setUnitPrice(order.getAmount().divide(BigDecimal.valueOf(order.getQuantity()), 2, RoundingMode.HALF_UP));
        response.setSeats((seats == null ? Collections.<OrderSeat>emptyList() : seats).stream()
                .map(this::toRefundSeatOption)
                .collect(Collectors.toList()));
        return response;
    }

    public RefundOptionsResponse getUserRefundOptions(Long orderId, Long userId) {
        Order order = getUserOwnedOrder(orderId, userId);
        if (order.getStatus() != STATUS_PAID) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可退款");
        }
        return getRefundOptions(orderId);
    }

    @GlobalTransactional(name = "omni-mark-partial-refunded", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public Order markPartialRefunded(Long orderId, MarkPartialRefundedRequest request) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() != STATUS_PAID) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不允许退款");
        }
        int quantity = requireRefundQuantity(request);
        List<OrderSeat> refundableSeats = orderSeatMapper.selectRefundableSeatsByOrderId(orderId);
        int refunded = safeCount(orderSeatMapper.countRefundedSeatsByOrderId(orderId));
        boolean hasSeats = refundableSeats != null && !refundableSeats.isEmpty();
        int refundable = hasSeats ? refundableSeats.size() : order.getQuantity() - refunded;
        if (quantity > refundable) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可退款票数不足");
        }

        List<OrderSeat> selectedSeats = selectRefundSeats(refundableSeats, request != null ? request.getOrderSeatIds() : null, quantity);
        if (!selectedSeats.isEmpty()) {
            List<Long> selectedIds = selectedSeats.stream().map(OrderSeat::getId).collect(Collectors.toList());
            int updated = orderSeatMapper.updateRefundedStatusByOrderIdAndIds(orderId, selectedIds);
            if (updated != quantity) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "所选票已存在退款申请或已退款");
            }
        } else {
            recordQuantityOnlyRefund(order, quantity);
        }
        TicketReleasedEvent event = refundTickets(order, selectedSeats, quantity);
        markPartialOrderAttendeesRefunded(orderId, selectedSeats, quantity);
        invalidateElectronicTicketsForPartialRefund(order, selectedSeats, quantity);
        publishWaitlistReleaseEvent(event);

        if (refunded + quantity >= order.getQuantity()) {
            order.setStatus(STATUS_REFUNDED);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
        }
        return order;
    }

    public List<Order> listOrders(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }

    public List<OrderListItemResponse> listOrderItems(Long userId) {
        return attachAttendees(orderMapper.selectVisibleOrderListItems(userId));
    }

    public List<OrderListItemResponse> listTrashOrderItems(Long userId) {
        return attachAttendees(orderMapper.selectTrashOrderListItems(userId));
    }

    public void hideOrder(Long id, Long userId) {
        Order order = getUserOwnedOrder(id, userId);
        if (order.getStatus() == STATUS_PAID) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已支付订单退款完成前不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setUserHidden(true);
        order.setUserDeletedAt(now);
        order.setUserDeleteExpiresAt(now.plusDays(7));
        order.setUpdateTime(now);
        orderMapper.updateById(order);
    }

    public void restoreOrder(Long id, Long userId) {
        Order order = getUserOwnedOrder(id, userId);
        order.setUserHidden(false);
        order.setUserDeletedAt(null);
        order.setUserDeleteExpiresAt(null);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    private Order getUserOwnedOrder(Long id, Long userId) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限操作该订单");
        }
        return order;
    }

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private RefundSeatOptionResponse toRefundSeatOption(OrderSeat seat) {
        RefundSeatOptionResponse response = new RefundSeatOptionResponse();
        response.setOrderSeatId(seat.getId());
        response.setSessionSeatId(seat.getSessionSeatId());
        response.setSessionId(seat.getSessionId());
        response.setTicketTypeId(seat.getTicketTypeId());
        response.setSeatLabel(seat.getSeatLabel());
        return response;
    }

    private OrderSeatItemResponse toOrderSeatItemResponse(OrderSeat seat) {
        OrderSeatItemResponse response = new OrderSeatItemResponse();
        response.setOrderSeatId(seat.getId());
        response.setSessionSeatId(seat.getSessionSeatId());
        response.setSessionId(seat.getSessionId());
        response.setTicketTypeId(seat.getTicketTypeId());
        response.setStatus(seat.getStatus());
        response.setSeatLabel(seat.getSeatLabel());
        return response;
    }

    private int requireRefundQuantity(MarkPartialRefundedRequest request) {
        if (request == null || request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款数量不正确");
        }
        return request.getQuantity();
    }

    private List<OrderSeat> selectRefundSeats(List<OrderSeat> refundableSeats, List<Long> orderSeatIds, int quantity) {
        if (refundableSeats == null || refundableSeats.isEmpty()) {
            return Collections.emptyList();
        }
        if (orderSeatIds == null || orderSeatIds.isEmpty()) {
            return refundableSeats.stream().limit(quantity).collect(Collectors.toList());
        }
        if (orderSeatIds.size() != quantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款座位数量不匹配");
        }
        Set<Long> ids = new HashSet<>(orderSeatIds);
        List<OrderSeat> selected = refundableSeats.stream()
                .filter(seat -> ids.contains(seat.getId()))
                .collect(Collectors.toList());
        if (selected.size() != quantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可退款票数不足");
        }
        return selected;
    }

    private void recordQuantityOnlyRefund(Order order, int quantity) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < quantity; i++) {
            OrderSeat refundRecord = new OrderSeat();
            refundRecord.setOrderId(order.getId());
            refundRecord.setSessionId(order.getSessionId());
            refundRecord.setTicketTypeId(order.getTicketTypeId());
            refundRecord.setStatus(ORDER_SEAT_REFUNDED);
            refundRecord.setCreateTime(now);
            refundRecord.setUpdateTime(now);
            orderSeatMapper.insert(refundRecord);
        }
    }

    public Long countPaidOrdersBySessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0L;
        }
        Long count = orderMapper.countPaidOrdersBySessions(sessionIds);
        return count != null ? count : 0L;
    }

    public List<Order> listPaidOrdersBySessions(List<Long> sessionIds) {
        return listOrdersBySessions(sessionIds, true);
    }

    public List<Order> listOrdersBySessions(List<Long> sessionIds, boolean paidOnly) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Order::getSessionId, sessionIds);
        if (paidOnly) {
            wrapper.eq(Order::getStatus, STATUS_PAID);
        }
        wrapper.orderByAsc(Order::getId);
        return orderMapper.selectList(wrapper);
    }

    public List<OrderListItemResponse> listOrderItemsBySessions(List<Long> sessionIds, boolean paidOnly) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return attachAttendees(orderMapper.selectOrderListItemsBySessions(sessionIds, paidOnly));
    }

    public SessionSeatUsageResponse inspectSessionSeatUsage(List<Long> sessionSeatIds) {
        if (sessionSeatIds == null || sessionSeatIds.isEmpty()) {
            return new SessionSeatUsageResponse(Collections.emptyList());
        }
        List<Long> ids = sessionSeatIds.stream()
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new SessionSeatUsageResponse(Collections.emptyList());
        }

        List<OrderSeat> orderSeats = orderSeatMapper.selectList(new LambdaQueryWrapper<OrderSeat>()
                .in(OrderSeat::getSessionSeatId, ids));
        Map<Long, Order> ordersById = loadOrdersById(orderSeats);
        Map<Long, OrderSeat> usedSeats = new LinkedHashMap<>();
        if (orderSeats != null) {
            LocalDateTime now = LocalDateTime.now();
            for (OrderSeat orderSeat : orderSeats) {
                if (orderSeat.getSessionSeatId() != null && isOccupyingSeat(orderSeat, ordersById.get(orderSeat.getOrderId()), now)) {
                    usedSeats.putIfAbsent(orderSeat.getSessionSeatId(), orderSeat);
                }
            }
        }

        List<SessionSeatUsageItemResponse> seats = new ArrayList<>();
        for (Long id : ids) {
            OrderSeat orderSeat = usedSeats.get(id);
            if (orderSeat != null) {
                seats.add(new SessionSeatUsageItemResponse(id, true, false, orderSeat.getOrderId(), orderSeat.getStatus()));
            } else {
                seats.add(new SessionSeatUsageItemResponse(id, false, true, null, null));
            }
        }
        return new SessionSeatUsageResponse(seats);
    }

    private Map<Long, Order> loadOrdersById(List<OrderSeat> orderSeats) {
        if (orderSeats == null || orderSeats.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> orderIds = orderSeats.stream()
                .map(OrderSeat::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Order> orders = orderMapper.selectBatchIds(orderIds);
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyMap();
        }
        return orders.stream()
                .filter(order -> order.getId() != null)
                .collect(Collectors.toMap(Order::getId, order -> order, (first, second) -> first));
    }

    private boolean isOccupyingSeat(OrderSeat orderSeat, Order order, LocalDateTime now) {
        if (orderSeat == null || order == null || orderSeat.getStatus() == null) {
            return false;
        }
        if (orderSeat.getStatus() == ORDER_SEAT_LOCKED) {
            return order.getStatus() == STATUS_PENDING
                    && (orderSeat.getLockExpireTime() == null || orderSeat.getLockExpireTime().isAfter(now));
        }
        return orderSeat.getStatus() == ORDER_SEAT_SOLD && order.getStatus() == STATUS_PAID;
    }

    private List<OrderListItemResponse> attachAttendees(List<OrderListItemResponse> orders) {
        if (orders == null || orders.isEmpty() || orderAttendeeMapper == null) {
            return orders;
        }
        List<Long> orderIds = orders.stream()
                .map(OrderListItemResponse::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return orders;
        }
        List<OrderAttendee> attendees = orderAttendeeMapper.selectByOrderIds(orderIds);
        Map<Long, List<OrderAttendeeResponse>> attendeesByOrderId = attendees == null ? Collections.emptyMap()
                : attendees.stream()
                .map(this::toOrderAttendeeResponse)
                .collect(Collectors.groupingBy(OrderAttendeeResponse::getOrderId));
        for (OrderListItemResponse order : orders) {
            order.setAttendees(attendeesByOrderId.getOrDefault(order.getId(), Collections.emptyList()));
        }
        return orders;
    }

    private OrderAttendeeResponse toOrderAttendeeResponse(OrderAttendee attendee) {
        OrderAttendeeResponse response = new OrderAttendeeResponse();
        response.setId(attendee.getId());
        response.setOrderId(attendee.getOrderId());
        response.setOrderSeatId(attendee.getOrderSeatId());
        response.setAttendeeUserProfileId(attendee.getAttendeeUserProfileId());
        response.setRealName(attendee.getRealName());
        response.setIdType(attendee.getIdType());
        response.setIdNoMask(attendee.getIdNoMask());
        response.setPhone(attendee.getPhone());
        response.setStatus(attendee.getStatus());
        return response;
    }

    public Order getOrderDetail(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    public Order getUserOrderDetail(Long id, Long userId) {
        return getUserOwnedOrder(id, userId);
    }

    public OrderListItemResponse getOrderItemDetail(Long id) {
        OrderListItemResponse order = orderMapper.selectOrderListItemById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        attachAttendees(List.of(order));
        return order;
    }

    @Transactional(readOnly = true)
    public OrderListItemResponse findOrderByGrabRequestId(String grabRequestId) {
        if (!StringUtils.hasText(grabRequestId)) {
            return null;
        }
        orderMapper.acquireAdvisoryTransactionLock("grab-order:" + grabRequestId);
        OrderListItemResponse order = orderMapper.selectOrderListItemByGrabRequestId(grabRequestId);
        if (order != null) {
            attachAttendees(List.of(order));
        }
        return order;
    }

    public List<OrderSeatItemResponse> listInternalOrderSeats(Long orderId) {
        if (orderId == null) {
            return Collections.emptyList();
        }
        List<OrderSeat> seats = orderSeatMapper.selectLockedAndSoldSeatsByOrderId(orderId);
        if (seats == null || seats.isEmpty()) {
            return Collections.emptyList();
        }
        return seats.stream()
                .map(this::toOrderSeatItemResponse)
                .collect(Collectors.toList());
    }

    @GlobalTransactional(name = "omni-cancel-order", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long id) {
        cancelOrderInternal(id, null);
    }

    @GlobalTransactional(name = "omni-cancel-user-order", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void cancelUserOrder(Long id, Long userId) {
        cancelOrderInternal(id, userId);
    }

    private void cancelOrderInternal(Long id, Long userId) {
        Order order = orderMapper.selectById(id);
        if (userId != null && order != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限操作该订单");
        }
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只能取消待支付状态的订单");
        }
        assertPendingOrderSafeToCancel(order);
        cancelPendingOrderOrThrow(order);
        releaseLockedResourcesStrict(order);
        markOrderAttendeesStatus(order.getId(), ORDER_ATTENDEE_CANCELLED);
        log.info("订单已取消: orderNo={}", order.getOrderNo());
    }

    @Transactional
    public int releaseExpiredSeatLocks() {
        return releaseExpiredSeatLocksDetailed().size();
    }

    @Transactional
    public List<TicketReleasedEvent> releaseExpiredSeatLocksDetailed() {
        if (orderSeatMapper == null) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        List<TicketReleasedEvent> events = new ArrayList<>();
        List<OrderSeat> expiredSeats = orderSeatMapper.selectList(new LambdaQueryWrapper<OrderSeat>()
                .eq(OrderSeat::getStatus, ORDER_SEAT_LOCKED)
                .le(OrderSeat::getLockExpireTime, now));
        if (expiredSeats != null && !expiredSeats.isEmpty()) {
            List<Long> expiredOrderIds = expiredSeats.stream()
                    .map(OrderSeat::getOrderId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            Map<Long, Order> expiredOrderById = expiredOrderIds.isEmpty()
                    ? Collections.emptyMap()
                    : orderMapper.selectBatchIds(expiredOrderIds).stream()
                    .collect(Collectors.toMap(Order::getId, order -> order, (a, b) -> a));
            for (OrderSeat orderSeat : expiredSeats) {
                Order order = expiredOrderById.get(orderSeat.getOrderId());
                if (order == null) {
                    continue;
                }
                if (order.getStatus() == STATUS_PENDING) {
                    try {
                        assertPendingOrderSafeToCancel(order);
                        cancelPendingOrderOrThrow(order);
                    } catch (BusinessException e) {
                        log.warn("过期锁释放前确认支付状态失败，跳过释放: orderId={}, orderNo={}, message={}", order.getId(), order.getOrderNo(), e.getMessage());
                        continue;
                    }
                } else if (order.getStatus() != STATUS_CANCELLED) {
                    continue;
                }
                TicketReleasedEvent event = releaseSingleLockedSeat(orderSeat);
                markOrderAttendeesStatus(order.getId(), ORDER_ATTENDEE_CANCELLED);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        events.addAll(releaseExpiredPendingOrders(now.minusMinutes(seatLockMinutes)));
        return events;
    }

    private List<TicketReleasedEvent> releaseExpiredPendingOrders(LocalDateTime expireBefore) {
        List<Order> expiredOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, STATUS_PENDING)
                .le(Order::getCreateTime, expireBefore));
        if (expiredOrders == null || expiredOrders.isEmpty()) {
            return Collections.emptyList();
        }
        List<TicketReleasedEvent> events = new ArrayList<>();
        for (Order order : expiredOrders) {
            try {
                assertPendingOrderSafeToCancel(order);
                cancelPendingOrderOrThrow(order);
                TicketReleasedEvent event = releaseLockedResourcesBestEffort(order);
                markOrderAttendeesStatus(order.getId(), ORDER_ATTENDEE_CANCELLED);
                if (event != null) {
                    events.add(event);
                }
            } catch (BusinessException e) {
                log.warn("过期待支付订单释放前确认支付状态失败，跳过释放: orderId={}, orderNo={}, message={}", order.getId(), order.getOrderNo(), e.getMessage());
            }
        }
        return events;
    }

    private void writeSnapshot(Order order,
                               TicketSalesQuoteResponse quote,
                               String grabRequestId,
                               Long requestedTicketTypeId,
                               Long matchedTicketTypeId,
                               Boolean autoDowngraded,
                               String seatSelectionMode) {
        writeSnapshot(order, quote, grabRequestId, requestedTicketTypeId, matchedTicketTypeId, autoDowngraded,
                seatSelectionMode, null, null, false);
    }

    private void writeSnapshot(Order order,
                               TicketSalesQuoteResponse quote,
                               String grabRequestId,
                               Long requestedTicketTypeId,
                               Long matchedTicketTypeId,
                               Boolean autoDowngraded,
                               String seatSelectionMode,
                               Long teamId,
                               String teamGrabRequestId,
                               Boolean teamOrder) {
        if (orderSnapshotMapper == null || order == null || quote == null) {
            return;
        }
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setOrderId(order.getId());
        snapshot.setActivityId(quote.getActivityId());
        snapshot.setActivityName(quote.getActivityName());
        snapshot.setActivityPoster(quote.getActivityPoster());
        snapshot.setTourId(quote.getTourId());
        snapshot.setStationId(quote.getStationId());
        snapshot.setSessionId(order.getSessionId());
        snapshot.setSessionTime(quote.getSessionTime());
        snapshot.setVenueName(quote.getVenueName());
        snapshot.setTicketTypeId(order.getTicketTypeId());
        snapshot.setTicketName(quote.getTicketName());
        snapshot.setUnitPrice(quote.getUnitPrice());
        snapshot.setQuantity(order.getQuantity());
        snapshot.setSeatLabels(quote.getSeatLabels());
        snapshot.setGrabRequestId(grabRequestId);
        snapshot.setRequestedTicketTypeId(requestedTicketTypeId);
        snapshot.setMatchedTicketTypeId(matchedTicketTypeId);
        snapshot.setAutoDowngraded(Boolean.TRUE.equals(autoDowngraded));
        snapshot.setTeamId(teamId);
        snapshot.setTeamGrabRequestId(teamGrabRequestId);
        snapshot.setTeamOrder(Boolean.TRUE.equals(teamOrder));
        snapshot.setSeatSelectionMode(seatSelectionMode);
        snapshot.setTicketTransferAllowed(!Boolean.FALSE.equals(quote.getTicketTransferAllowed()));
        snapshot.setRefundFullDaysBefore(quote.getRefundFullDaysBefore());
        snapshot.setRefundPartialDaysBefore(quote.getRefundPartialDaysBefore());
        snapshot.setRefundPartialRate(quote.getRefundPartialRate());
        LocalDateTime now = LocalDateTime.now();
        snapshot.setCreateTime(now);
        snapshot.setUpdateTime(now);
        orderSnapshotMapper.insert(snapshot);
    }

    // --- Internal helpers ---

    private void validateUserExists(Long userId) {
        if (userInternalClient == null) {
            return;
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        String token = requireInternalApiToken("用户服务接口令牌未配置");
        Result<InternalUserRefResponse> result;
        try {
            result = callUserValidate(() -> userInternalClient.getUserRef(userId, token));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("用户服务调用失败: userId={}", userId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "用户服务无响应");
        }
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        Integer status = result.getData().getStatus();
        if (status == null || status != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户状态不可用");
        }
    }

    private int requirePositiveQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "购买数量不正确");
        }
        return quantity;
    }

    private void validateAuthorizedPrice(BigDecimal authorizedMaxUnitPrice, TicketSalesQuoteResponse quote, String grabRequestId) {
        if (authorizedMaxUnitPrice == null) {
            if (StringUtils.hasText(grabRequestId) && !isWaitlistGrabRequestId(grabRequestId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "抢票订单缺少授权价格");
            }
            return;
        }
        if (quote == null || quote.getUnitPrice() == null) {
            return;
        }
        if (quote.getUnitPrice().compareTo(authorizedMaxUnitPrice) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票价超过授权价格");
        }
    }

    private void validateTeamOrderRequestBasics(CreateTeamOrderRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单请求不能为空");
        }
        if (request.getUserId() == null || request.getPayerUserId() == null
                || !request.getUserId().equals(request.getPayerUserId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "队长支付订单的付款人必须与下单用户一致");
        }
        if (request.getSessionId() == null || request.getTicketTypeId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单场次和票档不能为空");
        }
        if (!StringUtils.hasText(request.getTeamGrabRequestId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队抢票请求标识不能为空");
        }
        if (request.getTeamId() == null || request.getTeamId() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单缺少队伍标识");
        }
        if (!StringUtils.hasText(request.getGrabRequestId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单缺少抢票请求标识");
        }
        if (request.getTeamGrabRequestId().equals(request.getGrabRequestId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队抢票请求标识不能与普通抢票请求标识相同");
        }
    }

    private void lockTeamGrabNamespaces(String grabRequestId, String teamGrabRequestId) {
        List<String> grabOrderLockKeys = List.of(
                "grab-order:" + grabRequestId,
                "grab-order:" + teamGrabRequestId);
        grabOrderLockKeys.stream()
                .distinct()
                .sorted()
                .forEach(orderMapper::acquireAdvisoryTransactionLock);
    }

    private TeamOrderSeatPayload validateAndParseTeamOrderRequest(CreateTeamOrderRequest request) {
        validateTeamOrderRequestBasics(request);
        int quantity = requirePositiveQuantity(request.getQuantity());
        List<CreateTeamOrderRequest.TeamOrderSeatItem> seats = request.getSeats();
        if (seats == null || seats.isEmpty() || seats.size() != quantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单座位数量不一致");
        }

        Map<Long, String> seatLabelsById = new LinkedHashMap<>();
        for (CreateTeamOrderRequest.TeamOrderSeatItem seat : seats) {
            if (seat == null || seat.getSessionSeatId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单座位标识不能为空");
            }
            if (!StringUtils.hasText(seat.getSeatLabel())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单座位标签不能为空");
            }
            if (seatLabelsById.put(seat.getSessionSeatId(), seat.getSeatLabel()) != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单座位不能重复");
            }
        }
        if (request.getAuthorizedMaxUnitPrice() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单缺少授权价格");
        }
        return new TeamOrderSeatPayload(quantity, seatLabelsById);
    }

    private Order resolveExistingNormalGrabOrder(CreateOrderRequest request, int quantity) {
        return resolveExistingNormalGrabOrder(
                request.getUserId(),
                request.getSessionId(),
                request.getTicketTypeId(),
                quantity,
                request.getGrabRequestId(),
                request.getAuthorizedMaxUnitPrice(),
                request.getRequestedTicketTypeId(),
                request.getMatchedTicketTypeId(),
                request.getAutoDowngraded(),
                SEAT_SELECTION_NONE,
                null);
    }

    private Order resolveExistingNormalGrabOrder(LockSeatsRequest request, int quantity, String seatSelectionMode) {
        return resolveExistingNormalGrabOrder(
                request.getUserId(),
                request.getSessionId(),
                request.getTicketTypeId(),
                quantity,
                request.getGrabRequestId(),
                request.getAuthorizedMaxUnitPrice(),
                request.getRequestedTicketTypeId(),
                request.getMatchedTicketTypeId(),
                request.getAutoDowngraded(),
                seatSelectionMode,
                request.getSeatIds());
    }

    private Order resolveExistingNormalGrabOrder(Long userId,
                                                 Long sessionId,
                                                 Long ticketTypeId,
                                                 int quantity,
                                                 String grabRequestId,
                                                 BigDecimal authorizedMaxUnitPrice,
                                                 Long requestedTicketTypeId,
                                                 Long matchedTicketTypeId,
                                                 Boolean autoDowngraded,
                                                 String seatSelectionMode,
                                                 List<Long> requestedSeatIds) {
        if (!StringUtils.hasText(grabRequestId)) {
            return null;
        }
        orderMapper.acquireAdvisoryTransactionLock("grab-order:" + grabRequestId);
        OrderListItemResponse existingOrder = orderMapper.selectOrderListItemByGrabRequestId(grabRequestId);
        OrderListItemResponse existingTeamGrabOrder = orderMapper.selectTeamOrderListItemByTeamGrabRequestId(grabRequestId);
        if (existingTeamGrabOrder != null) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与组队抢票请求冲突");
        }
        if (existingOrder == null) {
            return null;
        }
        if (Boolean.TRUE.equals(existingOrder.getTeamOrder())) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求已关联组队订单");
        }
        if (!sameOrderPayload(existingOrder, userId, sessionId, ticketTypeId, quantity)
                || !grabRequestId.equals(existingOrder.getGrabRequestId())
                || !sameGrabRetrySnapshotPayload(existingOrder, requestedTicketTypeId, matchedTicketTypeId, autoDowngraded)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        Order loadedOrder = validateExistingNormalOrderAuthorizedPrice(authorizedMaxUnitPrice, existingOrder, grabRequestId);
        validateExistingNormalOrderSeats(seatSelectionMode, requestedSeatIds, existingOrder);
        return loadedOrder != null ? loadedOrder : loadExistingOrder(existingOrder);
    }

    private void validateTeamAuthorizedPrice(BigDecimal authorizedMaxUnitPrice, TicketSalesQuoteResponse quote) {
        if (authorizedMaxUnitPrice == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队订单缺少授权价格");
        }
        validateAuthorizedPrice(authorizedMaxUnitPrice, quote, "team-order");
    }

    private void validateTeamOrderRetryMatchesGrabRequest(CreateTeamOrderRequest request,
                                                          OrderListItemResponse existingOrder,
                                                          TeamOrderSeatPayload payload) {
        if (!sameTeamOrderPayload(request, existingOrder)) {
            throw new BusinessException(ResultCode.CONFLICT, "组队订单重试与抢票请求冲突");
        }
        validateExistingTeamOrderSeats(payload, existingOrder);
        validateExistingTeamOrderAuthorizedPrice(request.getAuthorizedMaxUnitPrice(), existingOrder);
    }

    private void validateGrabRetryMatchesTeamRequest(CreateTeamOrderRequest request,
                                                     OrderListItemResponse existingOrder,
                                                     TeamOrderSeatPayload payload) {
        if (!sameTeamOrderPayload(request, existingOrder)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求已关联其他订单");
        }
        validateExistingTeamOrderSeats(payload, existingOrder);
        validateExistingTeamOrderAuthorizedPrice(request.getAuthorizedMaxUnitPrice(), existingOrder);
    }

    private boolean sameTeamOrderPayload(CreateTeamOrderRequest request, OrderListItemResponse existingOrder) {
        if (!Boolean.TRUE.equals(existingOrder.getTeamOrder())
                || !teamSeatSelectionModeMatches(existingOrder)
                || !Objects.equals(request.getTeamGrabRequestId(), existingOrder.getTeamGrabRequestId())
                || !Objects.equals(request.getGrabRequestId(), existingOrder.getGrabRequestId())
                || !sameOrderPayload(existingOrder, request.getUserId(), request.getSessionId(), request.getTicketTypeId(), request.getQuantity())) {
            return false;
        }
        return Objects.equals(request.getTeamId(), existingOrder.getTeamId());
    }

    private boolean teamSeatSelectionModeMatches(OrderListItemResponse existingOrder) {
        return SEAT_SELECTION_TEAM.equals(existingOrder.getSeatSelectionMode()) || existingOrder.getSeatSelectionMode() == null;
    }

    private boolean sameGrabRetrySnapshotPayload(OrderListItemResponse existingOrder,
                                                 Long requestedTicketTypeId,
                                                 Long matchedTicketTypeId,
                                                 Boolean autoDowngraded) {
        return Objects.equals(requestedTicketTypeId, existingOrder.getRequestedTicketTypeId())
                && Objects.equals(matchedTicketTypeId, existingOrder.getMatchedTicketTypeId())
                && Objects.equals(autoDowngraded, existingOrder.getAutoDowngraded());
    }

    private boolean sameOrderPayload(OrderListItemResponse existingOrder,
                                     Long userId,
                                     Long sessionId,
                                     Long ticketTypeId,
                                     Integer quantity) {
        return Objects.equals(userId, existingOrder.getUserId())
                && Objects.equals(sessionId, existingOrder.getSessionId())
                && Objects.equals(ticketTypeId, existingOrder.getTicketTypeId())
                && Objects.equals(quantity, existingOrder.getQuantity());
    }

    private void validateExistingNormalOrderSeats(String seatSelectionMode,
                                                  List<Long> requestedSeatIds,
                                                  OrderListItemResponse existingOrder) {
        String existingMode = existingOrder.getSeatSelectionMode();
        if (existingMode == null) {
            validateNullExistingNormalOrderSeatMode(seatSelectionMode, requestedSeatIds, existingOrder);
            return;
        }
        if (!Objects.equals(seatSelectionMode, existingMode)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        if (SEAT_SELECTION_NONE.equals(seatSelectionMode) || SEAT_SELECTION_RANDOM.equals(seatSelectionMode)) {
            return;
        }
        if (requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        Set<Long> requested = new HashSet<>(requestedSeatIds);
        if (requested.size() != requestedSeatIds.size()) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        List<OrderSeat> existingSeats = orderSeatMapper.selectLockedAndSoldSeatsByOrderId(existingOrder.getId());
        Set<Long> existing = toSessionSeatIdSet(existingSeats);
        if (existing.size() != existingSeatsSize(existingSeats) || !existing.equals(requested)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
    }

    private void validateNullExistingNormalOrderSeatMode(String seatSelectionMode,
                                                         List<Long> requestedSeatIds,
                                                         OrderListItemResponse existingOrder) {
        List<OrderSeat> existingSeats = orderSeatMapper.selectLockedAndSoldSeatsByOrderId(existingOrder.getId());
        if (SEAT_SELECTION_RANDOM.equals(seatSelectionMode)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        if (SEAT_SELECTION_NONE.equals(seatSelectionMode)) {
            if (existingSeatsSize(existingSeats) != 0) {
                throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
            }
            return;
        }
        if (!SEAT_SELECTION_EXPLICIT.equals(seatSelectionMode)
                || requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        Set<Long> requested = new HashSet<>(requestedSeatIds);
        if (requested.size() != requestedSeatIds.size()) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
        Set<Long> existing = toSessionSeatIdSet(existingSeats);
        if (existing.size() != existingSeatsSize(existingSeats) || !existing.equals(requested)) {
            throw new BusinessException(ResultCode.CONFLICT, "抢票请求与当前订单意图不一致");
        }
    }

    private void validateExistingTeamOrderSeats(TeamOrderSeatPayload payload, OrderListItemResponse existingOrder) {
        List<OrderSeat> existingSeats = orderSeatMapper.selectLockedAndSoldSeatsByOrderId(existingOrder.getId());
        Map<Long, String> existingLabelsById = new LinkedHashMap<>();
        if (existingSeats != null) {
            for (OrderSeat seat : existingSeats) {
                if (seat == null || seat.getSessionSeatId() == null
                        || existingLabelsById.put(seat.getSessionSeatId(), seat.getSeatLabel()) != null) {
                    throw new BusinessException(ResultCode.CONFLICT, "组队订单重试与座位信息冲突");
                }
            }
        }
        if (!existingLabelsById.equals(payload.seatLabelsById)) {
            throw new BusinessException(ResultCode.CONFLICT, "组队订单重试与座位信息冲突");
        }
    }

    private void validateExistingTeamOrderAuthorizedPrice(BigDecimal authorizedMaxUnitPrice,
                                                          OrderListItemResponse existingOrder) {
        BigDecimal existingUnitPrice = existingOrder.getUnitPrice();
        if (existingUnitPrice == null) {
            throw new BusinessException(ResultCode.CONFLICT, "已有订单价格不一致");
        }
        if (authorizedMaxUnitPrice.compareTo(existingUnitPrice) < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票价超过授权价格");
        }
    }

    private Order validateExistingNormalOrderAuthorizedPrice(BigDecimal authorizedMaxUnitPrice,
                                                             OrderListItemResponse existingOrder,
                                                             String grabRequestId) {
        if (authorizedMaxUnitPrice == null) {
            if (isWaitlistGrabRequestId(grabRequestId)) {
                return null;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "抢票订单缺少授权价格");
        }
        BigDecimal existingUnitPrice = existingOrder.getUnitPrice();
        Order loadedOrder = null;
        if (existingUnitPrice == null) {
            loadedOrder = loadExistingOrder(existingOrder);
            existingUnitPrice = deriveUnitPriceFromOrder(loadedOrder);
        }
        if (existingUnitPrice == null) {
            throw new BusinessException(ResultCode.CONFLICT, "已有订单价格不一致");
        }
        if (authorizedMaxUnitPrice.compareTo(existingUnitPrice) < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票价超过授权价格");
        }
        return loadedOrder;
    }

    private boolean isWaitlistGrabRequestId(String grabRequestId) {
        return StringUtils.hasText(grabRequestId) && grabRequestId.startsWith("WAITLIST-");
    }

    private BigDecimal deriveUnitPriceFromOrder(Order order) {
        if (order == null || order.getAmount() == null || order.getQuantity() == null || order.getQuantity() <= 0) {
            return null;
        }
        return order.getAmount().divide(BigDecimal.valueOf(order.getQuantity()), 2, RoundingMode.HALF_UP);
    }

    private Set<Long> toSessionSeatIdSet(List<OrderSeat> seats) {
        if (seats == null || seats.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> seatIds = new HashSet<>();
        for (OrderSeat seat : seats) {
            if (seat == null || seat.getSessionSeatId() == null) {
                return Collections.emptySet();
            }
            seatIds.add(seat.getSessionSeatId());
        }
        return seatIds;
    }

    private int existingSeatsSize(List<OrderSeat> seats) {
        return seats == null ? 0 : seats.size();
    }

    private Order loadExistingOrder(OrderListItemResponse existingOrder) {
        Order order = existingOrder != null && existingOrder.getId() != null ? orderMapper.selectById(existingOrder.getId()) : null;
        if (order == null) {
            throw new BusinessException(ResultCode.CONFLICT, "已有订单快照不一致");
        }
        return order;
    }

    private TicketSalesQuoteResponse quoteTickets(Long sessionId, Long ticketTypeId, List<Long> seatIds, int quantity) {
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        TicketSalesQuoteRequest request = new TicketSalesQuoteRequest();
        request.setSessionId(sessionId);
        request.setTicketTypeId(ticketTypeId);
        request.setSeatIds(seatIds);
        request.setQuantity(quantity);
        Result<TicketSalesQuoteResponse> result = callTicketSales(() -> ticketSalesInternalClient.quote(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "票务服务无响应");
        }
        return result.getData();
    }

    private List<ResolvedAttendeeResponse> resolveOrderAttendees(Long userId,
                                                                  List<Long> attendeeIds,
                                                                  TicketSalesQuoteResponse quote,
                                                                  int quantity) {
        if (attendeeIds == null || attendeeIds.size() != quantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择实名观演人");
        }
        if (userInternalClient == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "用户服务实名观演人接口未配置");
        }
        String token = requireInternalApiToken("鐢ㄦ埛鏈嶅姟鎺ュ彛浠ょ墝鏈厤缃?");
        Result<List<ResolvedAttendeeResponse>> result = callUserValidate(() ->
                userInternalClient.resolveAttendees(new ResolveAttendeesRequest(userId, attendeeIds), token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "实名观演人校验失败");
        }
        List<ResolvedAttendeeResponse> attendees = result.getData();
        if (attendees.size() != quantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择实名观演人");
        }
        Set<String> identities = new HashSet<>();
        for (ResolvedAttendeeResponse attendee : attendees) {
            if (attendee == null || !StringUtils.hasText(attendee.getIdType()) || !StringUtils.hasText(attendee.getIdNoHash())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "实名观演人信息不完整");
            }
            String identity = attendee.getIdType() + ":" + attendee.getIdNoHash();
            if (!identities.add(identity)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "同一订单实名观演人不能重复");
            }
            if (orderAttendeeMapper != null) {
                orderMapper.acquireAdvisoryTransactionLock("order-attendee:" + quote.getSessionId() + ":" + identity);
                Long existing = orderAttendeeMapper.countActiveBySessionIdentity(
                        quote.getSessionId(), attendee.getIdType(), attendee.getIdNoHash());
                if (existing != null && existing > 0) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "该观演人已购买本场次门票");
                }
            }
        }
        return attendees;
    }

    private void writeOrderAttendees(Order order, List<ResolvedAttendeeResponse> attendees, List<Long> orderSeatIds) {
        if (orderAttendeeMapper == null || attendees == null || attendees.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < attendees.size(); i++) {
            ResolvedAttendeeResponse attendee = attendees.get(i);
            OrderAttendee snapshot = new OrderAttendee();
            snapshot.setOrderId(order.getId());
            snapshot.setOrderSeatId(orderSeatIds != null && i < orderSeatIds.size() ? orderSeatIds.get(i) : null);
            snapshot.setUserId(order.getUserId());
            snapshot.setSessionId(order.getSessionId());
            snapshot.setTicketTypeId(order.getTicketTypeId());
            snapshot.setAttendeeUserProfileId(attendee.getId());
            snapshot.setRealName(attendee.getRealName());
            snapshot.setIdType(attendee.getIdType());
            snapshot.setIdNoHash(attendee.getIdNoHash());
            snapshot.setIdNoMask(attendee.getIdNoMask());
            snapshot.setIdNoEncrypted(attendee.getIdNoEncrypted());
            snapshot.setPhone(attendee.getPhone());
            snapshot.setStatus(ORDER_ATTENDEE_ACTIVE);
            snapshot.setCreateTime(now);
            snapshot.setUpdateTime(now);
            orderAttendeeMapper.insert(snapshot);
        }
    }

    private void markOrderAttendeesStatus(Long orderId, int status) {
        if (orderAttendeeMapper == null || orderId == null) {
            return;
        }
        orderAttendeeMapper.updateStatusByOrderId(orderId, status);
    }

    private void markPartialOrderAttendeesRefunded(Long orderId, List<OrderSeat> selectedSeats, int quantity) {
        if (orderAttendeeMapper == null || orderId == null || quantity <= 0) {
            return;
        }
        List<Long> orderSeatIds = selectedSeats == null ? Collections.emptyList()
                : selectedSeats.stream().map(OrderSeat::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (!orderSeatIds.isEmpty()) {
            orderAttendeeMapper.updateStatusByOrderSeatIds(orderId, orderSeatIds, ORDER_ATTENDEE_REFUNDED);
            return;
        }
        List<OrderAttendee> attendees = orderAttendeeMapper.selectByOrderIds(List.of(orderId));
        if (attendees == null || attendees.isEmpty()) {
            return;
        }
        int changed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (OrderAttendee attendee : attendees) {
            if (changed >= quantity) {
                break;
            }
            if (Integer.valueOf(ORDER_ATTENDEE_ACTIVE).equals(attendee.getStatus())) {
                attendee.setStatus(ORDER_ATTENDEE_REFUNDED);
                attendee.setUpdateTime(now);
                orderAttendeeMapper.updateById(attendee);
                changed++;
            }
        }
    }

    private void lockStockForOrder(Order order) {
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        TicketSalesLockRequest request = new TicketSalesLockRequest();
        request.setOrderId(order.getId() != null ? order.getId() : 0L);
        request.setSessionId(order.getSessionId());
        request.setTicketTypeId(order.getTicketTypeId());
        request.setQuantity(order.getQuantity());
        Result<Void> result = callTicketSales(() -> ticketSalesInternalClient.lockStock(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "票务服务无响应");
        }
    }

    private void lockStockForTicketType(Long ticketTypeId, int quantity) {
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        TicketSalesLockRequest request = new TicketSalesLockRequest();
        request.setOrderId(0L);
        request.setTicketTypeId(ticketTypeId);
        request.setQuantity(quantity);
        Result<Void> result = callTicketSales(() -> ticketSalesInternalClient.lockStock(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "票务服务无响应");
        }
    }

    private TicketSalesSeatLockResponse lockSeats(TicketSalesLockRequest lockRequest) {
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<TicketSalesSeatLockResponse> result = callTicketSales(() -> ticketSalesInternalClient.lockSeats(lockRequest, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "票务服务无响应");
        }
        return result.getData();
    }

    private void validateTeamSeatLock(CreateTeamOrderRequest request, TeamOrderSeatPayload payload) {
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        TicketSalesLockRequest validationRequest = new TicketSalesLockRequest();
        validationRequest.setSessionId(request.getSessionId());
        validationRequest.setTicketTypeId(request.getTicketTypeId());
        validationRequest.setSeatIds(payload.seatIds);
        validationRequest.setLockRequestId(request.getTeamGrabRequestId());
        Result<TicketSalesSeatLockResponse> result = callTicketSales(
                () -> ticketSalesInternalClient.validateTeamSeatLock(validationRequest, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, result != null ? result.getMessage() : "票务服务无响应");
        }
        TicketSalesSeatLockResponse response = result.getData();
        List<Long> validatedSeatIds = validatedSeatIds(response);
        if (!Boolean.TRUE.equals(response.getValid()) || !sameSeatIds(payload.seatIds, validatedSeatIds)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座不属于当前请求");
        }
        Map<Long, String> validatedLabelsById = buildRequiredSeatLabelMap(validatedSeatIds, response.getSeatLabels(),
                "组队锁座标签与座位数量不一致");
        if (!payload.seatLabelsById.equals(validatedLabelsById)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座标签不属于当前请求");
        }
    }

    private List<Long> validatedSeatIds(TicketSalesSeatLockResponse response) {
        if (response.getSeatIds() != null) {
            return response.getSeatIds();
        }
        return response.getLockedSeatIds();
    }

    private Map<Long, String> buildSeatLabelMap(List<Long> seatIds, List<String> seatLabels, String mismatchMessage) {
        if (seatLabels == null || seatLabels.isEmpty()) {
            return Collections.emptyMap();
        }
        return buildRequiredSeatLabelMap(seatIds, seatLabels, mismatchMessage);
    }

    private Map<Long, String> buildRequiredSeatLabelMap(List<Long> seatIds, List<String> seatLabels, String mismatchMessage) {
        if (seatIds == null || seatLabels == null || seatIds.size() != seatLabels.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, mismatchMessage);
        }
        Map<Long, String> labelsById = new LinkedHashMap<>();
        for (int i = 0; i < seatIds.size(); i++) {
            labelsById.put(seatIds.get(i), seatLabels.get(i));
        }
        return labelsById;
    }

    private boolean sameSeatIds(List<Long> expected, List<Long> actual) {
        if (expected == null || actual == null || expected.size() != actual.size()) {
            return false;
        }
        return new HashSet<>(expected).equals(new HashSet<>(actual));
    }

    private void confirmTicketsSold(Order order) {
        if (orderSeatMapper == null) {
            return;
        }
        List<OrderSeat> orderSeats = orderSeatMapper.selectList(new LambdaQueryWrapper<OrderSeat>()
                .eq(OrderSeat::getOrderId, order.getId())
                .in(OrderSeat::getStatus, ORDER_SEAT_LOCKED, ORDER_SEAT_SOLD));
        List<OrderSeat> lockedSeats = new ArrayList<>();
        if (orderSeats != null) {
            for (OrderSeat orderSeat : orderSeats) {
                if (orderSeat.getStatus() != null && orderSeat.getStatus() == ORDER_SEAT_LOCKED) {
                    lockedSeats.add(orderSeat);
                }
            }
        }
        if (orderSeats != null && !orderSeats.isEmpty() && lockedSeats.isEmpty()) {
            return;
        }
        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(order.getId());
        request.setSessionId(order.getSessionId());
        request.setTicketTypeId(order.getTicketTypeId());
        request.setQuantity(order.getQuantity());
        if (!lockedSeats.isEmpty()) {
            request.setSeatIds(lockedSeats.stream().map(OrderSeat::getSessionSeatId).collect(Collectors.toList()));
            request.setLockRequestId(resolveTeamLockRequestId(order.getId()));
        }
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<Void> result = callTicketSales(() -> ticketSalesInternalClient.confirmSold(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, result != null ? result.getMessage() : "票务服务无响应");
        }
        if (!lockedSeats.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (OrderSeat orderSeat : lockedSeats) {
                orderSeat.setStatus(ORDER_SEAT_SOLD);
                orderSeat.setUpdateTime(now);
                orderSeatMapper.updateById(orderSeat);
            }
        }
    }

    private TicketReleasedEvent releaseLockedResourcesBestEffort(Order order) {
        return releaseLockedResources(order, false);
    }

    private TicketReleasedEvent releaseLockedResourcesStrict(Order order) {
        return releaseLockedResources(order, true);
    }

    private TicketReleasedEvent releaseLockedResources(Order order, boolean strict) {
        if (orderSeatMapper == null || order == null || order.getId() == null) {
            return null;
        }
        List<OrderSeat> orderSeats = orderSeatMapper.selectList(new LambdaQueryWrapper<OrderSeat>()
                .eq(OrderSeat::getOrderId, order.getId())
                .eq(OrderSeat::getStatus, ORDER_SEAT_LOCKED));
        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(order.getId());
        request.setSessionId(order.getSessionId());
        request.setTicketTypeId(order.getTicketTypeId());
        request.setQuantity(order.getQuantity());
        if (orderSeats != null && !orderSeats.isEmpty()) {
            List<Long> seatIds = new ArrayList<>();
            for (OrderSeat orderSeat : orderSeats) {
                seatIds.add(orderSeat.getSessionSeatId());
            }
            request.setSeatIds(seatIds);
            request.setLockRequestId(resolveTeamLockRequestId(order.getId()));
        }
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<TicketSalesReleaseResponse> result = callTicketSales(() -> ticketSalesInternalClient.release(request, token));
        if (result != null && result.getCode() == ResultCode.SUCCESS.getCode()) {
            if (orderSeats != null) {
                LocalDateTime now = LocalDateTime.now();
                for (OrderSeat orderSeat : orderSeats) {
                    orderSeat.setStatus(ORDER_SEAT_RELEASED);
                    orderSeat.setUpdateTime(now);
                    orderSeatMapper.updateById(orderSeat);
                }
            }
            return toTicketReleasedEvent("ORDER_TIMEOUT", orderTimeoutEventKey(order), order.getId(),
                    releaseResponseOrFallback(result.getData(), request));
        } else {
            log.warn("释放票务资源失败: orderId={}", order.getId());
            if (strict) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, result != null ? result.getMessage() : "票务服务无响应");
            }
        }
        return null;
    }

    private TicketReleasedEvent releaseSingleLockedSeat(OrderSeat orderSeat) {
        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(orderSeat.getOrderId());
        request.setSessionId(orderSeat.getSessionId());
        request.setTicketTypeId(orderSeat.getTicketTypeId());
        request.setSeatIds(List.of(orderSeat.getSessionSeatId()));
        request.setQuantity(1);
        request.setLockRequestId(resolveTeamLockRequestId(orderSeat.getOrderId()));
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<TicketSalesReleaseResponse> result = callTicketSales(() -> ticketSalesInternalClient.release(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("释放座位锁失败，票务服务拒绝: orderSeatId={}, sessionSeatId={}, orderId={}",
                    orderSeat.getId(), orderSeat.getSessionSeatId(), orderSeat.getOrderId());
            return null;
        }
        orderSeat.setStatus(ORDER_SEAT_RELEASED);
        orderSeat.setUpdateTime(LocalDateTime.now());
        orderSeatMapper.updateById(orderSeat);
        return toTicketReleasedEvent("ORDER_TIMEOUT", orderSeatTimeoutEventKey(orderSeat), orderSeat.getOrderId(),
                releaseResponseOrFallback(result.getData(), request));
    }

    private String resolveTeamLockRequestId(Long orderId) {
        if (orderSnapshotMapper == null || orderId == null) {
            return null;
        }
        OrderSnapshot snapshot = orderSnapshotMapper.selectOne(new LambdaQueryWrapper<OrderSnapshot>()
                .eq(OrderSnapshot::getOrderId, orderId));
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.getTeamOrder())
                || !StringUtils.hasText(snapshot.getTeamGrabRequestId())) {
            return null;
        }
        return snapshot.getTeamGrabRequestId();
    }

    private TicketReleasedEvent refundTicketsStrict(Order order) {
        if (orderSeatMapper == null || order == null || order.getId() == null) {
            return null;
        }
        List<OrderSeat> orderSeats = orderSeatMapper.selectList(new LambdaQueryWrapper<OrderSeat>()
                .eq(OrderSeat::getOrderId, order.getId())
                .eq(OrderSeat::getStatus, ORDER_SEAT_SOLD));
        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(order.getId());
        request.setSessionId(order.getSessionId());
        request.setTicketTypeId(order.getTicketTypeId());
        request.setQuantity(order.getQuantity());
        if (orderSeats != null && !orderSeats.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            List<Long> seatIds = orderSeats.stream().map(OrderSeat::getSessionSeatId).collect(Collectors.toList());
            request.setSeatIds(seatIds);
            for (OrderSeat orderSeat : orderSeats) {
                orderSeat.setStatus(ORDER_SEAT_REFUNDED);
                orderSeat.setUpdateTime(now);
                orderSeatMapper.updateById(orderSeat);
            }
        }
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<TicketSalesReleaseResponse> result = callTicketSales(() -> ticketSalesInternalClient.refund(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("退款恢复票务资源失败: orderId={}", order.getId());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, result != null ? result.getMessage() : "票务服务无响应");
        }
        return toRefundReleasedEvent(order, releaseResponseOrFallback(result.getData(), request));
    }

    private TicketReleasedEvent refundTickets(Order order, List<OrderSeat> orderSeats, int quantity) {
        TicketSalesOrderRequest request = new TicketSalesOrderRequest();
        request.setOrderId(order.getId());
        request.setSessionId(order.getSessionId());
        request.setTicketTypeId(order.getTicketTypeId());
        request.setQuantity(quantity);
        if (orderSeats != null && !orderSeats.isEmpty()) {
            request.setSeatIds(orderSeats.stream().map(OrderSeat::getSessionSeatId).collect(Collectors.toList()));
        }
        String token = requireInternalApiToken("票务库存接口令牌未配置");
        Result<TicketSalesReleaseResponse> result = callTicketSales(() -> ticketSalesInternalClient.refund(request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, result != null ? result.getMessage() : "票务服务无响应");
        }
        return toRefundReleasedEvent(order, releaseResponseOrFallback(result.getData(), request));
    }

    public void publishWaitlistReleaseEvents(List<TicketReleasedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (TicketReleasedEvent event : events) {
            publishWaitlistReleaseEvent(event);
        }
    }

    private void publishWaitlistReleaseEvent(TicketReleasedEvent event) {
        if (event == null || waitlistProducer == null || event.getQuantity() == null || event.getQuantity() <= 0) {
            return;
        }
        WaitlistReleasedMessage message = new WaitlistReleasedMessage();
        message.setEventKey(event.getEventKey());
        message.setSource(event.getSource());
        message.setSourceOrderId(event.getSourceOrderId());
        message.setSessionId(event.getSessionId());
        message.setTicketTypeId(event.getTicketTypeId());
        message.setQuantity(event.getQuantity());
        message.setSeatIds(event.getSeatIds());
        MqPublishSupport.afterCommitOrNow(() -> {
            try {
                waitlistProducer.sendReleasedEvent(message);
            } catch (RuntimeException e) {
                log.warn("候补释放事件发布失败: eventKey={}, message={}", event.getEventKey(), e.getMessage());
            }
        });
    }

    private void notifyWaitlistPaid(Long orderId) {
        if (orderId == null || waitlistProducer == null) {
            return;
        }
        MqPublishSupport.afterCommitOrNow(() -> {
            try {
                waitlistProducer.sendOrderPaidEvent(orderId);
            } catch (RuntimeException e) {
                log.warn("候补支付事件发布失败: orderId={}, message={}", orderId, e.getMessage());
            }
        });
    }

    private TicketReleasedEvent toRefundReleasedEvent(Order order, TicketSalesReleaseResponse response) {
        TicketReleasedEvent event = toTicketReleasedEvent("REFUND", refundEventKey(order, response), order.getId(), response);
        if (event == null || event.getQuantity() == null || event.getQuantity() <= 0) {
            return null;
        }
        return event;
    }

    private TicketSalesReleaseResponse releaseResponseOrFallback(TicketSalesReleaseResponse response, TicketSalesOrderRequest request) {
        if (response != null) {
            return response;
        }
        TicketSalesReleaseResponse fallback = new TicketSalesReleaseResponse();
        fallback.setSessionId(request.getSessionId());
        fallback.setTicketTypeId(request.getTicketTypeId());
        fallback.setSeatIds(request.getSeatIds() != null ? request.getSeatIds() : Collections.emptyList());
        int quantity = request.getSeatIds() != null && !request.getSeatIds().isEmpty()
                ? request.getSeatIds().size()
                : requirePositiveQuantity(request.getQuantity());
        fallback.setQuantity(quantity);
        fallback.setRestoredQuantity(quantity);
        return fallback;
    }

    private TicketReleasedEvent toTicketReleasedEvent(String source, String eventKey, Long sourceOrderId, TicketSalesReleaseResponse response) {
        if (response == null || response.getRestoredQuantity() == null || response.getRestoredQuantity() <= 0) {
            return null;
        }
        TicketReleasedEvent event = new TicketReleasedEvent();
        event.setEventKey(eventKey);
        event.setSource(source);
        event.setSourceOrderId(sourceOrderId);
        event.setSessionId(response.getSessionId());
        event.setTicketTypeId(response.getTicketTypeId());
        event.setQuantity(response.getRestoredQuantity());
        event.setSeatIds(response.getSeatIds() != null ? response.getSeatIds() : Collections.emptyList());
        return event;
    }

    private void issueElectronicTickets(Order order) {
        if (ticketWalletService != null) {
            ticketWalletService.issueForPaidOrder(order);
        }
    }

    private void invalidateElectronicTicketsForRefundedOrder(Order order) {
        if (ticketWalletService != null && order != null) {
            ticketWalletService.invalidateUnusedTicketsForOrder(order.getId(), "订单已退款");
        }
    }

    private void invalidateElectronicTicketsForPartialRefund(Order order, List<OrderSeat> selectedSeats, int quantity) {
        if (ticketWalletService == null || order == null) {
            return;
        }
        List<Long> orderSeatIds = selectedSeats == null ? Collections.emptyList()
                : selectedSeats.stream().map(OrderSeat::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (!orderSeatIds.isEmpty()) {
            ticketWalletService.invalidateUnusedTicketsByOrderSeats(order.getId(), orderSeatIds, "部分退款");
            return;
        }
        ticketWalletService.invalidateUnusedTicketsByQuantity(order.getId(), quantity, "部分退款");
    }

    private String orderTimeoutEventKey(Order order) {
        return "order-timeout:" + order.getId() + ":session:" + order.getSessionId() + ":ticket-type:" + order.getTicketTypeId();
    }

    private String orderSeatTimeoutEventKey(OrderSeat orderSeat) {
        return "order-seat-timeout:" + orderSeat.getId() + ":session:" + orderSeat.getSessionId() + ":ticket-type:" + orderSeat.getTicketTypeId();
    }

    private String refundEventKey(Order order, TicketSalesReleaseResponse response) {
        int quantity = response != null && response.getRestoredQuantity() != null ? response.getRestoredQuantity() : 0;
        return "refund:" + order.getId() + ":session:" + order.getSessionId() + ":ticket-type:" + order.getTicketTypeId() + ":quantity:" + quantity;
    }

    private static final class OrderFulfillmentLatencyTrace {
        private final Long requestedOrderId;
        private final long startedAtNanos = System.nanoTime();
        private Long orderId;
        private String orderNo;
        private long orderLoadNanos;
        private long statusUpdateNanos;
        private long ticketConfirmNanos;
        private long ticketIssueNanos;
        private long waitlistNotifyNanos;

        private OrderFulfillmentLatencyTrace(Long requestedOrderId) {
            this.requestedOrderId = requestedOrderId;
        }

        private void setOrder(Order order) {
            if (order == null) {
                return;
            }
            this.orderId = order.getId();
            this.orderNo = order.getOrderNo();
        }

        private Order measureOrderLoad(Supplier<Order> action) {
            long startedAt = System.nanoTime();
            try {
                return action.get();
            } finally {
                orderLoadNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private int measureStatusUpdate(Supplier<Integer> action) {
            long startedAt = System.nanoTime();
            try {
                return action.get();
            } finally {
                statusUpdateNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void measureTicketConfirm(Runnable action) {
            long startedAt = System.nanoTime();
            try {
                action.run();
            } finally {
                ticketConfirmNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void measureTicketIssue(Runnable action) {
            long startedAt = System.nanoTime();
            try {
                action.run();
            } finally {
                ticketIssueNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void measureWaitlistNotify(Runnable action) {
            long startedAt = System.nanoTime();
            try {
                action.run();
            } finally {
                waitlistNotifyNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void log(String outcome) {
            long totalNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
            OrderService.log.info(
                    "订单支付履约链路耗时: orderId={} orderNo={} outcome={} orderLoadMs={} statusUpdateMs={} ticketConfirmMs={} ticketIssueMs={} waitlistNotifyMs={} totalMs={}",
                    orderId != null ? orderId : requestedOrderId,
                    orderNo,
                    outcome,
                    millis(orderLoadNanos),
                    millis(statusUpdateNanos),
                    millis(ticketConfirmNanos),
                    millis(ticketIssueNanos),
                    millis(waitlistNotifyNanos),
                    millis(totalNanos)
            );
        }

        private long millis(long nanos) {
            return nanos / 1_000_000L;
        }
    }

    private <T> T callUserValidate(Supplier<T> call) {
        return callWithSentinel(OrderSentinelConfig.USER_VALIDATE_RESOURCE, "用户服务暂不可用，请稍后重试", call);
    }

    private <T> T callTicketSales(Supplier<T> call) {
        return callWithSentinel(OrderSentinelConfig.TICKET_SALES_RESOURCE, "票务服务暂不可用，请稍后重试", call);
    }

    private <T> T callWithSentinel(String resource, String blockMessage, Supplier<T> call) {
        Entry entry = null;
        try {
            entry = SphU.entry(resource);
            return call.get();
        } catch (BlockException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, blockMessage);
        } catch (RuntimeException e) {
            Tracer.traceEntry(e, entry);
            throw e;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private Order buildPendingOrder(Long userId, Long sessionId, Long ticketTypeId, Integer quantity, BigDecimal unitPrice) {
        String orderNo = "DM" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setSessionId(sessionId);
        order.setTicketTypeId(ticketTypeId);
        order.setQuantity(quantity);
        order.setAmount(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(STATUS_PENDING);
        return order;
    }

    private void assertPendingOrderSafeToCancel(Order order) {
        if (paymentInternalClient == null) {
            if (StringUtils.hasText(internalApiToken) && "test-internal-token".equals(internalApiToken)) {
                return;
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "支付状态确认客户端未配置");
        }
        if (!StringUtils.hasText(internalApiToken)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "内部接口令牌未配置，无法确认支付状态");
        }
        Result<PaymentSyncDecisionResponse> result;
        try {
            result = paymentInternalClient.syncOrderForCancel(order.getId(), internalApiToken);
        } catch (RuntimeException e) {
            log.warn("取消订单前确认支付状态失败: orderId={}, orderNo={}", order.getId(), order.getOrderNo(), e);
            throw new BusinessException(ResultCode.CONFLICT, "支付状态确认中，请稍后刷新后再操作");
        }
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new BusinessException(ResultCode.CONFLICT, "支付状态确认中，请稍后刷新后再操作");
        }
        PaymentSyncDecisionResponse decision = result.getData();
        if (Boolean.TRUE.equals(decision.getPaid())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付，不能取消");
        }
        if (!Boolean.TRUE.equals(decision.getSafeToCancel())) {
            throw new BusinessException(ResultCode.CONFLICT, "支付状态确认中，请稍后刷新后再操作");
        }
    }

    private void cancelPendingOrderOrThrow(Order order) {
        int updated = orderMapper.updateStatusIfCurrent(order.getId(), STATUS_PENDING, STATUS_CANCELLED);
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "订单状态已变化，请刷新后重试");
        }
        order.setStatus(STATUS_CANCELLED);
        order.setUpdateTime(LocalDateTime.now());
    }

    private String requireInternalApiToken(String message) {
        if (!StringUtils.hasText(internalApiToken)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, message);
        }
        return internalApiToken;
    }

    private static class TeamOrderSeatPayload {
        private final int quantity;
        private final Map<Long, String> seatLabelsById;
        private final List<Long> seatIds;

        private TeamOrderSeatPayload(int quantity, Map<Long, String> seatLabelsById) {
            this.quantity = quantity;
            this.seatLabelsById = seatLabelsById;
            this.seatIds = new ArrayList<>(seatLabelsById.keySet());
        }
    }
}
