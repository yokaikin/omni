package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.ticket.ai.TicketIntent;
import com.omni.ticket.dto.ActivityVO;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.SessionSeat;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.VenueMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TicketAvailabilityQueryService {
    private final ActivityMapper activityMapper;
    private final SessionMapper sessionMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final VenueMapper venueMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SeatAdjacencyEvaluator seatAdjacencyEvaluator;
    private final Clock clock;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    public TicketAvailabilityQueryService(ActivityMapper activityMapper,
                                           SessionMapper sessionMapper,
                                           TicketTypeMapper ticketTypeMapper,
                                           VenueMapper venueMapper,
                                           SessionSeatMapper sessionSeatMapper,
                                           SeatAdjacencyEvaluator seatAdjacencyEvaluator) {
        this(activityMapper, sessionMapper, ticketTypeMapper, venueMapper, sessionSeatMapper,
                seatAdjacencyEvaluator, Clock.system(DEFAULT_ZONE));
    }

    public TicketAvailabilityQueryService(ActivityMapper activityMapper,
                                           SessionMapper sessionMapper,
                                           TicketTypeMapper ticketTypeMapper,
                                           VenueMapper venueMapper,
                                           SessionSeatMapper sessionSeatMapper,
                                           SeatAdjacencyEvaluator seatAdjacencyEvaluator,
                                           Clock clock) {
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.venueMapper = venueMapper;
        this.sessionSeatMapper = sessionSeatMapper;
        this.seatAdjacencyEvaluator = seatAdjacencyEvaluator;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<TicketFinderResult> findAvailable(List<ActivityVO> candidates, TicketIntent intent) {
        if (intent == null
                || Boolean.TRUE.equals(intent.getNeedAdjacentSeats()) && intent.getPeopleCount() == null) {
            return Collections.emptyList();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> candidateIds = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !"tour".equals(candidate.getItemType()))
                .map(ActivityVO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (candidateIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Activity> activities = indexActivities(activityMapper.selectBatchIds(candidateIds));
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .in(Session::getActivityId, candidateIds)
                .eq(Session::getStatus, 1)
                .orderByAsc(Session::getStartTime));
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Venue> venues = indexVenues(venueMapper.selectBatchIds(
                sessions.stream().map(Session::getVenueId).filter(Objects::nonNull).collect(Collectors.toSet())));
        List<Long> sessionIds = sessions.stream().map(Session::getId).filter(Objects::nonNull).collect(Collectors.toList());
        List<TicketType> ticketTypes = sessionIds.isEmpty()
                ? Collections.emptyList()
                : ticketTypeMapper.selectList(new LambdaQueryWrapper<TicketType>()
                .in(TicketType::getSessionId, sessionIds)
                .eq(TicketType::getStatus, 1)
                .orderByAsc(TicketType::getPrice));
        Map<Long, List<TicketType>> ticketsBySession = ticketTypes == null
                ? Collections.emptyMap()
                : ticketTypes.stream().collect(Collectors.groupingBy(TicketType::getSessionId));

        java.util.ArrayList<TicketFinderResult> results = new java.util.ArrayList<>();
        for (Session session : sessions) {
            Activity activity = activities.get(session.getActivityId());
            Venue venue = venues.get(session.getVenueId());
            if (!isSellableSession(activity, session, venue, intent)) {
                continue;
            }
            Map<Long, Integer> liveSeatStock = liveSeatStock(session.getId());
            for (TicketType ticketType : ticketsBySession.getOrDefault(session.getId(), Collections.emptyList())) {
                Integer available = liveSeatStock.containsKey(ticketType.getId())
                        ? liveSeatStock.get(ticketType.getId())
                        : cappedRemainStock(ticketType.getRemainStock(), ticketType.getTotalStock());
                if (Boolean.TRUE.equals(intent.getIsSupportSeat())
                        && !liveSeatStock.containsKey(ticketType.getId())) {
                    continue;
                }
                if (!matchesTicket(ticketType, available, session, activity, intent)) {
                    continue;
                }
                if (Boolean.TRUE.equals(intent.getNeedAdjacentSeats())
                        && intent.getPeopleCount() != null) {
                    List<SessionSeat> seats = sessionSeatMapper.selectAvailableSeatsForFinder(
                            session.getId(), ticketType.getId());
                    if (!seatAdjacencyEvaluator.hasAdjacentSeats(seats, intent.getPeopleCount())) {
                        continue;
                    }
                }
                TicketFinderResult result = new TicketFinderResult();
                result.setActivityId(activity.getId());
                result.setActivityName(activity.getName());
                result.setSessionId(session.getId());
                result.setSessionStartTime(session.getStartTime());
                result.setVenueId(venue.getId());
                result.setVenueName(venue.getName());
                result.setCity(venue.getCity());
                result.setTicketTypeId(ticketType.getId());
                result.setTicketTypeName(ticketType.getName());
                result.setPrice(ticketType.getPrice());
                result.setAvailableQuantity(available);
                result.setSaleStatus("on_sale");
                results.add(result);
            }
        }
        return results;
    }

    private boolean isSellableSession(Activity activity, Session session, Venue venue, TicketIntent intent) {
        if (activity == null || session == null || venue == null
                || !Integer.valueOf(1).equals(activity.getStatus())
                || !"published".equals(activity.getPublishStatus())
                || !Integer.valueOf(1).equals(session.getStatus())
                || !Integer.valueOf(1).equals(venue.getStatus())
                || session.getStartTime() == null
                || !session.getStartTime().toLocalDate().isAfter(LocalDate.now(clock.withZone(DEFAULT_ZONE)))
                || !(session.getEndTime() == null ? session.getStartTime().plusHours(3) : session.getEndTime())
                        .isAfter(LocalDateTime.now(clock.withZone(DEFAULT_ZONE)))) {
            return false;
        }
        LocalDate from = intent.effectiveDateFrom();
        LocalDate to = intent.effectiveDateTo();
        if (from != null && session.getStartTime().toLocalDate().isBefore(from)
                || to != null && session.getStartTime().toLocalDate().isAfter(to)) {
            return false;
        }
        if (StringUtils.hasText(intent.getCity())
                && !intent.getCity().trim().equalsIgnoreCase(venue.getCity())) {
            return false;
        }
        if (intent.getRealNameRequired() != null
                && !Objects.equals(intent.getRealNameRequired(), activity.getRealNameRequired())) {
            return false;
        }
        if (Boolean.TRUE.equals(intent.getIsSupportSeat())
                && !"published".equalsIgnoreCase(activity.getSeatMapVisibility())) {
            return false;
        }
        return intent.getSaleStatus() == null || "on_sale".equals(intent.getSaleStatus());
    }

    private boolean matchesTicket(TicketType ticketType, Integer available, Session session,
                                  Activity activity, TicketIntent intent) {
        if (ticketType == null || !Integer.valueOf(1).equals(ticketType.getStatus())
                || ticketType.getPrice() == null || available == null || available < 1) {
            return false;
        }
        if (intent.getMinPrice() != null && ticketType.getPrice().compareTo(intent.getMinPrice()) < 0) {
            return false;
        }
        if (intent.getMaxPrice() != null && ticketType.getPrice().compareTo(intent.getMaxPrice()) > 0) {
            return false;
        }
        return intent.getPeopleCount() == null || available >= intent.getPeopleCount();
    }

    private Map<Long, Integer> liveSeatStock(Long sessionId) {
        List<TicketTypeSeatStockSnapshot> snapshots =
                sessionSeatMapper.selectSeatStockSnapshotsBySessionId(sessionId);
        if (snapshots == null) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (TicketTypeSeatStockSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.getTicketTypeId() != null) {
                result.put(snapshot.getTicketTypeId(),
                        cappedRemainStock(snapshot.getRemainStock(), snapshot.getTotalStock()));
            }
        }
        return result;
    }

    private Map<Long, Activity> indexActivities(List<Activity> values) {
        return values == null ? Collections.emptyMap()
                : values.stream().filter(Objects::nonNull).collect(Collectors.toMap(Activity::getId, Function.identity()));
    }

    private Map<Long, Venue> indexVenues(List<Venue> values) {
        return values == null ? Collections.emptyMap()
                : values.stream().filter(Objects::nonNull).collect(Collectors.toMap(Venue::getId, Function.identity()));
    }

    private Integer nonNegative(Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    private Integer cappedRemainStock(Integer remainStock, Integer totalStock) {
        Integer visible = nonNegative(remainStock);
        if (visible == null || totalStock == null) {
            return visible;
        }
        return Math.min(visible, Math.max(0, totalStock));
    }
}
