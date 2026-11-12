package com.omni.ticket.controller;

import com.omni.common.storage.AssetStorageService;
import com.omni.common.util.ProjectPathUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 公有上传文件读取：本地磁盘优先，对象存储兜底（迁移期混存兼容）。
 */
@RestController
public class UploadResourceController {

    private static final String URL_PREFIX = "/uploads/ticket/";
    private static final String PATH_PREFIX = "ticket/";

    private final AssetStorageService assetStorageService;
    private final Path uploadRoot;

    public UploadResourceController(AssetStorageService assetStorageService,
                                    @Value("${omni.upload.root:${OMNI_UPLOAD_ROOT:}}") String uploadRoot) {
        this.assetStorageService = assetStorageService;
        this.uploadRoot = ProjectPathUtil.resolvePublicUploadRoot(uploadRoot);
    }

    @GetMapping("/uploads/ticket/**")
    public ResponseEntity<InputStreamResource> serve(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.length() <= URL_PREFIX.length()) {
            return ResponseEntity.notFound().build();
        }
        String relativePath = PATH_PREFIX + uri.substring(URL_PREFIX.length());
        InputStream inputStream = assetStorageService.open(uploadRoot, "", relativePath);
        if (inputStream == null) {
            return ResponseEntity.notFound().build();
        }
        Optional<MediaType> mediaType = MediaTypeFactory.getMediaType(uri);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentType(mediaType.orElse(MediaType.APPLICATION_OCTET_STREAM));
        if (assetStorageService.localExists(uploadRoot, relativePath)) {
            try {
                builder.contentLength(Files.size(uploadRoot.resolve(relativePath).normalize()));
            } catch (Exception ignored) {
                // 本地文件读取大小失败时退化为 chunked 传输
            }
        }
        return builder.body(new InputStreamResource(inputStream));
    }
}
