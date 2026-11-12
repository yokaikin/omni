package com.omni.ticket.config;

import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.service.UserAccessService;
import io.jsonwebtoken.Claims;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理端统一鉴权拦截器。
 * <p>
 * 对 /api/ticket/admin/** 默认强制：有效 JWT 身份 + admin/organizer 角色。
 * 细粒度权限仍由各端点显式调用 UserAccessService 完成（纵深防御的内层）。
 * 白名单通过 omni.admin-auth.exclude-paths（Ant 风格）配置，默认为空。
 */
@Component
public class AdminApiAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminApiAuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final UserAccessService userAccessService;
    private final List<String> excludePaths;

    public AdminApiAuthInterceptor(
            @Lazy UserAccessService userAccessService,
            @Value("${omni.admin-auth.exclude-paths:}") String excludePaths) {
        this.userAccessService = userAccessService;
        this.excludePaths = StringUtils.hasText(excludePaths)
                ? Arrays.stream(excludePaths.split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toList())
                : List.of();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isExcluded(request.getRequestURI())) {
            return true;
        }
        // CORS 预检请求不携带凭据，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Long userId = parseUserId(request.getHeader("Authorization"));
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        try {
            userAccessService.requireAdminOrOrganizer(userId);
        } catch (BusinessException e) {
            if (ResultCode.FORBIDDEN.getCode() == e.getCode()) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权访问管理端接口");
            }
            throw e;
        }
        return true;
    }

    private boolean isExcluded(String requestUri) {
        for (String pattern : excludePaths) {
            if (StringUtils.hasText(pattern) && PATH_MATCHER.match(pattern, requestUri)) {
                log.debug("管理端鉴权白名单放行: uri={}, pattern={}", requestUri, pattern);
                return true;
            }
        }
        return false;
    }

    private Long parseUserId(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            Claims claims = JwtUtil.parseToken(authorization.substring(BEARER_PREFIX.length()));
            return Long.valueOf(claims.getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
