package com.vyom.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public final class ReferenceGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private ReferenceGenerator() {}

    public static String generateTransactionReference() {
        return "TXN-" + LocalDate.now().format(DATE_FORMATTER) + "-" + randomString(16);
    }

    public static String generateDisputeReference() {
        return "DISP-" + LocalDate.now().format(DATE_FORMATTER) + "-" + randomString(16);
    }

    public static String generateOrderReference() {
        return "ORD-" + LocalDate.now().format(DATE_FORMATTER) + "-" + randomString(16);
    }

    public static String generateAttemptReference() {
        return "ATT-" + LocalDate.now().format(DATE_FORMATTER) + "-" + randomString(16);
    }

    public static String generateSessionToken() {
        return "sess_" + LocalDate.now().format(DATE_FORMATTER) + "_" + randomString(24).toLowerCase();
    }

    private static String randomString(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
