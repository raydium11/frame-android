import com.frameapp.browser.UrlUtils;

/** Plain-JVM tests for the address bar logic. Run by tools/check.sh. */
public class UrlUtilsTest {
    static int passed = 0, failed = 0;

    static void eq(Object actual, Object expected, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) { passed++; }
        else { failed++; System.out.println("  FAIL " + what + "\n    expected: " + expected + "\n    actual:   " + actual); }
    }
    static void ok(boolean cond, String what) {
        if (cond) { passed++; } else { failed++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        // Addresses keep their scheme.
        eq(UrlUtils.toUrlOrSearch("https://example.com"), "https://example.com", "https kept");
        eq(UrlUtils.toUrlOrSearch("http://example.com/x?y=1"), "http://example.com/x?y=1", "http kept");
        eq(UrlUtils.toUrlOrSearch("  https://example.com  "), "https://example.com", "trimmed");

        // Bare hosts gain https.
        eq(UrlUtils.toUrlOrSearch("example.com"), "https://example.com", "bare host");
        eq(UrlUtils.toUrlOrSearch("www.google.com"), "https://www.google.com", "www host");
        eq(UrlUtils.toUrlOrSearch("en.wikipedia.org/wiki/Fresnel_lens"), "https://en.wikipedia.org/wiki/Fresnel_lens", "host with path");
        eq(UrlUtils.toUrlOrSearch("//example.com/a"), "https://example.com/a", "protocol-relative");
        eq(UrlUtils.toUrlOrSearch("localhost:8080"), "https://localhost:8080", "localhost with port");
        eq(UrlUtils.toUrlOrSearch("192.168.0.1"), "https://192.168.0.1", "ip address");

        // Everything else becomes a search.
        ok(UrlUtils.toUrlOrSearch("hello world").startsWith("https://www.google.com/search?q="), "text searches");
        ok(UrlUtils.toUrlOrSearch("best pizza").contains("best+pizza") || UrlUtils.toUrlOrSearch("best pizza").contains("best%20pizza"), "query encoded");
        ok(UrlUtils.toUrlOrSearch("frame").startsWith("https://www.google.com/search"), "single word searches");
        ok(UrlUtils.toUrlOrSearch("what is 2+2?").startsWith("https://www.google.com/search"), "question searches");

        // Dangerous schemes are never navigated to; they are searched instead.
        for (String bad : new String[]{"javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<script>alert(1)</script>",
                                       "file:///etc/passwd", "content://contacts", "about:blank", "intent://evil#Intent;end",
                                       "blob:https://x", "vbscript:msgbox"}) {
            String result = UrlUtils.toUrlOrSearch(bad);
            ok(result != null && result.startsWith("https://www.google.com/search"), "blocked scheme searched: " + bad);
        }

        // Nothing to load.
        eq(UrlUtils.toUrlOrSearch(""), null, "empty");
        eq(UrlUtils.toUrlOrSearch("   "), null, "blank");
        eq(UrlUtils.toUrlOrSearch(null), null, "null");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('a');
        eq(UrlUtils.toUrlOrSearch(huge.toString()), null, "over-long input");

        // Only http(s) may be loaded in a tab.
        ok(UrlUtils.isWebUrl("https://a.test"), "https is web");
        ok(UrlUtils.isWebUrl("http://a.test"), "http is web");
        ok(!UrlUtils.isWebUrl("mailto:a@b.test"), "mailto is not web");
        ok(!UrlUtils.isWebUrl("tel:123"), "tel is not web");
        ok(!UrlUtils.isWebUrl("intent://x"), "intent is not web");
        ok(!UrlUtils.isWebUrl(null), "null is not web");

        // Display helpers.
        eq(UrlUtils.hostOf("https://www.example.com/a/b?c=1"), "example.com", "hostOf strips www and path");
        eq(UrlUtils.hostOf("https://sub.example.co.uk:8443/x"), "sub.example.co.uk", "hostOf strips port");
        eq(UrlUtils.hostOf("https://user@example.com/x"), "example.com", "hostOf strips userinfo");
        eq(UrlUtils.hostOf(""), "", "hostOf empty");
        eq(UrlUtils.tabLabel("Example Domain", "https://example.com"), "Example Domain", "label uses title");
        eq(UrlUtils.tabLabel(null, "https://www.example.com/x"), "example.com", "label falls back to host");
        eq(UrlUtils.tabLabel("  ", "https://www.example.com/x"), "example.com", "label ignores blank title");
        eq(UrlUtils.tabLabel(null, ""), "New tab", "label for blank tab");
        ok(UrlUtils.tabLabel("A very long page title that will not fit on a small tab", "https://x.test").length() <= 28, "label truncated");

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
