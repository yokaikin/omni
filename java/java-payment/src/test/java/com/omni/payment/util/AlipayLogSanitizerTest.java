package com.omni.payment.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlipayLogSanitizerTest {

    @Test
    void safeMessageStripsSignedRequestParamBlock() {
        String raw = "RSA2签名遭遇异常，请检查私钥格式是否正确。java.security.InvalidKeyException: IOException : null "
                + "content=alipay_sdk=alipay-sdk-java-4.40.791.ALL&app_id=x&biz_content={\"out_trade_no\":\"DM1\"}&charset=utf-8";
        String safe = AlipayLogSanitizer.safeMessage(raw);
        assertTrue(safe.startsWith("RSA2签名遭遇异常，请检查私钥格式是否正确。"));
        assertTrue(safe.endsWith("（请求参数已隐藏）"));
        assertEquals(-1, safe.indexOf("out_trade_no"));
        assertEquals(-1, safe.indexOf("biz_content"));
    }

    @Test
    void safeMessageKeepsPlainMessageUntouched() {
        assertEquals("订单服务超时", AlipayLogSanitizer.safeMessage("订单服务超时"));
    }

    @Test
    void safeMessageHandlesNullAndEmpty() {
        assertEquals("unknown", AlipayLogSanitizer.safeMessage((String) null));
        assertEquals("unknown", AlipayLogSanitizer.safeMessage(""));
    }

    @Test
    void safeMessageHidesAllWhenMessageIsOnlyParams() {
        assertEquals("支付宝请求参数已隐藏", AlipayLogSanitizer.safeMessage("content=alipay_sdk=x&biz_content={}"));
    }

    @Test
    void safeMessageFromThrowableUsesItsMessage() {
        RuntimeException error = new RuntimeException("content=biz_content={\"a\":1}");
        assertEquals("支付宝请求参数已隐藏", AlipayLogSanitizer.safeMessage(error));
    }

    @Test
    void safeMessageNullThrowableReturnsUnknown() {
        assertEquals("unknown", AlipayLogSanitizer.safeMessage((Throwable) null));
    }
}
