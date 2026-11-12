package com.omni.user.controller;

import com.omni.common.dto.InternalAuthContextResponse;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.common.util.LogMaskUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.exception.BusinessException;
import com.omni.user.config.UserSentinelConfig;
import com.omni.user.dto.ChangePhoneRequest;
import com.omni.user.dto.ChangePasswordRequest;
import com.omni.user.dto.InternalUserRefResponse;
import com.omni.user.dto.InternalUserContactResponse;
import com.omni.user.dto.LoginRequest;
import com.omni.user.dto.LoginResponse;
import com.omni.user.dto.OrganizerApplicationRequest;
import com.omni.user.dto.OrganizerApplicationResponse;
import com.omni.user.dto.OrganizerApplicationReviewRequest;
import com.omni.user.dto.OrganizerApplicationMaterialResponse;
import com.omni.user.dto.RegisterRequest;
import com.omni.user.dto.ResolveAttendeesRequest;
import com.omni.user.dto.ResolvedAttendeeResponse;
import com.omni.user.dto.ResetPasswordRequest;
import com.omni.user.dto.UpdateProfileRequest;
import com.omni.user.dto.VerifyCurrentPhoneRequest;
import com.omni.user.dto.UserAttendeeExportResponse;
import com.omni.user.dto.UserAttendeeRequest;
import com.omni.user.dto.UserAttendeeResponse;
import com.omni.user.dto.UserBrowseHistoryRequest;
import com.omni.user.dto.UserBrowseHistoryResponse;
import com.omni.user.dto.UserInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.omni.user.service.OrganizerApplicationService;
import com.omni.user.service.OrganizerApplicationMaterialService;
import com.omni.user.service.RbacService;
import com.omni.user.service.UserAttendeeService;
import com.omni.user.service.UserAssetService;
import com.omni.user.service.UserBrowseHistoryService;
import com.omni.user.service.UserService;
import io.jsonwebtoken.Claims;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MOCK_SMS_CODE = "666666";

    private final UserService userService;
    private final OrganizerApplicationService organizerApplicationService;
    private final RbacService rbacService;
    private final UserAssetService userAssetService;
    private final UserAttendeeService userAttendeeService;
    private final UserBrowseHistoryService userBrowseHistoryService;
    private OrganizerApplicationMaterialService organizerApplicationMaterialService;
    private final String internalApiToken;
    @Value("${omni.sms.mock.enabled:false}")
    private boolean mockSmsEnabled;

    public UserController(UserService userService, OrganizerApplicationService organizerApplicationService) {
        this(userService, organizerApplicationService, null, null, null, null, "");
    }

    public UserController(UserService userService,
                          OrganizerApplicationService organizerApplicationService,
                          String internalApiToken) {
        this(userService, organizerApplicationService, null, null, null, null, internalApiToken);
    }

    public UserController(UserService userService,
                          OrganizerApplicationService organizerApplicationService,
                          String internalApiToken,
                          boolean mockSmsEnabled) {
        this(userService, organizerApplicationService, null, null, null, null, internalApiToken);
        this.mockSmsEnabled = mockSmsEnabled;
    }

    @Autowired
    public UserController(UserService userService,
                          OrganizerApplicationService organizerApplicationService,
                          RbacService rbacService,
                          UserAssetService userAssetService,
                          UserAttendeeService userAttendeeService,
                          UserBrowseHistoryService userBrowseHistoryService,
                          @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this.userService = userService;
        this.organizerApplicationService = organizerApplicationService;
        this.rbacService = rbacService;
        this.userAssetService = userAssetService;
        this.userAttendeeService = userAttendeeService;
        this.userBrowseHistoryService = userBrowseHistoryService;
        this.internalApiToken = internalApiToken;
        this.mockSmsEnabled = false;
    }

    @Autowired
    public void setOrganizerApplicationMaterialService(
            OrganizerApplicationMaterialService organizerApplicationMaterialService) {
        this.organizerApplicationMaterialService = organizerApplicationMaterialService;
    }

    public UserController(UserService userService,
                          OrganizerApplicationService organizerApplicationService,
                          UserAssetService userAssetService,
                          String internalApiToken) {
        this(userService, organizerApplicationService, null, userAssetService, null, null, internalApiToken);
    }

    public UserController(UserService userService,
                          OrganizerApplicationService organizerApplicationService,
                          UserAssetService userAssetService,
                          UserAttendeeService userAttendeeService,
                          String internalApiToken) {
        this(userService, organizerApplicationService, null, userAssetService, userAttendeeService, null, internalApiToken);
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterRequest request) {
        userService.register(request);
        return Result.success();
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @SentinelResource(value = UserSentinelConfig.LOGIN_PASSWORD, blockHandler = "loginBlocked")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return Result.success(response);
    }

    public Result<LoginResponse> loginBlocked(LoginRequest request, BlockException exception) {
        return Result.fail(429, "系统繁忙，请稍后重试");
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/info")
    public Result<UserInfoResponse> getUserInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        UserInfoResponse response = userService.getUserInfo(userId);
        return Result.success(response);
    }

    /**
     * 更新用户资料
     */
    @PutMapping("/profile")
    public Result<UserInfoResponse> updateProfile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestBody UpdateProfileRequest request) {
        Long userId = requireAuthUserId(authorization);
        if (request != null) {
            request.setUserId(userId);
        }
        UserInfoResponse response = userService.updateProfile(request);
        return Result.success(response);
    }

    @PostMapping("/assets/avatar")
    public Result<UserInfoResponse> uploadAvatar(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("file") MultipartFile file) {
        Long userId = requireAuthUserId(authorization);
        if (userAssetService == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "头像上传服务未配置");
        }
        UserInfoResponse response = userAssetService.uploadAvatar(userId, file);
        return Result.success(response);
    }

    @GetMapping("/attendees")
    public Result<List<UserAttendeeResponse>> listAttendees(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserAttendeeService().listMine(userId));
    }

    @GetMapping("/attendees/export")
    public Result<UserAttendeeExportResponse> exportAttendees(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserAttendeeService().exportMine(userId));
    }

    @PostMapping("/attendees")
    public Result<UserAttendeeResponse> createAttendee(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UserAttendeeRequest request) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserAttendeeService().create(userId, request));
    }

    @PutMapping("/attendees/{id}")
    public Result<UserAttendeeResponse> updateAttendee(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody UserAttendeeRequest request) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserAttendeeService().update(userId, id, request));
    }

    @DeleteMapping("/attendees/{id}")
    public Result<Void> deleteAttendee(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = requireAuthUserId(authorization);
        requireUserAttendeeService().delete(userId, id);
        return Result.success();
    }

    @PostMapping("/browse-history")
    public Result<UserBrowseHistoryResponse> recordBrowseHistory(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UserBrowseHistoryRequest request) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserBrowseHistoryService().record(userId, request));
    }

    @GetMapping("/browse-history")
    public Result<List<UserBrowseHistoryResponse>> listBrowseHistory(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireUserBrowseHistoryService().listMine(userId));
    }

    @DeleteMapping("/browse-history")
    public Result<Void> clearBrowseHistory(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        requireUserBrowseHistoryService().clearMine(userId);
        return Result.success();
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody ChangePasswordRequest request) {
        Long userId = requireAuthUserId(authorization);
        if (request != null) {
            request.setUserId(userId);
        }
        userService.changePassword(request);
        return Result.success();
    }

    @PostMapping("/password/verify")
    public Result<Void> verifyPasswordIdentity(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody ChangePasswordRequest request) {
        Long userId = requireAuthUserId(authorization);
        if (request != null) {
            request.setUserId(userId);
        }
        userService.verifyPasswordIdentity(request);
        return Result.success();
    }

    @PostMapping("/phone/verify-current")
    public Result<Void> verifyCurrentPhone(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody VerifyCurrentPhoneRequest request) {
        Long userId = requireAuthUserId(authorization);
        userService.verifyCurrentPhone(userId, request == null ? null : request.getSmsCode());
        return Result.success();
    }

    @PutMapping("/phone")
    public Result<UserInfoResponse> changePhone(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody ChangePhoneRequest request) {
        Long userId = requireAuthUserId(authorization);
        if (request != null) {
            request.setUserId(userId);
        }
        UserInfoResponse response = userService.changePhone(request);
        return Result.success(response);
    }

    /**
     * 找回密码重置
     */
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request);
        return Result.success();
    }

    @PostMapping("/organizer/applications")
    public Result<OrganizerApplicationResponse> submitOrganizerApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody OrganizerApplicationRequest request) {
        Long userId = requireAuthUserId(authorization);
        OrganizerApplicationResponse response = organizerApplicationService.submitOrUpdate(userId, request);
        return Result.success(response);
    }

    @GetMapping("/organizer/applications/my")
    public Result<OrganizerApplicationResponse> getMyOrganizerApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthUserId(authorization);
        OrganizerApplicationResponse response = organizerApplicationService.getMine(userId);
        return Result.success(response);
    }

    @GetMapping("/organizer/applications/admin")
    public Result<Page<OrganizerApplicationResponse>> listOrganizerApplicationsForAdmin(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String subjectType) {
        Long reviewerId = requireAuthUserId(authorization);
        Page<OrganizerApplicationResponse> response = organizerApplicationService.listForAdmin(
                reviewerId, page, size, keyword, status, subjectType);
        return Result.success(response);
    }

    @PostMapping("/organizer/applications/{id}/materials")
    public Result<OrganizerApplicationMaterialResponse> uploadOrganizerApplicationMaterial(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestParam String materialType,
            @RequestPart("file") MultipartFile file) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireOrganizerApplicationMaterialService()
                .upload(userId, id, materialType, file));
    }

    @GetMapping("/organizer/applications/{id}/materials")
    public Result<List<OrganizerApplicationMaterialResponse>> listOrganizerApplicationMaterials(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = requireAuthUserId(authorization);
        return Result.success(requireOrganizerApplicationMaterialService().list(userId, id));
    }

    @PostMapping("/organizer/applications/{id}/approve")
    public Result<OrganizerApplicationResponse> approveOrganizerApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) OrganizerApplicationReviewRequest request) {
        Long reviewerId = requireAuthUserId(authorization);
        String reviewNote = request == null ? null : request.getReviewNote();
        OrganizerApplicationResponse response = organizerApplicationService.approve(id, reviewerId, reviewNote);
        return Result.success(response);
    }

    @PostMapping("/organizer/applications/{id}/reject")
    public Result<OrganizerApplicationResponse> rejectOrganizerApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) OrganizerApplicationReviewRequest request) {
        Long reviewerId = requireAuthUserId(authorization);
        String reviewNote = request == null ? null : request.getReviewNote();
        OrganizerApplicationResponse response = organizerApplicationService.reject(id, reviewerId, reviewNote);
        return Result.success(response);
    }

    @GetMapping("/internal/{id}")
    public Result<InternalUserRefResponse> getInternalUserRef(
            @PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(userService.getInternalUserRef(id));
    }

    @GetMapping("/internal/auth/context/{id}")
    public Result<InternalAuthContextResponse> getInternalAuthContext(
            @PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(rbacService.getInternalAuthContext(id));
    }

    @GetMapping("/internal/{id}/contact")
    public Result<InternalUserContactResponse> getInternalUserContact(
            @PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(userService.getInternalUserContact(id));
    }

    @PostMapping("/internal/attendees/resolve")
    public Result<List<ResolvedAttendeeResponse>> resolveInternalAttendees(
            @RequestBody ResolveAttendeesRequest request,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!isValidInternalToken(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(requireUserAttendeeService().resolve(request.getUserId(), request.getAttendeeIds()));
    }

    private boolean isValidInternalToken(String token) {
        return StringUtils.hasText(internalApiToken) && internalApiToken.equals(token);
    }

    /**
     * 发送短信验证码（仅本地显式开启 mock 时返回固定验证码）
     */
    @PostMapping("/send-code")
    @SentinelResource(value = UserSentinelConfig.SEND_CODE, blockHandler = "sendCodeBlocked")
    public Result<String> sendCode(@RequestParam String phone) {
        if (!mockSmsEnabled) {
            return Result.fail(400, "当前环境未启用短信验证码");
        }
        System.out.println("==========================================");
        System.out.println("  短信验证码 [" + LogMaskUtil.maskPhone(phone) + "]: " + MOCK_SMS_CODE);
        System.out.println("==========================================");
        return Result.success(MOCK_SMS_CODE);
    }

    public Result<String> sendCodeBlocked(String phone, BlockException exception) {
        return Result.fail(429, "系统繁忙，请稍后重试");
    }

    private Long requireAuthUserId(String authorization) {
        Long userId = parseAuthUserId(authorization);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private Long parseAuthUserId(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        try {
            Claims claims = JwtUtil.parseToken(token);
            return Long.valueOf(claims.getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private UserAttendeeService requireUserAttendeeService() {
        if (userAttendeeService == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "实名观演人服务未配置");
        }
        return userAttendeeService;
    }

    private UserBrowseHistoryService requireUserBrowseHistoryService() {
        if (userBrowseHistoryService == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "浏览历史服务未配置");
        }
        return userBrowseHistoryService;
    }

    private OrganizerApplicationMaterialService requireOrganizerApplicationMaterialService() {
        if (organizerApplicationMaterialService == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "入驻材料服务未配置");
        }
        return organizerApplicationMaterialService;
    }
}
