package org.deltavault.android;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.deltavault.core.LocalStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** JVM tests of the Android boundary, with a controlled DocumentsProvider and no emulator. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalFolderTest {
    private static final String AUTHORITY = "org.deltavault.test.documents";
    private Context context;
    private Uri treeUri;
    private TestDocumentsProvider provider;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Files.deleteIfExists(new File(context.getNoBackupFilesDir(), "vaults.json").toPath());
        treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
        provider = new TestDocumentsProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY; info.exported = true; info.grantUriPermissions = true;
        info.readPermission = info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
    }

    @Test public void profilesMigrateLegacyWebDavAndRoundTripLocalWithoutPasswords() throws Exception {
        File file = new File(context.getNoBackupFilesDir(), "vaults.json");
        Files.write(file.toPath(), "[{\"id\":\"old\",\"name\":\"DAV\",\"url\":\"https://example.org/vault/\",\"user\":\"alice\"}]".getBytes(StandardCharsets.UTF_8));
        VaultProfiles storage = new VaultProfiles(context);
        VaultProfiles.Profile old = storage.load().get(0);
        assertFalse(old.isLocal()); assertEquals("alice", old.user);
        VaultProfiles.Profile local = storage.createLocal("  SD card  ", treeUri.toString());
        storage.save(Arrays.asList(old, local));
        List<VaultProfiles.Profile> saved = storage.load();
        assertEquals(2, saved.size()); assertFalse(saved.get(0).isLocal());
        assertTrue(saved.get(1).isLocal()); assertEquals("SD card", saved.get(1).name);
        assertEquals(treeUri.toString(), saved.get(1).url); assertEquals("", saved.get(1).user);
        assertFalse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("password"));
        assertThrows(IllegalArgumentException.class, () -> storage.createLocal("x", "file:///sdcard/vault"));
        assertThrows(IllegalArgumentException.class, () -> storage.createLocal("x", treeUri + "?query=1"));
    }

    @Test public void pickerRequestsPersistentReadWriteAndLocalUnlockHasOnePassword() throws Exception {
        ResolveInfo pickerApp = new ResolveInfo(); pickerApp.activityInfo = new ActivityInfo();
        pickerApp.activityInfo.packageName = "org.deltavault.test.picker";
        pickerApp.activityInfo.name = "Picker"; pickerApp.activityInfo.exported = true;
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pickerApp);
        VaultProfiles storage = new VaultProfiles(context);
        storage.save(List.of(storage.createLocal("Local", treeUri.toString())));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get();
            findText(screen.getWindow().getDecorView(), "解除する").performClick();
            AlertDialog unlock = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(1, countInputs(unlock.getWindow().getDecorView()));
            unlock.dismiss();
            findText(screen.getWindow().getDecorView(), "ローカルフォルダーを登録").performClick();
            AlertDialog add = ShadowAlertDialog.getLatestAlertDialog();
            shadowOf(Looper.getMainLooper()).idle();
            findInput(add.getWindow().getDecorView()).setText("Second");
            add.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Intent picker = shadowOf(screen).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.getAction());
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            assertEquals(flags, picker.getFlags() & flags);
        }
    }

    @Test public void adapterReadsWritesRenamesChangedIdsAndClosesActiveReadsOnApi28() throws Exception {
        exerciseAdapter();
    }

    @Test public void folderResultAfterRestartPersistsGrantAndLastRegistrationRemovalReleasesIt() throws Exception {
        VaultProfiles storage = new VaultProfiles(context);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get();
            activity.pause().stop().start();
            screen.onActivityResult(101, Activity.RESULT_OK, new Intent().setData(treeUri).setFlags(flags));
            activity.resume(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, storage.load().size());
            assertTrue(storage.load().get(0).isLocal());
            assertEquals(treeUri, context.getContentResolver().getPersistedUriPermissions().get(0).getUri());
            screen.onActivityResult(101, Activity.RESULT_OK, new Intent().setData(treeUri).setFlags(flags));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, storage.load().size());
            findText(screen.getWindow().getDecorView(), "登録を削除").performClick();
            shadowOf(Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, storage.load().size());
            assertEquals(1, context.getContentResolver().getPersistedUriPermissions().size());
            findText(screen.getWindow().getDecorView(), "登録を削除").performClick();
            shadowOf(Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(storage.load().isEmpty());
            assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
            assertEquals(2, provider.items.size()); // Root and outside fixture: no vault documents were deleted.
        }
    }

    @Test @Config(sdk = 29) public void adapterReadsWritesAndChecksTreeScopeOnApi29() throws Exception {
        exerciseAdapter();
        try (SafDocumentTree tree = new SafDocumentTree(context.getContentResolver(), treeUri)) {
            assertThrows(IOException.class, () -> tree.read("outside"));
        }
    }

    @Test public void constructionDoesNotQueryAndCloseCancelsFirstRootQuery() throws Exception {
        CancellationProvider blocked = new CancellationProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = AUTHORITY;
        blocked.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, blocked);
        SafDocumentTree tree = new SafDocumentTree(context.getContentResolver(), treeUri);
        assertEquals(0, blocked.calls.get());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> { tree.children("root"); return null; });
            assertTrue(blocked.started.await(3, TimeUnit.SECONDS));
            tree.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> read.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(blocked.cancelled.await(1, TimeUnit.SECONDS));
        } finally { tree.close(); worker.shutdownNow(); worker.awaitTermination(3, TimeUnit.SECONDS); }
    }

    private void exerciseAdapter() throws Exception {
        SafDocumentTree tree = new SafDocumentTree(context.getContentResolver(), treeUri);
        LocalStore.Document directory = tree.create(tree.rootId(), "data", true);
        LocalStore.Document file = tree.create(directory.id, "cipher.c9r", false);
        byte[] ciphertext = {1, 7, 3, 9}; tree.write(file.id, ciphertext);
        try (InputStream input = tree.read(file.id)) { assertArrayEquals(ciphertext, input.readAllBytes()); }
        LocalStore.Document renamed = tree.rename(file.id, "published.c9r");
        assertNotEquals(file.id, renamed.id); assertEquals("published.c9r", renamed.name);
        assertEquals(renamed.id, tree.children(directory.id).get(0).id);
        InputStream active = tree.read(renamed.id);
        tree.close(); tree.close();
        assertThrows(IOException.class, active::read); active.close();
        assertThrows(IOException.class, () -> tree.children("root"));
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = findInput(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static int countInputs(View view) {
        if (view instanceof EditText) return 1;
        int count = 0;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) count += countInputs(group.getChildAt(i));
        return count;
    }

    public static final class TestDocumentsProvider extends DocumentsProvider {
        private final Map<String, Item> items = new LinkedHashMap<>();
        private int nextId;
        @Override public boolean onCreate() {
            items.put("root", new Item("root", "vault", null, true, null));
            items.put("outside", new Item("outside", "outside", null, false, null));
            return true;
        }
        @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}); }
        @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            Item item = require(id); MatrixCursor cursor = cursor(projection); row(cursor, item); return cursor;
        }
        @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
            require(id); MatrixCursor cursor = cursor(projection);
            for (Item item : items.values()) if (id.equals(item.parent)) row(cursor, item);
            return cursor;
        }
        @Override public boolean isChildDocument(String parent, String child) {
            Item item = items.get(child);
            while (item != null && item.parent != null) {
                if (parent.equals(item.parent)) return true;
                item = items.get(item.parent);
            }
            return false;
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
            Item item = require(id);
            if (item.file == null) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            require(parent);
            boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            try {
                File file = directory ? null : File.createTempFile("cipher-", ".bin", getContext().getCacheDir());
                String id = "doc/" + ++nextId;
                items.put(id, new Item(id, name, parent, directory, file)); return id;
            } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        }
        @Override public String renameDocument(String id, String name) throws FileNotFoundException {
            Item old = require(id); String replacement = "renamed/" + ++nextId;
            items.remove(id); items.put(replacement, new Item(replacement, name, old.parent, old.directory, old.file));
            return replacement;
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException {
            Item item = require(id); items.remove(id); if (item.file != null) item.file.delete();
        }
        private Item require(String id) throws FileNotFoundException {
            Item item = items.get(id); if (item == null) throw new FileNotFoundException(id); return item;
        }
        private MatrixCursor cursor(String[] projection) {
            return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS} : projection);
        }
        private void row(MatrixCursor cursor, Item item) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(item.id);
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(item.name);
                    case DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(item.directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream");
                    case DocumentsContract.Document.COLUMN_FLAGS -> row.add(DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                            | DocumentsContract.Document.FLAG_SUPPORTS_DELETE | (item.directory
                            ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE));
                    default -> row.add(null);
                }
            }
        }
        private static final class Item {
            final String id, name, parent; final boolean directory; final File file;
            Item(String id, String name, String parent, boolean directory, File file) {
                this.id = id; this.name = name; this.parent = parent; this.directory = directory; this.file = file;
            }
        }
    }

    public static final class CancellationProvider extends ContentProvider {
        final CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
            calls.incrementAndGet(); signal.setOnCancelListener(cancelled::countDown); started.countDown();
            try { if (!cancelled.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cancellation did not arrive"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            signal.throwIfCanceled(); return null;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort, CancellationSignal signal) {
            return query(uri, projection, (Bundle) null, signal);
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
