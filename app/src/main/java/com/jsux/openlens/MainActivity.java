package com.jsux.openlens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 11;
    private static final int PICK_VIDEO = 12;
    private static final String PREFS = "openlens";
    private static final String PREF_TARGETS = "targets";
    private static final String PREF_ENABLED = "enabled";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout targetList;
    private ImageView preview;
    private TextView mediaStatus;
    private TextView modeStatus;
    private TextView targetStatus;
    private Button chooseImageButton;
    private Button chooseVideoButton;
    private Button enableButton;
    private Button disableButton;
    private boolean enabled;
    private String mediaType = "image";
    private String mediaName = "No media selected";
    private final Set<String> selectedPackages = new HashSet<>();
    private Runnable delayedMediaGrantRevoke;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 19, 24));
        getWindow().setNavigationBarColor(Color.rgb(16, 19, 24));
        getWindow().getDecorView().setSystemUiVisibility(0);
        enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
        Set<String> stored = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(PREF_TARGETS, Collections.emptySet());
        selectedPackages.addAll(stored);
        loadSavedMediaMetadata();
        buildUi();
        refreshMediaPreview();
        refreshStateUi();
        refreshTargets();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 19, 24));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text("OpenLens", 30, true, Color.WHITE);
        page.addView(title);
        TextView subtitle = text("Scoped camera preview and capture replacement", 14, false, 0xffaab4c3);
        LinearLayout.LayoutParams subLp = params(-1, -2);
        subLp.topMargin = dp(4);
        page.addView(subtitle, subLp);

        modeStatus = text("Framework status: not verifiable from this app", 13, false, 0xffaab4c3);
        LinearLayout.LayoutParams statusLp = params(-1, -2);
        statusLp.topMargin = dp(14);
        page.addView(modeStatus, statusLp);

        section(page, "01  SOURCE MEDIA");
        preview = new ImageView(this);
        preview.setBackgroundColor(0xff080a0d);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams previewLp = params(-1, dp(210));
        previewLp.topMargin = dp(8);
        page.addView(preview, previewLp);

        mediaStatus = text(mediaName, 14, true, Color.WHITE);
        LinearLayout.LayoutParams mediaLp = params(-1, -2);
        mediaLp.topMargin = dp(9);
        page.addView(mediaStatus, mediaLp);
        TextView sourceNote = text("Choose an image or video. Files stay in OpenLens app storage; target apps receive temporary read-only access only while configured.", 13, false, 0xffaab4c3);
        LinearLayout.LayoutParams noteLp = params(-1, -2);
        noteLp.topMargin = dp(5);
        page.addView(sourceNote, noteLp);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);
        chooseImageButton = button("Choose image", false);
        chooseVideoButton = button("Choose video", false);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        pickLp.topMargin = dp(12);
        pickLp.rightMargin = dp(6);
        pickRow.addView(chooseImageButton, pickLp);
        LinearLayout.LayoutParams pickVideoLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        pickVideoLp.topMargin = dp(12);
        pickVideoLp.leftMargin = dp(6);
        pickRow.addView(chooseVideoButton, pickVideoLp);
        page.addView(pickRow);
        chooseImageButton.setOnClickListener(v -> openPicker("image/*", PICK_IMAGE));
        chooseVideoButton.setOnClickListener(v -> openPicker("video/*", PICK_VIDEO));

        section(page, "02  TARGET APPS");
        TextView targetNote = text("Select each target app here and enable the same package in LSPosed/Vector scope. Adapters cover common Camera1 callbacks, recognized Camera1/Camera2 preview surfaces, and CPU-visible ImageReader planes. Unsupported native or protected paths pass through.", 13, false, 0xffaab4c3);
        LinearLayout.LayoutParams targetNoteLp = params(-1, -2);
        targetNoteLp.topMargin = dp(7);
        page.addView(targetNote, targetNoteLp);
        targetStatus = text("Searching installed apps…", 13, false, 0xff8bb8ff);
        LinearLayout.LayoutParams targetStatusLp = params(-1, -2);
        targetStatusLp.topMargin = dp(10);
        page.addView(targetStatus, targetStatusLp);
        targetList = new LinearLayout(this);
        targetList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = params(-1, -2);
        listLp.topMargin = dp(8);
        page.addView(targetList, listLp);

        section(page, "03  CONTROL");
        enableButton = button("Enable replacement", true);
        LinearLayout.LayoutParams enableLp = params(-1, dp(52));
        enableLp.topMargin = dp(10);
        page.addView(enableButton, enableLp);
        disableButton = button("Disable and return to camera", false);
        LinearLayout.LayoutParams disableLp = params(-1, dp(48));
        disableLp.topMargin = dp(8);
        page.addView(disableButton, disableLp);
        enableButton.setOnClickListener(v -> enableReplacement());
        disableButton.setOnClickListener(v -> disableReplacement());

        TextView disclaimer = text("Experimental compatibility: CameraX may work through its Camera2 surface/image path. Camera1 callbacks include NV21/YV12/YUY2/RGB565; ImageReader supports common flexible YUV, Y8, P010, and JPEG buffers. Private/native buffers, RAW, protected outputs, and vendor-specific paths may remain physical. Reopen the camera after enabling.", 12, false, 0xffe0b58b);
        LinearLayout.LayoutParams disclaimerLp = params(-1, -2);
        disclaimerLp.topMargin = dp(16);
        page.addView(disclaimer, disclaimerLp);

        setContentView(scroll);
    }

    private void refreshTargets() {
        targetList.removeAllViews();
        List<PackageInfo> candidates = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            List<PackageInfo> installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            for (PackageInfo info : installed) {
                if (info.packageName.equals(getPackageName()) || info.requestedPermissions == null) continue;
                boolean usesCamera = false;
                for (String permission : info.requestedPermissions) {
                    if (ModuleContract.CAMERA_PERMISSION.equals(permission)) {
                        usesCamera = true;
                        break;
                    }
                }
                if (!usesCamera || pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                candidates.add(info);
            }
        } catch (Throwable error) {
            targetStatus.setText("Could not enumerate apps: " + error.getClass().getSimpleName());
            return;
        }

        candidates.sort(Comparator.comparing(info -> {
            try { return pm.getApplicationLabel(info.applicationInfo).toString().toLowerCase(java.util.Locale.ROOT); }
            catch (Throwable ignored) { return info.packageName; }
        }));
        for (PackageInfo info : candidates) {
            ApplicationInfo app = info.applicationInfo;
            CheckBox checkBox = new CheckBox(this);
            String label;
            try { label = pm.getApplicationLabel(app).toString(); }
            catch (Throwable ignored) { label = info.packageName; }
            checkBox.setText(label + "\n" + info.packageName);
            checkBox.setTextColor(Color.WHITE);
            checkBox.setTextSize(14);
            checkBox.setPadding(dp(4), dp(6), dp(4), dp(6));
            checkBox.setMinHeight(dp(60));
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff8bb8ff));
            checkBox.setChecked(selectedPackages.contains(info.packageName));
            try {
                Drawable icon = pm.getApplicationIcon(app);
                icon.setBounds(0, 0, dp(36), dp(36));
                checkBox.setCompoundDrawables(icon, null, null, null);
                checkBox.setCompoundDrawablePadding(dp(12));
            } catch (Throwable ignored) { }
            checkBox.setEnabled(!enabled);
            checkBox.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) selectedPackages.add(info.packageName);
                else selectedPackages.remove(info.packageName);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putStringSet(PREF_TARGETS, new HashSet<>(selectedPackages)).apply();
                if (!enabled && !isChecked) revokeTarget(info.packageName);
                updateTargetCount();
            });
            targetList.addView(checkBox, params(-1, -2));
            View divider = new View(this);
            divider.setBackgroundColor(0xff29303a);
            targetList.addView(divider, params(-1, dp(1)));
        }
        updateTargetCount();
    }

    private void updateTargetCount() {
        int total = targetList == null ? 0 : targetList.getChildCount() / 2;
        targetStatus.setText(selectedPackages.size() + " selected · " + Math.max(0, total) + " camera-permission apps detected");
    }

    private void openPicker(String mimeType, int requestCode) {
        if (enabled) {
            toast("Disable replacement before changing the source.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Throwable error) {
            toast("No file picker is available.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != PICK_IMAGE && requestCode != PICK_VIDEO) return;
        Uri source = data.getData();
        String nextType = requestCode == PICK_VIDEO ? "video" : "image";
        String name = queryDisplayName(source);
        File mediaDir = new File(getFilesDir(), "media");
        if (!mediaDir.exists() && !mediaDir.mkdirs()) {
            toast("Could not create media storage.");
            return;
        }
        File partial = new File(mediaDir, "current.partial");
        File current = new File(mediaDir, "current");
        long copied = 0L;
        try (InputStream in = getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(partial, false)) {
            if (in == null) throw new IllegalStateException("File provider returned no stream");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (copied > ModuleContract.MAX_MEDIA_BYTES) throw new IllegalArgumentException("Media exceeds the 256 MiB import limit");
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
            if (copied == 0) throw new IllegalArgumentException("The selected file is empty");
            Files.move(partial.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING);
            mediaType = nextType;
            mediaName = name;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("mediaType", mediaType)
                    .putString("mediaName", mediaName)
                    .apply();
            writeConfig(false);
            refreshMediaPreview();
            refreshStateUi();
            toast("Imported " + name);
        } catch (Throwable error) {
            partial.delete();
            toast("Import failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "selected media" : last;
    }

    private void loadSavedMediaMetadata() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mediaType = prefs.getString("mediaType", "image");
        mediaName = prefs.getString("mediaName", "No media selected");
        File current = new File(new File(getFilesDir(), "media"), "current");
        if (!current.isFile()) {
            mediaName = "No media selected";
            enabled = false;
            prefs.edit().putBoolean(PREF_ENABLED, false).apply();
            writeConfig(false);
        }
    }

    private void refreshMediaPreview() {
        File file = new File(new File(getFilesDir(), "media"), "current");
        mediaStatus.setText(file.isFile() ? mediaName + " · " + formatBytes(file.length()) : "No media selected");
        if (!file.isFile()) {
            preview.setImageDrawable(null);
            return;
        }
        if ("video".equals(mediaType)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                Bitmap thumb = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                preview.setImageBitmap(thumb);
            } catch (Throwable error) {
                preview.setImageDrawable(null);
                mediaStatus.setText(mediaName + " · video preview unavailable");
            } finally {
                try { retriever.release(); } catch (Throwable ignored) { }
            }
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sample = 1;
            while (Math.max(options.outWidth, options.outHeight) / sample > 1600) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            preview.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath(), options));
        }
    }

    private void enableReplacement() {
        File source = new File(new File(getFilesDir(), "media"), "current");
        if (!source.isFile()) {
            toast("Choose an image or video first.");
            return;
        }
        if (selectedPackages.isEmpty()) {
            toast("Select at least one target app.");
            return;
        }
        if (delayedMediaGrantRevoke != null) handler.removeCallbacks(delayedMediaGrantRevoke);
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            for (String packageName : selectedPackages) {
                grantUriPermission(packageName, ModuleContract.CONFIG_URI, flags);
                grantUriPermission(packageName, ModuleContract.MEDIA_URI, flags);
            }
            if (!writeConfig(true)) {
                for (String packageName : selectedPackages) {
                    try { revokeUriPermission(packageName, ModuleContract.MEDIA_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) { }
                }
                return;
            }
            enabled = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply();
            refreshStateUi();
            refreshTargets();
            toast("Enabled. Reopen the target app's camera to apply it.");
        } catch (Throwable error) {
            writeConfig(false);
            enabled = false;
            refreshStateUi();
            toast("Could not enable replacement: " + error.getClass().getSimpleName());
        }
    }

    private void disableReplacement() {
        if (!writeConfig(false)) {
            toast("Disable state could not be saved. Keep OpenLens open and retry.");
            return;
        }
        enabled = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        refreshStateUi();
        refreshTargets();
        if (delayedMediaGrantRevoke != null) handler.removeCallbacks(delayedMediaGrantRevoke);
        delayedMediaGrantRevoke = () -> {
            for (String packageName : selectedPackages) {
                try { revokeUriPermission(packageName, ModuleContract.MEDIA_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Throwable ignored) { }
            }
        };
        handler.postDelayed(delayedMediaGrantRevoke, 1500);
        toast("Disabled. Scoped apps return to their physical camera feed.");
    }

    private void revokeTarget(String packageName) {
        try {
            revokeUriPermission(packageName, ModuleContract.CONFIG_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            revokeUriPermission(packageName, ModuleContract.MEDIA_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
    }

    private boolean writeConfig(boolean active) {
        try {
            JSONObject json = new JSONObject();
            json.put("enabled", active);
            json.put("mediaType", mediaType);
            json.put("updatedAt", System.currentTimeMillis());
            File destination = new File(getFilesDir(), "config.json");
            File temp = new File(getFilesDir(), "config.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable error) {
            Toast.makeText(this, "Configuration save failed: " + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void refreshStateUi() {
        if (modeStatus == null) return;
        modeStatus.setText(enabled
                ? "State: ENABLED · compatible callbacks, surfaces and image planes use the selected source"
                : "State: DISABLED · physical camera data passes through");
        modeStatus.setTextColor(enabled ? 0xff8ddea8 : 0xffaab4c3);
        if (chooseImageButton != null) chooseImageButton.setEnabled(!enabled);
        if (chooseVideoButton != null) chooseVideoButton.setEnabled(!enabled);
        if (enableButton != null) {
            enableButton.setEnabled(!enabled);
            enableButton.setAlpha(enabled ? 0.55f : 1f);
        }
        if (disableButton != null) {
            disableButton.setEnabled(enabled);
            disableButton.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    private void section(LinearLayout page, String label) {
        TextView view = text(label, 13, true, 0xff8bb8ff);
        LinearLayout.LayoutParams lp = params(-1, -2);
        lp.topMargin = dp(25);
        page.addView(view, lp);
        View line = new View(this);
        line.setBackgroundColor(0xff29303a);
        LinearLayout.LayoutParams lineLp = params(-1, dp(1));
        lineLp.topMargin = dp(7);
        page.addView(line, lineLp);
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? 0xff101318 : Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? 0xff8bb8ff : 0xff29303a));
        button.setMinHeight(dp(44));
        return button;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024d);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (targetList != null) refreshStateUi();
    }
}
