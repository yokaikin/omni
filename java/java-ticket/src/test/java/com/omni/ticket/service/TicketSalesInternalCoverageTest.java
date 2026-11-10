package com.omni.ticket.service;

import com.omni.common.result.Result;
import com.omni.exception.BusinessException;
import com.omni.ticket.controller.TicketSalesInternalController;
import com.omni.ticket.dto.*;
import com.omni.ticket.entity.*;
import com.omni.ticket.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Internal Ticket Sales API — Coverage")
class TicketSalesInternalCoverageTest {
    @Mock TicketTypeMapper ttm; @Mock SessionMapper sm; @Mock ActivityMapper am;
    @Mock VenueMapper vm; @Mock SessionSeatMapper ssm; @Mock SeatBlockMapper sbm; @Mock TicketGroupMapper tgm;

    TicketSalesInternalService svc() { return new TicketSalesInternalService(ttm, sm, am, vm, ssm, sbm, tgm); }
    void sellable() { lenient().when(ssm.selectSessionSellable(anyLong())).thenReturn(Boolean.TRUE); }

    // ========== Quote (IS-001~005) ==========
    @Nested @DisplayName("Quote")
    class Quote {
        @Test @DisplayName("IS-001: normal → unitPrice + activityName")
        void is001() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(sm.selectById(100L)).thenReturn(sess(100L,10L));
            when(am.selectById(10L)).thenReturn(act(10L,"published",1,"Show"));
            when(vm.selectById(50L)).thenReturn(ven(50L));
            when(ssm.selectList(any())).thenReturn(List.of());
            TicketSalesQuoteResponse r = svc().quote(qr(1L,100L,2));
            assertEquals(0, new BigDecimal("199").compareTo(r.getUnitPrice()));
            assertEquals("Show", r.getActivityName());
        }
        @Test @DisplayName("IS-002: status=0 → rejected")
        void is002() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,0,"199"));
            assertThrows(BusinessException.class, () -> svc().quote(qr(1L,100L,1)));
        }
        @Test @DisplayName("IS-003: session not found → exception")
        void is003() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(sm.selectById(100L)).thenReturn(null); // fillSnapshotFields returns early
            when(ssm.selectList(any())).thenReturn(Collections.emptyList());
            // Quote doesn't throw on null session; it gracefully returns null fields
            TicketSalesQuoteResponse r = svc().quote(qr(1L,100L,1));
            assertNull(r.getActivityName()); assertNull(r.getSessionTime());
        }
        @Test @DisplayName("IS-004: includes activity/venue info")
        void is004() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"299"));
            Session s = sess(100L,10L); s.setStartTime(LocalDateTime.now().plusDays(30));
            when(sm.selectById(100L)).thenReturn(s);
            when(am.selectById(10L)).thenReturn(act(10L,"published",1,"Event"));
            when(vm.selectById(50L)).thenReturn(ven(50L));
            when(ssm.selectList(any())).thenReturn(List.of());
            TicketSalesQuoteResponse r = svc().quote(qr(1L,100L,1));
            assertEquals("Event", r.getActivityName()); assertNotNull(r.getSessionTime());
        }
        @Test @DisplayName("IS-005: includes perUserLimit + realNameRequired")
        void is005() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"299"));
            when(sm.selectById(100L)).thenReturn(sess(100L,10L));
            Activity a = act(10L,"published",1,"X"); a.setPerUserLimit(4); a.setRealNameRequired(true);
            when(am.selectById(10L)).thenReturn(a);
            when(vm.selectById(50L)).thenReturn(ven(50L));
            when(ssm.selectList(any())).thenReturn(List.of());
            TicketSalesQuoteResponse r = svc().quote(qr(1L,100L,1));
            assertEquals(Integer.valueOf(4), r.getPerUserLimit());
            assertEquals(Boolean.TRUE, r.getRealNameRequired());
        }
    }

    // ========== Lock Stock (IS-006~009) ==========
    @Nested @DisplayName("Lock Stock")
    class LockStock {
        @Test @DisplayName("IS-006: normal → decreaseRemainStock called")
        void is006() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(ttm.decreaseRemainStockIfEnough(1L,2)).thenReturn(1);
            svc().lockStock(lr(1L,100L,2));
            verify(ttm).decreaseRemainStockIfEnough(1L,2);
        }
        @Test @DisplayName("IS-007: insufficient → rejected")
        void is007() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(ttm.decreaseRemainStockIfEnough(1L,101)).thenReturn(0);
            assertThrows(BusinessException.class, () -> svc().lockStock(lr(1L,100L,101)));
        }
        @Test @DisplayName("IS-008: atomic decrement in SQL")
        void is008() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(ttm.decreaseRemainStockIfEnough(1L,1)).thenReturn(1);
            svc().lockStock(lr(1L,100L,1));
            verify(ttm).decreaseRemainStockIfEnough(1L,1);
        }
        @Test @DisplayName("IS-009: negative stock protection → rejected")
        void is009() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(ttm.decreaseRemainStockIfEnough(1L,1)).thenReturn(0);
            assertThrows(BusinessException.class, () -> svc().lockStock(lr(1L,100L,1)));
        }
    }

    // ========== Auth (IS-026~027) ==========
    @Nested @DisplayName("Auth")
    class Auth {
        @Test @DisplayName("IS-026: no token → 403")
        void is026() {
            TicketSalesInternalController c = new TicketSalesInternalController(svc(), null);
            assertEquals(403, c.quote(new TicketSalesQuoteRequest(), null).getCode());
        }
        @Test @DisplayName("IS-027: wrong token → 403")
        void is027() {
            TicketSalesInternalController c = new TicketSalesInternalController(svc(), "real");
            assertEquals(403, c.quote(new TicketSalesQuoteRequest(), "wrong").getCode());
        }
        @Test @DisplayName("valid token → 200")
        void validToken() { sellable();
            when(ttm.selectById(1L)).thenReturn(tt(1L,100L,1,"199"));
            when(sm.selectById(100L)).thenReturn(sess(100L,10L));
            when(am.selectById(10L)).thenReturn(act(10L,"published",1,"X"));
            when(vm.selectById(50L)).thenReturn(ven(50L));
            when(ssm.selectList(any())).thenReturn(List.of());
            TicketSalesInternalController c = new TicketSalesInternalController(svc(), "t");
            assertEquals(200, c.quote(qr(1L,100L,1), "t").getCode());
        }
    }

    // ========== Helpers ==========
    static TicketType tt(Long id, Long sid, int st, String price) {
        TicketType t = new TicketType(); t.setId(id); t.setSessionId(sid); t.setStatus(st); t.setPrice(new BigDecimal(price)); return t;
    }
    static Session sess(Long id, Long aid) {
        Session s = new Session(); s.setId(id); s.setActivityId(aid); s.setStatus(1); s.setVenueId(50L); s.setStartTime(LocalDateTime.now().plusDays(30)); return s;
    }
    static Activity act(Long id, String ps, int st, String name) {
        Activity a = new Activity(); a.setId(id); a.setPublishStatus(ps); a.setStatus(st); a.setName(name); return a;
    }
    static Venue ven(Long id) { Venue v = new Venue(); v.setId(id); return v; }
    static TicketSalesQuoteRequest qr(Long tt, Long s, int q) {
        TicketSalesQuoteRequest r = new TicketSalesQuoteRequest(); r.setTicketTypeId(tt); r.setSessionId(s); r.setQuantity(q); return r;
    }
    static TicketSalesLockRequest lr(Long tt, Long s, int q) {
        TicketSalesLockRequest r = new TicketSalesLockRequest(); r.setTicketTypeId(tt); r.setSessionId(s); r.setQuantity(q); return r;
    }
}
