package com.omni.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omni.ticket.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
    @Select("SELECT id FROM activity WHERE status = 1 AND publish_status = 'published' "
            + "AND EXISTS (SELECT 1 FROM session ended WHERE ended.activity_id = activity.id AND ended.status = 1 "
            + "AND ended.start_time IS NOT NULL AND COALESCE(ended.end_time, ended.start_time + INTERVAL '3 hours') "
            + "<= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')) "
            + "AND NOT EXISTS (SELECT 1 FROM session s WHERE s.activity_id = activity.id AND s.status = 1 "
            + "AND COALESCE(s.end_time, s.start_time + INTERVAL '3 hours') > (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'))")
    List<Long> selectExpiredPublishedIds();

    @Update("UPDATE activity SET status = 0, publish_status = 'deactivated', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 1 AND publish_status = 'published' "
            + "AND EXISTS (SELECT 1 FROM session ended WHERE ended.activity_id = activity.id AND ended.status = 1 "
            + "AND ended.start_time IS NOT NULL "
            + "AND COALESCE(ended.end_time, ended.start_time + INTERVAL '3 hours') <= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')) "
            + "AND NOT EXISTS (SELECT 1 FROM session upcoming WHERE upcoming.activity_id = activity.id AND upcoming.status = 1 "
            + "AND (upcoming.start_time IS NULL OR COALESCE(upcoming.end_time, upcoming.start_time + INTERVAL '3 hours') "
            + "> (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'))) ")
    int deactivateExpired(@org.apache.ibatis.annotations.Param("id") Long id);
}
