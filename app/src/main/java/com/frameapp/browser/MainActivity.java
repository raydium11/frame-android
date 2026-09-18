package com.frameapp.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frame: a small tabbed browser built on the system WebView.
 *
 * Each tab owns a real WebView, which is a top-level browsing context, so sites
 * load exactly as they would in any browser. Nothing is proxied or rewritten:
 * the device talks to each site directly.
 *
 * The whole app lives in this one file, and the interface is built in code
 * rather than XML, so the project is only two files plus its Gradle config.
 */
public class MainActivity extends Activity {

    // Palette: black stage, charcoal surfaces, one warm accent.
    private static final int STAGE = 0xFF000000;
    private static final int CHARCOAL = 0xFF141418;
    private static final int CHARCOAL_2 = 0xFF1B1B20;
    private static final int LINE = 0x29F3F1EC;
    private static final int SMOKE = 0xFFA3A1AA;
    private static final int DIM = 0xFF75737C;
    private static final int PAPER = 0xFFF3F1EC;
    private static final int TUNGSTEN = 0xFFF4C27A;

    private static final String PREFS = "frame.tabs";
    private static final String KEY_URLS = "urls";
    private static final String KEY_ACTIVE = "active";
    private static final int MAX_TABS = 12;

    /** One open tab. */
    private static final class Tab {
        WebView web;
        String title;
        View chip;
        TextView chipLabel;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;

    private FrameLayout webContainer;
    private LinearLayout tabContainer;
    private HorizontalScrollView tabScroller;
    private EditText urlBar;
    private ProgressBar progress;
    private TextView backButton;
    private TextView forwardButton;
    private LinearLayout chrome;
    private FrameLayout fullscreenContainer;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int savedOrientation;

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(STAGE);
        getWindow().setNavigationBarColor(STAGE);
        setContentView(buildUi());

        CookieManager.getInstance().setAcceptCookie(true);
        restoreTabs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();
        WebView web = activeWeb();
        if (web != null) {
            web.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WebView web = activeWeb();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab tab : tabs) {
            tab.web.setWebChromeClient(null);
            tab.web.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        WebView web = activeWeb();
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (tabs.size() > 1) {
            closeTab(activeIndex);
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------- the UI

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /** A rounded, subtly outlined surface, used for the address bar and tabs. */
    private GradientDrawable surface(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            shape.setStroke(Math.max(1, dp(1) / 2), stroke);
        }
        return shape;
    }

    /** A flat toolbar button drawn as text, so the app needs no icon files. */
    private TextView toolButton(String glyph, float sizeSp, View.OnClickListener onClick) {
        TextView button = new TextView(this);
        button.setText(glyph);
        button.setTextColor(SMOKE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        button.setOnClickListener(onClick);
        return button;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(STAGE);
        root.setFitsSystemWindows(true);

        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);

        // --- toolbar ---
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));

        backButton = toolButton("←", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null && web.canGoBack()) {
                    web.goBack();
                }
            }
        });
        forwardButton = toolButton("→", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null && web.canGoForward()) {
                    web.goForward();
                }
            }
        });
        TextView reloadButton = toolButton("↻", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null) {
                    web.reload();
                }
            }
        });
        TextView newTabButton = toolButton("+", 24f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newTab(null, true);
                urlBar.requestFocus();
                showKeyboard();
            }
        });

        urlBar = new EditText(this);
        urlBar.setBackground(surface(CHARCOAL_2, LINE, 12));
        urlBar.setHint("Search or enter address");
        urlBar.setHintTextColor(DIM);
        urlBar.setTextColor(PAPER);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        urlBar.setSingleLine(true);
        urlBar.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams urlParams =
                new LinearLayout.LayoutParams(0, dp(38), 1f);
        urlParams.setMargins(dp(4), 0, dp(4), 0);
        urlBar.setLayoutParams(urlParams);
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });

        toolbar.addView(backButton);
        toolbar.addView(forwardButton);
        toolbar.addView(urlBar);
        toolbar.addView(reloadButton);
        toolbar.addView(newTabButton);
        chrome.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- loading bar ---
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(TUNGSTEN));
        progress.setBackgroundColor(Color.TRANSPARENT);
        progress.setVisibility(View.GONE);
        chrome.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        // --- tab strip ---
        tabScroller = new HorizontalScrollView(this);
        tabScroller.setHorizontalScrollBarEnabled(false);
        tabScroller.setPadding(dp(6), 0, dp(6), dp(4));
        tabScroller.setVisibility(View.GONE);
        tabContainer = new LinearLayout(this);
        tabContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabScroller.addView(tabContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chrome.addView(tabScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- pages ---
        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(CHARCOAL);
        chrome.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- fullscreen video overlay ---
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(STAGE);
        fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    // ----------------------------------------------------------------- tabs

    private WebView activeWeb() {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex).web : null;
    }

    /** Opens a tab. A null url shows the start page. */
    private void newTab(String url, boolean activate) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "That's the maximum number of tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        Tab tab = new Tab();
        tab.web = createWebView();
        tabs.add(tab);

        webContainer.addView(tab.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.web.setVisibility(View.GONE);

        if (url == null) {
            loadStartPage(tab.web);
        } else {
            tab.web.loadUrl(url);
        }

        rebuildTabStrip();
        if (activate || activeIndex < 0) {
            setActiveTab(tabs.size() - 1);
        }
    }

    private void setActiveTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        activeIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            tab.web.setVisibility(i == index ? View.VISIBLE : View.GONE);
            if (tab.chip != null) {
                tab.chip.setBackground(i == index
                        ? surface(CHARCOAL_2, LINE, 9)
                        : surface(Color.TRANSPARENT, 0, 9));
            }
        }
        scrollTabIntoView(index);
        updateChrome();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        Tab tab = tabs.remove(index);
        webContainer.removeView(tab.web);
        tab.web.stopLoading();
        tab.web.setWebChromeClient(null);
        tab.web.destroy();

        if (tabs.isEmpty()) {
            activeIndex = -1;
            newTab(null, true);
            return;
        }
        rebuildTabStrip();
        setActiveTab(Math.min(index, tabs.size() - 1));
    }

    private void rebuildTabStrip() {
        tabContainer.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab tab = tabs.get(i);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), 0, dp(4), 0);
            chip.setBackground(index == activeIndex
                    ? surface(CHARCOAL_2, LINE, 9)
                    : surface(Color.TRANSPARENT, 0, 9));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            chipParams.setMargins(0, 0, dp(4), 0);
            chip.setLayoutParams(chipParams);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setActiveTab(index);
                }
            });

            TextView label = new TextView(this);
            label.setText(tabLabel(tab.title, tab.web.getUrl()));
            label.setTextColor(PAPER);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setMaxWidth(dp(140));
            chip.addView(label);

            TextView close = new TextView(this);
            close.setText("×");
            close.setTextColor(SMOKE);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            close.setGravity(Gravity.CENTER);
            close.setClickable(true);
            close.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeTab(index);
                }
            });
            chip.addView(close);

            tab.chip = chip;
            tab.chipLabel = label;
            tabContainer.addView(chip);
        }
        tabScroller.setVisibility(tabs.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void scrollTabIntoView(final int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        final View chip = tabs.get(index).chip;
        if (chip == null) {
            return;
        }
        tabScroller.post(new Runnable() {
            @Override
            public void run() {
                int left = chip.getLeft();
                int right = left + chip.getWidth();
                int visibleLeft = tabScroller.getScrollX();
                int visibleRight = visibleLeft + tabScroller.getWidth();
                if (left < visibleLeft) {
                    tabScroller.smoothScrollTo(Math.max(0, left - dp(8)), 0);
                } else if (right > visibleRight) {
                    tabScroller.smoothScrollTo(right - tabScroller.getWidth() + dp(8), 0);
                }
            }
        });
    }

    private void updateTabLabel(WebView web) {
        for (Tab tab : tabs) {
            if (tab.web == web && tab.chipLabel != null) {
                tab.chipLabel.setText(tabLabel(tab.title, web.getUrl()));
                return;
            }
        }
    }

    private Tab tabFor(WebView web) {
        for (Tab tab : tabs) {
            if (tab.web == web) {
                return tab;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        // Keep the WebView away from the device's own files, and never mix
        // insecure content into a secure page.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == activeWeb()) {
                    setUrlBarText(url);
                    updateChrome();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Tab tab = tabFor(view);
                if (tab != null) {
                    tab.title = view.getTitle();
                }
                updateTabLabel(view);
                if (view == activeWeb()) {
                    setUrlBarText(url);
                    updateChrome();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view != activeWeb()) {
                    return;
                }
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress > 0 && newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                Tab tab = tabFor(view);
                if (tab != null) {
                    tab.title = title;
                }
                updateTabLabel(view);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // A link with target="_blank": give it a tab of its own.
                if (tabs.size() >= MAX_TABS) {
                    return false;
                }
                Tab tab = new Tab();
                tab.web = createWebView();
                tabs.add(tab);
                webContainer.addView(tab.web, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                tab.web.setVisibility(View.GONE);
                rebuildTabStrip();
                setActiveTab(tabs.size() - 1);

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tab.web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                for (int i = 0; i < tabs.size(); i++) {
                    if (tabs.get(i).web == window) {
                        closeTab(i);
                        return;
                    }
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                // Downloads are handed to the system rather than handled here,
                // so the app needs no storage permission.
                openExternally(url);
            }
        });

        return web;
    }

    /**
     * Decides what to do with a URL the page wants to open. Web pages load in
     * the tab; anything else (mailto:, tel:, maps: ...) is handed to the app
     * that owns it.
     */
    private boolean handleUrl(String url) {
        if (isWebUrl(url)) {
            return false;
        }
        openExternally(url);
        return true;
    }

    private void openExternally(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Nothing on this device can open that link", Toast.LENGTH_SHORT).show();
        }
    }

    /** A small built-in start page, so a new tab is never a blank white screen. */
    private void loadStartPage(WebView web) {
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "html,body{margin:0;height:100%;background:#000;color:#f3f1ec;"
                + "font-family:-apple-system,Roboto,'Segoe UI',sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}"
                + "h1{font-size:19vw;margin:0;letter-spacing:-.06em;font-weight:800;"
                + "background:linear-gradient(180deg,#fff,#f3f1ec 42%,#7d7a84);"
                + "-webkit-background-clip:text;background-clip:text;color:transparent}"
                + "p{color:#a3a1aa;font-size:1rem;margin:1.2rem 2rem 0;line-height:1.5}"
                + "</style></head><body>"
                + "<h1>Frame</h1>"
                + "<p>Type an address or a search above.</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------ chrome UI

    private void go(String input) {
        String url = toUrlOrSearch(input);
        if (url == null) {
            return;
        }
        WebView web = activeWeb();
        if (web == null) {
            newTab(url, true);
        } else {
            web.loadUrl(url);
        }
        hideKeyboard();
        urlBar.clearFocus();
    }

    private void setUrlBarText(String url) {
        if (urlBar.hasFocus()) {
            return;
        }
        urlBar.setText(url == null || url.startsWith("data:") ? "" : url);
    }

    private void updateChrome() {
        WebView web = activeWeb();
        boolean canBack = web != null && web.canGoBack();
        boolean canForward = web != null && web.canGoForward();
        backButton.setAlpha(canBack ? 1f : 0.35f);
        forwardButton.setAlpha(canForward ? 1f : 0.35f);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    // ----------------------------------------------------------- fullscreen

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        savedOrientation = getRequestedOrientation();

        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setVisibility(View.VISIBLE);
        chrome.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitFullscreen() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        chrome.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(savedOrientation);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    // ---------------------------------------------------------- persistence

    private void saveTabs() {
        StringBuilder joined = new StringBuilder();
        for (Tab tab : tabs) {
            String url = tab.web.getUrl();
            if (url != null && isWebUrl(url)) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(url);
            }
        }
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(KEY_URLS, joined.toString());
        editor.putInt(KEY_ACTIVE, activeIndex);
        editor.apply();
    }

    private void restoreTabs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_URLS, "");
        int wanted = prefs.getInt(KEY_ACTIVE, 0);

        if (!TextUtils.isEmpty(stored)) {
            for (String url : stored.split("\n")) {
                // Stored entries are re-checked rather than trusted.
                if (isWebUrl(url)) {
                    newTab(url, false);
                }
            }
        }
        if (tabs.isEmpty()) {
            newTab(null, true);
        } else {
            setActiveTab(Math.max(0, Math.min(wanted, tabs.size() - 1)));
        }
    }

    // ------------------------------------------------- address bar handling
    //
    // These are unit tested on a plain JVM; see tools/UrlUtilsTest.java in the
    // project this was extracted from.

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
     * ordinary browser address bar behaves. Returns null when there is nothing
     * to load.
     */
    static String toUrlOrSearch(String input) {
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
                // Typed scripts and local files are searched for rather than
                // run, so pasting one can never execute it.
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
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (isAllDigits(last)) {
            return true;
        }
        if (last.length() < 2) {
            return false;
        }
        for (int i = 0; i < last.length(); i++) {
            if (!Character.isLetter(last.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Builds a web search URL for free text. */
    static String searchUrl(String query) {
        String encoded;
        try {
            encoded = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = query.replace(" ", "+");
        }
        return "https://www.google.com/search?q=" + encoded;
    }

    /** True for URLs this browser is willing to load in a tab. */
    static boolean isWebUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Host without scheme, "www." or path, for compact display. */
    static String hostOf(String url) {
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
    static String tabLabel(String title, String url) {
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
