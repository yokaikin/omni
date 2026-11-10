package com.omni.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omni.order.dto.OrderListItemResponse;
import com.omni.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    String ORDER_LIST_COLUMNS = "o.id, o.order_no AS orderNo, o.user_id AS userId, o.session_id AS sessionId, " +
            "o.ticket_type_id AS ticketTypeId, o.quantity, o.amount, o.status, o.user_hidden AS userHidden, " +
            "o.user_deleted_at AS userDeletedAt, o.user_delete_expires_at AS userDeleteExpiresAt, " +
            "o.create_time AS createTime, o.update_time AS updateTime, os.activity_id AS activityId, " +
            "os.activity_name AS activityName, os.activity_poster AS activityPoster, os.venue_name AS venueName, " +
            "os.session_time AS sessionTime, os.ticket_name AS ticketName, os.unit_price AS unitPrice, os.seat_labels AS seatLabels, " +
            "os.grab_request_id AS grabRequestId, os.requested_ticket_type_id AS requestedTicketTypeId, " +
            "os.matched_ticket_type_id AS matchedTicketTypeId, os.auto_downgraded AS autoDowngraded, " +
            "os.team_id AS teamId, os.team_grab_request_id AS teamGrabRequestId, os.team_order AS teamOrder, " +
            "os.seat_selection_mode AS seatSelectionMode, " +
            "os.refund_full_days_before AS refundFullDaysBefore, os.refund_partial_days_before AS refundPartialDaysBefore, " +
            "os.refund_partial_rate AS refundPartialRate ";

    String ORDER_LIST_JOINS = "FROM \"order\" o " +
            "LEFT JOIN order_snapshot os ON os.order_id = o.id ";

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS +
            "WHERE o.user_id = #{userId} " +
            "ORDER BY o.create_time DESC")
    List<OrderListItemResponse> selectOrderListItems(Long userId);

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS +
            "WHERE o.user_id = #{userId} AND COALESCE(o.user_hidden, FALSE) = FALSE " +
            "ORDER BY o.create_time DESC")
    List<OrderListItemResponse> selectVisibleOrderListItems(Long userId);

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS +
            "WHERE o.user_id = #{userId} AND COALESCE(o.user_hidden, FALSE) = TRUE " +
            "AND (o.user_delete_expires_at IS NULL OR o.user_delete_expires_at > CURRENT_TIMESTAMP) " +
            "ORDER BY o.user_deleted_at DESC, o.create_time DESC")
    List<OrderListItemResponse> selectTrashOrderListItems(Long userId);

    @Select({"<script>",
            "SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS,
            "WHERE o.session_id IN",
            "<foreach collection='sessionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "<if test='paidOnly'>AND o.status = 2</if>",
            "ORDER BY o.id ASC",
            "</script>"})
    List<OrderListItemResponse> selectOrderListItemsBySessions(@Param("sessionIds") List<Long> sessionIds,
                                                               @Param("paidOnly") boolean paidOnly);

    @Select({"<script>",
            "SELECT COUNT(*) FROM \"order\" WHERE status = 2",
            "AND session_id IN",
            "<foreach collection='sessionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    Long countPaidOrdersBySessions(@Param("sessionIds") List<Long> sessionIds);

    @Select("SELECT COALESCE(SUM(o.quantity), 0) " +
            "FROM \"order\" o " +
            "JOIN order_snapshot os ON os.order_id = o.id " +
            "WHERE o.user_id = #{userId} " +
            "AND os.activity_id = #{activityId} " +
            "AND o.status IN (1, 2)")
    Integer sumEffectiveQuantityByUserAndActivity(@Param("userId") Long userId, @Param("activityId") Long activityId);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{key})::bigint)")
    int acquireAdvisoryTransactionLock(@Param("key") String key);

    @Select("SELECT * FROM \"order\" WHERE id = #{id} FOR UPDATE")
    Order selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS + "WHERE o.id = #{id} LIMIT 1")
    OrderListItemResponse selectOrderListItemById(@Param("id") Long id);

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS +
            "WHERE os.grab_request_id = #{grabRequestId} LIMIT 1")
    OrderListItemResponse selectOrderListItemByGrabRequestId(@Param("grabRequestId") String grabRequestId);

    @Select("SELECT " + ORDER_LIST_COLUMNS + ORDER_LIST_JOINS +
            "WHERE os.team_grab_request_id = #{teamGrabRequestId} " +
            "AND os.team_order = TRUE LIMIT 1")
    OrderListItemResponse selectTeamOrderListItemByTeamGrabRequestId(@Param("teamGrabRequestId") String teamGrabRequestId);

    @Update("UPDATE \"order\" SET status = #{nextStatus}, update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") Integer expectedStatus,
                              @Param("nextStatus") Integer nextStatus);
}
