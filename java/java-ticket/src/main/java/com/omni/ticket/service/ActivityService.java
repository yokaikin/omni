package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityDetailVO;
import com.omni.ticket.dto.ActivityArtistDto;
import com.omni.ticket.dto.ActivityVO;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.entity.*;
import com.omni.ticket.mapper.*;
import com.omni.ticket.search.ActivitySearchProvider;
import com.omni.ticket.search.ActivitySearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 活动服务
 */
@Service
public class ActivityService {

    private static final String PUBLISH_STATUS_PUBLISHED = "published";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final CategoryMapper categoryMapper;
    private final ArtistMapper artistMapper;
    private final SessionMapper sessionMapper;
    private final VenueMapper venueMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final ActivityArtistService activityArtistService;
    private final TourMapper tourMapper;
    private final StationMapper stationMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final ActivitySearchProvider activitySearchProvider;

    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                             ArtistMapper artistMapper, SessionMapper sessionMapper,
                             VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper) {
        this(activityMapper, categoryMapper, artistMapper, sessionMapper, venueMapper, ticketTypeMapper, null, null, null, null);
    }

    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                           ArtistMapper artistMapper, SessionMapper sessionMapper,
                           VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper,
                           ActivityArtistService activityArtistService) {
        this(activityMapper, categoryMapper, artistMapper, sessionMapper, venueMapper, ticketTypeMapper,
                activityArtistService, null, null, null);
    }

    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                            ArtistMapper artistMapper, SessionMapper sessionMapper,
                            VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper,
                            ActivityArtistService activityArtistService,
                            TourMapper tourMapper,
                            StationMapper stationMapper) {
        this(activityMapper, categoryMapper, artistMapper, sessionMapper, venueMapper, ticketTypeMapper,
                activityArtistService, tourMapper, stationMapper, null);
    }

    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                            ArtistMapper artistMapper, SessionMapper sessionMapper,
                            VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper,
                            ActivityArtistService activityArtistService,
                            TourMapper tourMapper,
                            StationMapper stationMapper,
                            SessionSeatMapper sessionSeatMapper) {
        this(activityMapper, categoryMapper, artistMapper, sessionMapper, venueMapper, ticketTypeMapper,
                activityArtistService, tourMapper, stationMapper, sessionSeatMapper, (ActivitySearchProvider) null);
    }

    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                            ArtistMapper artistMapper, SessionMapper sessionMapper,
                            VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper,
                            ActivityArtistService activityArtistService,
                            TourMapper tourMapper,
                            StationMapper stationMapper,
                            SessionSeatMapper sessionSeatMapper,
                            ActivitySearchProvider activitySearchProvider) {
        this.activityMapper = activityMapper;
        this.categoryMapper = categoryMapper;
        this.artistMapper = artistMapper;
        this.sessionMapper = sessionMapper;
        this.venueMapper = venueMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.activityArtistService = activityArtistService;
        this.tourMapper = tourMapper;
        this.stationMapper = stationMapper;
        this.sessionSeatMapper = sessionSeatMapper;
        this.activitySearchProvider = activitySearchProvider;
    }

    @Autowired
    public ActivityService(ActivityMapper activityMapper, CategoryMapper categoryMapper,
                            ArtistMapper artistMapper, SessionMapper sessionMapper,
                            VenueMapper venueMapper, TicketTypeMapper ticketTypeMapper,
                            ActivityArtistService activityArtistService,
                            TourMapper tourMapper,
                            StationMapper stationMapper,
                            SessionSeatMapper sessionSeatMapper,
                            ObjectProvider<ActivitySearchProvider> activitySearchProvider) {
        this(activityMapper, categoryMapper, artistMapper, sessionMapper, venueMapper, ticketTypeMapper,
                activityArtistService, tourMapper, stationMapper, sessionSeatMapper,
                activitySearchProvider == null ? null : activitySearchProvider.getIfAvailable());
    }

    /**
     * 活动列表（分页 + 分类筛选）
     */
    public Page<ActivityVO> listActivities(Integer page, Integer size, Long categoryId) {
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<>();
        if (categoryId != null) {
            wrapper.eq(Activity::getCategoryId, categoryId);
        }
        wrapper.eq(Activity::getStatus, 1);
        wrapper.eq(Activity::getPublishStatus, PUBLISH_STATUS_PUBLISHED);
        wrapper.apply("EXISTS (SELECT 1 FROM session s WHERE s.activity_id = activity.id AND s.status = 1 "
                + "AND COALESCE(s.end_time, s.start_time + INTERVAL '3 hours') "
                + "> (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'))");
        wrapper.isNull(Activity::getTourId);
        wrapper.orderByDesc(Activity::getCreateTime);

        Page<Activity> activityPage = activityMapper.selectPage(new Page<>(page, size), wrapper);
        List<Activity> records = activityPage.getRecords();
        Page<Tour> tourPage = selectAnnouncedTours(page, size, categoryId);
        if (records.isEmpty() && (tourPage == null || tourPage.getRecords().isEmpty())) {
            return new Page<ActivityVO>(page, size, activityPage.getTotal()).setRecords(Collections.emptyList());
        }
        List<ActivityVO> announcedTours = buildAnnouncedTourItems(tourPage);
        long tourTotal = tourPage == null ? 0 : tourPage.getTotal();
        if (records.isEmpty()) {
            return new Page<ActivityVO>(page, size, activityPage.getTotal() + tourTotal).setRecords(announcedTours);
        }

        // 1. 收集所有的 ID
        Set<Long> categoryIds = records.stream().map(Activity::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> artistIds = records.stream().map(Activity::getArtistId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> activityIds = records.stream().map(Activity::getId).collect(Collectors.toList());

        // 2. 批量查询分类和艺人
        Map<Long, Category> categoryMap = categoryIds.isEmpty() ? Collections.emptyMap() :
                categoryMapper.selectBatchIds(categoryIds).stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<Long, Artist> artistMap = artistIds.isEmpty() ? Collections.emptyMap() :
                artistMapper.selectBatchIds(artistIds).stream().collect(Collectors.toMap(Artist::getId, Function.identity()));

        // 3. 批量查询场次
        LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<>();
        sessionWrapper.in(Session::getActivityId, activityIds).eq(Session::getStatus, 1).orderByAsc(Session::getStartTime);
        List<Session> allSessions = sessionMapper.selectList(sessionWrapper);
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        allSessions = allSessions.stream()
                .filter(session -> session.getStartTime() == null
                        || (session.getEndTime() == null ? session.getStartTime().plusHours(3) : session.getEndTime()).isAfter(now))
                .collect(Collectors.toList());
        
        // 按活动ID分组场次
        Map<Long, List<Session>> activitySessionMap = allSessions.stream().collect(Collectors.groupingBy(Session::getActivityId));
        
        // 收集所有场馆ID和场次ID
        Set<Long> venueIds = allSessions.stream().map(Session::getVenueId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> sessionIds = allSessions.stream().map(Session::getId).collect(Collectors.toList());

        // 4. 批量查询场馆
        Map<Long, Venue> venueMap = venueIds.isEmpty() ? Collections.emptyMap() :
                venueMapper.selectBatchIds(venueIds).stream().collect(Collectors.toMap(Venue::getId, Function.identity()));

        // 5. 批量查询票档
        Map<Long, List<TicketType>> sessionTicketMap = Collections.emptyMap();
        if (!sessionIds.isEmpty()) {
            LambdaQueryWrapper<TicketType> ttWrapper = new LambdaQueryWrapper<>();
            ttWrapper.in(TicketType::getSessionId, sessionIds).eq(TicketType::getStatus, 1);
            List<TicketType> allTickets = ticketTypeMapper.selectList(ttWrapper);
            sessionTicketMap = allTickets.stream().collect(Collectors.groupingBy(TicketType::getSessionId));
        }

        final Map<Long, List<TicketType>> finalSessionTicketMap = sessionTicketMap;

        // 6. 组装结果
        Page<ActivityVO> voPage = new Page<>(page, size, activityPage.getTotal());
        List<ActivityVO> voList = records.stream().map(activity -> {
            ActivityVO vo = new ActivityVO();
            vo.setId(activity.getId());
            vo.setItemType("activity");
            vo.setName(activity.getName());
            vo.setPoster(activity.getPoster());
            vo.setCategoryId(activity.getCategoryId());
            vo.setOrganizerId(activity.getOrganizerId());
            vo.setSeatMapVisibility(activity.getSeatMapVisibility());
            vo.setStatus(activity.getStatus());
            vo.setRealNameRequired(true);
            vo.setTicketTransferAllowed(!Boolean.FALSE.equals(activity.getTicketTransferAllowed()));

            Category category = categoryMap.get(activity.getCategoryId());
            if (category != null) vo.setCategoryName(category.getName());

            List<ActivityArtistDto> publicArtists = activityArtistService == null ? List.of() : activityArtistService.listPublicLineup(activity.getId());
            vo.setArtists(publicArtists);
            if (!publicArtists.isEmpty()) {
                vo.setArtistName(publicArtists.stream()
                        .map(ActivityArtistDto::getName)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.joining("、")));
            } else {
                Artist artist = artistMap.get(activity.getArtistId());
                if (artist != null) vo.setArtistName(artist.getName());
            }

            List<Session> sessions = activitySessionMap.getOrDefault(activity.getId(), Collections.emptyList());
            if (!sessions.isEmpty()) {
                Session firstSession = sessions.get(0);
                vo.setStartTime(firstSession.getStartTime());
                Venue venue = venueMap.get(firstSession.getVenueId());
                if (venue != null) {
                    vo.setVenueCity(venue.getCity());
                    vo.setVenueName(venue.getName());
                }

                BigDecimal minPrice = null;
                BigDecimal maxPrice = null;
                for (Session s : sessions) {
                    List<TicketType> tickets = finalSessionTicketMap.getOrDefault(s.getId(), Collections.emptyList());
                    for (TicketType t : tickets) {
                        BigDecimal price = t.getPrice();
                        if (price == null) {
                            continue;
                        }
                        if (minPrice == null || price.compareTo(minPrice) < 0) {
                            minPrice = price;
                        }
                        if (maxPrice == null || price.compareTo(maxPrice) > 0) {
                            maxPrice = price;
                        }
                    }
                }
                vo.setMinPrice(minPrice);
                vo.setMaxPrice(maxPrice);
            }
            return vo;
        }).collect(Collectors.toList());
        voList.addAll(0, announcedTours);

        voPage.setRecords(voList);
        voPage.setTotal(activityPage.getTotal() + tourTotal);
        return voPage;
    }

    public Page<ActivityVO> searchActivities(Integer page,
                                             Integer size,
                                             Long categoryId,
                                             String keyword,
                                             String city,
                                             LocalDate dateFrom,
                                             LocalDate dateTo,
                                             BigDecimal minPrice,
                                             BigDecimal maxPrice,
                                             String saleStatus,
                                             Boolean seatMapOnly,
                                             Boolean realNameRequired,
                                             String sort) {
        if (activitySearchProvider == null) {
            throw new BusinessException(503, "搜索服务暂时不可用，请稍后重试");
        }
        return activitySearchProvider.search(ActivitySearchRequest.builder()
                .page(page)
                .size(size)
                .categoryId(categoryId)
                .keyword(keyword)
                .city(city)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .saleStatus(saleStatus)
                .seatMapOnly(seatMapOnly)
                .realNameRequired(realNameRequired)
                .sort(sort)
                .build());
    }

    private Page<Tour> selectAnnouncedTours(Integer page, Integer size, Long categoryId) {
        if (tourMapper == null) {
            return new Page<>(page, size, 0);
        }
        LambdaQueryWrapper<Tour> wrapper = new LambdaQueryWrapper<Tour>()
                .eq(Tour::getStatus, 1)
                .eq(Tour::getReviewStatus, "announced")
                .orderByDesc(Tour::getUpdateTime);
        if (categoryId != null) {
            wrapper.eq(Tour::getCategoryId, categoryId);
        }
        return tourMapper.selectPage(new Page<>(page, size), wrapper);
    }

    private List<ActivityVO> buildAnnouncedTourItems(Page<Tour> tourPage) {
        if (tourPage == null || tourPage.getRecords() == null || tourPage.getRecords().isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, List<Station>> stationsByTour = Collections.emptyMap();
        if (stationMapper != null) {
            List<Long> tourIds = tourPage.getRecords().stream().map(Tour::getId).collect(Collectors.toList());
            List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                    .in(Station::getTourId, tourIds)
                    .eq(Station::getStatus, 1)
                    .orderByAsc(Station::getId));
            stationsByTour = stations == null ? Collections.emptyMap()
                    : stations.stream().collect(Collectors.groupingBy(Station::getTourId));
        }
        Set<Long> stationIds = stationsByTour.values().stream()
                .flatMap(List::stream)
                .map(Station::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Activity> tourActivities = stationIds.isEmpty() ? Collections.emptyList()
                : activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                        .in(Activity::getStationId, stationIds)
                        .eq(Activity::getStatus, 1)
                        .eq(Activity::getPublishStatus, PUBLISH_STATUS_PUBLISHED));
        if (tourActivities == null) {
            tourActivities = Collections.emptyList();
        }
        Map<Long, Activity> activityByStation = tourActivities.stream()
                .filter(activity -> activity.getStationId() != null)
                .collect(Collectors.toMap(Activity::getStationId, Function.identity(), (a, b) -> a));
        Set<Long> activityIds = tourActivities.stream()
                .map(Activity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Session> sessions = activityIds.isEmpty() ? Collections.emptyList()
                : sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                        .in(Session::getActivityId, activityIds)
                        .eq(Session::getStatus, 1)
                        .orderByAsc(Session::getStartTime));
        if (sessions == null) {
            sessions = Collections.emptyList();
        }
        Map<Long, List<Session>> sessionsByActivity = sessions.stream()
                .collect(Collectors.groupingBy(Session::getActivityId));
        Set<Long> tourVenueIds = sessions.stream()
                .map(Session::getVenueId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Venue> tourVenues = tourVenueIds.isEmpty() ? Collections.emptyList()
                : venueMapper.selectBatchIds(tourVenueIds);
        if (tourVenues == null) {
            tourVenues = Collections.emptyList();
        }
        Map<Long, Venue> venueById = tourVenues.stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity()));
        Set<Long> sessionIds = sessions.stream()
                .map(Session::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<TicketType> ticketTypes = sessionIds.isEmpty() ? Collections.emptyList()
                : ticketTypeMapper.selectList(new LambdaQueryWrapper<TicketType>()
                        .in(TicketType::getSessionId, sessionIds)
                        .eq(TicketType::getStatus, 1));
        if (ticketTypes == null) {
            ticketTypes = Collections.emptyList();
        }
        Map<Long, List<TicketType>> ticketTypesBySession = ticketTypes.stream()
                .collect(Collectors.groupingBy(TicketType::getSessionId));
        final Map<Long, List<Station>> finalStationsByTour = stationsByTour;
        final Map<Long, Activity> finalActivityByStation = activityByStation;
        final Map<Long, List<Session>> finalSessionsByActivity = sessionsByActivity;
        final Map<Long, Venue> finalVenueById = venueById;
        final Map<Long, List<TicketType>> finalTicketTypesBySession = ticketTypesBySession;
        return tourPage.getRecords().stream().map(tour -> {
            ActivityVO vo = new ActivityVO();
            vo.setId(tour.getId());
            vo.setItemType("tour");
            vo.setName(tour.getTitle());
            vo.setPoster(tour.getPoster());
            vo.setCategoryId(tour.getCategoryId());
            vo.setOrganizerId(tour.getOrganizerId());
            Category category = tour.getCategoryId() == null ? null : categoryMapper.selectById(tour.getCategoryId());
            if (category != null) vo.setCategoryName(category.getName());
            Artist artist = tour.getArtistId() == null ? null : artistMapper.selectById(tour.getArtistId());
            if (artist != null) vo.setArtistName(artist.getName());
            List<Station> stations = finalStationsByTour.getOrDefault(tour.getId(), Collections.emptyList());
            vo.setVenueCity(stations.stream()
                    .map(Station::getCity)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(" / ")));
            List<Session> tourSessions = stations.stream()
                    .map(station -> finalActivityByStation.get(station.getId()))
                    .filter(Objects::nonNull)
                    .flatMap(activity -> finalSessionsByActivity.getOrDefault(activity.getId(), Collections.emptyList()).stream())
                    .collect(Collectors.toList());
            vo.setVenueName(tourSessions.stream()
                    .map(Session::getVenueId)
                    .map(finalVenueById::get)
                    .filter(Objects::nonNull)
                    .map(Venue::getName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.joining(" / ")));
            LocalDateTime firstStartTime = tourSessions.stream()
                    .map(Session::getStartTime)
                    .filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);
            vo.setStartTime(firstStartTime);
            BigDecimal minPrice = tourSessions.stream()
                    .flatMap(session -> finalTicketTypesBySession.getOrDefault(session.getId(), Collections.emptyList()).stream())
                    .map(TicketType::getPrice)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);
            BigDecimal maxPrice = tourSessions.stream()
                    .flatMap(session -> finalTicketTypesBySession.getOrDefault(session.getId(), Collections.emptyList()).stream())
                    .map(TicketType::getPrice)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            vo.setMinPrice(minPrice);
            vo.setMaxPrice(maxPrice);
            vo.setStatus(minPrice == null ? 2 : 1);
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 活动详情
     */
    public ActivityDetailVO getActivityDetail(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (!isPublicActivity(activity)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "活动不存在");
        }

        ActivityDetailVO detail = new ActivityDetailVO();
        detail.setActivity(activity);
        detail.setCategory(categoryMapper.selectById(activity.getCategoryId()));
        detail.setArtist(artistMapper.selectById(activity.getArtistId()));
        detail.setArtists(activityArtistService == null ? List.of() : activityArtistService.listPublicLineup(id));

        // 查询所有场次及票档
        LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<>();
        sessionWrapper.eq(Session::getActivityId, id)
                      .eq(Session::getStatus, 1)
                      .orderByAsc(Session::getStartTime);
        List<Session> sessions = sessionMapper.selectList(sessionWrapper);
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        boolean hasSessions = sessions != null && !sessions.isEmpty();
        sessions = sessions.stream()
                .filter(session -> session.getStartTime() == null
                        || (session.getEndTime() == null ? session.getStartTime().plusHours(3) : session.getEndTime()).isAfter(now))
                .collect(Collectors.toList());
        if (hasSessions && sessions.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "活动已结束");
        }

        List<ActivityDetailVO.SessionDetail> sessionDetails = new ArrayList<>();
        for (Session session : sessions) {
            ActivityDetailVO.SessionDetail sd = new ActivityDetailVO.SessionDetail();
            sd.setSession(session);
            sd.setVenue(venueMapper.selectById(session.getVenueId()));

            LambdaQueryWrapper<TicketType> ttWrapper = new LambdaQueryWrapper<>();
            ttWrapper.eq(TicketType::getSessionId, session.getId())
                     .eq(TicketType::getStatus, 1)
                     .orderByAsc(TicketType::getPrice);
            List<TicketType> sessionTicketTypes = ticketTypeMapper.selectList(ttWrapper);
            applySeatStockSnapshots(session.getId(), sessionTicketTypes);
            sd.setTicketTypes(sessionTicketTypes);

            sessionDetails.add(sd);
        }
        detail.setSessions(sessionDetails);

        return detail;
    }

    private boolean isPublicActivity(Activity activity) {
        return activity != null
                && Integer.valueOf(1).equals(activity.getStatus())
                && PUBLISH_STATUS_PUBLISHED.equals(activity.getPublishStatus());
    }

    private void applySeatStockSnapshots(Long sessionId, List<TicketType> ticketTypes) {
        if (sessionSeatMapper == null || sessionId == null || ticketTypes == null || ticketTypes.isEmpty()) {
            return;
        }
        List<TicketTypeSeatStockSnapshot> snapshots = sessionSeatMapper.selectSeatStockSnapshotsBySessionId(sessionId);
        if (snapshots == null || snapshots.isEmpty()) {
            ticketTypes.forEach(ticketType -> ticketType.setRemainStock(visibleRemainStock(ticketType)));
            return;
        }
        Map<Long, TicketTypeSeatStockSnapshot> stockByTicketTypeId = snapshots.stream()
                .filter(snapshot -> snapshot != null && snapshot.getTicketTypeId() != null)
                .collect(Collectors.toMap(TicketTypeSeatStockSnapshot::getTicketTypeId, Function.identity(), (first, second) -> first));
        for (TicketType ticketType : ticketTypes) {
            if (ticketType == null || ticketType.getId() == null) {
                continue;
            }
            TicketTypeSeatStockSnapshot snapshot = stockByTicketTypeId.get(ticketType.getId());
            if (snapshot == null) {
                ticketType.setRemainStock(visibleRemainStock(ticketType));
                continue;
            }
            ticketType.setTotalStock(nonNegative(snapshot.getTotalStock()));
            ticketType.setRemainStock(cappedRemainStock(snapshot.getRemainStock(), snapshot.getTotalStock()));
        }
    }

    private Integer visibleRemainStock(TicketType ticketType) {
        if (ticketType == null || ticketType.getRemainStock() == null) {
            return null;
        }
        return cappedRemainStock(ticketType.getRemainStock(), ticketType.getTotalStock());
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

    private Integer nonNegative(Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    /**
     * 分类列表
     */
    public List<Category> listCategories() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1).orderByAsc(Category::getSort);
        return categoryMapper.selectList(wrapper);
    }
}
