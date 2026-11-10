package com.omni.ticket.service;

import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.search.ActivitySearchIndexEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/** 将最后一个有效场次结束的活动持久化为已下架。 */
@Component
public class ActivityExpiryScheduler {
    private final ActivityMapper activityMapper;
    private final ActivitySearchIndexEventPublisher searchIndexPublisher;

    public ActivityExpiryScheduler(ActivityMapper activityMapper,
                                   ActivitySearchIndexEventPublisher searchIndexPublisher) {
        this.activityMapper = activityMapper;
        this.searchIndexPublisher = searchIndexPublisher;
    }

    @Scheduled(fixedDelayString = "${omni.ticket.activity-expiry-scan-ms:60000}")
    public void deactivateExpiredActivities() {
        List<Long> ids = activityMapper.selectExpiredPublishedIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            if (activityMapper.deactivateExpired(id) > 0) {
                searchIndexPublisher.publishDelete(id);
            }
        }
    }
}
