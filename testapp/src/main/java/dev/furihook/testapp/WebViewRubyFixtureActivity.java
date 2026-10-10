package dev.furihook.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class WebViewRubyFixtureActivity extends Activity {
    private static final String TAG = "FuriHookWebViewE2E";
    private WebView webView;
    private boolean pageLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
            }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("file:///android_asset/webview_ruby_fixture.html");
    }

    public WebView webView() {
        return webView;
    }

    public boolean pageLoaded() {
        return pageLoaded;
    }

    public File saveEvidence(JSONObject evidence) throws Exception {
        File directory = getExternalFilesDir(null);
        if (directory == null) throw new IllegalStateException("应用外部文件目录不可用");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建 E2E 证据目录");
        }
        File report = new File(directory, "webview-ruby-e2e.json");
        try (FileOutputStream output = new FileOutputStream(report, false)) {
            output.write((evidence.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Log.i(TAG, "report=" + report.getAbsolutePath());
        return report;
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
