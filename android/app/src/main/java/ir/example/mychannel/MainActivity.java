package ir.example.mychannel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.WindowManager;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private static final String PREFS = "channel_local_v2";
    private static final String HASH = "password_hash";
    private static final String SALT = "password_salt";
    private static final String CHANNEL = "channel_username";
    private static final int ROUNDS = 180000;
    private static final Pattern USERNAME =
        Pattern.compile("^(?:(?:https://)?(?:www\\.)?t\\.me/)?@?([A-Za-z0-9_]{5,32})/?$", Pattern.CASE_INSENSITIVE);
    private final SecureRandom random = new SecureRandom();
    private SharedPreferences prefs;
    private LinearLayout root;
    private WebView web;
    private TextView feedback;
    private boolean unlocked = false;
    private int attempts = 0;
    private long lockedUntil = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // Avoid exposing screenshots containing the unlocked channel.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (prefs.contains(HASH) && prefs.contains(SALT)) showLogin();
        else showFirstSetup();
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(29, 42, 54));
        t.setGravity(Gravity.CENTER); t.setPadding(dp(6), dp(12), dp(6), dp(12));
        return t;
    }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(9); b.setLayoutParams(p); return b;
    }
    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setInputType(secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }
    private void base(boolean scroll) {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.WHITE);
        if (scroll) {
            ScrollView s = new ScrollView(this); s.setFillViewport(true); s.addView(root); setContentView(s);
        } else setContentView(root);
        feedback = null;
    }
    private void heading(String label) {
        TextView t = text(label, 23); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(t);
    }
    private void note(String value) { if (feedback != null) feedback.setText(value); }

    private byte[] derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ROUNDS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); java.util.Arrays.fill(password, '\0'); }
    }
    private String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = digits[(bytes[i] >>> 4) & 15];
            chars[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }
    private byte[] fromHex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int a = Character.digit(s.charAt(i * 2), 16);
            int b = Character.digit(s.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte)((a << 4) | b);
        }
        return out;
    }
    private boolean savePassword(String candidate) {
        try {
            byte[] salt = new byte[16]; random.nextBytes(salt);
            byte[] hash = derive(candidate.toCharArray(), salt);
            return prefs.edit().putString(SALT, hex(salt)).putString(HASH, hex(hash)).commit();
        } catch (Exception ex) { return false; }
    }
    private boolean matches(String candidate) {
        try {
            byte[] salt = fromHex(prefs.getString(SALT, ""));
            byte[] expected = fromHex(prefs.getString(HASH, ""));
            byte[] actual = derive(candidate.toCharArray(), salt);
            return expected.length == 32 && MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) { return false; }
    }
    private void showFirstSetup() {
        unlocked = false; base(true); heading("راه‌اندازی کانال من");
        root.addView(text("یک رمز برای همین گوشی تعیین کنید. رمز را در جای امن نگه دارید؛ در صورت فراموشی، بازیابی آن در برنامه وجود ندارد.", 16));
        EditText pass = input("رمز جدید (حداقل ۶ نویسه)", true); root.addView(pass);
        EditText confirm = input("تکرار رمز", true); root.addView(confirm);
        feedback = text("", 14); root.addView(feedback);
        Button setup = button("ثبت رمز و ادامه"); root.addView(setup);
        setup.setOnClickListener(v -> {
            String a = pass.getText().toString(), b = confirm.getText().toString();
            if (a.length() < 6 || a.length() > 128) { note("رمز باید بین ۶ تا ۱۲۸ نویسه باشد."); return; }
            if (!a.equals(b)) { note("تکرار رمز با رمز اصلی یکسان نیست."); return; }
            if (!savePassword(a)) { note("ثبت رمز انجام نشد. دوباره امتحان کنید."); return; }
            pass.setText(""); confirm.setText(""); unlocked = true;
            showSettings();
        });
    }
    private void showLogin() {
        unlocked = false; base(true); heading("ورود به کانال من");
        root.addView(text("رمز تعیین‌شده روی همین گوشی را وارد کنید.", 16));
        EditText pass = input("رمز عبور", true); root.addView(pass);
        feedback = text("", 14); root.addView(feedback);
        Button enter = button("ورود"); root.addView(enter);
        enter.setOnClickListener(v -> {
            if (System.currentTimeMillis() < lockedUntil) {
                note("تلاش‌های ناموفق زیاد است؛ کمی بعد دوباره امتحان کنید."); return;
            }
            boolean valid = matches(pass.getText().toString());
            pass.setText("");
            if (!valid) {
                attempts++;
                if (attempts >= 5) { attempts = 0; lockedUntil = System.currentTimeMillis() + 30000L; }
                note("رمز اشتباه است."); return;
            }
            attempts = 0; unlocked = true; showChannel();
        });
    }
    private String normalizeChannel(String input) {
        String s = input.trim();
        Matcher matcher = USERNAME.matcher(s);
        return matcher.matches() ? matcher.group(1) : "";
    }
    private void showSettings() {
        if (!unlocked) { showLogin(); return; }
        base(true); heading("تنظیمات برنامه");
        root.addView(text("نام عمومی کانال را وارد کنید. می‌توانید این قسمت را بعداً تکمیل کنید.", 15));
        EditText channel = input("mychannel یا https://t.me/mychannel", false);
        channel.setText(prefs.getString(CHANNEL, "")); root.addView(channel);
        feedback = text("", 14); root.addView(feedback);
        Button save = button("ذخیره کانال"); root.addView(save);
        save.setOnClickListener(v -> {
            String value = normalizeChannel(channel.getText().toString());
            if (value.isEmpty()) { note("نام عمومی کانال معتبر نیست. لینک دعوت خصوصی پشتیبانی نمی‌شود."); return; }
            if (prefs.edit().putString(CHANNEL, value).commit()) { note("کانال ذخیره شد: @" + value); }
            else note("ذخیره کانال انجام نشد.");
        });
        root.addView(text("تغییر رمز فقط روی همین گوشی اعمال می‌شود.", 15));
        EditText old = input("رمز فعلی", true); root.addView(old);
        EditText next = input("رمز جدید (حداقل ۶ نویسه)", true); root.addView(next);
        EditText repeat = input("تکرار رمز جدید", true); root.addView(repeat);
        Button change = button("تغییر رمز"); root.addView(change);
        change.setOnClickListener(v -> {
            String previous = old.getText().toString();
            String candidate = next.getText().toString();
            String repeated = repeat.getText().toString();
            old.setText(""); next.setText(""); repeat.setText("");
            if (!matches(previous)) { note("رمز فعلی درست نیست."); return; }
            if (candidate.length() < 6 || candidate.length() > 128 || !candidate.equals(repeated)) {
                note("رمز جدید باید حداقل ۶ نویسه داشته باشد و تکرار آن یکسان باشد."); return;
            }
            if (savePassword(candidate)) { note("رمز این گوشی تغییر کرد."); }
            else note("تغییر رمز انجام نشد.");
        });
        Button view = button("نمایش کانال"); root.addView(view); view.setOnClickListener(v -> showChannel());
        Button exit = button("قفل کردن برنامه"); root.addView(exit); exit.setOnClickListener(v -> showLogin());
    }
    private boolean isSamePreview(Uri u, String username) {
        return "https".equalsIgnoreCase(u.getScheme())
            && "t.me".equalsIgnoreCase(u.getHost())
            && ("/s/" + username).equalsIgnoreCase(u.getPath());
    }
    private void showChannel() {
        if (!unlocked) { showLogin(); return; }
        String channel = prefs.getString(CHANNEL, "");
        if (channel.isEmpty()) {
            base(true); heading("کانال هنوز تعیین نشده است");
            root.addView(text("از تنظیمات، نام عمومی کانال را وارد کنید.", 16));
            Button settings = button("تنظیمات"); root.addView(settings);
            settings.setOnClickListener(v -> showSettings());
            Button exit = button("قفل کردن برنامه"); root.addView(exit);
            exit.setOnClickListener(v -> showLogin());
            return;
        }
        base(false);
        heading("کانال @" + channel);
        Button settings = button("تنظیمات"); root.addView(settings);
        settings.setOnClickListener(v -> showSettings());
        Button lock = button("قفل کردن برنامه"); root.addView(lock);
        lock.setOnClickListener(v -> showLogin());
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);  // Telegram public preview media.
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return !isSamePreview(req.getUrl(), channel);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !isSamePreview(Uri.parse(url), channel);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl("https://t.me/s/" + channel);
    }
    @Override public void onBackPressed() {
        if (unlocked) new AlertDialog.Builder(this).setMessage("برنامه قفل شود؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("قفل", (d, which) -> showLogin()).show();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
}
