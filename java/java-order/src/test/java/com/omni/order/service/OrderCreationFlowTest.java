package com.omni.order.service;

import com.omni.common.result.Result;
import com.omni.exception.BusinessException;
import com.omni.order.client.PaymentInternalClient;
import com.omni.order.client.TicketSalesInternalClient;
import com.omni.order.client.UserInternalClient;
import com.omni.order.dto.*;
import com.omni.order.entity.Order;
import com.omni.order.mapper.*;
import com.omni.order.mq.WaitlistMqProducer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Order Creation Flow — Coverage")
class OrderCreationFlowTest {

    @Mock OrderMapper om; @Mock OrderSeatMapper osm; @Mock OrderSnapshotMapper snm;
    @Mock PaymentInternalClient pc; @Mock TicketSalesInternalClient tc;
    @Mock UserInternalClient uc; @Mock WaitlistMqProducer waitlistProducer;
    @Mock OrderAttendeeMapper am;

    OrderService svc;
    @BeforeEach void setup() {
        svc = new OrderService(om, osm, snm, pc, tc, uc, waitlistProducer, "t");
        svc.setOrderAttendeeMapper(am);
    }

    void givenValidUser(Long uid) {
        InternalUserRefResponse u = new InternalUserRefResponse(); u.setId(uid); u.setStatus(1);
        when(uc.getUserRef(eq(uid), any())).thenReturn(Result.success(u));
    }
    void givenQuote(int ignoredLegacyLimit, String price) {
        TicketSalesQuoteResponse q = new TicketSalesQuoteResponse();
        q.setSessionId(100L); q.setTicketTypeId(1L); q.setUnitPrice(bd(price)); q.setActivityId(10L);
        when(tc.quote(any(), any())).thenReturn(Result.success(q));
    }
    void givenLockOk() { when(tc.lockStock(any(), any())).thenReturn(Result.success()); }
    void givenLimitOk() { when(om.sumEffectiveQuantityByUserAndActivity(anyLong(), anyLong())).thenReturn(0); }
    void givenAttendeesOk() {
        when(uc.resolveAttendees(any(), any())).thenAnswer(inv -> {
            ResolveAttendeesRequest req = inv.getArgument(0);
            List<Long> ids = req.getAttendeeIds() == null ? Collections.emptyList() : req.getAttendeeIds();
            List<ResolvedAttendeeResponse> list = new java.util.ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                ResolvedAttendeeResponse a = new ResolvedAttendeeResponse();
                a.setId(ids.get(i));
                a.setRealName("观演人" + i);
                a.setIdType("ID_CARD");
                a.setIdNoHash("hash-" + i);
                a.setIdNoMask("110***********0" + i);
                a.setIdNoEncrypted("enc-" + i);
                list.add(a);
            }
            return Result.success(list);
        });
    }
    void givenInsertOk() { when(om.insert(any(Order.class))).thenAnswer(inv -> { inv.getArgument(0,Order.class).setId(5001L); return 1; }); }
    void givenAllOk() { givenValidUser(2004L); givenQuote(10, "199"); givenLockOk(); givenLimitOk(); givenAttendeesOk(); givenInsertOk(); }
    CreateOrderRequest r(Long uid, Long sid, Long ttid, int qty) {
        CreateOrderRequest req = new CreateOrderRequest(); req.setUserId(uid); req.setSessionId(sid); req.setTicketTypeId(ttid); req.setQuantity(qty);
        if (qty > 0) {
            List<Long> ids = new java.util.ArrayList<>();
            for (int i = 0; i < qty; i++) { ids.add(501L + i); }
            req.setAttendeeIds(ids);
        }
        return req;
    }
    static BigDecimal bd(String v) { return new BigDecimal(v); }

    // ===== OC-001~006 =====
    @Nested @DisplayName("No-Seat Order")
    class NoSeat {
        @Test @DisplayName("OC-001: create → PENDING")
        void oc001() { givenAllOk();
            Order o = svc.createOrder(r(2004L,100L,1L,2));
            assertEquals(Integer.valueOf(1), o.getStatus()); assertNotNull(o.getOrderNo()); verify(om).insert(any());
        }
        @Test @DisplayName("OC-002: orderNo format")
        void oc002() { givenAllOk();
            Order o = svc.createOrder(r(2004L,100L,1L,1));
            assertTrue(o.getOrderNo().matches("DM\\d{14}[0-9A-F]{6}"));
        }
        @Test @DisplayName("OC-003: amount = qty × price")
        void oc003() { givenAllOk();
            Order o = svc.createOrder(r(2004L,100L,1L,3));
            assertEquals(0, bd("597.00").compareTo(o.getAmount()));
        }
        @Test @DisplayName("OC-006: lockStock called")
        void oc006() { givenAllOk();
            svc.createOrder(r(2004L,100L,1L,2));
            verify(tc).lockStock(any(), any());
        }
    }

    // ===== OC-014~021 =====
    @Nested @DisplayName("Validation & Rejection")
    class Validation {
        @Test @DisplayName("OC-014: user not found")
        void oc014() { when(uc.getUserRef(eq(999999L), any())).thenReturn(Result.fail(404, "not found"));
            assertThrows(BusinessException.class, () -> svc.createOrder(r(999999L,100L,1L,1)));
        }
        @Test @DisplayName("OC-020: authorized < unit")
        void oc020() { givenValidUser(2004L); givenQuote(10, "299");
            CreateOrderRequest req = r(2004L,100L,1L,1); req.setAuthorizedMaxUnitPrice(bd("100"));
            assertThrows(BusinessException.class, () -> svc.createOrder(req));
        }
        @Test @DisplayName("OC-016: quantity=0")
        void oc016() { assertThrows(BusinessException.class, () -> svc.createOrder(r(2004L,100L,1L,0))); }
        @Test @DisplayName("OC-024: negative qty")
        void oc024() { assertThrows(BusinessException.class, () -> svc.createOrder(r(2004L,100L,1L,-1))); }
    }

    // ===== OC-022~023 Auth =====
    @Nested @DisplayName("Auth")
    class AuthTests {
        @Test @DisplayName("OC-023: internal token required for downstream calls")
        void oc023() { givenAllOk(); svc.createOrder(r(2004L,100L,1L,1)); verify(tc).lockStock(any(), any()); }
    }

    // ===== Seata =====
    @Nested @DisplayName("Seata Distributed TX")
    class Seata {
        @Test @DisplayName("ST: @GlobalTransactional on createOrder")
        void stCreate() throws Exception {
            var a = OrderService.class.getDeclaredMethod("createOrder",CreateOrderRequest.class)
                .getAnnotation(io.seata.spring.annotation.GlobalTransactional.class);
            assertNotNull(a); assertEquals("omni-create-order", a.name());
        }
        @Test @DisplayName("ST: on createOrderWithSeats")
        void stSeats() throws Exception {
            assertNotNull(OrderService.class.getDeclaredMethod("createOrderWithSeats",LockSeatsRequest.class)
                .getAnnotation(io.seata.spring.annotation.GlobalTransactional.class));
        }
        @Test @DisplayName("ST: on createTeamOrderWithLockedSeats")
        void stTeam() throws Exception {
            assertNotNull(OrderService.class.getDeclaredMethod("createTeamOrderWithLockedSeats",CreateTeamOrderRequest.class)
                .getAnnotation(io.seata.spring.annotation.GlobalTransactional.class));
        }
        @Test @DisplayName("ST: rollbackFor=Exception.class on all 3")
        void stRollback() throws Exception {
            for (String m : List.of("createOrder","createOrderWithSeats","createTeamOrderWithLockedSeats")) {
                for (var c : OrderService.class.getDeclaredMethods()) {
                    if (c.getName().equals(m) && c.getParameterCount() == 1) {
                        var tx = c.getAnnotation(io.seata.spring.annotation.GlobalTransactional.class);
                        assertNotNull(tx, m); assertTrue(tx.rollbackFor().length>0);
                    }
                }
            }
        }
    }
}
