package org.deltavault.android;

import static org.junit.Assert.assertArrayEquals;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContentResolver;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.common.EncryptingReadableByteChannel;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.deltavault.core.VaultStream;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/** Native LibVLC path: authenticated vault ranges -> private proxy FD -> secure video surface. */
public final class VideoPlaybackTest extends InstrumentationTestCase {
	private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
	private MainActivity activity;
	private CryptoFixture fixture;

	public void testSafReaderSupportsSeekBoundsAndCancellation() throws Exception {
		Context context = getInstrumentation().getTargetContext();
		byte[] encrypted = new byte[100_000];
		for (int i = 0; i < encrypted.length; i++) encrypted[i] = (byte) (i * 29 + 11);
		File ciphertextFile = new File(context.getCacheDir(), "saf-video-" + UUID.randomUUID() + ".cipher");
		try (FileOutputStream output = new FileOutputStream(ciphertextFile)) { output.write(encrypted); }

		FixtureDocumentsProvider provider = new FixtureDocumentsProvider(ciphertextFile);
		SafDocumentTree tree = null;
		RemoteStore.Reader reader = null;
		try {
			ProviderInfo info = new ProviderInfo();
			info.authority = FixtureDocumentsProvider.AUTHORITY;
			info.name = FixtureDocumentsProvider.class.getName();
			info.exported = true;
            info.grantUriPermissions = true;
            info.readPermission = info.writePermission = "android.permission.MANAGE_DOCUMENTS";
			info.applicationInfo = context.getApplicationInfo();
			provider.attachInfo(context, info);
			MockContentResolver resolver = new MockContentResolver(context);
			resolver.addProvider(FixtureDocumentsProvider.AUTHORITY, provider);
			Uri selectedTree = DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,
					FixtureDocumentsProvider.ROOT_ID);
			tree = new SafDocumentTree(resolver, selectedTree);
            assertEquals(FixtureDocumentsProvider.ROOT_ID, tree.rootId());
            assertEquals(1, tree.children(tree.rootId()).size());
			reader = tree.openReader(FixtureDocumentsProvider.VIDEO_ID);
			assertEquals(encrypted.length, reader.size());
			assertEquals("r", provider.lastOpenMode);
			assertTrue("SAF root-child authorization was not checked", provider.childChecks > 0);

			byte[] late = new byte[37];
			reader.readFully(90_000, late, 0, late.length);
			assertArrayEquals(Arrays.copyOfRange(encrypted, 90_000, 90_037), late);
			byte[] early = new byte[19];
			reader.readFully(3, early, 0, early.length);
			assertArrayEquals(Arrays.copyOfRange(encrypted, 3, 22), early);

			byte[] eof = new byte[8];
			Arrays.fill(eof, (byte) 0x49);
			try { reader.readFully(encrypted.length - 3L, eof, 0, eof.length); fail("SAF reader accepted a range beyond EOF"); }
			catch (IOException expected) { }
			reader.close();
			try { reader.readFully(0, new byte[1], 0, 1); fail("closed SAF descriptor remained readable"); }
			catch (IOException expected) { }
			reader = null;
		} finally {
			if (reader != null) reader.close();
			if (tree != null) tree.close();
			ciphertextFile.delete();
			Arrays.fill(encrypted, (byte) 0);
		}
	}

	public void testVideosUseSecureEphemeralProxyAndLockRevokesPlayback() throws Exception {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.setClassName(getInstrumentation().getTargetContext(), MainActivity.class.getName());
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		activity = (MainActivity) getInstrumentation().startActivitySync(intent);
		fixture = new CryptoFixture(getInstrumentation().getContext());
		setField(activity, "session", fixture.session);
		setField(activity, "remote", fixture.store);

		Map<String, String> baseline = appDataSnapshot(activity);
		File appVlcDirectory = new File(activity.getFilesDir().getParentFile(), "app_vlc");
		List<String> failures = new ArrayList<>();
		PlaybackHandle finalHandle = null;
		try {
			String[] assets = {"preview.avi", "cover-art.mkv", "cover-art.mp4"};
			for (int i = 0; i < assets.length; i++) {
				PlaybackHandle handle = null;
				try {
					handle = openDialog(assets[i]);
					verifyPlayback(handle, assets[i]);
					assertEquals("video source failed for " + assets[i], 0, handle.failures.get());
					assertEquals("secure playback wrote to app data for " + assets[i], baseline, appDataSnapshot(activity));
					assertFalse("LibVLC created app_vlc for " + assets[i], appVlcDirectory.exists());
					assertNoArtCacheLocation();
				} catch (AssertionError | Exception e) {
					failures.add(assets[i] + ": " + e);
				}
				if (i == assets.length - 1) {
					finalHandle = handle;
				} else if (handle != null) {
					closeDialog(handle);
				}
			}

			// Keep the last preview live so MainActivity.lock must revoke its descriptor first.
			lockActivity();
			assertNull("lock retained its player", finalHandle == null ? null : player(finalHandle.preview));
			if (finalHandle != null) {
				assertStreamAndCallbackRevoked(finalHandle);
				assertEquals("locking raised an asynchronous playback failure", 0, finalHandle.failures.get());
			}
			assertTrue("session masterkey was not destroyed by lock", awaitDestroyed(fixture.masterkey, 5_000));
			assertNull("lock retained its session", getField(activity, "session"));
			assertFalse("LibVLC created app_vlc", appVlcDirectory.exists());
			assertEquals("video data remained in app files/cache/noBackup", baseline, appDataSnapshot(activity));
		} finally {
			if (activity != null && fixture != null && !fixture.masterkey.isDestroyed()) {
				try { lockActivity(); } catch (Exception ignored) { }
			}
			if (fixture != null) fixture.close();
			if (activity != null) {
				getInstrumentation().runOnMainSync(activity::finish);
			}
		}
		if (!failures.isEmpty()) {
			AssertionError combined = new AssertionError("Native video cases failed: " + String.join("; ", failures));
			fail(combined.getMessage());
		}
	}

	private PlaybackHandle openDialog(String filename) throws Exception {
		VaultSession.Node node = fixture.nodes.get(filename);
		VaultStream stream = fixture.session.openStream(node);
		AtomicInteger failures = new AtomicInteger();
		AtomicReferenceHolder<PlaybackHandle> result = new AtomicReferenceHolder<>();
		getInstrumentation().runOnMainSync(() -> {
			try {
				SecureViews views = (SecureViews) getField(activity, "views");
                VideoPreview preview = new VideoPreview(activity, views, stream, filename, failures::incrementAndGet);
                preview.pause(); // Pause while opening, before the first native Playing event.
				VideoReadCallback callback = ((VideoSource) getField(preview, "source")).callback;
				AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(filename).setView(preview.view)
						.setPositiveButton("閉じる", null).create();
				dialog.setOnDismissListener(ignored -> preview.close());
				dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
				if (android.os.Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
				dialog.show();
				setField(activity, "video", preview);
				setField(activity, "dialog", dialog);
				result.set(new PlaybackHandle(preview, dialog, stream, callback, failures));
			} catch (Exception e) {
				stream.close();
				throw new RuntimeException(e);
			}
		});
		if (result.get() == null) throw new AssertionError("Video preview did not open for " + filename);
		return result.get();
	}

	private void verifyPlayback(PlaybackHandle handle, String filename) throws Exception {
		assertTrue("activity window is not secure for " + filename,
				(activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
		assertTrue("player dialog is not secure for " + filename,
				(handle.dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        Object player = player(handle.preview);
        assertNotNull("LibVLC player was not created", player);
        waitForPlayer("opening pause did not take effect in " + filename, 10_000, (MediaPlayer) player,
                () -> ((MediaPlayer) player).getPlayerState() == IMedia.State.Paused && !((MediaPlayer) player).isPlaying());
        getInstrumentation().runOnMainSync(() -> {
            try { ((android.widget.Button) getField(handle.preview, "toggle")).performClick(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
		waitForPlayer("LibVLC did not begin decoding " + filename, 10_000, (MediaPlayer) player, () -> {
			try {
				return ((MediaPlayer) player).isPlaying() && ((MediaPlayer) player).getLength() > 0
						&& ((MediaPlayer) player).getVideoTracksCount() > 0
						&& ((MediaPlayer) player).getVLCVout().areViewsAttached() && displayedVideo((MediaPlayer) player);
			} catch (RuntimeException e) { return false; }
		});
		MediaPlayer mediaPlayer = (MediaPlayer) player;
		assertTrue("player has no seekable video stream in " + filename, mediaPlayer.isSeekable());
		assertTrue("LibVLC attached no video SurfaceView in " + filename, containsSurface((View) getField(handle.preview, "surface")));

		long duration = mediaPlayer.getLength();
		long target = Math.min(3_000, Math.max(500, duration / 2));
		assertTrue("test video is too short to seek", duration > 1_000);
		mediaPlayer.setTime(target);
		waitFor("LibVLC seek did not complete in " + filename, 4_000,
				() -> Math.abs(mediaPlayer.getTime() - target) < 1_500);
		mediaPlayer.pause();
		waitFor("LibVLC did not pause " + filename, 2_000, () -> !mediaPlayer.isPlaying());
		assertEquals("video read failure for " + filename, 0, handle.failures.get());
		assertEquals("XDG cache directory escaped the no-storage context", "/dev/null", Os.getenv("XDG_CACHE_HOME"));
		assertEquals("XDG data directory escaped the no-storage context", "/dev/null", Os.getenv("XDG_DATA_HOME"));
		assertEquals("XDG config directory escaped the no-storage context", "/dev/null", Os.getenv("XDG_CONFIG_HOME"));
		assertEquals("LibVLC HOME escaped the no-storage context", "/dev/null", Os.getenv("HOME"));
	}

	private void closeDialog(PlaybackHandle handle) {
		getInstrumentation().runOnMainSync(() -> {
			handle.dialog.dismiss();
			setField(activity, "dialog", null);
            setField(activity, "video", null);
        });
        getInstrumentation().waitForIdleSync(); // Dialog dispatches OnDismiss asynchronously.
        assertStreamAndCallbackRevoked(handle);
	}

	private void lockActivity() throws Exception {
		getInstrumentation().runOnMainSync(() -> {
			try {
				Method lock = MainActivity.class.getDeclaredMethod("lock", boolean.class);
				lock.setAccessible(true);
				lock.invoke(activity, false);
			} catch (Exception e) { throw new RuntimeException(e); }
		});
	}

	private void assertStreamAndCallbackRevoked(PlaybackHandle handle) {
		try { handle.stream.size(); fail("closed video stream remained readable"); }
		catch (IOException expected) { }
		try {
			VideoReadCallback callback = handle.callback;
			try { callback.onGetSize(); fail("closed proxy descriptor still reported a size"); }
			catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
			byte[] output = new byte[16];
			Arrays.fill(output, (byte) 0x6d);
			try { callback.onRead(0, output.length, output); fail("closed proxy descriptor returned bytes"); }
			catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
			assertEquals(0, countNonZero(output));
		} catch (Exception e) { throw new AssertionError("unable to inspect the closed proxy callback", e); }
	}

	private static int countNonZero(byte[] bytes) {
		int count = 0;
		for (byte value : bytes) if (value != 0) count++;
		return count;
	}

	private static Object player(VideoPreview preview) throws Exception { return getField(preview, "player"); }

	private static boolean containsSurface(View view) {
		if (view instanceof SurfaceView) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) if (containsSurface(group.getChildAt(i))) return true;
		}
		return false;
	}

	private void assertNoArtCacheLocation() throws Exception {
		try {
			FileDescriptor descriptor = Os.open("/dev/null/art/test", OsConstants.O_CREAT | OsConstants.O_WRONLY, 0600);
			Os.close(descriptor);
			fail("the /dev/null cache location unexpectedly accepted an art file");
		} catch (ErrnoException expected) {
			assertEquals("art cache path did not fail at the non-directory device", OsConstants.ENOTDIR, expected.errno);
		}
	}

	private static Map<String, String> appDataSnapshot(MainActivity activity) {
		Map<String, String> snapshot = new TreeMap<>();
		for (File root : new File[] {activity.getFilesDir(), activity.getCacheDir(), activity.getNoBackupFilesDir()}) {
			snapshotTree(root, root.getName(), snapshot);
		}
		return snapshot;
	}

	private static void snapshotTree(File file, String relative, Map<String, String> snapshot) {
		if (!file.exists()) return;
		snapshot.put(relative, file.isDirectory() ? "dir" : Long.toString(file.length()));
		File[] children = file.listFiles();
		if (children != null) {
			Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
			for (File child : children) snapshotTree(child, relative + "/" + child.getName(), snapshot);
		}
	}

	private static boolean awaitDestroyed(Masterkey key, long timeoutMillis) {
		long deadline = SystemClock.uptimeMillis() + timeoutMillis;
		while (SystemClock.uptimeMillis() < deadline) {
			if (key.isDestroyed()) return true;
			SystemClock.sleep(25);
		}
		return key.isDestroyed();
	}

	private void waitFor(String message, long timeoutMillis, Condition condition) {
		long deadline = SystemClock.uptimeMillis() + timeoutMillis;
		while (SystemClock.uptimeMillis() < deadline) {
			if (condition.ok()) return;
			SystemClock.sleep(50);
		}
		assertTrue(message, condition.ok());
	}

	private interface Condition { boolean ok(); }

	private static boolean displayedVideo(MediaPlayer player) {
		IMedia media = player.getMedia();
		if (media == null) return false;
		try {
			IMedia.Stats stats = media.getStats();
			return stats != null && stats.decodedVideo > 0 && stats.displayedPictures > 0;
		} finally { media.release(); }
	}

	private void waitForPlayer(String message, long timeoutMillis, MediaPlayer player, Condition condition) {
		try { waitFor(message, timeoutMillis, condition); }
		catch (AssertionError e) {
			fail(message + " (state=" + player.getPlayerState() + ", playing=" + player.isPlaying()
					+ ", length=" + player.getLength() + ", time=" + player.getTime()
					+ ", tracks=" + player.getVideoTracksCount() + ", displayed=" + displayedVideo(player) + ")");
		}
	}

	private static Object getField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (Exception e) { throw new RuntimeException(e); }
	}

	private static final class PlaybackHandle {
		final VideoPreview preview;
		final AlertDialog dialog;
		final VaultStream stream;
		final VideoReadCallback callback;
		final AtomicInteger failures;
		PlaybackHandle(VideoPreview preview, AlertDialog dialog, VaultStream stream, VideoReadCallback callback, AtomicInteger failures) {
			this.preview = preview; this.dialog = dialog; this.stream = stream; this.callback = callback; this.failures = failures;
		}
	}

	private static final class AtomicReferenceHolder<T> {
		private T value;
		synchronized void set(T value) { this.value = value; }
		synchronized T get() { return value; }
	}

	private static final class CryptoFixture implements AutoCloseable {
		final Masterkey masterkey;
		final Cryptor cryptor;
		final FixtureStore store = new FixtureStore();
		final VaultSession session;
		final Map<String, VaultSession.Node> nodes = new LinkedHashMap<>();
		boolean closed;

		CryptoFixture(android.content.Context context) throws Exception {
			SecureRandom random = new SecureRandom();
			masterkey = Masterkey.generate(random);
			cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_CTRMAC).provide(masterkey, random);
			try {
				for (String filename : new String[] {"preview.avi", "cover-art.mkv", "cover-art.mp4"}) {
					byte[] clear = readAsset(context, filename);
					try { store.files.put("movie-" + filename, encrypt(cryptor, clear)); }
					finally { Arrays.fill(clear, (byte) 0); }
				}
				Constructor<VaultSession> sessionConstructor = VaultSession.class.getDeclaredConstructor(
						RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
				sessionConstructor.setAccessible(true);
				session = sessionConstructor.newInstance(store, masterkey, cryptor, 220);
				Constructor<VaultSession.Node> nodeConstructor = VaultSession.Node.class.getDeclaredConstructor(
						VaultSession.class, String.class, String.class, boolean.class, String.class,
						String.class, String.class, String.class);
				nodeConstructor.setAccessible(true);
				for (String filename : new String[] {"preview.avi", "cover-art.mkv", "cover-art.mp4"}) {
					String contentPath = "movie-" + filename;
					nodes.put(filename, nodeConstructor.newInstance(session, filename, contentPath, false, null,
							contentPath, "", filename));
				}
			} catch (Exception | Error e) {
				store.close(); cryptor.destroy(); if (!masterkey.isDestroyed()) masterkey.destroy(); throw e;
			}
		}

		private static byte[] readAsset(android.content.Context context, String filename) throws IOException {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			try (InputStream input = context.getAssets().open(filename)) {
				int count;
				while ((count = input.read(buffer)) != -1) {
					if (output.size() + count > MAX_ASSET_BYTES) throw new IOException("Instrumented video fixture is too large");
					output.write(buffer, 0, count);
				}
				return output.toByteArray();
			} finally { Arrays.fill(buffer, (byte) 0); }
		}

		private static byte[] encrypt(Cryptor cryptor, byte[] plaintext) throws IOException {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] scratch = new byte[8192];
			try (ReadableByteChannel encrypted = new EncryptingReadableByteChannel(
					Channels.newChannel(new ByteArrayInputStream(plaintext)), cryptor)) {
				ByteBuffer buffer = ByteBuffer.wrap(scratch);
				while (true) {
					buffer.clear();
					int count = encrypted.read(buffer);
					if (count < 0) break;
					if (count == 0) continue;
					output.write(scratch, 0, count);
				}
				return output.toByteArray();
			} finally { Arrays.fill(scratch, (byte) 0); }
		}

		@Override public void close() {
			if (!closed) { session.close(); closed = true; }
		}
	}

	private static final class FixtureStore implements RemoteStore {
		final Map<String, byte[]> files = new LinkedHashMap<>();
		final List<FixtureReader> readers = new ArrayList<>();
		boolean closed;
		@Override public synchronized Reader openReader(String path) throws IOException {
			if (closed || !files.containsKey(path)) throw new IOException("Missing fixture video");
			FixtureReader reader = new FixtureReader(this, path);
			readers.add(reader);
			return reader;
		}
		@Override public Blob get(String path, int maxBytes) throws IOException { throw new IOException("Unused"); }
		@Override public List<Entry> list(String path) throws IOException { throw new IOException("Unused"); }
		@Override public void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException { throw new IOException("Unused"); }
		@Override public void mkdir(String path) throws IOException { throw new IOException("Unused"); }
		@Override public void move(String sourcePath, String destinationPath) throws IOException { throw new IOException("Unused"); }
		@Override public synchronized void close() {
			closed = true;
			for (FixtureReader reader : readers) reader.close();
			for (byte[] ciphertext : files.values()) Arrays.fill(ciphertext, (byte) 0);
			files.clear();
		}
	}

	private static final class FixtureReader implements RemoteStore.Reader {
		final FixtureStore store;
		final String path;
		volatile boolean closed;
		FixtureReader(FixtureStore store, String path) { this.store = store; this.path = path; }
		@Override public long size() { return store.files.get(path).length; }
		@Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
			byte[] ciphertext = store.files.get(path);
			if (closed || store.closed || ciphertext == null) throw new IOException("Fixture video reader closed");
			RemoteStore.checkRange(ciphertext.length, position, destination, offset, length);
			System.arraycopy(ciphertext, (int) position, destination, offset, length);
		}
		@Override public void close() { closed = true; }
	}

	private static final class FixtureDocumentsProvider extends DocumentsProvider {
		static final String AUTHORITY = "org.deltavault.android.test.documents";
		static final String ROOT_ID = "vault-root";
		static final String VIDEO_ID = "opaque-video-document";
		private final File ciphertextFile;
		volatile String lastOpenMode;
		volatile int childChecks;

		FixtureDocumentsProvider(File ciphertextFile) { this.ciphertextFile = ciphertextFile; }
		@Override public boolean onCreate() { return true; }
		@Override public Cursor queryRoots(String[] projection) {
			return cursor(projection, new String[] {
					DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
					DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
					DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
			}, rootValues());
		}
		@Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
			if (ROOT_ID.equals(documentId)) {
				return cursor(projection, documentColumns(), documentValues(ROOT_ID, "Test vault",
						DocumentsContract.Document.MIME_TYPE_DIR, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE, 0));
			}
			if (VIDEO_ID.equals(documentId)) {
				return cursor(projection, documentColumns(), documentValues(VIDEO_ID, "encrypted-movie.bin",
						"application/octet-stream", 0, ciphertextFile.length()));
			}
			throw new FileNotFoundException(documentId);
		}
		@Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
				throws FileNotFoundException {
			MatrixCursor result = new MatrixCursor(projection == null ? documentColumns() : projection);
			if (!ROOT_ID.equals(parentDocumentId)) return result;
			Map<String, Object> values = documentValues(VIDEO_ID, "encrypted-movie.bin", "application/octet-stream", 0, ciphertextFile.length());
			addRow(result, projection == null ? documentColumns() : projection, values);
			return result;
		}
		@Override public boolean isChildDocument(String parentDocumentId, String documentId) {
			childChecks++;
			return ROOT_ID.equals(parentDocumentId) && VIDEO_ID.equals(documentId);
		}
		@Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
				throws FileNotFoundException {
			if (!VIDEO_ID.equals(documentId) || !"r".equals(mode)) throw new FileNotFoundException(documentId);
			lastOpenMode = mode;
			return ParcelFileDescriptor.open(ciphertextFile, ParcelFileDescriptor.MODE_READ_ONLY);
		}

		private Map<String, Object> rootValues() {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
			values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
			values.put(DocumentsContract.Root.COLUMN_TITLE, "Test vault");
			values.put(DocumentsContract.Root.COLUMN_FLAGS, 0);
			values.put(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
			values.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 1_000_000L);
			return values;
		}
		private static String[] documentColumns() {
			return new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
					DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
					DocumentsContract.Document.COLUMN_SIZE};
		}
		private static Map<String, Object> documentValues(String id, String name, String mime, int flags, long size) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
			values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
			values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, mime);
			values.put(DocumentsContract.Document.COLUMN_FLAGS, flags);
			values.put(DocumentsContract.Document.COLUMN_SIZE, size);
			return values;
		}
		private static Cursor cursor(String[] projection, String[] defaults, Map<String, Object> values) {
			String[] columns = projection == null ? defaults : projection;
			MatrixCursor result = new MatrixCursor(columns);
			addRow(result, columns, values);
			return result;
		}
		private static void addRow(MatrixCursor cursor, String[] columns, Map<String, Object> values) {
			Object[] row = new Object[columns.length];
			for (int i = 0; i < columns.length; i++) row[i] = values.get(columns[i]);
			cursor.addRow(row);
		}
	}
}
