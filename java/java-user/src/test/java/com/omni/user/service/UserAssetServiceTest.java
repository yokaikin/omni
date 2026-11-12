package com.omni.user.service;

import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.DisabledObjectStorageClient;
import com.omni.common.storage.DisabledVirusScanner;
import com.omni.exception.BusinessException;
import com.omni.user.dto.UserInfoResponse;
import com.omni.user.entity.User;
import com.omni.user.entity.UserAsset;
import com.omni.user.mapper.UserAssetMapper;
import com.omni.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserAssetServiceTest {

    private static final Long USER_ID = 2004L;

    private final UserAssetMapper userAssetMapper = mock(UserAssetMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AssetStorageService assetStorageService =
            new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

    @Test
    void uploadAvatarStoresImageAndUpdatesUserAvatar() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        User user = userWithAvatar(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userAssetMapper.insert(any(UserAsset.class))).thenAnswer(invocation -> {
            UserAsset asset = invocation.getArgument(0);
            asset.setId(9001L);
            return 1;
        });

        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, assetStorageService, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4}
        );

        UserInfoResponse response = service.uploadAvatar(USER_ID, file);

        assertNotNull(response.getAvatar());
        assertTrue(response.getAvatar().startsWith("/uploads/user/avatar/"));
        assertTrue(response.getAvatar().endsWith(".png"));
        assertEquals(response.getAvatar(), user.getAvatar());
        assertTrue(Files.exists(uploadRoot.resolve(response.getAvatar().substring("/uploads/".length()))));
        verify(userAssetMapper).insert(any(UserAsset.class));
        verify(userMapper).updateById(user);
    }

    @Test
    void uploadAvatarRejectsNonImageFile() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, assetStorageService, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.txt",
                "text/plain",
                new byte[] {1, 2, 3}
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.uploadAvatar(USER_ID, file));

        assertEquals("仅支持上传 JPG、PNG、WEBP 或 GIF 图片", exception.getMessage());
        verify(userAssetMapper, never()).insert(any());
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void uploadAvatarRejectsFileLargerThanTwoMb() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, assetStorageService, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                new byte[2 * 1024 * 1024 + 1]
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.uploadAvatar(USER_ID, file));

        assertEquals("头像图片不能超过2MB", exception.getMessage());
        verify(userAssetMapper, never()).insert(any());
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void uploadAvatarRejectsForgedImageContentType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, assetStorageService, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "not-an-image".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.uploadAvatar(USER_ID, file));

        assertEquals("文件内容不是有效图片", exception.getMessage());
        verifyNoInteractions(userAssetMapper);
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void uploadAvatarRejectsFileThatFailsVirusScan() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        AssetStorageService infectedStorage = new AssetStorageService(
                new DisabledObjectStorageClient(),
                new com.omni.common.storage.VirusScanner() {
                    @Override
                    public com.omni.common.storage.VirusScanResult scan(byte[] content, String filename) {
                        return com.omni.common.storage.VirusScanResult.infected("EICAR-Test-File");
                    }

                    @Override
                    public boolean isEnabled() {
                        return true;
                    }
                },
                true);
        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, infectedStorage, uploadRoot.toString());
        User user = userWithAvatar(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4}
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.uploadAvatar(USER_ID, file));

        assertEquals("文件未通过病毒扫描，上传已拒绝", exception.getMessage());
        verifyNoInteractions(userAssetMapper);
    }

    @Test
    void uploadAvatarDeletesStoredFileWhenDatabaseInsertFails() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-user-avatar-test");
        User user = userWithAvatar(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userAssetMapper.insert(any(UserAsset.class))).thenThrow(new RuntimeException("db down"));

        UserAssetService service = new UserAssetService(userAssetMapper, userMapper, assetStorageService, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.gif",
                "image/gif",
                new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3}
        );

        assertThrows(RuntimeException.class, () -> service.uploadAvatar(USER_ID, file));

        assertEquals(0L, Files.walk(uploadRoot).filter(Files::isRegularFile).count());
        verify(userMapper, never()).updateById(any());
    }

    private User userWithAvatar(String avatar) {
        User user = new User();
        user.setId(USER_ID);
        user.setPhone("13900000001");
        user.setNickname("测试用户");
        user.setRole("user");
        user.setAvatar(avatar);
        return user;
    }
}
