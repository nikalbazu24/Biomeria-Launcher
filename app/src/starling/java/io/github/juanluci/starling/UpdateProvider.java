package io.github.juanluci.starling;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;

/**
 * The update the game downloaded (files/updates/, game/update_offer.rs), read by Android's package
 * installer: an app may hand another its private files only through a provider like this one. It
 * serves update APKs and explicitly exported diagnostic ZIPs read-only, only to recipients
 * granted access by the install or share intent.
 */
public final class UpdateProvider extends ContentProvider {
    static final String TYPE = "application/vnd.android.package-archive";

    static Uri reportUri(String packageName, String slot, String name) {
        return new Uri.Builder().scheme("content").authority(packageName + ".updates")
                .appendPath("diagnostics").appendPath(BiomeriaProfiles.slot(slot)).appendPath(name).build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        String name = uri.getLastPathSegment();
        boolean report = isReport(path);
        if (name == null || !(report
                ? name.matches("starling-diagnostics-[0-9]+\\.zip")
                : path.size() == 1 && name.matches("[A-Za-z0-9._-]+\\.apk"))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File root = getContext().getFilesDir();
        if (report && path.size() == 3) {
            try {
                root = BiomeriaProfiles.existing(root, path.get(1));
            } catch (IllegalArgumentException error) {
                throw new FileNotFoundException(String.valueOf(uri));
            }
        }
        File directory = new File(root, report ? "performance" : "updates");
        File file = new File(directory, name);
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException(file.getPath());
        }
        return file;
    }

    private static boolean isReport(List<String> path) {
        return (path.size() == 2 || path.size() == 3) && "diagnostics".equals(path.get(0));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return isReport(uri.getPathSegments()) ? "application/zip" : TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The name and size, which installers ask before reading. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file;
        try {
            file = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {file.getName(), file.length()});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
