package com.omni.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogMaskUtilTest {

    @Test
    void maskPhoneMasksMiddleDigits() {
        assertEquals("138****0001", LogMaskUtil.maskPhone("13800000001"));
    }

    @Test
    void maskPhoneKeepsShortValueOpaque() {
        assertEquals("****", LogMaskUtil.maskPhone("12345"));
        assertEquals("****", LogMaskUtil.maskPhone("123456"));
    }

    @Test
    void maskPhoneHandlesNullAndBlank() {
        assertNull(LogMaskUtil.maskPhone(null));
        assertNull(LogMaskUtil.maskPhone("   "));
    }

    @Test
    void maskPhoneTrimsInput() {
        assertEquals("138****0001", LogMaskUtil.maskPhone(" 13800000001 "));
    }

    @Test
    void maskEmailKeepsFirstCharAndDomain() {
        assertEquals("a***@example.com", LogMaskUtil.maskEmail("alice@example.com"));
    }

    @Test
    void maskEmailMasksSingleCharLocalPart() {
        assertEquals("*@example.com", LogMaskUtil.maskEmail("a@example.com"));
    }

    @Test
    void maskEmailHandlesInvalidInput() {
        assertEquals("****", LogMaskUtil.maskEmail("not-an-email"));
        assertNull(LogMaskUtil.maskEmail(null));
    }

    @Test
    void maskIdNoKeepsHeadAndTail() {
        assertEquals("11****34", LogMaskUtil.maskIdNo("110101199001011234"));
    }

    @Test
    void maskIdNoHandlesShortValue() {
        assertEquals("****", LogMaskUtil.maskIdNo("12345"));
        assertNull(LogMaskUtil.maskIdNo(null));
    }

    @Test
    void maskNameKeepsSurnameOnly() {
        assertEquals("张*", LogMaskUtil.maskName("张三"));
        assertEquals("欧**", LogMaskUtil.maskName("欧阳锋"));
        assertEquals("*", LogMaskUtil.maskName("张"));
        assertNull(LogMaskUtil.maskName(null));
    }
}
