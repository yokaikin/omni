package com.omni.common.util;

/**
 * 日志脱敏工具：手机号 / 邮箱 / 身份证 / 姓名统一掩码。
 * <p>
 * 所有方法对 null、空串、长度不足的输入返回安全占位符，不抛异常。
 */
public final class LogMaskUtil {

    private static final String SHORT_PLACEHOLDER = "****";

    private LogMaskUtil() {
    }

    /**
     * 手机号脱敏：138****0001。长度不足 7 位时整体打码。
     */
    public static String maskPhone(String phone) {
        String value = normalize(phone);
        if (value == null) {
            return null;
        }
        if (value.length() < 7) {
            return SHORT_PLACEHOLDER;
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 邮箱脱敏：a***@example.com。本地部分只保留首字符。
     */
    public static String maskEmail(String email) {
        String value = normalize(email);
        if (value == null) {
            return null;
        }
        int atIndex = value.indexOf('@');
        if (atIndex <= 0) {
            return SHORT_PLACEHOLDER;
        }
        String local = value.substring(0, atIndex);
        String domain = value.substring(atIndex);
        String maskedLocal = local.length() <= 1 ? "*" : local.charAt(0) + "***";
        return maskedLocal + domain;
    }

    /**
     * 身份证脱敏：保留前 2 后 2，其余打码。长度不足 6 位时整体打码。
     */
    public static String maskIdNo(String idNo) {
        String value = normalize(idNo);
        if (value == null) {
            return null;
        }
        if (value.length() < 6) {
            return SHORT_PLACEHOLDER;
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * 姓名脱敏：保留姓氏，其余打码（张*、欧**）。
     */
    public static String maskName(String name) {
        String value = normalize(name);
        if (value == null) {
            return null;
        }
        if (value.length() == 1) {
            return "*";
        }
        StringBuilder masked = new StringBuilder(value.substring(0, 1));
        for (int i = 1; i < value.length(); i++) {
            masked.append('*');
        }
        return masked.toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
