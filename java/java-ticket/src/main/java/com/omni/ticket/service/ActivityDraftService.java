package com.omni.ticket.service;

import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityArtistDto;
import com.omni.ticket.dto.ActivityDraftResponse;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Station;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.StationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ActivityDraftService {
    private static final String SEAT_MAP_VISIBILITY_PUBLISHED = "published";
    private static final String SEAT_MAP_VISIBILITY_HIDDEN = "hidden";

    private final ActivityMapper activityMapper;
    private final StationMapper stationMapper;
    private final UserAccessService userAccessService;
    private final ActivityArtistService activityArtistService;

    public ActivityDraftService(ActivityMapper activityMapper,
                                StationMapper stationMapper,
                                UserAccessService userAccessService,
                                ActivityArtistService activityArtistService) {
        this.activityMapper = activityMapper;
        this.stationMapper = stationMapper;
        this.userAccessService = userAccessService;
        this.activityArtistService = activityArtistService;
    }

    @Transactional
    public ActivityDraftResponse createDraft(Long userId, Map<String, Object> body) {
        userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "activity.manage");
        if (body == null) {
            throw new BusinessException(400, "活动参数不能为空");
        }
        Long categoryId = parsePositiveLong(body.get("categoryId"));
        if (categoryId == null) {
            throw new BusinessException(400, "分类ID不正确");
        }
        List<ActivityArtistDto> artists = parseArtists(body.get("artists"));
        Long artistId = artists.stream()
                .filter(artist -> Boolean.TRUE.equals(artist.getPrimary()))
                .map(ActivityArtistDto::getArtistId)
                .findFirst()
                .orElse(parsePositiveLong(body.get("artistId")));
        if (artistId == null && !artists.isEmpty()) {
            artistId = artists.get(0).getArtistId();
        }
        if (artistId == null) {
            throw new BusinessException(400, "艺人/团队名称不能为空");
        }
        String name = optionalText(body.get("name"));
        if (name == null) {
            throw new BusinessException(400, "活动名称不能为空");
        }
        String seatMapVisibility = parseSeatMapVisibility(body.get("seatMapVisibility"), SEAT_MAP_VISIBILITY_HIDDEN);
        if (seatMapVisibility == null) {
            throw new BusinessException(400, "座位图展示策略不正确");
        }

        LocalDateTime now = LocalDateTime.now();
        Activity activity = new Activity();
        activity.setCategoryId(categoryId);
        activity.setArtistId(artistId);
        activity.setName(name);
        activity.setDescription(textOrNull(body.get("description")));
        activity.setPoster(textOrNull(body.get("poster")));
        activity.setPublishStatus("draft");
        activity.setSeatMapVisibility(seatMapVisibility);
        activity.setPerUserLimit(parsePerUserLimit(body.get("perUserLimit")));
        int fullDaysBefore = parsePositiveInteger(body.get("refundFullDaysBefore"), 30, "全额退款提前天数");
        int partialDaysBefore = parsePositiveInteger(body.get("refundPartialDaysBefore"), 7, "折扣退款提前天数");
        int partialRate = parsePositiveInteger(body.get("refundPartialRate"), 80, "折扣退款比例");
        if (fullDaysBefore <= partialDaysBefore || partialRate > 100) {
            throw new BusinessException(400, "退款规则需满足全额退款天数大于折扣退款天数，折扣比例不超过100%");
        }
        activity.setRefundFullDaysBefore(fullDaysBefore);
        activity.setRefundPartialDaysBefore(partialDaysBefore);
        activity.setRefundPartialRate(partialRate);
        activity.setRealNameRequired(true);
        activity.setTicketTransferAllowed(!Boolean.FALSE.equals(parseBooleanFlag(body.get("ticketTransferAllowed"))));
        activity.setStatus(1);
        activity.setOrganizerId(userId);
        activity.setCreateTime(now);
        activity.setUpdateTime(now);
        activityMapper.insert(activity);
        if (!artists.isEmpty()) {
            try {
                activityArtistService.saveLineup(activity.getId(), artists);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, e.getMessage());
            }
        }

        Station station = new Station();
        station.setActivityId(activity.getId());
        station.setTourId(null);
        station.setPublishStatus("draft");
        station.setStatus(1);
        station.setCreateTime(now);
        station.setUpdateTime(now);
        stationMapper.insert(station);

        return new ActivityDraftResponse(activity, station);
    }

    private List<ActivityArtistDto> parseArtists(Object value) {
        if (!(value instanceof List<?>)) {
            return List.of();
        }
        List<ActivityArtistDto> artists = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            ActivityArtistDto dto = new ActivityArtistDto();
            dto.setArtistId(parsePositiveLong(map.get("artistId")));
            dto.setPrimary(Boolean.TRUE.equals(map.get("isPrimary")) || Boolean.TRUE.equals(map.get("primary")));
            dto.setRoleType(optionalText(map.get("roleType")));
            dto.setRoleName(optionalText(map.get("roleName")));
            dto.setVisibility(optionalText(map.get("visibility")));
            Long sort = parsePositiveLong(map.get("sort"));
            dto.setSort(sort == null ? null : sort.intValue());
            artists.add(dto);
        }
        return artists;
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

    private String parseSeatMapVisibility(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String visibility = value.toString().trim();
        if (SEAT_MAP_VISIBILITY_PUBLISHED.equals(visibility) || SEAT_MAP_VISIBILITY_HIDDEN.equals(visibility)) {
            return visibility;
        }
        return null;
    }

    private Integer parsePerUserLimit(Object value) {
        String text = optionalText(value);
        if (text == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(text);
            if (parsed <= 0) {
                throw new BusinessException(400, "个人限购张数必须大于0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "个人限购张数必须为数字");
        }
    }

    private int parsePositiveInteger(Object value, int defaultValue, String label) {
        String text = optionalText(value);
        if (text == null) return defaultValue;
        try {
            int parsed = Integer.parseInt(text);
            if (parsed <= 0) throw new BusinessException(400, label + "必须大于0");
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "必须为整数");
        }
    }

    private String textOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private Boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        String text = value.toString().trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }
}
