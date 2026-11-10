package com.omni.ticket.service;

import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.TeamSeatLockReleaseRequest;
import com.omni.ticket.dto.TeamSeatLockRequest;
import com.omni.ticket.dto.TeamSeatLockResponse;
import com.omni.ticket.dto.TeamSeatLockValidationRequest;
import com.omni.ticket.dto.TeamSeatLockValidationResponse;
import com.omni.ticket.dto.TicketPurchaseContextRequest;
import com.omni.ticket.dto.TicketPurchaseContextResponse;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.dto.TicketTypeVisibleResponse;
import com.omni.ticket.dto.TicketTypesVisibleRequest;
import com.omni.ticket.dto.TicketSalesLockRequest;
import com.omni.ticket.dto.TicketSalesOrderRequest;
import com.omni.ticket.dto.TicketSalesQuoteRequest;
import com.omni.ticket.dto.TicketSalesQuoteResponse;
import com.omni.ticket.dto.TicketSalesReleaseResponse;
import com.omni.ticket.dto.TicketSalesSeatLockResponse;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.SeatBlock;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.SessionSeat;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SeatBlockMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.TicketGroupMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.VenueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TicketSalesInternalService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String STRICT_CONTIGUOUS = "STRICT_CONTIGUOUS";
    private static final String SAME_BLOCK = "SAME_BLOCK";
    private static final String SAME_TICKET_TYPE = "SAME_TICKET_TYPE";
    private static final String FALLBACK = "FALLBACK";
    private static final List<String> STANDARD_TEAM_LOCK_STRATEGIES = List.of(
            STRICT_CONTIGUOUS, SAME_BLOCK, SAME_TICKET_TYPE);
    private static final int TEAM_LOCK_CANDIDATE_MULTIPLIER = 10;

    @Value("${omni.ticket.team-lock.max-candidates:2000}")
    private int teamLockMaxCandidates = 2000;

    private final TicketTypeMapper ticketTypeMapper;
    private final SessionMapper sessionMapper;
    private final ActivityMapper activityMapper;
    private final VenueMapper venueMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SeatBlockMapper seatBlockMapper;

    public TicketSalesInternalService(TicketTypeMapper ticketTypeMapper,
                                       SessionMapper sessionMapper,
                                       ActivityMapper activityMapper,
                                       VenueMapper venueMapper,
                                       SessionSeatMapper sessionSeatMapper) {
        this(ticketTypeMapper, sessionMapper, activityMapper, venueMapper, sessionSeatMapper, null, null);
    }

    @Autowired
    public TicketSalesInternalService(TicketTypeMapper ticketTypeMapper,
                                       SessionMapper sessionMapper,
                                       ActivityMapper activityMapper,
                                       VenueMapper venueMapper,
                                       SessionSeatMapper sessionSeatMapper,
                                       SeatBlockMapper seatBlockMapper,
                                       TicketGroupMapper ticketGroupMapper) {
        this.ticketTypeMapper = ticketTypeMapper;
        this.sessionMapper = sessionMapper;
        this.activityMapper = activityMapper;
        this.venueMapper = venueMapper;
        this.sessionSeatMapper = sessionSeatMapper;
        this.seatBlockMapper = seatBlockMapper;
    }

    public TicketSalesQuoteResponse quote(TicketSalesQuoteRequest request) {
        if (request == null || request.getTicketTypeId() == null || request.getSessionId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票务报价参数不能为空");
        }
        int quantity = request.getSeatIds() != null && !request.getSeatIds().isEmpty()
                ? request.getSeatIds().size()
                : requirePositiveQuantity(request.getQuantity());
        TicketType ticketType = ticketTypeMapper.selectById(request.getTicketTypeId());
        if (ticketType == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票档不存在");
        }
        if (!request.getSessionId().equals(ticketType.getSessionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档不属于当前场次");
        }
        if (!Integer.valueOf(1).equals(ticketType.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档不可售");
        }

        requireSessionSellable(request.getSessionId());

        TicketSalesQuoteResponse response = new TicketSalesQuoteResponse();
        response.setSessionId(request.getSessionId());
        response.setTicketTypeId(request.getTicketTypeId());
        response.setUnitPrice(ticketType.getPrice());
        response.setTicketName(ticketType.getName());
        response.setQuantity(quantity);
        response.setSeatBased(request.getSeatIds() != null && !request.getSeatIds().isEmpty());
        fillSnapshotFields(response, request.getSessionId());
        if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            response.setSeatLabels(String.join(", ", sessionSeatMapper.selectSeatLabelsByIds(request.getSeatIds())));
        }
        return response;
    }

    public TicketPurchaseContextResponse getPurchaseContext(TicketPurchaseContextRequest request) {
        if (request == null || request.getSessionId() == null || request.getTicketTypeId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票务上下文参数不能为空");
        }
        TicketType ticketType = ticketTypeMapper.selectById(request.getTicketTypeId());
        if (ticketType == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票档不存在");
        }
        if (!request.getSessionId().equals(ticketType.getSessionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档不属于当前场次");
        }

        Session session = sessionMapper.selectById(request.getSessionId());
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        }

        TicketPurchaseContextResponse response = new TicketPurchaseContextResponse();
        response.setSessionId(request.getSessionId());
        response.setTicketTypeId(request.getTicketTypeId());
        response.setTicketTypeName(ticketType.getName());
        response.setSessionTime(session.getStartTime());
        response.setActivityId(session.getActivityId());

        Activity activity = activityMapper.selectById(session.getActivityId());
        if (activity != null) {
            response.setActivityName(activity.getName());
            response.setActivityPoster(activity.getPoster());
        }
        Venue venue = venueMapper.selectById(session.getVenueId());
        if (venue != null) {
            response.setVenueName(venue.getName());
        }
        return response;
    }

    public List<TicketTypeVisibleResponse> listVisibleTicketTypes(TicketTypesVisibleRequest request) {
        if (request == null || request.getSessionId() == null || request.getTicketTypeIds() == null || request.getTicketTypeIds().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档参数不能为空");
        }
        if (isSessionExplicitlyUnsellable(request.getSessionId())) {
            return Collections.emptyList();
        }
        List<TicketType> ticketTypes = ticketTypeMapper.selectBatchIds(request.getTicketTypeIds());
        Map<Long, TicketTypeSeatStockSnapshot> seatStockByTicketTypeId = seatStockSnapshotsByTicketTypeId(request.getSessionId());
        return ticketTypes.stream()
                .filter(ticketType -> request.getSessionId().equals(ticketType.getSessionId()))
                .filter(ticketType -> Integer.valueOf(1).equals(ticketType.getStatus()))
                .map(ticketType -> {
                    TicketTypeVisibleResponse response = new TicketTypeVisibleResponse();
                    response.setTicketTypeId(ticketType.getId());
                    response.setName(ticketType.getName());
                    response.setPrice(ticketType.getPrice());
                    TicketTypeSeatStockSnapshot seatStock = seatStockByTicketTypeId.get(ticketType.getId());
                    if (seatStock != null) {
                        response.setTotalStock(nonNegative(seatStock.getTotalStock()));
                        response.setRemainStock(cappedRemainStock(seatStock.getRemainStock(), seatStock.getTotalStock()));
                    } else {
                        response.setTotalStock(ticketType.getTotalStock());
                        response.setRemainStock(visibleRemainStock(ticketType));
                    }
                    return response;
                })
                .collect(Collectors.toList());
    }

    private Map<Long, TicketTypeSeatStockSnapshot> seatStockSnapshotsByTicketTypeId(Long sessionId) {
        List<TicketTypeSeatStockSnapshot> snapshots = sessionSeatMapper.selectSeatStockSnapshotsBySessionId(sessionId);
        if (snapshots == null || snapshots.isEmpty()) {
            return Collections.emptyMap();
        }
        return snapshots.stream()
                .filter(snapshot -> snapshot != null && snapshot.getTicketTypeId() != null)
                .collect(Collectors.toMap(TicketTypeSeatStockSnapshot::getTicketTypeId, snapshot -> snapshot, (first, second) -> first));
    }

    private Integer visibleRemainStock(TicketType ticketType) {
        Integer remainStock = ticketType.getRemainStock();
        if (remainStock == null) {
            return null;
        }
        return cappedRemainStock(remainStock, ticketType.getTotalStock());
    }

    private Integer cappedRemainStock(Integer remainStock, Integer totalStock) {
        if (remainStock == null) {
            return null;
        }
        int visibleRemainStock = Math.max(0, remainStock);
        if (totalStock != null) {
            visibleRemainStock = Math.min(visibleRemainStock, Math.max(0, totalStock));
        }
        return visibleRemainStock;
    }

    private Integer nonNegative(Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    @Transactional(rollbackFor = Exception.class)
    public void lockStock(TicketSalesLockRequest request) {
        int quantity = requirePositiveQuantity(request.getQuantity());
        requireSessionSellable(request.getSessionId());
        int updated = ticketTypeMapper.decreaseRemainStockIfEnough(request.getTicketTypeId(), quantity);
        if (updated != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档库存不足");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketSalesSeatLockResponse lockSeats(TicketSalesLockRequest request) {
        requireSessionSellable(request.getSessionId());
        List<Long> seatIds = request.getSeatIds();
        if ((seatIds == null || seatIds.isEmpty()) && Boolean.TRUE.equals(request.getAllocateRandom())) {
            int quantity = requirePositiveQuantity(request.getQuantity());
            List<SessionSeat> availableSeats = sortSeats(sessionSeatMapper.selectAvailableSeatsForFinder(
                    request.getSessionId(), request.getTicketTypeId()));
            List<SessionSeat> allocatedSeats = selectStrictContiguous(availableSeats, quantity);
            if (allocatedSeats.isEmpty()) {
                allocatedSeats = availableSeats.size() >= quantity
                        ? new ArrayList<>(availableSeats.subList(0, quantity))
                        : Collections.emptyList();
            }
            seatIds = allocatedSeats.stream().map(SessionSeat::getId).collect(Collectors.toList());
            if (seatIds == null || seatIds.size() < quantity) {
                requireSeatlessStandingTicketType(request.getSessionId(), request.getTicketTypeId());
                int updated = ticketTypeMapper.decreaseRemainStockIfEnough(request.getTicketTypeId(), quantity);
                if (updated != 1) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "票档库存不足");
                }
                TicketSalesSeatLockResponse response = new TicketSalesSeatLockResponse();
                response.setLockedSeatIds(Collections.emptyList());
                response.setSeatLabels(List.of("系统分配站区票 x" + quantity));
                return response;
            }
        }
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "座位不能为空");
        }
        for (Long seatId : seatIds) {
            int updated = sessionSeatMapper.lockSeat(seatId, request.getSessionId(), request.getTicketTypeId(), request.getLockExpireTime());
            if (updated != 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "座位已锁定或不可售");
            }
        }
        TicketSalesSeatLockResponse response = new TicketSalesSeatLockResponse();
        response.setLockedSeatIds(seatIds);
        response.setSeatLabels(sessionSeatMapper.selectSeatLabelsByIds(seatIds));
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamSeatLockResponse lockTeamSeats(TeamSeatLockRequest request) {
        validateTeamLockRequest(request);
        sessionSeatMapper.acquireTeamLockRequestLock(request.getLockRequestId());
        List<String> strategies = teamLockStrategies(request);

        List<SessionSeat> existingLockedSeats = sortSeats(sessionSeatMapper.selectLockedByRequestId(
                request.getSessionId(), request.getTicketTypeId(), request.getLockRequestId()));
        if (!existingLockedSeats.isEmpty()) {
            if (existingLockedSeats.size() != request.getQuantity()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座请求已存在且数量不一致");
            }
            return teamSeatLockResponse(existingLockedSeats,
                    matchedExistingStrategy(existingLockedSeats, request.getQuantity()));
        }

        requireFutureLockExpireTime(request.getLockExpireTime());
        requireSessionSellable(request.getSessionId());
        requireSellableTicketType(request.getSessionId(), request.getTicketTypeId());

        int maxLimit = teamLockMaxCandidateLimit(request.getQuantity());
        for (String strategy : strategies) {
            int limit = Math.min(teamLockCandidateLimit(request.getQuantity()), maxLimit);
            while (true) {
                List<SessionSeat> availableSeats = sortSeats(sessionSeatMapper.selectAvailableForTeamLock(
                        request.getSessionId(), request.getTicketTypeId(), limit));
                List<SessionSeat> selectedSeats = selectByStrategy(availableSeats, request.getQuantity(), strategy);
                if (selectedSeats.size() == request.getQuantity()) {
                    List<Long> seatIds = selectedSeats.stream().map(SessionSeat::getId).collect(Collectors.toList());
                    int updated = sessionSeatMapper.lockTeamSeatIds(
                            request.getSessionId(),
                            request.getTicketTypeId(),
                            seatIds,
                            request.getLockRequestId(),
                            request.getLockExpireTime());
                    if (updated != request.getQuantity()) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座状态已变化，请重试");
                    }
                    List<SessionSeat> lockedSeats = sessionSeatMapper.selectLockedByRequest(
                            request.getSessionId(), request.getTicketTypeId(), seatIds, request.getLockRequestId());
                    if (lockedSeats.size() != request.getQuantity()) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座归属校验失败");
                    }
                    return teamSeatLockResponse(lockedSeats, strategy);
                }
                if (availableSeats.size() < limit || limit >= maxLimit) {
                    break;
                }
                limit = Math.min(maxLimit, Math.max(limit + 1, limit * 2));
            }
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "没有可满足数量的组队锁座策略");
    }

    public TeamSeatLockValidationResponse validateTeamSeatLock(TeamSeatLockValidationRequest request) {
        if (request == null || request.getSessionId() == null || request.getTicketTypeId() == null
                || request.getSeatIds() == null || request.getSeatIds().isEmpty()
                || !StringUtils.hasText(request.getLockRequestId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座校验参数不能为空");
        }
        List<SessionSeat> lockedSeats = sessionSeatMapper.selectLockedByRequest(
                request.getSessionId(), request.getTicketTypeId(), request.getSeatIds(), request.getLockRequestId());
        TeamSeatLockValidationResponse response = new TeamSeatLockValidationResponse();
        response.setValid(lockedSeats.size() == request.getSeatIds().size());
        response.setSeatIds(lockedSeats.stream().map(SessionSeat::getId).collect(Collectors.toList()));
        response.setSeatLabels(lockedSeats.stream().map(this::seatLabel).collect(Collectors.toList()));
        return response;
    }

    public Boolean releaseTeamSeatLock(TeamSeatLockReleaseRequest request) {
        if (request == null || !StringUtils.hasText(request.getLockRequestId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座释放参数不能为空");
        }
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            sessionSeatMapper.releaseTeamSeatLockByRequestId(request.getLockRequestId());
            return sessionSeatMapper.countTeamSeatLocksByRequestId(request.getLockRequestId()) == 0;
        }
        int released = sessionSeatMapper.releaseTeamSeatLockByRequest(request.getLockRequestId(), request.getSeatIds());
        if (released == request.getSeatIds().size()) {
            return true;
        }
        return sessionSeatMapper.countTeamSeatLocksByRequest(request.getLockRequestId(), request.getSeatIds()) == 0;
    }

    private void requireSeatlessStandingTicketType(Long sessionId, Long ticketTypeId) {
        if (seatBlockMapper == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档库存不足");
        }
        TicketType ticketType = ticketTypeMapper.selectById(ticketTypeId);
        if (ticketType == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票档不存在");
        }
        if (ticketType.getSeatBlockId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档库存不足");
        }
        SeatBlock block = seatBlockMapper.selectSalesBindingById(ticketType.getSeatBlockId());
        if (block == null
                || !Objects.equals("session", block.getOwnerType())
                || !Objects.equals(sessionId, block.getOwnerId())
                || !Objects.equals("standingBlock", block.getBlockType())
                || !Integer.valueOf(1).equals(block.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档库存不足");
        }
    }

    private void validateTeamLockRequest(TeamSeatLockRequest request) {
        if (request == null || request.getSessionId() == null || request.getTicketTypeId() == null
                || request.getQuantity() == null || !StringUtils.hasText(request.getStrategy())
                || !StringUtils.hasText(request.getLockRequestId()) || request.getLockExpireTime() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座参数不能为空");
        }
        if (request.getQuantity() < 2 || request.getQuantity() > 6) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座数量必须为 2-6 张");
        }
    }

    private void requireFutureLockExpireTime(LocalDateTime lockExpireTime) {
        if (!lockExpireTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座过期时间必须晚于当前时间");
        }
    }

    private TicketType requireSellableTicketType(Long sessionId, Long ticketTypeId) {
        TicketType ticketType = ticketTypeMapper.selectById(ticketTypeId);
        if (ticketType == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票档不存在");
        }
        if (!sessionId.equals(ticketType.getSessionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档不属于当前场次");
        }
        if (!Integer.valueOf(1).equals(ticketType.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票档不可售");
        }
        return ticketType;
    }

    private List<String> teamLockStrategies(TeamSeatLockRequest request) {
        String primary = normalizeStrategy(request.getStrategy());
        if (FALLBACK.equals(primary)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兜底策略不能作为首选策略");
        }
        LinkedHashSet<String> strategies = new LinkedHashSet<>();
        strategies.add(primary);
        if (request.getFallbacks() != null) {
            for (String fallback : request.getFallbacks()) {
                String strategy = normalizeStrategy(fallback);
                if (!FALLBACK.equals(strategy)) {
                    strategies.add(strategy);
                }
            }
        }
        return new ArrayList<>(strategies);
    }

    private String normalizeStrategy(String strategy) {
        if (!StringUtils.hasText(strategy)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "组队锁座策略不能为空");
        }
        String normalized = strategy.trim().toUpperCase();
        if (!STRICT_CONTIGUOUS.equals(normalized)
                && !SAME_BLOCK.equals(normalized)
                && !SAME_TICKET_TYPE.equals(normalized)
                && !FALLBACK.equals(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持该组队锁座策略");
        }
        return normalized;
    }

    private List<SessionSeat> selectByStrategy(List<SessionSeat> seats, int quantity, String strategy) {
        if (STRICT_CONTIGUOUS.equals(strategy)) {
            return selectStrictContiguous(seats, quantity);
        }
        if (SAME_BLOCK.equals(strategy)) {
            return selectSameBlock(seats, quantity);
        }
        if (SAME_TICKET_TYPE.equals(strategy)) {
            return seats.size() >= quantity ? new ArrayList<>(seats.subList(0, quantity)) : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private List<SessionSeat> selectStrictContiguous(List<SessionSeat> seats, int quantity) {
        Map<String, List<SessionSeat>> grouped = new LinkedHashMap<>();
        for (SessionSeat seat : seats) {
            if (seat.getRowNo() == null || seat.getSeatNo() == null) {
                continue;
            }
            for (String groupKey : seatGroupKeys(seat)) {
                grouped.computeIfAbsent(groupKey + ":" + seat.getRowNo(), key -> new ArrayList<>()).add(seat);
            }
        }
        for (List<SessionSeat> rowSeats : grouped.values()) {
            List<SessionSeat> sorted = sortSeats(rowSeats);
            for (int start = 0; start <= sorted.size() - quantity; start++) {
                boolean contiguous = true;
                for (int offset = 1; offset < quantity; offset++) {
                    int previous = sorted.get(start + offset - 1).getSeatNo();
                    int current = sorted.get(start + offset).getSeatNo();
                    if (current != previous + 1) {
                        contiguous = false;
                        break;
                    }
                }
                if (contiguous) {
                    return new ArrayList<>(sorted.subList(start, start + quantity));
                }
            }
        }
        return Collections.emptyList();
    }

    private List<SessionSeat> selectSameBlock(List<SessionSeat> seats, int quantity) {
        Map<String, List<SessionSeat>> grouped = new LinkedHashMap<>();
        for (SessionSeat seat : seats) {
            for (String groupKey : seatGroupKeys(seat)) {
                grouped.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(seat);
            }
        }
        for (List<SessionSeat> blockSeats : grouped.values()) {
            if (blockSeats.size() >= quantity) {
                return new ArrayList<>(sortSeats(blockSeats).subList(0, quantity));
            }
        }
        return Collections.emptyList();
    }

    private int teamLockCandidateLimit(int quantity) {
        return Math.max(quantity, quantity * TEAM_LOCK_CANDIDATE_MULTIPLIER);
    }

    private int teamLockMaxCandidateLimit(int quantity) {
        return Math.max(quantity, teamLockMaxCandidates);
    }

    private TeamSeatLockResponse teamSeatLockResponse(List<SessionSeat> lockedSeats, String matchedStrategy) {
        TeamSeatLockResponse response = new TeamSeatLockResponse();
        response.setLockedSeatIds(lockedSeats.stream().map(SessionSeat::getId).collect(Collectors.toList()));
        response.setSeatLabels(lockedSeats.stream().map(this::seatLabel).collect(Collectors.toList()));
        response.setMatchedStrategy(matchedStrategy);
        return response;
    }

    private String matchedExistingStrategy(List<SessionSeat> lockedSeats, int quantity) {
        List<Long> lockedSeatIds = lockedSeats.stream().map(SessionSeat::getId).collect(Collectors.toList());
        for (String strategy : STANDARD_TEAM_LOCK_STRATEGIES) {
            List<Long> selectedSeatIds = selectByStrategy(lockedSeats, quantity, strategy).stream()
                    .map(SessionSeat::getId)
                    .collect(Collectors.toList());
            if (lockedSeatIds.equals(selectedSeatIds)) {
                return strategy;
            }
        }
        return SAME_TICKET_TYPE;
    }

    private List<SessionSeat> sortSeats(List<SessionSeat> seats) {
        if (seats == null || seats.isEmpty()) {
            return Collections.emptyList();
        }
        return seats.stream()
                .sorted(Comparator
                        .comparing(SessionSeat::getSeatBlockId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(this::fallbackSectionSortKey, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(SessionSeat::getRowNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SessionSeat::getSeatNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SessionSeat::getId, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());
    }

    private Long fallbackSectionSortKey(SessionSeat seat) {
        return seat.getSeatBlockId() == null ? seat.getLayoutSectionId() : null;
    }

    private List<String> seatGroupKeys(SessionSeat seat) {
        if (StringUtils.hasText(seat.getTicketGroupKey())) {
            return List.of("ticket-group:" + seat.getTicketGroupKey());
        }
        if (seat.getSeatBlockId() != null) {
            return List.of("block:" + seat.getSeatBlockId());
        }
        if (seat.getLayoutSectionId() != null) {
            return List.of("section:" + seat.getLayoutSectionId());
        }
        if (seat.getAreaId() != null) {
            return List.of("area:" + seat.getAreaId());
        }
        return Collections.emptyList();
    }

    private String seatLabel(SessionSeat seat) {
        if (StringUtils.hasText(seat.getSeatLabel())) {
            return seat.getSeatLabel();
        }
        if (seat.getRowNo() != null && seat.getSeatNo() != null) {
            return seat.getRowNo() + "-" + seat.getSeatNo();
        }
        return String.valueOf(seat.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmSold(TicketSalesOrderRequest request) {
        if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            int updated = 0;
            for (Long seatId : request.getSeatIds()) {
                updated += sessionSeatMapper.markSeatSold(seatId, request.getSessionId(), request.getTicketTypeId(),
                        request.getOrderId(), request.getLockRequestId());
            }
            if (updated != request.getSeatIds().size()) {
                throw new BusinessException(ResultCode.CONFLICT, "确认售出时座位锁校验失败");
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketSalesReleaseResponse release(TicketSalesOrderRequest request) {
        TicketSalesReleaseResponse response = releaseResponseBase(request);
        if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            int updated = 0;
            for (Long seatId : request.getSeatIds()) {
                updated += sessionSeatMapper.releaseLockedSeat(seatId, request.getSessionId(), request.getTicketTypeId(),
                        request.getLockRequestId());
            }
            int requestedSeats = request.getSeatIds().size();
            if (updated != requestedSeats
                    && sessionSeatMapper.countAvailableBySeatIds(request.getSessionId(), request.getTicketTypeId(), request.getSeatIds()) != requestedSeats) {
                throw new BusinessException(ResultCode.CONFLICT, "释放座位锁失败，请刷新后重试");
            }
            response.setQuantity(requestedSeats);
            response.setRestoredQuantity(requestedSeats);
        } else {
            int quantity = requirePositiveQuantity(request.getQuantity());
            ticketTypeMapper.increaseRemainStock(request.getTicketTypeId(), quantity);
            response.setQuantity(quantity);
            response.setRestoredQuantity(quantity);
        }
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketSalesReleaseResponse refund(TicketSalesOrderRequest request) {
        TicketSalesReleaseResponse response = releaseResponseBase(request);
        if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            boolean canResell = canResellRefundedSeats(request.getSessionId(), request.getTicketTypeId());
            int restored = 0;
            for (Long seatId : request.getSeatIds()) {
                if (canResell) {
                    restored += sessionSeatMapper.restoreSoldSeat(seatId, request.getSessionId());
                } else {
                    sessionSeatMapper.markRefundedSeatUnavailable(seatId, request.getSessionId());
                }
            }
            if (restored > 0) {
                ticketTypeMapper.increaseRemainStock(request.getTicketTypeId(), restored);
            }
            response.setQuantity(request.getSeatIds().size());
            response.setRestoredQuantity(restored);
        } else {
            int quantity = requirePositiveQuantity(request.getQuantity());
            ticketTypeMapper.increaseRemainStock(request.getTicketTypeId(), quantity);
            response.setQuantity(quantity);
            response.setRestoredQuantity(quantity);
        }
        return response;
    }

    private TicketSalesReleaseResponse releaseResponseBase(TicketSalesOrderRequest request) {
        TicketSalesReleaseResponse response = new TicketSalesReleaseResponse();
        response.setSessionId(request.getSessionId());
        response.setTicketTypeId(request.getTicketTypeId());
        response.setSeatIds(request.getSeatIds() != null ? request.getSeatIds() : Collections.emptyList());
        response.setQuantity(request.getQuantity());
        response.setRestoredQuantity(0);
        return response;
    }

    private int requirePositiveQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "购买数量不正确");
        }
        return quantity;
    }

    private void requireSessionSellable(Long sessionId) {
        if (isSessionExplicitlyUnsellable(sessionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前场次不可售");
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session != null && (session.getStartTime() == null
                || !session.getStartTime().toLocalDate().isAfter(LocalDate.now(BUSINESS_ZONE))
                || !(session.getEndTime() == null ? session.getStartTime().plusHours(3) : session.getEndTime())
                        .isAfter(LocalDateTime.now(BUSINESS_ZONE)))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "演出当天及开始后不可购票");
        }
    }

    private boolean isSessionExplicitlyUnsellable(Long sessionId) {
        return Boolean.FALSE.equals(sessionSeatMapper.selectSessionSellable(sessionId));
    }

    private void fillSnapshotFields(TicketSalesQuoteResponse response, Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        response.setSessionTime(session.getStartTime());
        response.setActivityId(session.getActivityId());
        Activity activity = activityMapper.selectById(session.getActivityId());
        if (activity != null) {
            response.setActivityName(activity.getName());
            response.setActivityPoster(activity.getPoster());
            response.setTourId(activity.getTourId());
            response.setStationId(activity.getStationId());
            response.setPerUserLimit(activity.getPerUserLimit());
            response.setRealNameRequired(true);
            response.setTicketTransferAllowed(!Boolean.FALSE.equals(activity.getTicketTransferAllowed()));
            response.setRefundFullDaysBefore(activity.getRefundFullDaysBefore() == null ? 30 : activity.getRefundFullDaysBefore());
            response.setRefundPartialDaysBefore(activity.getRefundPartialDaysBefore() == null ? 7 : activity.getRefundPartialDaysBefore());
            response.setRefundPartialRate(activity.getRefundPartialRate() == null ? 80 : activity.getRefundPartialRate());
        }
        Venue venue = venueMapper.selectById(session.getVenueId());
        if (venue != null) {
            response.setVenueName(venue.getName());
        }
    }

    private boolean canResellRefundedSeats(Long sessionId, Long ticketTypeId) {
        LocalDateTime startTime = sessionSeatMapper.selectSessionStartTime(sessionId);
        if (startTime == null || !startTime.isAfter(LocalDateTime.now().plusHours(24))) {
            return false;
        }
        return Boolean.TRUE.equals(sessionSeatMapper.selectSessionSellable(sessionId))
                && Boolean.TRUE.equals(ticketTypeMapper.selectTicketTypeSellable(ticketTypeId));
    }
}
