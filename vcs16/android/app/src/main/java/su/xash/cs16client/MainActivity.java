package su.xash.cs16client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class MainActivity extends Activity {

    private static final String CRASH_LOG_PATH =
            Environment.getExternalStorageDirectory() + "/xash/cstrike/crash.log";
    private static final int REQUEST_MANAGE_EXTERNAL = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!checkAndShowCrash()) {
            launchXash();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!checkAndShowCrash()) {
            launchXash();
        }
    }

    private boolean checkAndShowCrash() {
        if (hasStoragePermission() && new File(CRASH_LOG_PATH).exists()) {
            showCrashDialog();
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestStoragePermission();
            return true;
        }
        return false;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        return Environment.isExternalStorageManager();
    }

    private void requestStoragePermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_EXTERNAL) {
            if (hasStoragePermission() && new File(CRASH_LOG_PATH).exists()) {
                showCrashDialog();
            } else {
                launchXash();
            }
        }
    }

    private void showCrashDialog() {
        String crashText = readCrashLog();
        if (crashText == null) crashText = "(crash log unreadable)";

        TextView textView = new TextView(this);
        textView.setText(crashText);
        textView.setTextColor(0xFFE0E0E0);
        textView.setTextSize(10);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        textView.setPadding(16, 16, 16, 16);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
                .setTitle("Game Crashed")
                .setView(scrollView)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", crashText));
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                    clearCrashLog();
                    launchXash();
                })
                .setNeutralButton("Continue", (d, w) -> {
                    clearCrashLog();
                    launchXash();
                })
                .setNegativeButton("Exit", (d, w) -> {
                    clearCrashLog();
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private String readCrashLog() {
        File f = new File(CRASH_LOG_PATH);
        if (!f.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
    }

    private void clearCrashLog() {
        try {
            new File(CRASH_LOG_PATH).delete();
        } catch (Exception ignored) {}
    }

    private void launchXash() {
        String pkg = "su.xash.engine.test";
        try {
            getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                pkg = "su.xash.engine";
                getPackageManager().getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous"))
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
                return;
            }
        }
        startActivity(new Intent().setComponent(new ComponentName(pkg, "su.xash.engine.XashActivity"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("gamedir", "cstrike")
                .putExtra("gamelibdir", getApplicationInfo().nativeLibraryDir)
                .putExtra("argv", "-dev 2 -log -dll @yapb")
                .putExtra("package", getPackageName()));
        finish();
    }
}
