package com.jsux.openlens;

import android.net.Uri;

final class ModuleContract {
    static final String PACKAGE_NAME = "com.jsux.openlens";
    static final String AUTHORITY = "com.jsux.openlens.provider";
    static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final Uri MEDIA_URI = Uri.parse("content://" + AUTHORITY + "/media/current");
    static final String CAMERA_PERMISSION = "android.permission.CAMERA";
    static final int MAX_MEDIA_BYTES = 256 * 1024 * 1024;

    private ModuleContract() {
    }
}
