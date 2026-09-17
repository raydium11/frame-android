package com.frameapp.browser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Turns whatever a person types into something safe to navigate to.
 *
 * Deliberately free of Android imports so its behaviour can be unit tested on a
 * plain JVM.
 */
public final class UrlUtils {

    private UrlUtils() {
    }

    private static final int MAX_INPUT = 4096;

    /** Schemes a typed address is never allowed to use. */
    private static final String[] BLOCKED_SCHEMES = {
            "javascript:", "data:", "file:", "content:", "blob:", "about:", "vbscript:", "intent:", "jar:",
    };

    /**
     * Normalises typed input into a URL to load.
     *
     * Anything that looks like an address becomes one (adding https:// when no
     * scheme is given); anything else becomes a web search, which is how an
     * ordinary browser address bar behaves.
     *
     * Returns null when there is nothing to load.
     */
    public static String toUrlOrSearch(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty() || text.length() > MAX_INPUT) {
            return null;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_SCHEMES) {
            if (lower.startsWith(blocked)) {
                // Typed scripts and local files are treated as a search rather
                // than executed, so pasting one can never run it.
                return searchUrl(text);
            }
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return text;
        }

        if (looksLikeAddress(text)) {
            return "https://" + stripLeadingSlashes(text);
        }

        return searchUrl(text);
    }

    /** True when text resembles a host, optionally with a port and path. */
    static boolean looksLikeAddress(String text) {
        String candidate = stripLeadingSlashes(text);
        if (candidate.isEmpty() || candidate.indexOf(' ') >= 0) {
            return false;
        }

        String authority = candidate;
        int cut = indexOfFirst(authority, "/?#");
        if (cut >= 0) {
            authority = authority.substring(0, cut);
        }
        if (authority.isEmpty()) {
            return false;
        }

        // "localhost:8080" and bare IP addresses are addresses too.
        String host = authority;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && isAllDigits(host.substring(colon + 1))) {
            host = host.substring(0, colon);
        }
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }

        int dot = host.indexOf('.');
        if (dot <= 0 || dot == host.length() - 1) {
            return false;
        }
        // A trailing segment of all digits (an IP address) or at least two letters.
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (isAllDigits(last)) {
            return true;
        }
        if (last.length() < 2) {
            return false;
        }
        boolean lettersOnly = true;
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            if (!Character.isLetter(c)) {
                lettersOnly = false;
                break;
            }
        }
        return lettersOnly || isAllDigits(last);
    }

    /** Builds a web search URL for free text. */
    public static String searchUrl(String query) {
        String encoded;
        try {
            encoded = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = query.replace(" ", "+");
        }
        return "https://www.google.com/search?q=" + encoded;
    }

    /** True for URLs this browser is willing to load in a tab. */
    public static boolean isWebUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Host without scheme, "www." or path, for compact display. */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String text = url;
        int scheme = text.indexOf("://");
        if (scheme >= 0) {
            text = text.substring(scheme + 3);
        }
        int cut = indexOfFirst(text, "/?#");
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            text = text.substring(at + 1);
        }
        int colon = text.lastIndexOf(':');
        if (colon > 0 && isAllDigits(text.substring(colon + 1))) {
            text = text.substring(0, colon);
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("www.")) {
            text = text.substring(4);
        }
        return text;
    }

    /** A short label for a tab: the page title if there is one, else the host. */
    public static String tabLabel(String title, String url) {
        if (title != null && !title.trim().isEmpty() && !title.startsWith("http")) {
            String clean = title.trim();
            return clean.length() > 28 ? clean.substring(0, 27) + "…" : clean;
        }
        String host = hostOf(url);
        return host.isEmpty() ? "New tab" : host;
    }

    private static String stripLeadingSlashes(String text) {
        return text.startsWith("//") ? text.substring(2) : text;
    }

    private static int indexOfFirst(String text, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int at = text.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
