package com.omni.ticket.config;

import com.omni.ticket.dto.InternalUserRefResponse;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.service.UserAccessService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminApiAuthInterceptorTest {

    private static final Long ADMIN_ID = 2002L;
    private static final Long ORGANIZER_ID = 2003L;
    private static final Long USER_ID = 2004L;

    @Mock
    private UserAccessService userAccessService;

    @BeforeAll
    static void ensureJwtSecret() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank()) {
            System.setProperty("JWT_SECRET", "test-jwt-secret-must-be-at-least-32-bytes");
        }
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private String bearer(Long userId, String role, String phone) {
        return "Bearer " + JwtUtil.generateToken(userId, phone, role);
    }

    @Test
    void rejectsMissingAuthorization() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/activities");

        BusinessException error = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), error.getCode());
        verify(userAccessService, never()).requireAdminOrOrganizer(anyLong());
    }

    @Test
    void rejectsInvalidToken() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/activities");
        request.addHeader("Authorization", "Bearer invalid-token");

        BusinessException error = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), error.getCode());
    }

    @Test
    void rejectsPlainUserRole() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/activities");
        request.addHeader("Authorization", bearer(USER_ID, "user", "13900000001"));
        when(userAccessService.requireAdminOrOrganizer(USER_ID))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "无权限"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ResultCode.FORBIDDEN.getCode(), error.getCode());
        assertEquals("无权访问管理端接口", error.getMessage());
    }

    @Test
    void passesAdminRole() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/activities");
        request.addHeader("Authorization", bearer(ADMIN_ID, "admin", "13800000001"));
        when(userAccessService.requireAdminOrOrganizer(ADMIN_ID)).thenReturn(new InternalUserRefResponse());

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        verify(userAccessService).requireAdminOrOrganizer(ADMIN_ID);
    }

    @Test
    void passesOrganizerRole() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("POST", "/api/ticket/admin/venues");
        request.addHeader("Authorization", bearer(ORGANIZER_ID, "organizer", "13800000002"));
        when(userAccessService.requireAdminOrOrganizer(ORGANIZER_ID)).thenReturn(new InternalUserRefResponse());

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void passesOptionsPreflightWithoutToken() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("OPTIONS", "/api/ticket/admin/activities");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(userAccessService, never()).requireAdminOrOrganizer(anyLong());
    }

    @Test
    void excludedPathSkipsAuthentication() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "/api/ticket/admin/ping");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/ping");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(userAccessService, never()).requireAdminOrOrganizer(anyLong());
    }

    @Test
    void excludedPathSupportsAntPattern() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "/api/ticket/admin/public/**");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/public/health");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void propagatesUserServiceFailure() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService, "");
        MockHttpServletRequest request = request("GET", "/api/ticket/admin/activities");
        request.addHeader("Authorization", bearer(ADMIN_ID, "admin", "13800000001"));
        when(userAccessService.requireAdminOrOrganizer(anyLong()))
                .thenThrow(new BusinessException(ResultCode.INTERNAL_ERROR, "用户服务无响应"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ResultCode.INTERNAL_ERROR.getCode(), error.getCode());
        verify(userAccessService).requireAdminOrOrganizer(ADMIN_ID);
    }

    @Test
    void multipleExcludePathsSplitByComma() {
        AdminApiAuthInterceptor interceptor = new AdminApiAuthInterceptor(userAccessService,
                "/api/ticket/admin/a, /api/ticket/admin/b");
        MockHttpServletRequest requestA = request("GET", "/api/ticket/admin/a");
        MockHttpServletRequest requestB = request("GET", "/api/ticket/admin/b");
        MockHttpServletRequest requestC = request("GET", "/api/ticket/admin/c");
        lenient().when(userAccessService.requireAdminOrOrganizer(anyLong())).thenReturn(new InternalUserRefResponse());

        assertTrue(interceptor.preHandle(requestA, new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(requestB, new MockHttpServletResponse(), new Object()));
        MockHttpServletRequest requestWithToken = requestC;
        requestWithToken.addHeader("Authorization", bearer(ADMIN_ID, "admin", "13800000001"));
        assertTrue(interceptor.preHandle(requestWithToken, new MockHttpServletResponse(), new Object()));
    }
}
