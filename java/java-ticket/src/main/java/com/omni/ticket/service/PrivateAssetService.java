package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.result.ResultCode;
import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.StoredFile;
import com.omni.common.util.ProjectPathUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.InternalUserRefResponse;
import com.omni.ticket.dto.PrivateAssetDownload;
import com.omni.ticket.dto.PrivateAssetResponse;
import com.omni.ticket.entity.PrivateAsset;
import com.omni.ticket.entity.VenueApplication;
import com.omni.ticket.mapper.PrivateAssetMapper;
import com.omni.ticket.mapper.VenueApplicationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PrivateAssetService {

    private static final String SERVICE_NAME = "ticket";
    private static final String OBJECT_KEY_PREFIX = "private/ticket";
    private static final String BIZ_TYPE_VENUE_PROOF = "venue-proof";
    private static final Set<String> VENUE_MATERIAL_BIZ_TYPES = Set.of(
            "venue-proof", "venue-fire-safety", "venue-lease-agreement");
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_BOUND = "bound";
    private static final String STATUS_DELETED = "deleted";
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");

    private final PrivateAssetMapper privateAssetMapper;
    private final VenueApplicationMapper venueApplicationMapper;
    private final UserAccessService userAccessService;
    private final AssetStorageService assetStorageService;
    private final Path privateRoot;

    public PrivateAssetService(PrivateAssetMapper privateAssetMapper,
                               VenueApplicationMapper venueApplicationMapper,
                               UserAccessService userAccessService,
                               AssetStorageService assetStorageService,
                               @Value("${omni.private-asset.root:${OMNI_PRIVATE_ASSET_ROOT:}}") String privateRoot) {
        this.privateAssetMapper = privateAssetMapper;
        this.venueApplicationMapper = venueApplicationMapper;
        this.userAccessService = userAccessService;
        this.assetStorageService = assetStorageService;
        this.privateRoot = ProjectPathUtil.resolvePrivateUploadRoot(privateRoot, SERVICE_NAME);
    }

    @Transactional
    public PrivateAssetResponse upload(Long uploaderId, String bizType, MultipartFile file) {
        if (!isVenueMaterialBizType(bizType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的私有资产类型");
        }
        userAccessService.requireAdminOrOrganizerOrAnyPermission(uploaderId, "activity.manage", "tour.manage");
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        validateFile(file);

        LocalDate now = LocalDate.now();
        String contentType = file.getContentType();
        String storedName = UUID.randomUUID() + "." + extensionFor(contentType);
        String relativePath = String.format(Locale.ROOT, "%s/%04d/%02d/%s",
                bizType, now.getYear(), now.getMonthValue(), storedName);

        StoredFile stored = assetStorageService.store(privateRoot, OBJECT_KEY_PREFIX, relativePath, file);

        try {
            PrivateAsset asset = new PrivateAsset();
            asset.setServiceName(SERVICE_NAME);
            asset.setBizType(bizType);
            asset.setUploaderId(uploaderId);
            asset.setOriginalFilename(file.getOriginalFilename());
            asset.setStoredFilename(storedName);
            asset.setRelativePath(relativePath.replace('\\', '/'));
            asset.setContentType(contentType);
            asset.setFileSize(file.getSize());
            asset.setSha256(stored.getSha256());
            asset.setStatus(STATUS_PENDING);
            asset.setCreateTime(LocalDateTime.now());
            privateAssetMapper.insert(asset);
            return PrivateAssetResponse.from(asset);
        } catch (RuntimeException e) {
            assetStorageService.delete(privateRoot, OBJECT_KEY_PREFIX, relativePath.replace('\\', '/'));
            throw e;
        }
    }

    @Transactional
    public PrivateAssetResponse bindVenueProof(Long assetId, Long venueApplicationId, Long userId) {
        return bindVenueMaterial(assetId, venueApplicationId, userId, BIZ_TYPE_VENUE_PROOF);
    }

    @Transactional
    public PrivateAssetResponse bindVenueMaterial(Long assetId,
                                                  Long venueApplicationId,
                                                  Long userId,
                                                  String expectedBizType) {
        userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage", "tour.manage");
        if (venueApplicationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场馆审核资料ID不能为空");
        }
        if (!isVenueMaterialBizType(expectedBizType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的私有资产类型");
        }
        PrivateAsset asset = requireAsset(assetId);
        if (!expectedBizType.equals(asset.getBizType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的私有资产类型");
        }
        if (!STATUS_PENDING.equals(asset.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "私有资产状态不正确");
        }
        if (!userId.equals(asset.getUploaderId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权绑定该私有资产");
        }

        asset.setBizId(venueApplicationId);
        asset.setStatus(STATUS_BOUND);
        asset.setBindTime(LocalDateTime.now());
        privateAssetMapper.updateById(asset);
        return PrivateAssetResponse.from(asset);
    }

    public PrivateAssetResponse getById(Long assetId) {
        return PrivateAssetResponse.from(requireAsset(assetId));
    }

    public PrivateAssetDownload prepareDownload(Long assetId, Long userId) {
        InternalUserRefResponse user = userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage", "tour.manage");
        PrivateAsset asset = requireAsset(assetId);
        verifyDownloadPermission(asset, user);
        InputStream inputStream = openAssetStream(asset);
        return new PrivateAssetDownload(inputStream, asset.getOriginalFilename(), asset.getContentType(), asset.getFileSize());
    }

    /**
     * 生成短期有效的签名下载地址；对象存储未启用时抛出业务异常。
     */
    public String presignedDownloadUrl(Long assetId, Long userId, int expirySeconds) {
        InternalUserRefResponse user = userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage", "tour.manage");
        PrivateAsset asset = requireAsset(assetId);
        verifyDownloadPermission(asset, user);
        String url = assetStorageService.presignedGetUrl(OBJECT_KEY_PREFIX, asset.getRelativePath(), expirySeconds);
        if (url == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "对象存储未启用，无法生成签名下载地址");
        }
        return url;
    }

    /**
     * 清理超期未绑定的 pending 私有资产：软删除记录并删除物理文件。
     *
     * @return 清理条数
     */
    @Transactional
    public int cleanupExpiredPendingAssets(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        List<PrivateAsset> expired = privateAssetMapper.selectList(new LambdaQueryWrapper<PrivateAsset>()
                .eq(PrivateAsset::getStatus, STATUS_PENDING)
                .lt(PrivateAsset::getCreateTime, cutoff)
                .last("LIMIT 200"));
        int cleaned = 0;
        for (PrivateAsset asset : expired) {
            asset.setStatus(STATUS_DELETED);
            asset.setDeletedAt(LocalDateTime.now());
            privateAssetMapper.updateById(asset);
            if (asset.getRelativePath() != null) {
                assetStorageService.delete(privateRoot, OBJECT_KEY_PREFIX, asset.getRelativePath());
            }
            cleaned++;
        }
        return cleaned;
    }

    private InputStream openAssetStream(PrivateAsset asset) {
        if (asset.getRelativePath() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "私有资产文件不存在");
        }
        InputStream inputStream = assetStorageService.open(privateRoot, OBJECT_KEY_PREFIX, asset.getRelativePath());
        if (inputStream == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "私有资产文件不存在");
        }
        return inputStream;
    }

    private void validateFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件");
        }
        validateExtension(file.getOriginalFilename(), contentType);
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明文件不能超过20MB");
        }
        if ("application/pdf".equals(contentType)) {
            validatePdfMagic(file);
        } else {
            validateImageMagic(contentType, file);
        }
    }

    private void verifyDownloadPermission(PrivateAsset asset, InternalUserRefResponse user) {
        if (!isVenueMaterialBizType(asset.getBizType())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
        }
        if (STATUS_PENDING.equals(asset.getStatus())) {
            if (userAccessService.isAdmin(user) || !userAccessService.isOrganizer(user)) {
                return;
            }
            if (asset.getUploaderId().equals(user.getId())) {
                return;
            }
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
        }
        if (STATUS_BOUND.equals(asset.getStatus())) {
            if (userAccessService.isAdmin(user) || !userAccessService.isOrganizer(user)) {
                return;
            }
            VenueApplication application = requireVenueApplication(asset.getBizId());
            if (user.getId().equals(application.getApplicantId())) {
                return;
            }
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
    }

    private PrivateAsset requireAsset(Long assetId) {
        if (assetId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "私有资产ID不能为空");
        }
        PrivateAsset asset = privateAssetMapper.selectById(assetId);
        if (asset == null || asset.getDeletedAt() != null || STATUS_DELETED.equals(asset.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "私有资产不存在");
        }
        return asset;
    }

    private boolean isVenueMaterialBizType(String bizType) {
        return bizType != null && VENUE_MATERIAL_BIZ_TYPES.contains(bizType);
    }

    private VenueApplication requireVenueApplication(Long applicationId) {
        if (applicationId == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
        }
        VenueApplication application = venueApplicationMapper.selectById(applicationId);
        if (application == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该私有资产");
        }
        return application;
    }

    private void validateExtension(String originalFilename, String contentType) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件");
        }
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0 || dot == lowerName.length() - 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件");
        }
        String extension = lowerName.substring(dot + 1);
        boolean valid = ("application/pdf".equals(contentType) && "pdf".equals(extension))
                || ("image/jpeg".equals(contentType) && ("jpg".equals(extension) || "jpeg".equals(extension)))
                || ("image/png".equals(contentType) && "png".equals(extension))
                || ("image/webp".equals(contentType) && "webp".equals(extension));
        if (!valid) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件");
        }
    }

    private void validatePdfMagic(MultipartFile file) {
        if (!startsWith(readHeader(file), new int[] {'%', 'P', 'D', 'F'})) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件内容不是有效PDF");
        }
    }

    private void validateImageMagic(String contentType, MultipartFile file) {
        byte[] header = readHeader(file);
        boolean valid;
        if ("image/jpeg".equals(contentType)) {
            valid = startsWith(header, new int[] {0xff, 0xd8, 0xff});
        } else if ("image/png".equals(contentType)) {
            valid = startsWith(header, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
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
        if ("application/pdf".equals(contentType)) {
            return "pdf";
        }
        if ("image/jpeg".equals(contentType)) {
            return "jpg";
        }
        if ("image/png".equals(contentType)) {
            return "png";
        }
        if ("image/webp".equals(contentType)) {
            return "webp";
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件");
    }
}
