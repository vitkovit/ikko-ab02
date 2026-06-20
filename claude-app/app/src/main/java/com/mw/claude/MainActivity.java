package com.mw.claude;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {

    private static GeckoRuntime sRuntime;
    private GeckoSession session;
    private GeckoView geckoView;
    private TextView loading;
    private boolean canGoBack = false;

    private static final String CLAUDE_URL = "https://claude.ai/login";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        geckoView = new GeckoView(this);
        geckoView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(geckoView);

        loading = new TextView(this);
        loading.setText("Claude…");
        loading.setTextColor(0xFFCC785C);
        loading.setTextSize(20f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        loading.setLayoutParams(lp);
        root.addView(loading);

        setContentView(root);

        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(this, settings);
        }

        session = new GeckoSession();
        session.getSettings().setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP);
        session.setNavigationDelegate(mainNav);
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStop(GeckoSession s, boolean ok) {
                if (loading != null) loading.setVisibility(View.GONE);
            }
            @Override public void onProgressChange(GeckoSession s, int p) {
                if (loading != null && p > 40) loading.setVisibility(View.GONE);
            }
        });

        session.open(sRuntime);
        geckoView.setSession(session);
        session.loadUri(CLAUDE_URL);
    }

    private final GeckoSession.NavigationDelegate mainNav = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) { canGoBack = value; }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            String u = request.uri == null ? "" : request.uri;
            if (u.contains("play.google.com") || u.contains("apps.apple.com")
                    || u.contains("market://") || u.startsWith("intent://")) {
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        // Handle popups (e.g. Google OAuth window) by showing them in a dialog
        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
            GeckoSession popup = new GeckoSession();
            popup.getSettings().setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
            showPopup(popup);
            return GeckoResult.fromValue(popup);
        }
    };

    private void showPopup(final GeckoSession popup) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final GeckoView popupView = new GeckoView(this);
        popupView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(popupView);

        popup.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(GeckoSession s) {
                try { dialog.dismiss(); } catch (Exception ignored) {}
                s.close();
            }
        });

        // GeckoView opens the popup session itself; just attach it for display.
        popupView.setSession(popup);
        dialog.setOnDismissListener(d -> {
            try { if (popup.isOpen()) popup.close(); } catch (Exception ignored) {}
        });
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) session.goBack();
        else super.onBackPressed();
    }
}
