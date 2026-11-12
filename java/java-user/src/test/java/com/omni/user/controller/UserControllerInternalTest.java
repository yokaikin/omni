package com.omni.user.controller;

import com.omni.user.dto.InternalUserRefResponse;
import com.omni.user.service.OrganizerApplicationService;
import com.omni.user.service.RbacService;
import com.omni.user.service.UserAssetService;
import com.omni.user.service.UserAttendeeService;
import com.omni.user.service.UserBrowseHistoryService;
import com.omni.user.service.UserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerInternalTest {

    private final UserService userService = mock(UserService.class);
    private final OrganizerApplicationService organizerApplicationService = mock(OrganizerApplicationService.class);
    private final UserController controller = new UserController(userService, organizerApplicationService, "internal-token");

    @Test
    void internalUserRefRejectsMissingToken() {
        var result = controller.getInternalUserRef(2004L, null);

        assertEquals(403, result.getCode());
        verify(userService, never()).getInternalUserRef(2004L);
    }

    @Test
    void internalUserRefRejectsWrongToken() {
        var result = controller.getInternalUserRef(2004L, "wrong-token");

        assertEquals(403, result.getCode());
        verify(userService, never()).getInternalUserRef(2004L);
    }

    @Test
    void internalUserRefReturnsUserWhenTokenMatches() {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(2004L);
        user.setRole("organizer");
        when(userService.getInternalUserRef(2004L)).thenReturn(user);

        var result = controller.getInternalUserRef(2004L, "internal-token");

        assertEquals(200, result.getCode());
        assertEquals(2004L, result.getData().getId());
        assertEquals("organizer", result.getData().getRole());
    }

    @Test
    void internalUserRefRejectsEmptyConfiguredToken() {
        UserController emptyTokenController = new UserController(userService, organizerApplicationService, "");

        var result = emptyTokenController.getInternalUserRef(2004L, "internal-token");

        assertEquals(403, result.getCode());
        assertNull(result.getData());
    }

    @Test
    void internalUserContactRejectsMissingToken() {
        var result = controller.getInternalUserContact(2004L, null);

        assertEquals(403, result.getCode());
        verify(userService, never()).getInternalUserContact(2004L);
    }

    @Test
    void internalUserContactReturnsContactWhenTokenMatches() {
        com.omni.user.dto.InternalUserContactResponse contact = new com.omni.user.dto.InternalUserContactResponse();
        contact.setUserId(2004L);
        contact.setPhone("13800001111");
        contact.setEmail("user@example.com");
        when(userService.getInternalUserContact(2004L)).thenReturn(contact);

        var result = controller.getInternalUserContact(2004L, "internal-token");

        assertEquals(200, result.getCode());
        assertEquals(2004L, result.getData().getUserId());
        assertEquals("13800001111", result.getData().getPhone());
        assertEquals("user@example.com", result.getData().getEmail());
    }

    @Test
    void springUsesConstructorWithInternalTokenValue() {
        Constructor<?> constructor = Assertions.assertDoesNotThrow(() -> UserController.class.getConstructor(
                UserService.class,
                OrganizerApplicationService.class,
                RbacService.class,
                UserAssetService.class,
                UserAttendeeService.class,
                UserBrowseHistoryService.class,
                String.class
        ));

        Assertions.assertNotNull(constructor.getAnnotation(Autowired.class));
        Assertions.assertNotNull(constructor.getParameters()[6].getAnnotation(Value.class));
    }
}
