package com.frameapp.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A small tabbed browser built on the system WebView.
 *
 * Each tab owns a real WebView, which is a top-level browsing context, so sites
 * load exactly as they would in any browser. Nothing is proxied or rewritten:
 * the device talks to each site directly.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "frame.tabs";
    private static final String KEY_URLS = "urls";
    private static final String KEY_ACTIVE = "active";
    private static final int MAX_TABS = 12;

    /** One open tab. */
    private static final class Tab {
        WebView web;
        String title;
        View chip;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;

    private FrameLayout webContainer;
    private LinearLayout tabContainer;
    private HorizontalScrollView tabScroller;
    private EditText urlBar;
    private ProgressBar progress;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton reloadButton;
    private TextView tabCount;

    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int savedOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webContainer = findViewById(R.id.web_container);
        tabContainer = findViewById(R.id.tab_container);
        tabScroller = findViewById(R.id.tab_scroller);
        urlBar = findViewById(R.id.url_bar);
        progress = findViewById(R.id.progress);
        backButton = findViewById(R.id.action_back);
        forwardButton = findViewById(R.id.action_forward);
        reloadButton = findViewById(R.id.action_reload);
        tabCount = findViewById(R.id.tab_count);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        // Third-party cookies are needed for ordinary sign-in flows to work.
        CookieManager.getInstance().setAcceptCookie(true);

        urlBar.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });
        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    urlBar.setSelection(0, urlBar.getText().length());
                }
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null && web.canGoBack()) {
                    web.goBack();
                }
            }
        });
        forwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null && web.canGoForward()) {
                    web.goForward();
                }
            }
        });
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView web = activeWeb();
                if (web != null) {
                    web.reload();
                }
            }
        });
        findViewById(R.id.action_new_tab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newTab(null, true);
                urlBar.requestFocus();
                showKeyboard();
            }
        });

        restoreTabs();
    }

    // ---------------------------------------------------------------- tabs

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
                tab.chip.setSelected(i == index);
                tab.chip.setBackgroundResource(i == index ? R.drawable.bg_tab_active : R.drawable.bg_tab);
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
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab tab = tabs.get(i);
            View chip = inflater.inflate(R.layout.tab_chip, tabContainer, false);
            TextView label = chip.findViewById(R.id.tab_label);
            label.setText(UrlUtils.tabLabel(tab.title, tab.web.getUrl()));
            chip.setBackgroundResource(index == activeIndex ? R.drawable.bg_tab_active : R.drawable.bg_tab);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setActiveTab(index);
                }
            });
            ImageView close = chip.findViewById(R.id.tab_close);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeTab(index);
                }
            });
            tab.chip = chip;
            tabContainer.addView(chip);
        }
        tabCount.setText(String.valueOf(tabs.size()));
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
                    tabScroller.smoothScrollTo(Math.max(0, left - 16), 0);
                } else if (right > visibleRight) {
                    tabScroller.smoothScrollTo(right - tabScroller.getWidth() + 16, 0);
                }
            }
        });
    }

    private void updateTabLabel(WebView web) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (tab.web == web && tab.chip != null) {
                TextView label = tab.chip.findViewById(R.id.tab_label);
                label.setText(UrlUtils.tabLabel(tab.title, web.getUrl()));
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

    // ------------------------------------------------------------- webview

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
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
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
                // so no storage permission is needed.
                openExternally(url);
            }
        });

        return web;
    }

    /**
     * Decides what to do with a URL the page wants to open. Web pages load in
     * the tab; anything else (mailto:, tel:, maps:, market: …) is handed to the
     * app that owns it.
     */
    private boolean handleUrl(String url) {
        if (UrlUtils.isWebUrl(url)) {
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
        String url = UrlUtils.toUrlOrSearch(input);
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
        backButton.setEnabled(canBack);
        backButton.setAlpha(canBack ? 1f : 0.35f);
        forwardButton.setEnabled(canForward);
        forwardButton.setAlpha(canForward ? 1f : 0.35f);
        reloadButton.setEnabled(web != null);
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

    // ---------------------------------------------------------- fullscreen

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
        findViewById(R.id.chrome).setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitFullscreen() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        findViewById(R.id.chrome).setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(savedOrientation);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    // ------------------------------------------------------ lifecycle, back

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

    // -------------------------------------------------------- persistence

    private void saveTabs() {
        StringBuilder joined = new StringBuilder();
        for (Tab tab : tabs) {
            String url = tab.web.getUrl();
            if (url != null && UrlUtils.isWebUrl(url)) {
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
                if (UrlUtils.isWebUrl(url)) {
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
}
