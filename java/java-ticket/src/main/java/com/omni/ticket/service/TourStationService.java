package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.DeactivateActivityRequest;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.dto.TourDraftCreateDTO;
import com.omni.ticket.dto.TourStationCityDTO;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.dto.RefundImpactResponse;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.Station;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Tour;
import com.omni.ticket.dto.InternalUserRefResponse;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.entity.VenueApplication;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.StationMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.TourMapper;
import com.omni.ticket.mapper.VenueMapper;
import com.omni.ticket.mapper.VenueApplicationMapper;
import com.omni.ticket.service.UserAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.lang.reflect.Array;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TourStationService {

    private static final String PUBLISH_STATUS_DRAFT = "draft";
    private static final String PUBLISH_STATUS_CITY_ANNOUNCED = "city_announced";
    private static final String PUBLISH_STATUS_RISK_SUSPENDED = "risk_suspended";
    private static final String PUBLISH_STATUS_PUBLISHED = "published";
    private static final String PUBLISH_STATUS_DEACTIVATED = "deactivated";
    private static final String REVIEW_STATUS_DEACTIVATED = "deactivated";
    private static final String SALE_STATUS_UNANNOUNCED = "unannounced";
    private static final String SALE_STATUS_COMING_SOON = "coming_soon";
    private static final String SALE_STATUS_TICKET_TBA = "ticket_tba";
    private static final String SALE_STATUS_TO_BE_SCHEDULED = "to_be_scheduled";
    private static final String SALE_STATUS_SUSPENDED = "suspended";
    private static final String SALE_STATUS_DEACTIVATED = "deactivated";
    private static final String SALE_STATUS_SOLD_OUT = "sold_out";
    private static final String SALE_STATUS_ON_SALE = "on_sale";
    private static final String SALE_STATUS_TEXT_UNANNOUNCED = "未公布";
    private static final String SALE_STATUS_TEXT_COMING_ANNOUNCE = "即将公布";
    private static final String SALE_STATUS_TEXT_TO_BE_SCHEDULED = "待定";
    private static final String SALE_STATUS_TEXT_TICKET_TBA = "票档待公布";
    private static final String SALE_STATUS_TEXT_SUSPENDED = "暂时停止售票";
    private static final String SALE_STATUS_TEXT_DEACTIVATED = "已下架";
    private static final String SALE_STATUS_TEXT_SOLD_OUT = "已售罄";
    private static final String SALE_STATUS_TEXT_ON_SALE = "售票中";
    private static final String PRIMARY_ACTION_NONE = "none";
    private static final String PRIMARY_ACTION_BUY = "buy";

    private final TourMapper tourMapper;
    private final StationMapper stationMapper;
    private final UserAccessService userAccessService;
    private final VenueApplicationMapper venueApplicationMapper;
    private final ActivityMapper activityMapper;
    private final SessionMapper sessionMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final VenueMapper venueMapper;
    private final ActivitySeatLayoutService activitySeatLayoutService;
    private final SessionSeatLayoutService sessionSeatLayoutService;
    private final ActivityAdminService activityAdminService;
    private final SessionSeatMapper sessionSeatMapper;
    private final ActivityArtistService activityArtistService;

    public TourStationService(TourMapper tourMapper,
                               StationMapper stationMapper,
                               UserAccessService userAccessService) {
        this(tourMapper, stationMapper, userAccessService, null, null, null, null, null, null, null, null, null, null);
    }

    public TourStationService(TourMapper tourMapper,
                              StationMapper stationMapper,
                              UserAccessService userAccessService,
                              VenueApplicationMapper venueApplicationMapper,
                              ActivityMapper activityMapper,
                              SessionMapper sessionMapper,
                              ActivitySeatLayoutService activitySeatLayoutService,
                              SessionSeatLayoutService sessionSeatLayoutService) {
        this(tourMapper, stationMapper, userAccessService, venueApplicationMapper, activityMapper, sessionMapper,
                null, null, activitySeatLayoutService, sessionSeatLayoutService, null, null, null);
    }

    public TourStationService(TourMapper tourMapper,
                               StationMapper stationMapper,
                               UserAccessService userAccessService,
                               VenueApplicationMapper venueApplicationMapper,
                               ActivityMapper activityMapper,
                               SessionMapper sessionMapper,
                               TicketTypeMapper ticketTypeMapper,
                               VenueMapper venueMapper,
                               ActivitySeatLayoutService activitySeatLayoutService,
                               SessionSeatLayoutService sessionSeatLayoutService) {
        this(tourMapper, stationMapper, userAccessService, venueApplicationMapper, activityMapper, sessionMapper,
                ticketTypeMapper, venueMapper, activitySeatLayoutService, sessionSeatLayoutService, null, null, null);
    }

    public TourStationService(TourMapper tourMapper,
                               StationMapper stationMapper,
                               UserAccessService userAccessService,
                               VenueApplicationMapper venueApplicationMapper,
                               ActivityMapper activityMapper,
                               SessionMapper sessionMapper,
                               TicketTypeMapper ticketTypeMapper,
                               VenueMapper venueMapper,
                               ActivitySeatLayoutService activitySeatLayoutService,
                               SessionSeatLayoutService sessionSeatLayoutService,
                               ActivityAdminService activityAdminService) {
        this(tourMapper, stationMapper, userAccessService, venueApplicationMapper, activityMapper, sessionMapper,
                ticketTypeMapper, venueMapper, activitySeatLayoutService, sessionSeatLayoutService, activityAdminService, null, null);
    }

    public TourStationService(TourMapper tourMapper,
                               StationMapper stationMapper,
                               UserAccessService userAccessService,
                               VenueApplicationMapper venueApplicationMapper,
                               ActivityMapper activityMapper,
                               SessionMapper sessionMapper,
                               TicketTypeMapper ticketTypeMapper,
                               VenueMapper venueMapper,
                               ActivitySeatLayoutService activitySeatLayoutService,
                               SessionSeatLayoutService sessionSeatLayoutService,
                               ActivityAdminService activityAdminService,
                               SessionSeatMapper sessionSeatMapper) {
        this(tourMapper, stationMapper, userAccessService, venueApplicationMapper, activityMapper, sessionMapper,
                ticketTypeMapper, venueMapper, activitySeatLayoutService, sessionSeatLayoutService, activityAdminService, sessionSeatMapper, null);
    }

    @Autowired
    public TourStationService(TourMapper tourMapper,
                               StationMapper stationMapper,
                               UserAccessService userAccessService,
                               VenueApplicationMapper venueApplicationMapper,
                               ActivityMapper activityMapper,
                               SessionMapper sessionMapper,
                               TicketTypeMapper ticketTypeMapper,
                               VenueMapper venueMapper,
                               ActivitySeatLayoutService activitySeatLayoutService,
                               SessionSeatLayoutService sessionSeatLayoutService,
                               ActivityAdminService activityAdminService,
                               SessionSeatMapper sessionSeatMapper,
                               ActivityArtistService activityArtistService) {
        this.tourMapper = tourMapper;
        this.stationMapper = stationMapper;
        this.userAccessService = userAccessService;
        this.venueApplicationMapper = venueApplicationMapper;
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.venueMapper = venueMapper;
        this.activitySeatLayoutService = activitySeatLayoutService;
        this.sessionSeatLayoutService = sessionSeatLayoutService;
        this.activityAdminService = activityAdminService;
        this.sessionSeatMapper = sessionSeatMapper;
        this.activityArtistService = activityArtistService;
    }

    @Transactional
    public Tour createTourDraft(Long userId, Map<String, Object> body) {
        return createTourDraftFromRequest(userId, toTourDraftCreateDTO(body));
    }

    @Transactional
    public Tour createTourDraftFromRequest(Long userId, TourDraftCreateDTO body) {
        InternalUserRefResponse user = requireTourManager(userId);
        if (body == null) {
            body = new TourDraftCreateDTO();
        }
        String title = requireText(body.getTitle(), "演出项目名称不能为空");
        LocalDateTime now = LocalDateTime.now();
        Tour tour = new Tour();
        tour.setTitle(title);
        tour.setArtistId(parsePositiveLong(body.getArtistId()));
        tour.setCategoryId(parsePositiveLong(body.getCategoryId()));
        tour.setPoster(optionalText(body.getPoster()));
        tour.setDescription(optionalText(body.getDescription()));
        tour.setOrganizerId("admin".equals(user.getRole())
                ? defaultLong(parsePositiveLong(body.getOrganizerId()), userId)
                : userId);
        tour.setReviewStatus("draft");
        tour.setStatus(1);
        tour.setCreateTime(now);
        tour.setUpdateTime(now);
        tourMapper.insert(tour);
        for (TourStationCityDTO cityInput : normalizeCities(body.getCities())) {
            String city = optionalText(cityInput.getCity());
            if (city == null) {
                continue;
            }
            Station station = new Station();
            station.setTourId(tour.getId());
            station.setCity(city);
            station.setStationName(defaultText(optionalText(cityInput.getStationName()), city + "站"));
            station.setPublishStatus(PUBLISH_STATUS_DRAFT);
            station.setStatus(1);
            station.setCreateTime(now);
            station.setUpdateTime(now);
            stationMapper.insert(station);
        }
        return tour;
    }

    @Transactional
    public Station createStationDraft(Long userId, Long tourId, Map<String, Object> body) {
        InternalUserRefResponse user = requireTourManager(userId);
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能管理自己的演出项目");
        }
        LocalDateTime now = LocalDateTime.now();
        Station station = new Station();
        station.setTourId(tourId);
        String city = requireText(body == null ? null : body.get("city"), "城市不能为空");
        station.setCity(city);
        station.setStationName(defaultText(optionalText(body == null ? null : body.get("stationName")), city + "站"));
        Long venueApplicationId = parsePositiveLong(body.get("venueApplicationId"));
        station.setVenueApplicationId(venueApplicationId);
        station.setPoster(optionalText(body.get("poster")));
        station.setDescription(optionalText(body.get("description")));
        station.setPublishStatus(isTrue(body.get("announceOnly")) && venueApplicationId == null
                ? PUBLISH_STATUS_CITY_ANNOUNCED
                : PUBLISH_STATUS_DRAFT);
        station.setStatus(1);
        station.setCreateTime(now);
        station.setUpdateTime(now);
        stationMapper.insert(station);
        return station;
    }

    public Page<Tour> listManageableTours(Long userId, int page, int size) {
        return listManageableTours(userId, page, size, null);
    }

    public Page<Tour> listManageableTours(Long userId, int page, int size, Long categoryId) {
        return listManageableTours(userId, page, size, categoryId, false);
    }

    public Page<Tour> listManageableTours(Long userId, int page, int size, Long categoryId, Boolean historical) {
        InternalUserRefResponse user = requireTourManager(userId);
        LambdaQueryWrapper<Tour> wrapper = new LambdaQueryWrapper<Tour>()
                .eq(Tour::getStatus, Boolean.TRUE.equals(historical) ? 0 : 1);
        if ("organizer".equals(user.getRole())) {
            wrapper.eq(Tour::getOrganizerId, userId);
        }
        if (categoryId != null) {
            wrapper.eq(Tour::getCategoryId, categoryId);
        }
        wrapper.orderByAsc(Tour::getId);
        return tourMapper.selectPage(new Page<>(Math.max(1, page), Math.max(1, size)), wrapper);
    }

    @Transactional
    public Tour announceTourCities(Long userId, Long tourId) {
        InternalUserRefResponse user = requireTourManager(userId);
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能官宣自己的演出项目");
        }
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getTourId, tourId)
                .eq(Station::getStatus, 1));
        if (stations == null) {
            stations = Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        for (Station station : stations) {
            if (PUBLISH_STATUS_PUBLISHED.equals(station.getPublishStatus())) {
                continue;
            }
            station.setPublishStatus(PUBLISH_STATUS_CITY_ANNOUNCED);
            station.setUpdateTime(now);
            stationMapper.updateById(station);
        }
        tour.setReviewStatus("announced");
        tour.setUpdateTime(now);
        tourMapper.updateById(tour);
        return tour;
    }

    @Transactional
    public void deleteTourDraft(Long userId, Long tourId) {
        InternalUserRefResponse user = requireTourManager(userId);
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能删除自己的演出项目");
        }
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getTourId, tourId)
                .eq(Station::getStatus, 1));
        if (stations == null) {
            stations = Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        for (Station station : stations) {
            station.setStatus(0);
            station.setPublishStatus("cancelled");
            station.setUpdateTime(now);
            stationMapper.updateById(station);
        }
        tour.setStatus(0);
        tour.setReviewStatus("deleted");
        tour.setUpdateTime(now);
        tourMapper.updateById(tour);
    }

    @Transactional
    public RefundImpactResponse deactivateTour(Long tourId, DeactivateActivityRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new BusinessException(400, "下架参数不能为空");
        }
        if (!Boolean.TRUE.equals(request.getConfirmRefund())) {
            throw new BusinessException(400, "下架巡演活动前必须确认同意为所有已支付订单退款");
        }
        if (activityAdminService == null) {
            throw new BusinessException(500, "活动下架服务未配置");
        }
        InternalUserRefResponse user = requireTourManager(request.getUserId());
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !request.getUserId().equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能管理自己的演出项目");
        }
        List<Activity> activities = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getTourId, tourId)
                .in(Activity::getPublishStatus, List.of(PUBLISH_STATUS_PUBLISHED, PUBLISH_STATUS_RISK_SUSPENDED)));
        if (activities == null) {
            activities = Collections.emptyList();
        }
        RefundImpactResponse response = activityAdminService.deactivateActivities(activities, request.getReason());

        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getTourId, tourId)
                .eq(Station::getStatus, 1));
        if (stations == null) {
            stations = Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        for (Station station : stations) {
            station.setPublishStatus(PUBLISH_STATUS_DEACTIVATED);
            station.setUpdateTime(now);
            stationMapper.updateById(station);
        }
        tour.setReviewStatus(REVIEW_STATUS_DEACTIVATED);
        tour.setUpdateTime(now);
        tourMapper.updateById(tour);
        response.setActivityId(tour.getId());
        response.setActivityName(tour.getTitle());
        activityAdminService.writeDeactivationAudit(request.getUserId(), user.getRole(), "tour", tour.getId(),
                tour.getTitle(), request.getReason(), response);
        return response;
    }

    public Map<String, Object> getTourDetail(Long tourId) {
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getTourId, tourId)
                .eq(Station::getStatus, 1)
                .orderByAsc(Station::getId));
        Map<String, Object> detail = new HashMap<>();
        detail.put("tour", tour);
        detail.put("stations", stations);
        detail.put("stationDetails", buildStationDetails(tourId, stations));
        return detail;
    }

    public Map<String, Object> getManageableTourDetail(Long userId, Long tourId) {
        InternalUserRefResponse user = requireTourManager(userId);
        Tour tour = tourMapper.selectById(tourId);
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能查看自己的演出项目");
        }
        return getTourDetail(tourId);
    }

    private List<Map<String, Object>> buildStationDetails(Long tourId, List<Station> stations) {
        if (stations == null || stations.isEmpty() || activityMapper == null || sessionMapper == null) {
            return Collections.emptyList();
        }
        List<Activity> activities = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getTourId, tourId)
                .eq(Activity::getStatus, 1));
        Map<Long, Activity> activityByStation = activities == null ? Collections.emptyMap() : activities.stream()
                .filter(activity -> activity.getStationId() != null)
                .collect(Collectors.toMap(Activity::getStationId, activity -> activity, (a, b) -> a));
        Set<Long> activityIds = activityByStation.values().stream().map(Activity::getId).collect(Collectors.toSet());
        List<Session> selectedSessions = activityIds.isEmpty() ? Collections.emptyList()
                : sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                        .in(Session::getActivityId, activityIds)
                        .eq(Session::getStatus, 1)
                        .orderByAsc(Session::getStartTime));
        if (selectedSessions == null) {
            selectedSessions = Collections.emptyList();
        }
        Map<Long, List<Session>> sessionsByActivity = selectedSessions.stream()
                .collect(Collectors.groupingBy(Session::getActivityId));
        Set<Long> sessionIds = sessionsByActivity.values().stream()
                .flatMap(List::stream)
                .map(Session::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<TicketType> selectedTicketTypes = sessionIds.isEmpty() || ticketTypeMapper == null ? Collections.emptyList()
                : ticketTypeMapper.selectList(new LambdaQueryWrapper<TicketType>()
                        .in(TicketType::getSessionId, sessionIds)
                        .eq(TicketType::getStatus, 1));
        if (selectedTicketTypes == null) {
            selectedTicketTypes = Collections.emptyList();
        }
        Map<Long, List<TicketType>> ticketTypesBySession = selectedTicketTypes.stream()
                .collect(Collectors.groupingBy(TicketType::getSessionId));
        Map<Long, Map<Long, TicketTypeSeatStockSnapshot>> seatStockBySessionId = seatStockSnapshotsBySessionId(selectedSessions);
        Set<Long> venueIds = sessionsByActivity.values().stream()
                .flatMap(List::stream)
                .map(Session::getVenueId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Venue> selectedVenues = venueIds.isEmpty() || venueMapper == null ? Collections.emptyList()
                : venueMapper.selectBatchIds(venueIds);
        if (selectedVenues == null) {
            selectedVenues = Collections.emptyList();
        }
        Map<Long, Venue> venueById = selectedVenues.stream()
                .collect(Collectors.toMap(Venue::getId, venue -> venue, (a, b) -> a));
        return stations.stream().map(station -> {
            Activity activity = activityByStation.get(station.getId());
            List<Session> sessions = activity == null ? Collections.emptyList() : sessionsByActivity.getOrDefault(activity.getId(), Collections.emptyList());
            Map<String, Object> item = new HashMap<>();
            item.put("station", station);
            item.put("activity", activity);
            item.put("sessions", sessions);
            applyStationSaleSummary(item, station, activity, sessions, ticketTypesBySession, seatStockBySessionId, venueById);
            return item;
        }).collect(Collectors.toList());
    }

    private Map<Long, Map<Long, TicketTypeSeatStockSnapshot>> seatStockSnapshotsBySessionId(List<Session> sessions) {
        if (sessionSeatMapper == null || sessions == null || sessions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Map<Long, TicketTypeSeatStockSnapshot>> result = new HashMap<>();
        for (Session session : sessions) {
            if (session == null || session.getId() == null) {
                continue;
            }
            List<TicketTypeSeatStockSnapshot> snapshots = sessionSeatMapper.selectSeatStockSnapshotsBySessionId(session.getId());
            if (snapshots == null || snapshots.isEmpty()) {
                continue;
            }
            Map<Long, TicketTypeSeatStockSnapshot> stockByTicketTypeId = snapshots.stream()
                    .filter(snapshot -> snapshot != null && snapshot.getTicketTypeId() != null)
                    .collect(Collectors.toMap(TicketTypeSeatStockSnapshot::getTicketTypeId, snapshot -> snapshot, (first, second) -> first));
            if (!stockByTicketTypeId.isEmpty()) {
                result.put(session.getId(), stockByTicketTypeId);
            }
        }
        return result;
    }

    private void applyStationSaleSummary(Map<String, Object> item,
                                         Station station,
                                         Activity activity,
                                         List<Session> sessions,
                                         Map<Long, List<TicketType>> ticketTypesBySession,
                                         Map<Long, Map<Long, TicketTypeSeatStockSnapshot>> seatStockBySessionId,
                                         Map<Long, Venue> venueById) {
        item.put("venueName", null);
        item.put("venueAddress", null);
        item.put("priceMin", null);
        item.put("priceMax", null);
        item.put("remainStock", null);
        if (PUBLISH_STATUS_CITY_ANNOUNCED.equals(station.getPublishStatus())) {
            putSaleState(item, SALE_STATUS_UNANNOUNCED, SALE_STATUS_TEXT_UNANNOUNCED, PRIMARY_ACTION_NONE);
            return;
        }
        if (PUBLISH_STATUS_RISK_SUSPENDED.equals(station.getPublishStatus())) {
            putSaleState(item, SALE_STATUS_SUSPENDED, SALE_STATUS_TEXT_SUSPENDED, PRIMARY_ACTION_NONE);
            return;
        }
        if (PUBLISH_STATUS_DEACTIVATED.equals(station.getPublishStatus())) {
            putSaleState(item, SALE_STATUS_DEACTIVATED, SALE_STATUS_TEXT_DEACTIVATED, PRIMARY_ACTION_NONE);
            return;
        }
        if (!PUBLISH_STATUS_PUBLISHED.equals(station.getPublishStatus()) || activity == null) {
            putSaleState(item, SALE_STATUS_COMING_SOON, SALE_STATUS_TEXT_COMING_ANNOUNCE, PRIMARY_ACTION_NONE);
            return;
        }
        if (sessions == null || sessions.isEmpty()) {
            putSaleState(item, SALE_STATUS_TO_BE_SCHEDULED, SALE_STATUS_TEXT_TO_BE_SCHEDULED, PRIMARY_ACTION_NONE);
            return;
        }
        Venue venue = sessions.stream()
                .map(Session::getVenueId)
                .filter(Objects::nonNull)
                .map(venueById::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (venue != null) {
            item.put("venueName", venue.getName());
            item.put("venueAddress", venue.getAddress());
        }
        List<TicketType> ticketTypes = sessions.stream()
                .flatMap(session -> ticketTypesBySession.getOrDefault(session.getId(), Collections.emptyList()).stream())
                .collect(Collectors.toList());
        if (ticketTypes.isEmpty()) {
            putSaleState(item, SALE_STATUS_TICKET_TBA, SALE_STATUS_TEXT_TICKET_TBA, PRIMARY_ACTION_NONE);
            return;
        }
        List<BigDecimal> prices = ticketTypes.stream()
                .map(TicketType::getPrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (!prices.isEmpty()) {
            item.put("priceMin", prices.stream().min(BigDecimal::compareTo).orElse(null));
            item.put("priceMax", prices.stream().max(BigDecimal::compareTo).orElse(null));
        }
        int remainStock = ticketTypes.stream()
                .mapToInt(ticketType -> visibleRemainStock(ticketType, seatStockBySessionId))
                .sum();
        item.put("remainStock", remainStock);
        if (remainStock > 0) {
            putSaleState(item, SALE_STATUS_ON_SALE, SALE_STATUS_TEXT_ON_SALE, PRIMARY_ACTION_BUY);
            return;
        }
        putSaleState(item, SALE_STATUS_SOLD_OUT, SALE_STATUS_TEXT_SOLD_OUT, PRIMARY_ACTION_NONE);
    }

    private int visibleRemainStock(TicketType ticketType,
                                   Map<Long, Map<Long, TicketTypeSeatStockSnapshot>> seatStockBySessionId) {
        if (ticketType == null) {
            return 0;
        }
        TicketTypeSeatStockSnapshot snapshot = null;
        if (ticketType.getSessionId() != null && ticketType.getId() != null && seatStockBySessionId != null) {
            Map<Long, TicketTypeSeatStockSnapshot> stockByTicketTypeId = seatStockBySessionId.get(ticketType.getSessionId());
            if (stockByTicketTypeId != null) {
                snapshot = stockByTicketTypeId.get(ticketType.getId());
            }
        }
        if (snapshot != null) {
            return nonNegative(cappedRemainStock(snapshot.getRemainStock(), snapshot.getTotalStock()));
        }
        return nonNegative(cappedRemainStock(ticketType.getRemainStock(), ticketType.getTotalStock()));
    }

    private Integer cappedRemainStock(Integer remainStock, Integer totalStock) {
        if (remainStock == null) {
            return null;
        }
        int visible = Math.max(0, remainStock);
        if (totalStock != null) {
            visible = Math.min(visible, Math.max(0, totalStock));
        }
        return visible;
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private void putSaleState(Map<String, Object> item, String saleStatus, String saleStatusText, String primaryAction) {
        item.put("saleStatus", saleStatus);
        item.put("saleStatusText", saleStatusText);
        item.put("primaryAction", primaryAction);
    }

    @Transactional
    public Map<String, Object> publishStation(Long userId, Long stationId, Map<String, Object> body) {
        Station station = stationMapper.selectById(stationId);
        if (station == null || !Integer.valueOf(1).equals(station.getStatus())) {
            throw new BusinessException(404, "站点不存在");
        }
        if (station.getActivityId() != null && station.getTourId() == null) {
            InternalUserRefResponse user = requireActivityManager(userId);
            return publishActivityStation(user, userId, station, body);
        }
        InternalUserRefResponse user = requireTourManager(userId);
        Tour tour = tourMapper.selectById(station.getTourId());
        if (tour == null || !Integer.valueOf(1).equals(tour.getStatus())) {
            throw new BusinessException(404, "演出项目不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(tour.getOrganizerId())) {
            throw new BusinessException(403, "只能发布自己的演出项目");
        }
        boolean scheduleTba = isTrue(body == null ? null : body.get("scheduleTba"));
        LocalDateTime startTime = scheduleTba ? null : parseTime(body == null ? null : body.get("startTime"), "开始时间不能为空");
        LocalDateTime endTime = scheduleTba ? null : parseTime(body == null ? null : body.get("endTime"), "结束时间不能为空");
        if (!scheduleTba && !endTime.isAfter(startTime)) {
            throw new BusinessException(400, "结束时间必须晚于开始时间");
        }
        VenueApplication application = requireVenueApplication(station.getVenueApplicationId());
        if ("organizer".equals(user.getRole()) && !userId.equals(application.getApplicantId())) {
            throw new BusinessException(403, "只能使用自己的场馆审核资料");
        }
        if (!Integer.valueOf(1).equals(application.getStatus()) || application.getVenueId() == null) {
            throw new BusinessException(400, "场馆审核资料未通过");
        }
        if (!scheduleTba) {
            validateApplicationValidity(application, startTime, endTime);
            ensureNoVenueConflict(application.getVenueId(), startTime, endTime);
        }

        LocalDateTime now = LocalDateTime.now();
        Activity activity = activityMapper.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStationId, station.getId())
                .eq(Activity::getStatus, 1));
        boolean existingActivity = activity != null;
        if (!existingActivity) {
            activity = new Activity();
            activity.setCreateTime(now);
            activity.setStatus(1);
        }
        activity.setCategoryId(tour.getCategoryId());
        activity.setArtistId(tour.getArtistId());
        activity.setOrganizerId(tour.getOrganizerId());
        activity.setTourId(tour.getId());
        activity.setStationId(station.getId());
        activity.setVenueApplicationId(application.getId());
        activity.setName(tour.getTitle() + " " + station.getStationName());
        activity.setDescription(defaultText(station.getDescription(), tour.getDescription()));
        activity.setPoster(defaultText(station.getPoster(), tour.getPoster()));
        activity.setPublishStatus("publishing");
        activity.setRealNameRequired(true);
        activity.setUpdateTime(now);
        if (!existingActivity) {
            activityMapper.insert(activity);
        }

        syncTourPrimaryArtist(activity.getId(), tour.getArtistId());
        copyPublishSeatCraftLayout(userId, activity, station, application);

        Session session = null;
        if (!scheduleTba) {
            session = new Session();
            session.setActivityId(activity.getId());
            session.setVenueId(application.getVenueId());
            session.setStartTime(startTime);
            session.setEndTime(endTime);
            session.setStatus(1);
            session.setCreateTime(now);
            session.setUpdateTime(now);
            sessionMapper.insert(session);

            sessionSeatLayoutService.copyFromActivity(userId, session.getId(), activity.getId());
            sessionSeatLayoutService.generateSessionSeats(session.getId());
        }

        activity.setPublishStatus(PUBLISH_STATUS_PUBLISHED);
        activityMapper.updateById(activity);
        station.setPublishStatus(PUBLISH_STATUS_PUBLISHED);
        station.setUpdateTime(now);
        stationMapper.updateById(station);

        Map<String, Object> result = new HashMap<>();
        result.put("tour", tour);
        result.put("station", station);
        result.put("activity", activity);
        result.put("session", session);
        return result;
    }

    private void syncTourPrimaryArtist(Long activityId, Long artistId) {
        if (activityArtistService == null || activityId == null || artistId == null || artistId <= 0) {
            return;
        }
        activityArtistService.ensurePrimaryArtist(activityId, artistId);
    }

    private Map<String, Object> publishActivityStation(InternalUserRefResponse user, Long userId, Station station, Map<String, Object> body) {
        Activity activity = activityMapper.selectById(station.getActivityId());
        if (activity == null || !Integer.valueOf(1).equals(activity.getStatus())) {
            throw new BusinessException(404, "活动不存在");
        }
        if ("organizer".equals(user.getRole()) && !userId.equals(activity.getOrganizerId())) {
            throw new BusinessException(403, "只能发布自己的活动");
        }
        boolean scheduleTba = isTrue(body == null ? null : body.get("scheduleTba"));
        LocalDateTime startTime = scheduleTba ? null : parseTime(body == null ? null : body.get("startTime"), "开始时间不能为空");
        LocalDateTime endTime = scheduleTba ? null : parseTime(body == null ? null : body.get("endTime"), "结束时间不能为空");
        if (!scheduleTba && !endTime.isAfter(startTime)) {
            throw new BusinessException(400, "结束时间必须晚于开始时间");
        }
        VenueApplication application = requireVenueApplication(station.getVenueApplicationId());
        if ("organizer".equals(user.getRole()) && !userId.equals(application.getApplicantId())) {
            throw new BusinessException(403, "只能使用自己的场馆审核资料");
        }
        if (!Integer.valueOf(1).equals(application.getStatus()) || application.getVenueId() == null) {
            throw new BusinessException(400, "场馆审核资料未通过");
        }
        if (!scheduleTba) {
            validateApplicationValidity(application, startTime, endTime);
            ensureNoVenueConflict(application.getVenueId(), startTime, endTime);
        }

        LocalDateTime now = LocalDateTime.now();
        activity.setVenueApplicationId(application.getId());
        activity.setPublishStatus(PUBLISH_STATUS_PUBLISHED);
        activity.setRealNameRequired(true);
        activity.setUpdateTime(now);
        copyPublishSeatCraftLayout(userId, activity, station, application);

        Session session = null;
        if (!scheduleTba) {
            session = new Session();
            session.setActivityId(activity.getId());
            session.setVenueId(application.getVenueId());
            session.setStartTime(startTime);
            session.setEndTime(endTime);
            session.setStatus(1);
            session.setCreateTime(now);
            session.setUpdateTime(now);
            sessionMapper.insert(session);
            sessionSeatLayoutService.copyFromActivity(userId, session.getId(), activity.getId());
            sessionSeatLayoutService.generateSessionSeats(session.getId());
        }

        activityMapper.updateById(activity);
        station.setPublishStatus(PUBLISH_STATUS_PUBLISHED);
        station.setUpdateTime(now);
        stationMapper.updateById(station);

        Map<String, Object> result = new HashMap<>();
        result.put("station", station);
        result.put("activity", activity);
        result.put("session", session);
        return result;
    }

    private void copyPublishSeatCraftLayout(Long userId, Activity activity, Station station, VenueApplication application) {
        if (station.getTourId() != null && activitySeatLayoutService.hasBlockLayout("station", station.getId())) {
            activitySeatLayoutService.copyFromSeatCraftOwner(userId, activity.getId(), "station", station.getId());
            return;
        }
        if (station.getActivityId() != null && activitySeatLayoutService.hasBlockLayout("activity", activity.getId())) {
            activitySeatLayoutService.copyFromSeatCraftOwner(userId, activity.getId(), "activity", activity.getId());
            return;
        }
        activitySeatLayoutService.copyFromVenueApplication(userId, activity.getId(), application.getId());
    }

    private VenueApplication requireVenueApplication(Long venueApplicationId) {
        if (venueApplicationId == null) {
            throw new BusinessException(400, "站点缺少场馆审核资料");
        }
        VenueApplication application = venueApplicationMapper.selectById(venueApplicationId);
        if (application == null) {
            throw new BusinessException(404, "场馆审核资料不存在");
        }
        return application;
    }

    private void validateApplicationValidity(VenueApplication application, LocalDateTime startTime, LocalDateTime endTime) {
        if (application.getValidFrom() == null || application.getValidTo() == null
                || startTime.isBefore(application.getValidFrom()) || endTime.isAfter(application.getValidTo())) {
            throw new BusinessException(400, "场次时间不在场馆审批文件有效期内");
        }
    }

    private void ensureNoVenueConflict(Long venueId, LocalDateTime startTime, LocalDateTime endTime) {
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getVenueId, venueId)
                .eq(Session::getStatus, 1));
        for (Session session : sessions) {
            LocalDateTime existingStart = session.getStartTime();
            LocalDateTime existingEnd = session.getEndTime() == null ? existingStart.plusHours(3) : session.getEndTime();
            if (startTime.isBefore(existingEnd) && endTime.isAfter(existingStart)) {
                throw new BusinessException(400, "同一场馆该时间段已有场次");
            }
        }
    }

    private LocalDateTime parseTime(Object value, String message) {
        String text = optionalText(value);
        if (text == null) {
            throw new BusinessException(400, message);
        }
        return LocalDateTime.parse(text.replace(" ", "T"));
    }

    private InternalUserRefResponse requireActivityManager(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage");
    }

    private InternalUserRefResponse requireTourManager(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "tour.manage");
    }

    private String requireText(Object value, String message) {
        String text = optionalText(value);
        if (text == null) {
            throw new BusinessException(400, message);
        }
        return text;
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<String> parseCities(Object value) {
        return parseCityInputs(value).stream()
                .map(TourStationCityDTO::getCity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private TourDraftCreateDTO toTourDraftCreateDTO(Map<String, Object> body) {
        TourDraftCreateDTO request = new TourDraftCreateDTO();
        if (body == null) {
            return request;
        }
        request.setTitle(optionalText(body.get("title")));
        request.setArtistId(parsePositiveLong(body.get("artistId")));
        request.setCategoryId(parsePositiveLong(body.get("categoryId")));
        request.setPoster(optionalText(body.get("poster")));
        request.setDescription(optionalText(body.get("description")));
        request.setOrganizerId(parsePositiveLong(body.get("organizerId")));
        request.setCities(parseCityInputs(body.get("cities")));
        return request;
    }

    private List<TourStationCityDTO> normalizeCities(List<TourStationCityDTO> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        return input.stream()
                .map(this::copyCityInput)
                .filter(city -> city.getCity() != null)
                .collect(Collectors.toList());
    }

    private List<TourStationCityDTO> parseCityInputs(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<TourStationCityDTO> cities = new ArrayList<>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                TourStationCityDTO city = toCityInput(item);
                if (city.getCity() != null) {
                    cities.add(city);
                }
            }
            return cities;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                TourStationCityDTO city = toCityInput(Array.get(value, i));
                if (city.getCity() != null) {
                    cities.add(city);
                }
            }
            return cities;
        }
        for (String part : value.toString().split(",")) {
            TourStationCityDTO city = toCityInput(part);
            if (city.getCity() != null) {
                cities.add(city);
            }
        }
        return cities;
    }

    private TourStationCityDTO toCityInput(Object value) {
        if (value instanceof TourStationCityDTO) {
            return copyCityInput((TourStationCityDTO) value);
        }
        TourStationCityDTO city = new TourStationCityDTO();
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            city.setCity(optionalText(map.get("city")));
            city.setStationName(optionalText(map.get("stationName")));
            return city;
        }
        city.setCity(optionalText(value));
        return city;
    }

    private TourStationCityDTO copyCityInput(TourStationCityDTO source) {
        TourStationCityDTO city = new TourStationCityDTO();
        if (source == null) {
            return city;
        }
        city.setCity(optionalText(source.getCity()));
        city.setStationName(optionalText(source.getStationName()));
        return city;
    }

    private Long parsePositiveLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Long parsed = Long.valueOf(value.toString());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long defaultLong(Long value, Long fallback) {
        return value == null ? fallback : value;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
