package com.omni.payment.util;

/**
 * 支付宝 SDK 异常消息脱敏。
 * AlipayApiException 的 message 会内嵌完整签名参数串（content=...&biz_content=...，含订单号/金额/标题），
 * 直接落日志或落库会造成业务参数泄露；本工具在日志边界统一截断参数段。
 */
public final class AlipayLogSanitizer {

    private static final String PARAM_MARK = "content=";

    private AlipayLogSanitizer() {
    }

    public static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return safeMessage(error.getMessage());
    }

    public static String safeMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "unknown";
        }
        int index = message.indexOf(PARAM_MARK);
        if (index < 0) {
            return message;
        }
        String head = message.substring(0, index).trim();
        return head.isEmpty() ? "支付宝请求参数已隐藏" : head + "（请求参数已隐藏）";
    }
}
