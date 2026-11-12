package com.omni.notification.service;

import com.omni.common.result.Result;
import com.omni.notification.client.UserInternalClient;
import com.omni.notification.dto.InternalUserContactResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecipientResolverTest {

    private final UserInternalClient userInternalClient = mock(UserInternalClient.class);
    private final RecipientResolver resolver = new RecipientResolver(userInternalClient, "internal-token");

    @Test
    void resolveContactReturnsContactOnSuccess() {
        InternalUserContactResponse contact = new InternalUserContactResponse();
        contact.setUserId(2004L);
        contact.setPhone("13800001111");
        contact.setEmail("user@example.com");
        when(userInternalClient.getUserContact(2004L, "internal-token")).thenReturn(Result.success(contact));

        assertEquals("13800001111", resolver.resolvePhone(2004L));
        assertEquals("user@example.com", resolver.resolveEmail(2004L));
    }

    @Test
    void resolveContactReturnsNullWhenUserMissing() {
        when(userInternalClient.getUserContact(404L, "internal-token")).thenReturn(Result.fail(404, "用户不存在"));

        assertNull(resolver.resolvePhone(404L));
        assertNull(resolver.resolveEmail(404L));
    }

    @Test
    void resolveContactReturnsNullWhenClientThrows() {
        when(userInternalClient.getUserContact(2004L, "internal-token")).thenThrow(new RuntimeException("feign timeout"));

        assertNull(resolver.resolvePhone(2004L));
    }

    @Test
    void resolveContactReturnsNullWhenUserIdMissing() {
        assertNull(resolver.resolvePhone(null));
        assertNull(resolver.resolveEmail(null));
    }
}
