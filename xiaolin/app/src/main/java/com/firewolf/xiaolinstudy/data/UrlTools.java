package com.firewolf.xiaolinstudy.data;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class UrlTools {
    public static final String HOME_URL = "https://www.xiaolincoding.com/";

    private UrlTools() {}

    public static String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return HOME_URL;
        try {
            URI input = new URI(rawUrl.trim());
            String scheme = input.getScheme();
            String host = input.getHost();
            if (scheme == null || host == null) return rawUrl.trim();

            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("xiaolincoding.com")) host = "www.xiaolincoding.com";
            if (host.equals("www.xiaolincoding.com")) scheme = "https";

            String path = input.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new URI(scheme, input.getUserInfo(), host, input.getPort(), path,
                    null, null).toASCIIString();
        } catch (URISyntaxException ignored) {
            int hashIndex = rawUrl.indexOf('#');
            return hashIndex >= 0 ? rawUrl.substring(0, hashIndex) : rawUrl;
        }
    }

    public static boolean isWebUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    public static boolean isCompletable(String url) {
        if (!isWebUrl(url)) return false;
        String normalized = normalize(url);
        return !HOME_URL.equals(normalized) && !normalized.matches(".*\\.(png|jpe?g|gif|webp|svg|pdf|zip)$");
    }

    public static String displayTitle(String title, String url) {
        String clean = title == null ? "" : title.trim();
        clean = clean.replace(" - 小林coding", "").replace(" | 小林coding", "");
        if (!clean.isEmpty() && !clean.equalsIgnoreCase("about:blank")) return clean;
        try {
            String path = new URI(url).getPath();
            if (path == null || path.equals("/")) return "小林coding 首页";
            String[] parts = path.split("/");
            String last = parts[parts.length - 1].replace(".html", "");
            return last.isEmpty() ? "学习内容" : last;
        } catch (Exception ignored) {
            return "学习内容";
        }
    }
}
