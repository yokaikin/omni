package com.omni.user.service;

import com.omni.common.result.ResultCode;
import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.StoredFile;
import com.omni.common.util.ProjectPathUtil;
import com.omni.exception.BusinessException;
import com.omni.user.dto.AssetUploadResponse;
import com.omni.user.dto.UserInfoResponse;
import com.omni.user.entity.User;
import com.omni.user.entity.UserAsset;
import com.omni.user.mapper.UserAssetMapper;
import com.omni.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAssetService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024L * 1024L;
    private static final String BIZ_TYPE_AVATAR = "avatar";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final UserAssetMapper userAssetMapper;
    private final UserMapper userMapper;
    private final AssetStorageService assetStorageService;
    private final Path uploadRoot;

    public UserAssetService(UserAssetMapper userAssetMapper,
                            UserMapper userMapper,
                            AssetStorageService assetStorageService,
                            @Value("${omni.upload.root:${OMNI_UPLOAD_ROOT:}}") String uploadRoot) {
        this.userAssetMapper = userAssetMapper;
        this.userMapper = userMapper;
        this.assetStorageService = assetStorageService;
        this.uploadRoot = ProjectPathUtil.resolvePublicUploadRoot(uploadRoot);
    }

    @Transactional
    public UserInfoResponse uploadAvatar(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String contentType = file.getContentType();
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持上传 JPG、PNG、WEBP 或 GIF 图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "头像图片不能超过2MB");
        }
        validateImageMagic(contentType, file);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        LocalDate now = LocalDate.now();
        String extension = extensionFor(contentType);
        String storedName = UUID.randomUUID() + "." + extension;
        String relativePath = String.format(Locale.ROOT, "user/avatar/%04d/%02d/%s", now.getYear(), now.getMonthValue(), storedName);

        StoredFile stored = assetStorageService.store(uploadRoot, "", relativePath, file);

        String publicUrl = "/uploads/" + relativePath.replace('\\', '/');
        try {
            UserAsset asset = new UserAsset();
            asset.setUploaderId(userId);
            asset.setBizType(BIZ_TYPE_AVATAR);
            asset.setOriginalName(file.getOriginalFilename());
            asset.setStoredName(storedName);
            asset.setRelativePath(relativePath.replace('\\', '/'));
            asset.setPublicUrl(publicUrl);
            asset.setMimeType(contentType);
            asset.setSizeBytes(file.getSize());
            asset.setSha256(stored.getSha256());
            asset.setStatus(1);
            userAssetMapper.insert(asset);

            user.setAvatar(publicUrl);
            userMapper.updateById(user);
            return toUserInfoResponse(user);
        } catch (RuntimeException e) {
            assetStorageService.delete(uploadRoot, "", relativePath.replace('\\', '/'));
            throw e;
        }
    }

    @Transactional
    public UserAsset uploadImageAsset(Long userId, String bizType, MultipartFile file) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String contentType = file.getContentType();
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(contentType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持上传 JPG、PNG 或 WEBP 图片");
        }
        if (file.getSize() > 10L * 1024L * 1024L) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "材料图片不能超过10MB");
        }
        validateImageMagic(contentType, file);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        LocalDate now = LocalDate.now();
        String extension = extensionFor(contentType);
        String storedName = UUID.randomUUID() + "." + extension;
        String relativePath = String.format(Locale.ROOT, "user/organizer/%04d/%02d/%s",
                now.getYear(), now.getMonthValue(), storedName);

        StoredFile stored = assetStorageService.store(uploadRoot, "", relativePath, file);

        try {
            UserAsset asset = new UserAsset();
            asset.setUploaderId(userId);
            asset.setBizType(bizType);
            asset.setOriginalName(file.getOriginalFilename());
            asset.setStoredName(storedName);
            asset.setRelativePath(relativePath.replace('\\', '/'));
            asset.setPublicUrl("/uploads/" + relativePath.replace('\\', '/'));
            asset.setMimeType(contentType);
            asset.setSizeBytes(file.getSize());
            asset.setSha256(stored.getSha256());
            asset.setStatus(1);
            userAssetMapper.insert(asset);
            return asset;
        } catch (RuntimeException e) {
            assetStorageService.delete(uploadRoot, "", relativePath.replace('\\', '/'));
            throw e;
        }
    }

    public UserAsset getAsset(Long assetId) {
        return assetId == null ? null : userAssetMapper.selectById(assetId);
    }

    @Transactional
    public void deleteAsset(UserAsset asset) {
        if (asset == null) {
            return;
        }
        userAssetMapper.deleteById(asset.getId());
        if (asset.getRelativePath() != null) {
            assetStorageService.delete(uploadRoot, "", asset.getRelativePath());
        }
    }

    AssetUploadResponse toAssetUploadResponse(UserAsset asset) {
        AssetUploadResponse response = new AssetUploadResponse();
        response.setId(asset.getId());
        response.setBizType(asset.getBizType());
        response.setPublicUrl(asset.getPublicUrl());
        response.setOriginalName(asset.getOriginalName());
        response.setMimeType(asset.getMimeType());
        response.setSizeBytes(asset.getSizeBytes());
        return response;
    }

    private byte[] readHeader(MultipartFile file) {
        byte[] header = new byte[12];
        try (InputStream inputStream = file.getInputStream()) {
            int offset = 0;
            while (offset < header.length) {
                int read = inputStream.read(header, offset, header.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset == header.length) {
                return header;
            }
            byte[] actual = new byte[offset];
            System.arraycopy(header, 0, actual, 0, offset);
            return actual;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件读取失败");
        }
    }

    private void validateImageMagic(String contentType, MultipartFile file) {
        byte[] header = readHeader(file);
        boolean valid;
        if ("image/jpeg".equals(contentType)) {
            valid = startsWith(header, new int[] {0xff, 0xd8, 0xff});
        } else if ("image/png".equals(contentType)) {
            valid = startsWith(header, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        } else if ("image/gif".equals(contentType)) {
            valid = startsWith(header, new int[] {'G', 'I', 'F', '8', '7', 'a'})
                    || startsWith(header, new int[] {'G', 'I', 'F', '8', '9', 'a'});
        } else {
            valid = startsWith(header, new int[] {'R', 'I', 'F', 'F'})
                    && header.length >= 12
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P';
        }
        if (!valid) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件内容不是有效图片");
        }
    }

    private boolean startsWith(byte[] bytes, int[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private String extensionFor(String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return "jpg";
        }
        if ("image/png".equals(contentType)) {
            return "png";
        }
        if ("image/webp".equals(contentType)) {
            return "webp";
        }
        return "gif";
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
}
