package io.maru.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Bundle;

public final class HelperSharedStateProvider extends ContentProvider {
    public static final String AUTHORITY = "io.maru.helper.sharedstate";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_SHARED_AUTH_USER = "getSharedAuthUser";
    public static final String METHOD_GET_SERVER_ORIGIN = "getServerOrigin";
    public static final String KEY_VALUE = "value";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }

        Bundle result = new Bundle();
        if (METHOD_GET_SHARED_AUTH_USER.equals(method)) {
            result.putString(KEY_VALUE, HelperStorage.getSharedAuthUser(context));
            return result;
        }
        if (METHOD_GET_SERVER_ORIGIN.equals(method)) {
            result.putString(KEY_VALUE, HelperStorage.resolveDetectorServerOrigin(context));
            return result;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        return 0;
    }
}
