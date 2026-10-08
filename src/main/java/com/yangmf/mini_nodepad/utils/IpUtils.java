package com.yangmf.mini_nodepad.utils;

import jakarta.servlet.http.HttpServletRequest;

public final class IpUtils {

    private IpUtils() {}

    private static final String UNKNOWN = "unknown";

    public static String getIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String ip = firstNonBlank(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Proxy-Client-IP"),
                request.getHeader("WL-Proxy-Client-IP"),
                request.getRemoteAddr()
        );
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : UNKNOWN;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !UNKNOWN.equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }
}