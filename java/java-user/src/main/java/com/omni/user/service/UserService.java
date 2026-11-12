package com.omni.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.omni.common.dto.InternalAuthContextResponse;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.common.util.LogMaskUtil;
import com.omni.exception.BusinessException;
import com.omni.user.dto.ChangePhoneRequest;
import com.omni.user.dto.ChangePasswordRequest;
import com.omni.user.dto.LoginRequest;
import com.omni.user.dto.LoginResponse;
import com.omni.user.dto.RegisterRequest;
import com.omni.user.dto.ResetPasswordRequest;
import com.omni.user.dto.UpdateProfileRequest;
import com.omni.user.dto.InternalUserRefResponse;
import com.omni.user.dto.InternalUserContactResponse;
import com.omni.user.dto.UserInfoResponse;
import com.omni.user.entity.User;
import com.omni.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户服务
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String MOCK_SMS_CODE = "666666";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RbacService rbacService;
    private final boolean mockSmsEnabled;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, RbacService rbacService) {
        this(userMapper, passwordEncoder, rbacService, false);
    }

    @Autowired
    public UserService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       RbacService rbacService,
                       @Value("${omni.sms.mock.enabled:false}") boolean mockSmsEnabled) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.rbacService = rbacService;
        this.mockSmsEnabled = mockSmsEnabled;
    }

    /**
     * 用户注册
     */
    public void register(RegisterRequest request) {
        // 校验两次密码一致
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次密码输入不一致");
        }

        // 校验手机号是否已注册
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, request.getPhone());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该手机号已注册");
        }

        // 创建用户
        User user = new User();
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userMapper.insert(user);

        log.info("用户注册成功: phone={}", LogMaskUtil.maskPhone(request.getPhone()));
    }

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request) {
        // 根据账号查询用户（支持手机号或邮箱）
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, request.getAccount())
               .or()
               .eq(User::getEmail, request.getAccount());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账号不存在");
        }
        if (Integer.valueOf(0).equals(user.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账号已停用");
        }

        if ("password".equals(request.getLoginType())) {
            // 密码登录
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "密码错误");
            }
        } else if ("sms".equals(request.getLoginType())) {
            if (!isValidMockSmsCode(request.getSmsCode())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误");
            }
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的登录方式");
        }

        // 生成 JWT Token
        String role = user.getRole() != null ? user.getRole() : "user";
        String token = JwtUtil.generateToken(user.getId(), user.getPhone(), role);
        InternalAuthContextResponse authContext = loadAuthContext(user.getId());

        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setPhone(user.getPhone());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setToken(token);
        response.setRole(resolveFrontendRole(role, authContext));
        response.setPermissionCodes(resolvePermissionCodes(authContext));

        log.info("用户登录成功: userId={}, phone={}", user.getId(), LogMaskUtil.maskPhone(user.getPhone()));
        return response;
    }

    public User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public InternalUserRefResponse getInternalUserRef(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return toInternalUserRefResponse(user);
    }

    /**
     * 内部联系方式查询：供通知服务解析短信/邮件收件人。
     */
    public InternalUserContactResponse getInternalUserContact(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        InternalUserContactResponse response = new InternalUserContactResponse();
        response.setUserId(user.getId());
        response.setPhone(user.getPhone());
        response.setEmail(user.getEmail());
        return response;
    }

    /**
     * 获取用户信息
     */
    public UserInfoResponse getUserInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        UserInfoResponse response = toUserInfoResponse(user);
        InternalAuthContextResponse authContext = loadAuthContext(userId);
        response.setRole(resolveFrontendRole(response.getRole(), authContext));
        response.setPermissionCodes(resolvePermissionCodes(authContext));
        return response;
    }

    /**
     * 更新用户资料
     */
    public UserInfoResponse updateProfile(UpdateProfileRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户资料参数不能为空");
        }
        if (request.getUserId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, user.getId());
        boolean hasUpdate = false;

        if (request.getNickname() != null) {
            String nickname = trimToNull(request.getNickname());
            if (nickname != null && nickname.length() > 50) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "昵称长度不能超过50个字符");
            }
            updateWrapper.set(User::getNickname, nickname);
            hasUpdate = true;
        }

        if (request.getEmail() != null) {
            String email = trimToNull(request.getEmail());
            if (email != null && email.length() > 100) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱长度不能超过100个字符");
            }
            if (email != null && !email.contains("@")) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱格式不正确");
            }
            updateWrapper.set(User::getEmail, email);
            hasUpdate = true;
        }

        if (request.getAvatar() != null) {
            String avatar = trimToNull(request.getAvatar());
            if (avatar != null && avatar.length() > 255) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "头像地址长度不能超过255个字符");
            }
            updateWrapper.set(User::getAvatar, avatar);
            hasUpdate = true;
        }

        if (request.getOrganizerName() != null) {
            String role = user.getRole();
            if (!"admin".equals(role) && !"organizer".equals(role)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权限更新主办方名称");
            }
            String organizerName = trimToNull(request.getOrganizerName());
            if (organizerName != null && organizerName.length() > 100) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "主办方名称长度不能超过100个字符");
            }
            updateWrapper.set(User::getOrganizerName, organizerName);
            hasUpdate = true;
        }

        if (hasUpdate) {
            userMapper.update(null, updateWrapper);
        }
        User updatedUser = userMapper.selectById(user.getId());
        return toUserInfoResponse(updatedUser);
    }

    /**
     * 修改密码
     */
    public void changePassword(ChangePasswordRequest request) {
        User user = verifyPasswordIdentity(request);
        String newPassword = trimToNull(request.getNewPassword());
        String confirmPassword = trimToNull(request.getConfirmPassword());

        if (newPassword == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能为空");
        }
        if (confirmPassword == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "确认密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码长度不能少于6位");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次密码输入不一致");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    /**
     * 校验修改密码前的原身份凭证
     */
    public User verifyPasswordIdentity(ChangePasswordRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "修改密码参数不能为空");
        }
        if (request.getUserId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        if (request.getOldPassword() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "原密码不能为空");
        }
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "原密码错误");
        }

        requireValidSmsCode(request.getSmsCode(), "验证码不能为空", "验证码错误");
        return user;
    }

    /**
     * 校验当前安全手机号
     */
    public void verifyCurrentPhone(Long userId, String smsCode) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        requireValidSmsCode(smsCode, "验证码不能为空", "验证码错误");
    }

    /**
     * 更换安全手机号
     */
    public UserInfoResponse changePhone(ChangePhoneRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "更换手机号参数不能为空");
        }
        if (request.getUserId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        requireValidSmsCode(request.getCurrentSmsCode(), "原手机验证码不能为空", "原手机验证码错误");
        String newPhone = trimToNull(request.getNewPhone());
        if (newPhone == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新手机号不能为空");
        }
        if (!newPhone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新手机号格式不正确");
        }
        if (newPhone.equals(user.getPhone())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新手机号不能与当前手机号相同");
        }
        requireValidSmsCode(request.getNewSmsCode(), "新手机验证码不能为空", "新手机验证码错误");

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, newPhone);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该手机号已被绑定");
        }

        user.setPhone(newPhone);
        userMapper.updateById(user);
        return toUserInfoResponse(user);
    }

    private void requireValidSmsCode(String rawSmsCode, String blankMessage, String invalidMessage) {
        String smsCode = trimToNull(rawSmsCode);
        if (smsCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, blankMessage);
        }
        if (!isValidMockSmsCode(smsCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, invalidMessage);
        }
    }

    /**
     * 找回密码重置
     */
    public void resetPassword(ResetPasswordRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "重置密码参数不能为空");
        }
        String phone = trimToNull(request.getPhone());
        String smsCode = trimToNull(request.getSmsCode());
        String newPassword = trimToNull(request.getNewPassword());
        String confirmPassword = trimToNull(request.getConfirmPassword());

        if (phone == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号不能为空");
        }
        if (smsCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不能为空");
        }
        if (newPassword == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能为空");
        }
        if (confirmPassword == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "确认密码不能为空");
        }

        if (!isValidMockSmsCode(smsCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号或验证码错误");
        }
        if (newPassword.length() < 6) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码长度不能少于6位");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次密码输入不一致");
        }

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, phone);
        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号或验证码错误");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    private InternalAuthContextResponse loadAuthContext(Long userId) {
        try {
            return rbacService.getInternalAuthContext(userId);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidMockSmsCode(String smsCode) {
        return mockSmsEnabled && MOCK_SMS_CODE.equals(smsCode);
    }

    private List<String> resolvePermissionCodes(InternalAuthContextResponse authContext) {
        if (authContext == null || authContext.getPermissionCodes() == null) {
            return List.of();
        }
        return authContext.getPermissionCodes();
    }

    private String resolveFrontendRole(String rawRole, InternalAuthContextResponse authContext) {
        if (authContext == null || authContext.getEffectiveRole() == null) {
            return rawRole != null ? rawRole : "user";
        }
        String effectiveRole = authContext.getEffectiveRole();
        if ("platform_super_admin".equals(effectiveRole) || "organizer_admin".equals(effectiveRole)) {
            return effectiveRole;
        }
        return rawRole != null ? rawRole : "user";
    }

    private InternalUserRefResponse toInternalUserRefResponse(User user) {
        InternalUserRefResponse response = new InternalUserRefResponse();
        response.setId(user.getId());
        response.setPhone(user.getPhone());
        response.setRole(user.getRole() != null ? user.getRole() : "user");
        response.setStatus(user.getStatus());
        response.setOrganizerStatus(user.getOrganizerStatus());
        response.setOrganizerName(user.getOrganizerName());
        return response;
    }

    private UserInfoResponse toUserInfoResponse(User user) {
        UserInfoResponse response = new UserInfoResponse();
        response.setId(user.getId());
        response.setPhone(user.getPhone());
        response.setNickname(user.getNickname());
        response.setEmail(user.getEmail());
        response.setAvatar(user.getAvatar());
        response.setStatus(user.getStatus());
        response.setRole(user.getRole());
        response.setOrganizerStatus(user.getOrganizerStatus());
        response.setOrganizerName(user.getOrganizerName());
        response.setCreateTime(user.getCreateTime());
        response.setUpdateTime(user.getUpdateTime());
        return response;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
