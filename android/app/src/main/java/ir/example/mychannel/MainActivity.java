package ir.example.mychannel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    // Set this to your deployed HTTPS worker URL, without a trailing slash.
    private static final String API = BuildConfig.API_BASE_URL;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String sessionToken = ""; // Intentionally memory-only; relaunch requires the shared password.
    private String channel = "";
    private LinearLayout root;
    private WebView web;
    private TextView notice;
    private boolean checking = false;
    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            if (!sessionToken.isEmpty()) checkSession(false);
            handler.postDelayed(this, 120000); // Password rotations take effect on next check/resume.
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        showLogin();
    }
    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(periodicCheck);
        handler.postDelayed(periodicCheck, 120000);
        if (!sessionToken.isEmpty()) checkSession(false);
        if (web != null) web.onResume();
    }
    @Override protected void onPause() {
        handler.removeCallbacks(periodicCheck);
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(periodicCheck);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        io.shutdownNow();
        super.onDestroy();
    }
    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(29, 42, 54));
        v.setGravity(Gravity.CENTER); v.setPadding(dp(8), dp(12), dp(8), dp(12)); return v;
    }
    private Button button(String text) {
        Button v = new Button(this); v.setText(text); v.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52)); p.topMargin = dp(10); v.setLayoutParams(p); return v;
    }
    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(10), dp(12), dp(10), dp(12)); return e;
    }
    private void initRoot(boolean scroll) {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(8), dp(20), dp(12));
        root.setBackgroundColor(Color.WHITE); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(dp(20), bars.top + dp(8), dp(20), bars.bottom + dp(12));
            return insets;
        });
        if (scroll) {
            ScrollView sc = new ScrollView(this); sc.setFillViewport(true); sc.addView(root); setContentView(sc);
        } else setContentView(root);
    }
    private void title(String text) {
        TextView t = label(text, 23); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(t);
    }
    private void message(String text) { if (notice != null) notice.setText(text); }
    private void call(String path, JSONObject payload, java.util.function.BiConsumer<Integer, JSONObject> done) {
        io.execute(() -> {
            int code = 0; JSONObject result;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(API + path).openConnection();
                connection.setRequestMethod("POST"); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                code = connection.getResponseCode();
                InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder b = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) b.append(line);
                    result = new JSONObject(b.toString());
                }
            } catch (Exception error) {
                result = new JSONObject();
                try { result.put("error", "اتصال برقرار نشد. اینترنت یا آدرس سرویس را بررسی کنید."); } catch (Exception ignored) {}
            } finally { if (connection != null) connection.disconnect(); }
            final int status = code; final JSONObject data = result;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) done.accept(status, data); });
        });
    }
    private JSONObject data(String... pairs) {
        JSONObject obj = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) obj.put(pairs[i], pairs[i + 1]); } catch (Exception ignored) {}
        return obj;
    }
    private void showLogin() {
        sessionToken = ""; channel = ""; checking = false;
        initRoot(true);
        title("ورود به کانال من");
        root.addView(label("رمز مشترک برنامه را وارد کنید.", 16));
        EditText password = field("رمز عبور مشترک", true); root.addView(password);
        notice = label("", 14); root.addView(notice);
        Button enter = button("ورود"); root.addView(enter);
        enter.setOnClickListener(v -> {
            String entered = password.getText().toString();
            if (entered.isEmpty()) { message("رمز عبور را وارد کنید."); return; }
            enter.setEnabled(false); message("در حال بررسی رمز...");
            call("/login", data("password", entered), (code, result) -> {
                enter.setEnabled(true); password.setText("");
                if (code == 200) {
                    sessionToken = result.optString("token", "");
                    channel = result.optString("channel", "");
                    showChannel();
                } else message(code == 429 ? "تلاش‌های ناموفق زیاد است؛ ۱۵ دقیقه بعد امتحان کنید." : "رمز اشتباه است یا سرویس در دسترس نیست.");
            });
        });
        Button admin = button("تنظیمات مدیر"); root.addView(admin); admin.setOnClickListener(v -> showAdmin());
    }
    private void showAdmin() {
        initRoot(true);
        title("تنظیمات مدیر");
        root.addView(label("کلید محرمانه مدیر را وارد کنید؛ آن را به کاربران ندهید.", 14));
        EditText adminKey = field("کلید مدیر", true); root.addView(adminKey);
        EditText newPassword = field("رمز مشترک جدید (حداقل ۱۲ نویسه)", true); root.addView(newPassword);
        EditText newChannel = field("نام یا لینک عمومی کانال، مثلاً mychannel", false); root.addView(newChannel);
        notice = label("", 14); root.addView(notice);
        Button change = button("تغییر رمز همه کاربران"); root.addView(change);
        change.setOnClickListener(v -> {
            String pw = newPassword.getText().toString(), key = adminKey.getText().toString();
            if (pw.length() < 12 || pw.length() > 128) { message("رمز جدید باید بین ۱۲ تا ۱۲۸ نویسه باشد."); return; }
            if (key.isEmpty()) { message("کلید مدیر را وارد کنید."); return; }
            change.setEnabled(false); message("در حال ذخیره رمز جدید...");
            call("/admin/change", data("adminKey", key, "newPassword", pw), (code, result) -> {
                change.setEnabled(true); newPassword.setText("");
                if (code == 200) { message("رمز مشترک تغییر کرد. برای ورود مجدد از رمز جدید استفاده کنید."); sessionToken = ""; }
                else message(code == 429 ? "تلاش‌های زیاد؛ بعداً امتحان کنید." : "تغییر رمز انجام نشد؛ کلید مدیر و اتصال را بررسی کنید.");
            });
        });
        Button saveChannel = button("ثبت / تغییر کانال"); root.addView(saveChannel);
        saveChannel.setOnClickListener(v -> {
            String key = adminKey.getText().toString(), ch = newChannel.getText().toString().trim();
            if (key.isEmpty() || ch.isEmpty()) { message("کلید مدیر و نام کانال را وارد کنید."); return; }
            saveChannel.setEnabled(false); message("در حال ذخیره کانال...");
            call("/admin/channel", data("adminKey", key, "channel", ch), (code, result) -> {
                saveChannel.setEnabled(true);
                if (code == 200) { channel = result.optString("channel", ""); message("کانال ثبت شد: @" + channel); }
                else message(code == 422 ? "نام کانال عمومی معتبر نیست." : "ثبت کانال انجام نشد؛ کلید مدیر و اتصال را بررسی کنید.");
            });
        });
        Button back = button("بازگشت"); root.addView(back);
        back.setOnClickListener(v -> { adminKey.setText(""); showLogin(); });
    }
    private boolean isChannelPreview(String value) {
        if (channel.isEmpty()) return false;
        try {
            android.net.Uri u = android.net.Uri.parse(value);
            return "https".equalsIgnoreCase(u.getScheme()) && "t.me".equalsIgnoreCase(u.getHost())
                && ("/s/" + channel).equalsIgnoreCase(u.getPath());
        } catch (Exception ex) { return false; }
    }
    private void showChannel() {
        initRoot(false);
        if (channel.isEmpty()) {
            title("کانال هنوز تنظیم نشده است");
            root.addView(label("از بخش تنظیمات مدیر، نام عمومی کانال را ثبت کنید.", 16));
            Button settings = button("تنظیمات مدیر"); root.addView(settings); settings.setOnClickListener(v -> showAdmin());
            Button refresh = button("بررسی دوباره"); root.addView(refresh); refresh.setOnClickListener(v -> checkSession(true));
            Button logout = button("خروج"); root.addView(logout); logout.setOnClickListener(v -> showLogin());
            return;
        }
        TextView heading = label("کانال @" + channel, 18); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(heading);
        Button logout = button("خروج از برنامه"); root.addView(logout); logout.setOnClickListener(v -> showLogin());
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); // Public Telegram preview may need JS for media.
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isChannelPreview(request.getUrl().toString()); // Never open other sites/chats in this app.
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !isChannelPreview(url); }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl("https://t.me/s/" + channel);
    }
    private void checkSession(boolean showFeedback) {
        if (sessionToken.isEmpty() || checking) return;
        checking = true;
        call("/session", data("token", sessionToken), (code, result) -> {
            checking = false;
            if (code == 401) { showLogin(); return; }
            if (code == 200) {
                String fresh = result.optString("channel", "");
                if (!fresh.equals(channel)) { channel = fresh; showChannel(); }
                else if (showFeedback) new AlertDialog.Builder(this).setMessage("اطلاعات کانال به‌روز است.").setPositiveButton("باشه", null).show();
            } else {
                // Fail closed: do not keep showing the channel while the gate cannot be checked.
                showLogin();
                message("دسترسی تأیید نشد. اتصال اینترنت و سرویس را بررسی کنید.");
            }
        });
    }
    @Override public void onBackPressed() {
        if (!sessionToken.isEmpty()) new AlertDialog.Builder(this).setMessage("از کانال خارج می‌شوید؟").setNegativeButton("انصراف", null).setPositiveButton("خروج", (d, w) -> showLogin()).show();
        else super.onBackPressed();
    }
}
