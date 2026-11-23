package com.example.fitcounter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class DiagnosticsProvider extends ContentProvider {
    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".diagnostics")
                .appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/zip"; }

    private File report(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null
                || !name.matches("fitcounter-diagnostics-[0-9-]+\\.zip")) {
            throw new IllegalArgumentException("Invalid diagnostics URI");
        }
        return new File(DiagnosticsExport.directory(getContext()), name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only diagnostics report");
        return ParcelFileDescriptor.open(report(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = report(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        if (!file.isFile()) return cursor;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
