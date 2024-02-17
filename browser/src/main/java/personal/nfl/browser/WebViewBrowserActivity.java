// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package personal.nfl.browser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import personal.nfl.browser.util.FileUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This activity is designed for starting a "mini-browser" for manual testing of WebView.
 * It takes an optional URL as an argument, and displays the page. There is a URL bar
 * on top of the webview for manually specifying URLs to load.
 */
public class WebViewBrowserActivity extends Activity {
    private static final String TAG = "WebViewShell";

    public static final String INIT_SCREEN_ORIENTATION = "InitScreenOrientation";

    // Our imaginary Android permission to associate with the WebKit geo permission
    private static final String RESOURCE_GEO = "RESOURCE_GEO";
    // Our imaginary WebKit permission to request when loading a file:// URL
    private static final String RESOURCE_FILE_URL = "RESOURCE_FILE_URL";
    // WebKit permissions with no corresponding Android permission can always be granted
    private static final String NO_ANDROID_PERMISSION = "NO_ANDROID_PERMISSION";

    // TODO(timav): Remove these variables after http://crbug.com/626202 is fixed.
    // The Bundle key for WebView serialized state
    private static final String SAVE_RESTORE_STATE_KEY = "WEBVIEW_CHROMIUM_STATE";
    // Maximal size of this state.
    private static final int MAX_STATE_LENGTH = 300 * 1024;

    // Map from WebKit permissions to Android permissions
    private static final HashMap<String, String> sPermissions;
    static {
        sPermissions = new HashMap<String, String>();
        sPermissions.put(RESOURCE_GEO, Manifest.permission.ACCESS_FINE_LOCATION);
        sPermissions.put(RESOURCE_FILE_URL, Manifest.permission.READ_EXTERNAL_STORAGE);
        sPermissions.put(PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                Manifest.permission.RECORD_AUDIO);
        sPermissions.put(PermissionRequest.RESOURCE_MIDI_SYSEX, NO_ANDROID_PERMISSION);
        sPermissions.put(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID, NO_ANDROID_PERMISSION);
        sPermissions.put(PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                Manifest.permission.CAMERA);
    }

    private static final Pattern WEBVIEW_VERSION_PATTERN =
            Pattern.compile("(Chrome/)([\\d\\.]+)\\s");

    private WebView mWebView;
    private final int REQUEST_CODE_FILE_CHOOSER = 2001;
    private ValueCallback mUploadMessage;
    private String mWebViewVersion;
    private boolean mEnableTracing;
    public static String url;

    // Each time we make a request, store it here with an int key. onRequestPermissionsResult will
    // look up the request in order to grant the approprate permissions.
    private SparseArray<PermissionRequest> mPendingRequests = new SparseArray<PermissionRequest>();
    private int mNextRequestKey;

    // Work around our wonky API by wrapping a geo permission prompt inside a regular
    // PermissionRequest.
    @SuppressLint("NewApi") // GeoPermissionRequest class requires API level 21.
    private static class GeoPermissionRequest extends PermissionRequest {
        private String mOrigin;
        private GeolocationPermissions.Callback mCallback;

        public GeoPermissionRequest(String origin, GeolocationPermissions.Callback callback) {
            mOrigin = origin;
            mCallback = callback;
        }

        @Override
        public Uri getOrigin() {
            return Uri.parse(mOrigin);
        }

        @Override
        public String[] getResources() {
            return new String[] { WebViewBrowserActivity.RESOURCE_GEO };
        }

        @Override
        public void grant(String[] resources) {
            assert resources.length == 1;
            assert WebViewBrowserActivity.RESOURCE_GEO.equals(resources[0]);
            mCallback.invoke(mOrigin, true, false);
        }

        @Override
        public void deny() {
            mCallback.invoke(mOrigin, false, false);
        }
    }

    // For simplicity, also treat the read access needed for file:// URLs as a regular
    // PermissionRequest.
    @SuppressLint("NewApi") // FilePermissionRequest class requires API level 21.
    private class FilePermissionRequest extends PermissionRequest {
        private String mOrigin;

        public FilePermissionRequest(String origin) {
            mOrigin = origin;
        }

        @Override
        public Uri getOrigin() {
            return Uri.parse(mOrigin);
        }

        @Override
        public String[] getResources() {
            return new String[] { WebViewBrowserActivity.RESOURCE_FILE_URL };
        }

        @Override
        public void grant(String[] resources) {
            assert resources.length == 1;
            assert WebViewBrowserActivity.RESOURCE_FILE_URL.equals(resources[0]);
            // Try again now that we have read access.
            WebViewBrowserActivity.this.mWebView.loadUrl(mOrigin);
        }

        @Override
        public void deny() {
            // womp womp
        }
    }

    private static class TracingLogger extends FileOutputStream {
        private long mByteCount;
        private long mChunkCount;
        private final Activity mActivity;

        public TracingLogger(String fileName, Activity activity) throws FileNotFoundException {
            super(fileName);
            mActivity = activity;
        }

        @Override
        public void write(byte[] chunk) throws IOException {
            mByteCount += chunk.length;
            mChunkCount++;
            super.write(chunk);
        }

        @Override
        public void close() throws IOException {
            super.close();
            showDialog(mByteCount);
        }

        private void showDialog(long nbBytes) {
            StringBuilder info = new StringBuilder();
            info.append("Tracing data written to file\n");
            info.append("number of bytes: " + nbBytes);

            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AlertDialog dialog = new AlertDialog.Builder(mActivity)
                                                 .setTitle("Tracing API")
                                                 .setMessage(info)
                                                 .setNeutralButton(" OK ", null)
                                                 .create();
                    dialog.show();
                }
            });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int screenOrientation = getIntent().getIntExtra(INIT_SCREEN_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_USER);
        setRequestedOrientation(screenOrientation);

        WebView.setWebContentsDebuggingEnabled(true);
        mWebView = new WebView(this);
        setContentView(mWebView);
        createAndInitializeWebView();
        String url = getUrlFromIntent(getIntent());
        if (url == null) {
            mWebView.restoreState(savedInstanceState);
            url = mWebView.getUrl();
            if (url != null) {
                hideKeyboard(mWebView);
                mWebView.reload();
                mWebView.requestFocus();
                return;
            }else {
                url  = WebViewBrowserActivity.url ;
            }
            // Make sure to load a blank page to make it immediately inspectable with
            // chrome://inspect.
            // url = "about:blank";
        }
        loadUrl(url);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Deliberately don't catch TransactionTooLargeException here.
        mWebView.saveState(savedInstanceState);

        // TODO(timav): Remove this hack after http://crbug.com/626202 is fixed.
        // Drop the saved state of it is too long since Android N and above
        // can't handle large states without a crash.
        byte[] webViewState = savedInstanceState.getByteArray(SAVE_RESTORE_STATE_KEY);
        if (webViewState != null && webViewState.length > MAX_STATE_LENGTH) {
            savedInstanceState.remove(SAVE_RESTORE_STATE_KEY);
            String message = String.format(
                    Locale.US, "Can't save state: %dkb is too long", webViewState.length / 1024);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void createAndInitializeWebView() {
        WebSettings settings = mWebView.getSettings();
        initializeSettings(settings);

        Matcher matcher = WEBVIEW_VERSION_PATTERN.matcher(settings.getUserAgentString());
        if (matcher.find()) {
            mWebViewVersion = matcher.group(2);
        } else {
            mWebViewVersion = "-";
        }

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 101);
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 102);
                    }
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 103);
                    }
                }
            }

            @SuppressWarnings("deprecation") // because we support api level 19 and up.
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                // "about:" and "chrome:" schemes are internal to Chromium;
                // don't want these to be dispatched to other apps.
                if (url.startsWith("about:") || url.startsWith("chrome:")) {
                    return false;
                }
                return startBrowsingIntent(WebViewBrowserActivity.this, url);
            }

            @SuppressWarnings("deprecation") // because we support api level 19 and up.
            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                    String failingUrl) {
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(
                        new int[] {Color.TRANSPARENT}, 1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Pre Lollipop versions (< api level 21) do not have PermissionRequest,
                    // hence grant here immediately.
                    callback.invoke(origin, true, false);
                    return;
                }

                onPermissionRequest(new GeoPermissionRequest(origin, callback));
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                WebViewBrowserActivity.this.requestPermissionsForPage(request);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {

            }

            @Override
            public void onHideCustomView() {
            }

            // 处理文件上传问题

            // For Android 3.0+
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "File Chooser"), REQUEST_CODE_FILE_CHOOSER);
            }

            // For Android 3.0+
            public void openFileChooser(ValueCallback uploadMsg, String acceptType) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String type = TextUtils.isEmpty(acceptType) ? "*/*" : acceptType;
                i.setType(type);
                startActivityForResult(Intent.createChooser(i, "File Chooser"),
                        REQUEST_CODE_FILE_CHOOSER);
            }

            // For Android 4.1
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String type = TextUtils.isEmpty(acceptType) ? "*/*" : acceptType;
                i.setType(type);
                startActivityForResult(Intent.createChooser(i, "File Chooser"), REQUEST_CODE_FILE_CHOOSER);
            }


            //Android 5.0+
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }
                // LogUtil.d("UPFILE", "file chooser params：" + fileChooserParams.toString());
                mUploadMessage = filePathCallback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                if (fileChooserParams != null && fileChooserParams.getAcceptTypes() != null
                        && fileChooserParams.getAcceptTypes().length > 0) {
                    i.setType(fileChooserParams.getAcceptTypes()[0]);
                } else {
                    i.setType("*/*");
                }
                startActivityForResult(Intent.createChooser(i, "File Chooser"), REQUEST_CODE_FILE_CHOOSER);
                return true;
            }
        });
    }

    // WebKit permissions which can be granted because either they have no associated Android
    // permission or the associated Android permission has been granted
    @TargetApi(Build.VERSION_CODES.M)
    private boolean canGrant(String webkitPermission) {
        String androidPermission = sPermissions.get(webkitPermission);
        if (androidPermission.equals(NO_ANDROID_PERMISSION)) {
            return true;
        }
        return PackageManager.PERMISSION_GRANTED == checkSelfPermission(androidPermission);
    }

    @SuppressLint("NewApi") // PermissionRequest#deny requires API level 21.
    private void requestPermissionsForPage(PermissionRequest request) {
        // Deny any unrecognized permissions.
        for (String webkitPermission : request.getResources()) {
            if (!sPermissions.containsKey(webkitPermission)) {
                Log.w(TAG, "Unrecognized WebKit permission: " + webkitPermission);
                request.deny();
                return;
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            request.grant(request.getResources());
            return;
        }

        // Find what Android permissions we need before we can grant these WebKit permissions.
        ArrayList<String> androidPermissionsNeeded = new ArrayList<String>();
        for (String webkitPermission : request.getResources()) {
            if (!canGrant(webkitPermission)) {
                // We already checked for unrecognized permissions, and canGrant will skip over
                // NO_ANDROID_PERMISSION cases, so this is guaranteed to be a regular Android
                // permission.
                String androidPermission = sPermissions.get(webkitPermission);
                androidPermissionsNeeded.add(androidPermission);
            }
        }

        // If there are no such Android permissions, grant the WebKit permissions immediately.
        if (androidPermissionsNeeded.isEmpty()) {
            request.grant(request.getResources());
            return;
        }

        // Otherwise, file a new request
        if (mNextRequestKey == Integer.MAX_VALUE) {
            Log.e(TAG, "Too many permission requests");
            return;
        }
        int requestCode = mNextRequestKey;
        mNextRequestKey++;
        mPendingRequests.append(requestCode, request);
        requestPermissions(androidPermissionsNeeded.toArray(new String[0]), requestCode);
    }

    @Override
    @SuppressLint("NewApi") // PermissionRequest#deny requires API level 21.
    public void onRequestPermissionsResult(int requestCode,
            String permissions[], int[] grantResults) {
        if (requestCode == 101 || requestCode == 102 || requestCode == 103) {
            return;
        }
        // Verify that we can now grant all the requested permissions. Note that although grant()
        // takes a list of permissions, grant() is actually all-or-nothing. If there are any
        // requested permissions not included in the granted permissions, all will be denied.
        PermissionRequest request = mPendingRequests.get(requestCode);
        mPendingRequests.delete(requestCode);
        for (String webkitPermission : request.getResources()) {
            if (!canGrant(webkitPermission)) {
                request.deny();
                return;
            }
        }
        request.grant(request.getResources());
    }

    // setGeolocationDatabasePath deprecated in api level 24,
    // but we still use it because we support api level 19 and up.
    @SuppressWarnings("deprecation")
    private void initializeSettings(WebSettings settings) {
        File appcache = null;
        File geolocation = null;

//        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        appcache = getDir("appcache", 0);
        geolocation = getDir("geolocation", 0);
//        StrictMode.setThreadPolicy(oldPolicy);

        settings.setJavaScriptEnabled(true);

        // configure local storage apis and their database paths.
        settings.setAppCachePath(appcache.getPath());
        settings.setGeolocationDatabasePath(geolocation.getPath());

        settings.setAppCacheEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);

        // Default layout behavior for chrome on android.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
    }

    private void about() {
        WebSettings settings = mWebView.getSettings();
        StringBuilder summary = new StringBuilder();
        summary.append("WebView version : " + mWebViewVersion + "\n");

        for (Method method : settings.getClass().getMethods()) {
            if (!methodIsSimpleInspector(method)) continue;
            try {
                summary.append(method.getName() + " : " + method.invoke(settings) + "\n");
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) { }
        }
    }

    // Returns true is a method has no arguments and returns either a boolean or a String.
    private boolean methodIsSimpleInspector(Method method) {
        Class<?> returnType = method.getReturnType();
        return ((returnType.equals(boolean.class) || returnType.equals(String.class))
                && method.getParameterTypes().length == 0);
    }

    private void loadUrl(String url) {
        // Request read access if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && "file".equals(Uri.parse(url).getScheme())
                && PackageManager.PERMISSION_DENIED
                        == checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            requestPermissionsForPage(new FilePermissionRequest(url));
        }

        // If it is file:// and we don't have permission, they'll get the "Webpage not available"
        // "net::ERR_ACCESS_DENIED" page. When we get permission, FilePermissionRequest.grant()
        // will reload.
        mWebView.loadUrl(url);
        mWebView.requestFocus();
    }

    /**
     * Hides the keyboard.
     * @param view The {@link View} that is currently accepting input.
     * @return Whether the keyboard was visible before.
     */
    private static boolean hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        return imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private static String getUrlFromIntent(Intent intent) {
        return intent != null ? intent.getDataString() : null;
    }

    static final Pattern BROWSER_URI_SCHEMA = Pattern.compile(
            "(?i)"   // switch on case insensitive matching
            + "("    // begin group for schema
            + "(?:http|https|file):\\/\\/"
            + "|(?:inline|data|about|chrome|javascript):"
            + ")"
            + "(.*)");

    private static boolean startBrowsingIntent(Context context, String url) {
        Intent intent;
        // Perform generic parsing of the URI to turn it into an Intent.
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (Exception ex) {
            Log.w(TAG, "Bad URI " + url, ex);
            return false;
        }
        // Check for regular URIs that WebView supports by itself, but also
        // check if there is a specialized app that had registered itself
        // for this kind of an intent.
        Matcher m = BROWSER_URI_SCHEMA.matcher(url);
        if (m.matches() && !isSpecializedHandlerAvailable(context, intent)) {
            return false;
        }
        // Sanitize the Intent, ensuring web pages can not bypass browser
        // security (only access to BROWSABLE activities).
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        Intent selector = intent.getSelector();
        if (selector != null) {
            selector.addCategory(Intent.CATEGORY_BROWSABLE);
            selector.setComponent(null);
        }

        // Pass the package name as application ID so that the intent from the
        // same application can be opened in the same tab.
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "No application can handle " + url);
        } catch (SecurityException ex) {
            // This can happen if the Activity is exported="true", guarded by a permission, and sets
            // up an intent filter matching this intent. This is a valid configuration for an
            // Activity, so instead of crashing, we catch the exception and do nothing. See
            // https://crbug.com/808494 and https://crbug.com/889300.
            Log.w(TAG, "SecurityException when starting intent for " + url);
        }
        return false;
    }

    /**
     * Search for intent handlers that are specific to the scheme of the URL in the intent.
     */
    private static boolean isSpecializedHandlerAvailable(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> handlers = pm.queryIntentActivities(intent,
                PackageManager.GET_RESOLVED_FILTER);
        if (handlers == null || handlers.size() == 0) {
            return false;
        }
        for (ResolveInfo resolveInfo : handlers) {
            if (!isNullOrGenericHandler(resolveInfo.filter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullOrGenericHandler(IntentFilter filter) {
        return filter == null
                || (filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_FILE_CHOOSER) {
            uploadFileToWebView(data);
        }
    }

    private void uploadFileToWebView(Intent data) {
        if (null == mUploadMessage) return;
        Uri result = data == null ? null : data.getData();
        if (result == null) {
            mUploadMessage.onReceiveValue(null);
            mUploadMessage = null;
            return;
        }
        String path = FileUtil.getPath(this, result);
        if (TextUtils.isEmpty(path)) {
            mUploadMessage.onReceiveValue(null);
            mUploadMessage = null;
            return;
        }
        Uri uri = Uri.fromFile(new File(path));
        // LogUtil.d("uploadFileUri:" + uri.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mUploadMessage.onReceiveValue(new Uri[]{uri});
        } else {
            mUploadMessage.onReceiveValue(uri);
        }
        mUploadMessage = null;
    }
}
