package com.jsux.openlens;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public final class MediaProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        if (getContext() == null) return false;
        File mediaDir = new File(getContext().getFilesDir(), "media");
        if (!mediaDir.exists() && !mediaDir.mkdirs()) return false;
        File config = new File(getContext().getFilesDir(), "config.json");
        if (!config.exists()) {
            try (FileOutputStream out = new FileOutputStream(config)) {
                out.write("{\"enabled\":false,\"mediaType\":\"image\",\"updatedAt\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("Provider context unavailable");
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        enforceReadGrant(uri);

        String path = uri.getPath();
        File file;
        if ("/config".equals(path)) {
            file = new File(getContext().getFilesDir(), "config.json");
        } else if ("/media/current".equals(path)) {
            if (!isEnabled()) throw new FileNotFoundException("OpenLens is disabled");
            file = new File(new File(getContext().getFilesDir(), "media"), "current");
        } else {
            throw new FileNotFoundException("Unknown OpenLens URI");
        }
        if (!file.isFile()) throw new FileNotFoundException("Media source is not available");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void enforceReadGrant(Uri uri) throws FileNotFoundException {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        int result = getContext().checkUriPermission(
                uri, Binder.getCallingPid(), uid, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (result != PackageManager.PERMISSION_GRANTED) {
            throw new FileNotFoundException("Caller has no URI read grant");
        }
    }

    private boolean isEnabled() {
        File file = new File(getContext().getFilesDir(), "config.json");
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 16 * 1024)];
            int count = in.read(data);
            if (count <= 0) return false;
            return new JSONObject(new String(data, 0, count, java.nio.charset.StandardCharsets.UTF_8))
                    .optBoolean("enabled", false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) return null;
        if ("/config".equals(uri.getPath())) return "application/json";
        if ("/media/current".equals(uri.getPath()) && getContext() != null) {
            String type = getContext().getSharedPreferences("openlens", 0).getString("mediaType", "image");
            return "video".equals(type) ? "video/mp4" : "image/*";
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri == null || !"/media/current".equals(uri.getPath()) || getContext() == null || !isEnabled()) return null;
        enforceCursorReadGrant(uri);
        File file = new File(new File(getContext().getFilesDir(), "media"), "current");
        if (!file.isFile()) return null;
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = getContext().getSharedPreferences("openlens", 0).getString("mediaName", "current");
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    private void enforceCursorReadGrant(Uri uri) {
        if (Binder.getCallingUid() == Process.myUid()) return;
        int result = getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (result != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Caller has no URI read grant");
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }
}
