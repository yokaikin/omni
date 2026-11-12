package com.omni.common.storage;

import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 统一资产存储管道：
 * - 上传前执行病毒扫描（可配置 fail-open）；
 * - 对象存储启用时写入对象存储，未启用时降级写本地磁盘；
 * - 读取本地优先、对象存储兜底，兼容迁移期混存；
 * - 删除时本地与对象存储双删。
 */
@Service
public class AssetStorageService {

    private static final Logger log = LoggerFactory.getLogger(AssetStorageService.class);

    private final ObjectStorageClient objectStorageClient;
    private final VirusScanner virusScanner;
    private final boolean virusScanFailOpen;

    public AssetStorageService(ObjectStorageClient objectStorageClient,
                               VirusScanner virusScanner,
                               @Value("${omni.virus-scanner.fail-open:false}") boolean virusScanFailOpen) {
        this.objectStorageClient = objectStorageClient;
        this.virusScanner = virusScanner;
        this.virusScanFailOpen = virusScanFailOpen;
    }

    /**
     * 存储上传文件：病毒扫描 → SHA-256 摘要 → 写入对象存储（启用时）或本地磁盘（降级）。
     *
     * @param localRoot       本地降级根目录
     * @param objectKeyPrefix 对象存储 key 前缀（可为空串）
     * @param relativePath    相对路径（同时是对象 key 的后半段）
     */
    public StoredFile store(Path localRoot, String objectKeyPrefix, String relativePath, MultipartFile file) {
        byte[] content = readAllBytes(file);
        scanForViruses(content, file.getOriginalFilename());
        String sha256 = sha256Hex(content);
        if (objectStorageClient.isEnabled()) {
            String key = objectKey(objectKeyPrefix, relativePath);
            objectStorageClient.putObject(key, new ByteArrayInputStream(content), content.length, file.getContentType());
            return new StoredFile(sha256, true);
        }
        Path target = resolveInside(localRoot, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "资产文件保存失败");
        }
        return new StoredFile(sha256, false);
    }

    /**
     * 读取文件流：本地存在优先本地，否则尝试对象存储；均不存在返回 null。
     */
    public InputStream open(Path localRoot, String objectKeyPrefix, String relativePath) {
        Path local = resolveInside(localRoot, relativePath);
        if (Files.exists(local) && Files.isRegularFile(local)) {
            try {
                return Files.newInputStream(local);
            } catch (IOException e) {
                log.warn("读取本地资产失败: path={}, error={}", local, e.getMessage());
            }
        }
        if (objectStorageClient.isEnabled()) {
            return objectStorageClient.getObject(objectKey(objectKeyPrefix, relativePath));
        }
        return null;
    }

    /**
     * 生成本地文件是否存在的快速判断（不读对象存储）。
     */
    public boolean localExists(Path localRoot, String relativePath) {
        Path local = resolveInside(localRoot, relativePath);
        return Files.exists(local) && Files.isRegularFile(local);
    }

    /**
     * 生成对象存储签名下载地址；对象存储未启用时返回 null。
     */
    public String presignedGetUrl(String objectKeyPrefix, String relativePath, int expirySeconds) {
        if (!objectStorageClient.isEnabled()) {
            return null;
        }
        return objectStorageClient.presignedGetUrl(objectKey(objectKeyPrefix, relativePath), expirySeconds);
    }

    /**
     * 删除文件：本地与对象存储双删，均静默容错。
     */
    public void delete(Path localRoot, String objectKeyPrefix, String relativePath) {
        Path local = resolveInside(localRoot, relativePath);
        try {
            Files.deleteIfExists(local);
        } catch (IOException e) {
            log.warn("删除本地资产失败: path={}, error={}", local, e.getMessage());
        }
        if (objectStorageClient.isEnabled()) {
            objectStorageClient.removeObject(objectKey(objectKeyPrefix, relativePath));
        }
    }

    private void scanForViruses(byte[] content, String filename) {
        if (!virusScanner.isEnabled()) {
            return;
        }
        VirusScanResult result;
        try {
            result = virusScanner.scan(content, filename);
        } catch (RuntimeException e) {
            if (virusScanFailOpen) {
                log.warn("病毒扫描服务不可用，按 fail-open 策略放行: filename={}, error={}", filename, e.getMessage());
                return;
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "病毒扫描服务暂不可用，请稍后重试");
        }
        if (!result.isClean()) {
            log.warn("上传文件检出病毒，已拒绝: filename={}, signature={}", filename, result.getSignature());
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件未通过病毒扫描，上传已拒绝");
        }
    }

    private byte[] readAllBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件读取失败");
        }
    }

    private Path resolveInside(Path root, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "资产路径不合法");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "资产路径不合法");
        }
        return resolved;
    }

    private String objectKey(String objectKeyPrefix, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        if (StringUtils.hasText(objectKeyPrefix)) {
            return objectKeyPrefix + "/" + normalized;
        }
        return normalized;
    }

    private String sha256Hex(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件摘要计算失败");
        }
        byte[] hash = digest.digest(content);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
