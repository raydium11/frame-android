# Frame for Android

A small tabbed browser built on Android's system WebView. Unlike the Frame website, this one can open **any** site, including google.com, because each tab is a real top-level browser view rather than an iframe inside a web page.

Nothing is proxied. Your phone connects to each site directly, exactly as Chrome would.

## Getting the APK without installing anything

You don't need Android Studio, a build toolchain, or a computer capable of compiling Android apps. GitHub builds it for you, free.

1. Create a new repository at github.com (call it `frame-android`).
2. Upload these files. **Drag the folders** `app` and `.github` into the upload page rather than using "choose your files", which cannot select folders.
3. Commit. Open the **Actions** tab and watch the "Build APK" job, which takes roughly three minutes.
4. When it finishes, go to **Releases** in the right-hand sidebar. There will be a release called "Frame (latest build)" with **Frame.apk** attached.
5. Open that release page on your Android phone and tap `Frame.apk`.

If `.github` doesn't appear after uploading, GitHub's web uploader sometimes hides folders beginning with a dot. In that case create the file by hand: **Add file → Create new file**, name it `.github/workflows/build-apk.yml` (typing the slashes creates the folders), and paste the contents from this project.

### Installing it on the phone

Android will warn that the file came from outside the Play Store. That's expected for any app installed this way.

1. Tap the downloaded `Frame.apk`.
2. When prompted, allow your browser or Files app to install unknown apps.
3. Tap **Install**, then **Open**.

Play Protect may show a "scan app" or "unsafe app blocked" prompt, because the app isn't registered with Google. Choose **Install anyway** or **More details → Install anyway**. This is normal for a self-built app and not a sign anything is wrong.

### Rebuilding after a change

Every push to `main` rebuilds automatically and replaces the release. You can also start a build by hand from **Actions → Build APK → Run workflow**.

## Building it yourself instead

If you do have Android Studio, open this folder and press Run. From a terminal with the Android SDK installed:

```bash
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

There's no Gradle wrapper in this project, because its `gradle-wrapper.jar` is a binary that couldn't be generated here. Android Studio adds one automatically on first open, or run `gradle wrapper` once.

## What it does

- **Address bar** that takes a URL or a search. Anything that isn't an address becomes a Google search, as in any browser.
- **Tabs**, up to 12, shown in a strip when more than one is open. Open tabs are restored when you come back.
- **Back, forward and reload**, with the phone's back button stepping through history, then closing the tab.
- **Fullscreen video**, including rotation, so YouTube behaves properly.
- **Links that open elsewhere**: `mailto:`, `tel:`, map and app links are handed to the app that owns them. Downloads are passed to the system.
- **Pages opening new windows** (`target="_blank"`) get their own tab.

## Why this can open google.com when a website can't

`X-Frame-Options` and `Content-Security-Policy: frame-ancestors` tell a browser not to display a page **nested inside another web page**. That's what an iframe is, which is why the Frame website can't show Google.

A WebView isn't nesting anything. The page is the top-level document and the WebView is simply a window around it, so those headers don't apply. No rule is bypassed; the situation they guard against doesn't arise.

Two things Google does restrict, which are worth knowing before a demo:

- **Signing in to a Google account won't work.** Google deliberately blocks OAuth sign-in inside plain WebViews, as a phishing defence. Search, Maps, News and signed-out browsing all work normally.
- Some sites serve a simplified page when they detect a WebView.

## Security choices

- The WebView cannot read the device's files: `setAllowFileAccess(false)` and `setAllowContentAccess(false)`.
- No `addJavascriptInterface` anywhere, so page JavaScript has no bridge into the app.
- Insecure content is never mixed into a secure page (`MIXED_CONTENT_NEVER_ALLOW`).
- Typed input can't execute: `javascript:`, `data:`, `file:`, `content:`, `intent:` and similar are treated as searches rather than navigated to. This is unit-tested in `tools/UrlUtilsTest.java`.
- Only `http(s)` URLs load in a tab; every other scheme is handed to the system, which asks you before acting.
- The app requests two permissions: internet access and network state. No storage, camera, microphone or location.
- Plain `http` sites are allowed to load, as in any browser. The protection that matters — not mixing insecure content into secure pages — is kept.

## Project layout

```
app/src/main/
  java/com/frameapp/browser/
    MainActivity.java     tabs, WebView setup, chrome, fullscreen, persistence
    UrlUtils.java         address-bar logic, no Android dependencies
  res/
    layout/               toolbar, tab strip, web container
    drawable/             vector icons and surfaces
    values/               colours, strings, theme
  AndroidManifest.xml
tools/UrlUtilsTest.java   runs on a plain JVM, no Android needed
.github/workflows/build-apk.yml
```

Run the address-bar tests without Android:

```bash
javac -d /tmp/frame app/src/main/java/com/frameapp/browser/UrlUtils.java tools/UrlUtilsTest.java
java -cp /tmp/frame UrlUtilsTest
```

## Honest status

`UrlUtils` is unit-tested (41 assertions, all passing) and every resource reference has been checked to resolve. The rest of the app has **not been compiled or run**, because this was written in an environment without the Android SDK. The GitHub build is the first real compile, so treat the first run as the test. If it fails, the Actions log names the file and line.
