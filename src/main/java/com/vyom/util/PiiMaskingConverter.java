package com.vyom.util;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

public class PiiMaskingConverter extends MessageConverter {

    private static final Pattern PASSWORD_JSON_PATTERN =
            Pattern.compile("(\"(?:password|currentPassword|newPassword|confirmPassword)\"\\s*:\\s*\")[^\"]*(\")", Pattern.CASE_INSENSITIVE);

    private static final Pattern PASSWORD_KV_PATTERN =
            Pattern.compile("((?:password|currentPassword|newPassword|confirmPassword)\\s*=\\s*)[^,;\\s&]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOKEN_JSON_PATTERN =
            Pattern.compile("(\"(?:token|accessToken|refreshToken|jwt|secret)\"\\s*:\\s*\")[^\"]*(\")", Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b([a-zA-Z0-9_.+-])[a-zA-Z0-9_.+-]*@([a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+)\\b");

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (message == null || message.isEmpty()) {
            return message;
        }
        return mask(message);
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String masked = PASSWORD_JSON_PATTERN.matcher(message).replaceAll("$1******$2");
        masked = PASSWORD_KV_PATTERN.matcher(masked).replaceAll("$1******");
        masked = BEARER_TOKEN_PATTERN.matcher(masked).replaceAll("$1[MASKED_TOKEN]");
        masked = TOKEN_JSON_PATTERN.matcher(masked).replaceAll("$1******$2");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("$1***@$2");

        return masked;
    }
}
