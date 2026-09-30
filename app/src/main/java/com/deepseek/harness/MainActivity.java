package com.deepseek.harness;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Button;
import android.view.View;
import android.content.Intent;
import android.net.Uri;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button retryButton;
    private NodeLauncher launcher;
    private static final String TARGET_URL = "http://127.0.0.1:19387";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showLoadingScreen("Initializing DeepSeek Harness...");
        
        launcher = new NodeLauncher(this);
        launcher.start(new NodeLauncher.Callback() {
            @Override
            public void onReady(String url) {
                runOnUiThread(() -> showWebView(url));
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() -> showError(message));
            }
            @Override
            public void onLog(String line) {
                runOnUiThread(() -> statusText.setText(line));
            }
            @Override
            public void onProgress(String msg) {
                runOnUiThread(() -> statusText.setText(msg));
            }
        });
    }
    
    private void showLoadingScreen(String msg) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        
        statusText = new TextView(this);
        statusText.setText(msg);
        statusText.setTextSize(16);
        statusText.setPadding(0, 64, 0, 32);
        layout.addView(statusText);
        
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        layout.addView(progressBar, pbParams);
        
        retryButton = new Button(this);
        retryButton.setText("Retry");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> recreate());
        layout.addView(retryButton);
        
        setContentView(layout);
    }
    
    private void showWebView(String url) {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " DeepSeekHarness/0.2.0");
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false;
                }
                if (url.startsWith("https://platform.deepseek.com")
                    || url.startsWith("https://api.deepseek.com")
                    || url.startsWith("https://harness.deepseek.com")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl(url);
    }
    
    private void showError(String message) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (statusText != null) statusText.setText("Error:\n" + message);
        if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
    }
    
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
    
    @Override
    protected void onDestroy() {
        if (launcher != null) launcher.stop();
        super.onDestroy();
    }
}