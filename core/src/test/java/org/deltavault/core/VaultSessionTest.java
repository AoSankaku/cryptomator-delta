package org.deltavault.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class VaultSessionTest {
	@Rule public TemporaryFolder localFolders = new TemporaryFolder();
	private static final char[] PASSWORD = "test passphrase".toCharArray();
	private static Masterkey fixtureMasterkey;
	private static byte[] fixtureMasterkeyFile;

	@BeforeClass
	public static void createMasterkeyFixture() throws Exception {
		SecureRandom random = new SecureRandom();
		fixtureMasterkey = Masterkey.generate(random);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new MasterkeyFileAccess(new byte[0], random).persist(fixtureMasterkey, output, java.nio.CharBuffer.wrap(PASSWORD), 999);
		fixtureMasterkeyFile = output.toByteArray();
	}

	@AfterClass
	public static void destroyFixtureKey() {
		if (fixtureMasterkey != null && !fixtureMasterkey.isDestroyed()) {
			fixtureMasterkey.destroy();
		}
		Arrays.fill(PASSWORD, '\0');
		if (fixtureMasterkeyFile != null) {
			Arrays.fill(fixtureMasterkeyFile, (byte) 0);
		}
	}

	@Test
	public void roundTripsBothSupportedContentSchemesAndFormat8LongNames() throws Exception {
		for (String combo : List.of("SIV_GCM", "SIV_CTRMAC")) {
			MemoryStore store = newVault(combo, fixtureMasterkeyFile);
			try (VaultSession session = VaultSession.unlock(store, PASSWORD.clone())) {
				byte[] expected = ("content for " + combo).getBytes(StandardCharsets.UTF_8);
				session.writeNew("", "notes.txt", expected);
				VaultSession.Node file = find(session.list(""), "notes.txt");
				VaultSession.ReadFile read = session.read(file);
				assertArrayEquals(expected, read.bytes);
				byte[] returnedBytes = read.bytes;
				read.close();
				assertArrayEquals(new byte[expected.length], returnedBytes);

				if ("SIV_GCM".equals(combo)) {
					session.createDirectory("", "nested");
					VaultSession.Node directory = find(session.list(""), "nested");
					assertTrue(directory.directory);
					assertNotNull(directory.directoryId);
					assertTrue(session.list(directory.directoryId).isEmpty());

					String fullHash = hashDirectoryId(combo, directory.directoryId);
					String expectedPhysicalPath = "d/" + fullHash.substring(0, 2) + "/" + fullHash.substring(2);
					assertEquals(expectedPhysicalPath, directoryPathForId(combo, directory.directoryId));
					assertTrue(store.paths().contains(expectedPhysicalPath));
					assertEquals(30, expectedPhysicalPath.substring(expectedPhysicalPath.lastIndexOf('/') + 1).length());

					String longName = "long-" + "x".repeat(230) + ".txt";
					byte[] longFile = new byte[] { 0, 1, 2, 3, (byte) 0xff };
					session.writeNew(directory.directoryId, longName, longFile);
					VaultSession.Node shortEntry = find(session.list(directory.directoryId), longName);
					assertTrue(shortEntry.path.endsWith(".c9s"));
					try (VaultSession.ReadFile longRead = session.read(shortEntry)) {
						assertArrayEquals(longFile, longRead.bytes);
					}
				}
			}
		}
	}

	@Test
	public void localVaultRoundTripsFilesFoldersAndRecoverableEditsWithChangingDocumentIds() throws Exception {
		for (String combo : List.of("SIV_GCM", "SIV_CTRMAC")) {
			Path root = localFolders.newFolder(combo).toPath();
			Files.write(root.resolve("masterkey.cryptomator"), fixtureMasterkeyFile);
			Files.write(root.resolve("vault.cryptomator"), vaultConfig(combo));
			Files.createDirectories(root.resolve(directoryPathForId(combo, "")));
			LocalStore store = new LocalStore(new DiskTree(root));
			try (VaultSession session = VaultSession.unlock(store, PASSWORD.clone())) {
				session.writeNew("", "local.txt", "first".getBytes(StandardCharsets.UTF_8));
				VaultSession.Node node = find(session.list(""), "local.txt");
				String etag;
				try (VaultSession.ReadFile read = session.read(node)) { etag = read.etag; }
				session.overwrite(node, "edited".getBytes(StandardCharsets.UTF_8), etag);
				assertThrows(IOException.class, () -> session.overwrite(node, new byte[]{0}, etag));
				assertEquals(1, session.list("").size());
				try (VaultSession.ReadFile read = session.read(node)) {
					assertArrayEquals("edited".getBytes(StandardCharsets.UTF_8), read.bytes);
				}
				assertTrue(store.list(directoryPathForId(combo, "")).stream()
						.anyMatch(entry -> LocalStore.isRecoveryName(entry.path.substring(entry.path.lastIndexOf('/') + 1))));
				session.createDirectory("", "nested");
				VaultSession.Node directory = find(session.list(""), "nested");
				String longName = "local-" + "x".repeat(230) + ".txt";
				session.writeNew(directory.directoryId, longName, new byte[]{1, 2, 3});
				VaultSession.Node longNode = find(session.list(directory.directoryId), longName);
				try (VaultSession.ReadFile read = session.read(longNode)) {
					assertArrayEquals(new byte[]{1, 2, 3}, read.bytes);
					session.overwrite(longNode, new byte[]{4, 5}, read.etag);
				}
				try (VaultSession.ReadFile read = session.read(longNode)) { assertArrayEquals(new byte[]{4, 5}, read.bytes); }
				store.put(longNode.path + "/" + UUID.randomUUID() + ".upload", new byte[]{1, 7}, null, true);
				assertEquals(longName, session.list(directory.directoryId).get(0).name);
				try (VaultSession reopened = VaultSession.unlock(new LocalStore(new DiskTree(root)), PASSWORD.clone())) {
					VaultSession.Node reopenedDirectory = find(reopened.list(""), "nested");
					assertEquals(longName, reopened.list(reopenedDirectory.directoryId).get(0).name);
				}
			}
		}
	}

	@Test
	public void rejectsWrongPasswordTamperedConfigAndUnsafeKdfMetadataBeforeUnlock() throws Exception {
		MemoryStore store = newVault("SIV_GCM", fixtureMasterkeyFile);
		assertThrows(IOException.class, () -> VaultSession.unlock(store, "wrong password".toCharArray()));

		String token = new String(store.bytes("vault.cryptomator"), StandardCharsets.UTF_8);
		String[] parts = token.split("\\.");
		byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
		signature[0] ^= 1;
		parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		store.replace("vault.cryptomator", String.join(".", parts).getBytes(StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> VaultSession.unlock(store, PASSWORD.clone()));
		store.close();

		JsonObject expensive = JsonParser.parseString(new String(fixtureMasterkeyFile, StandardCharsets.UTF_8)).getAsJsonObject();
		expensive.addProperty("scryptCostParam", 131_072);
		MemoryStore expensiveStore = newVault("SIV_GCM", expensive.toString().getBytes(StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> VaultSession.unlock(expensiveStore, PASSWORD.clone()));
		expensiveStore.close();

		JsonObject deeplyNested = JsonParser.parseString(new String(fixtureMasterkeyFile, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonArray nested = new JsonArray();
		JsonArray level = nested;
		for (int i = 0; i < 40; i++) {
			JsonArray next = new JsonArray();
			level.add(next);
			level = next;
		}
		level.add(true);
		deeplyNested.add("untrusted", nested);
		MemoryStore nestedStore = newVault("SIV_GCM", deeplyNested.toString().getBytes(StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> VaultSession.unlock(nestedStore, PASSWORD.clone()));
		nestedStore.close();
	}

	@Test
	public void usesConditionalOverwriteWipesReadsAndRejectsForeignOrClosedNodes() throws Exception {
		MemoryStore firstStore = newVault("SIV_CTRMAC", fixtureMasterkeyFile);
		VaultSession first = VaultSession.unlock(firstStore, PASSWORD.clone());
		byte[] original = "original".getBytes(StandardCharsets.UTF_8);
		first.writeNew("", "file.txt", original);
		VaultSession.Node node = find(first.list(""), "file.txt");
		VaultSession.ReadFile initial = first.read(node);
		String etag = initial.etag;
		assertTrue(etag.startsWith("\"") && etag.endsWith("\""));
		initial.close();
		assertArrayEquals(new byte[original.length], initial.bytes);
		assertThrows(IOException.class, () -> first.overwrite(node, original, "W/\"weak\""));

		first.overwrite(node, "updated".getBytes(StandardCharsets.UTF_8), etag);
		assertThrows(IOException.class, () -> first.overwrite(node, original, etag));
		try (VaultSession.ReadFile updated = first.read(node)) {
			assertArrayEquals("updated".getBytes(StandardCharsets.UTF_8), updated.bytes);
		}

		MemoryStore secondStore = newVault("SIV_CTRMAC", fixtureMasterkeyFile);
		try (VaultSession second = VaultSession.unlock(secondStore, PASSWORD.clone())) {
			assertThrows(IOException.class, () -> second.read(node));
		}

		firstStore.flipFirstByte(node.path);
		assertThrows(IOException.class, () -> first.read(node));
		first.close();
		assertThrows(IOException.class, () -> first.read(node));
		assertThrows(IOException.class, () -> first.list(""));
	}

	@Test
	public void failedStagedPublicationDoesNotExposeAnIncompleteShortenedNode() throws Exception {
		MemoryStore store = newVault("SIV_GCM", fixtureMasterkeyFile);
		try (VaultSession session = VaultSession.unlock(store, PASSWORD.clone())) {
			store.failNextMove = true;
			String longName = "failed-" + "y".repeat(240);
			assertThrows(IOException.class, () -> session.writeNew("", longName, new byte[] { 1 }));
			assertTrue(session.list("").isEmpty());
			assertTrue(store.paths().stream().anyMatch(path -> path.matches(".*/[0-9a-fA-F-]{36}\\.upload(/.*)?")));
		}
	}

	@Test
	public void rejectsDirectoryIdsOutsideAsciiAndThirtySixCharacterBound() throws Exception {
		MemoryStore store = newVault("SIV_GCM", fixtureMasterkeyFile);
		try (VaultSession session = VaultSession.unlock(store, PASSWORD.clone())) {
			assertThrows(IOException.class, () -> session.list("a".repeat(37)));
			assertThrows(IOException.class, () -> session.list("\u00e9"));
			assertThrows(IOException.class, () -> session.writeNew("", "x".repeat(256), new byte[] { 1 }));
		}
	}

	private static VaultSession.Node find(List<VaultSession.Node> nodes, String name) {
		return nodes.stream().filter(node -> name.equals(node.name)).findFirst().orElseThrow();
	}

	private static MemoryStore newVault(String combo, byte[] masterkeyFile) throws Exception {
		MemoryStore store = new MemoryStore();
		store.putInitial("masterkey.cryptomator", masterkeyFile);
		store.putInitial("vault.cryptomator", vaultConfig(combo));
		store.mkdir("d");
		String rootHash = hashDirectoryId(combo, "");
		store.mkdir("d/" + rootHash.substring(0, 2));
		store.mkdir("d/" + rootHash.substring(0, 2) + "/" + rootHash.substring(2));
		return store;
	}

	private static String directoryPathForId(String combo, String id) throws Exception {
		String hash = hashDirectoryId(combo, id);
		return "d/" + hash.substring(0, 2) + "/" + hash.substring(2);
	}

	private static String hashDirectoryId(String combo, String id) throws Exception {
		Masterkey copy = fixtureMasterkey.copy();
		Cryptor cryptor = null;
		try {
			CryptorProvider.Scheme scheme = "SIV_GCM".equals(combo) ? CryptorProvider.Scheme.SIV_GCM : CryptorProvider.Scheme.SIV_CTRMAC;
			cryptor = CryptorProvider.forScheme(scheme).provide(copy, new SecureRandom());
			return cryptor.fileNameCryptor().hashDirectoryId(id);
		} finally {
			if (cryptor != null) {
				cryptor.destroy();
			}
			if (!copy.isDestroyed()) {
				copy.destroy();
			}
		}
	}

	private static byte[] vaultConfig(String combo) throws Exception {
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"kid\":\"masterkeyfile:masterkey.cryptomator\",\"typ\":\"JWT\",\"alg\":\"HS256\"}").getBytes(StandardCharsets.UTF_8));
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"format\":8,\"cipherCombo\":\"" + combo + "\",\"shorteningThreshold\":220,\"jti\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(fixtureMasterkey.getEncoded(), "HmacSHA256"));
		byte[] signature = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
		try {
			String jwt = header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
			return jwt.getBytes(StandardCharsets.US_ASCII);
		} finally {
			Arrays.fill(signature, (byte) 0);
		}
	}

	/** Path-derived opaque IDs intentionally change when a file or directory is renamed. */
	private static final class DiskTree implements LocalStore.DocumentTree {
		private final Path root;
		private boolean closed;
		DiskTree(Path root) { this.root = root.toAbsolutePath().normalize(); }
		@Override public String rootId() { return "vault-root"; }
		private Path path(String id) throws IOException {
			if (closed) throw new IOException("Closed tree");
			Path value = "vault-root".equals(id) ? root : root.resolve(id).normalize();
			if (!value.startsWith(root)) throw new IOException("Outside tree");
			return value;
		}
		private LocalStore.Document document(Path path) {
			return new LocalStore.Document(root.relativize(path).toString().replace('\\', '/'), path.getFileName().toString(), Files.isDirectory(path));
		}
		@Override public List<LocalStore.Document> children(String id) throws IOException {
			try (var children = Files.list(path(id))) { return children.map(this::document).toList(); }
		}
		@Override public InputStream read(String id) throws IOException { return Files.newInputStream(path(id)); }
		@Override public void write(String id, byte[] bytes) throws IOException { Files.write(path(id), bytes); }
		@Override public LocalStore.Document create(String parent, String name, boolean directory) throws IOException {
			Path created = path(parent).resolve(name);
			return document(directory ? Files.createDirectory(created) : Files.createFile(created));
		}
		@Override public LocalStore.Document rename(String id, String name) throws IOException {
			Path source = path(id); return document(Files.move(source, source.resolveSibling(name)));
		}
		@Override public void delete(String id) throws IOException { Files.delete(path(id)); }
		@Override public void close() { closed = true; }
	}

	private static final class MemoryStore implements RemoteStore {
		private final Set<String> directories = new LinkedHashSet<>();
		private final Map<String, Stored> files = new LinkedHashMap<>();
		private int nextEtag = 1;
		private boolean closed;
		private boolean failNextMove;

		MemoryStore() {
			directories.add("");
		}

		void putInitial(String path, byte[] bytes) {
			files.put(path, new Stored(bytes.clone(), "\"" + nextEtag++ + "\""));
		}

		byte[] bytes(String path) {
			return files.get(path).bytes.clone();
		}

		void replace(String path, byte[] bytes) {
			Stored old = files.get(path);
			Arrays.fill(old.bytes, (byte) 0);
			files.put(path, new Stored(bytes.clone(), "\"" + nextEtag++ + "\""));
		}

		void flipFirstByte(String path) {
			Stored stored = files.get(path);
			assertNotNull(stored);
			stored.bytes[0] ^= 1;
		}

		Set<String> paths() {
			Set<String> result = new LinkedHashSet<>(directories);
			result.addAll(files.keySet());
			return result;
		}

		@Override
		public Blob get(String path, int maxBytes) throws IOException {
			ensureOpen();
			Stored stored = files.get(path);
			if (stored == null) {
				throw new IOException("Missing object " + path);
			}
			if (stored.bytes.length > maxBytes) {
				throw new IOException("Object too large");
			}
			return new Blob(stored.bytes.clone(), stored.etag);
		}

		@Override
		public List<Entry> list(String path) throws IOException {
			ensureOpen();
			if (!directories.contains(path)) {
				throw new IOException("Missing directory " + path);
			}
			List<Entry> result = new ArrayList<>();
			for (String directory : directories) {
				if (isDirectChild(path, directory)) {
					result.add(new Entry(directory, true));
				}
			}
			for (String file : files.keySet()) {
				if (isDirectChild(path, file)) {
					result.add(new Entry(file, false));
				}
			}
			return result;
		}

		@Override
		public void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException {
			ensureOpen();
			String parent = parent(path);
			if (!directories.contains(parent) || directories.contains(path)) {
				throw new IOException("Invalid file parent");
			}
			Stored old = files.get(path);
			if (createOnly) {
				if (old != null) {
					throw new IOException("Already exists");
				}
			} else if (old == null || !old.etag.equals(expectedEtag)) {
				throw new IOException("Conditional update failed");
			}
			if (old != null) {
				Arrays.fill(old.bytes, (byte) 0);
			}
			files.put(path, new Stored(bytes.clone(), "\"" + nextEtag++ + "\""));
		}

		@Override
		public void mkdir(String path) throws IOException {
			ensureOpen();
			if (directories.contains(path)) {
				return;
			}
			if (files.containsKey(path) || !directories.contains(parent(path))) {
				throw new IOException("Invalid directory parent or existing file");
			}
			directories.add(path);
		}

		@Override
		public void move(String sourcePath, String destinationPath) throws IOException {
			ensureOpen();
			if (failNextMove) {
				failNextMove = false;
				throw new IOException("Simulated MOVE failure");
			}
			if (!directories.contains(sourcePath) || directories.contains(destinationPath) || files.containsKey(destinationPath)
					|| !directories.contains(parent(destinationPath))) {
				throw new IOException("Invalid or existing MOVE destination");
			}
			List<String> oldDirectories = directories.stream().filter(path -> path.equals(sourcePath) || path.startsWith(sourcePath + "/"))
					.sorted(Comparator.comparingInt(String::length)).toList();
			List<Map.Entry<String, Stored>> oldFiles = files.entrySet().stream()
					.filter(entry -> entry.getKey().startsWith(sourcePath + "/"))
					.map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
			directories.removeAll(oldDirectories);
			for (String old : oldDirectories) {
				directories.add(destinationPath + old.substring(sourcePath.length()));
			}
			for (Map.Entry<String, Stored> entry : oldFiles) {
				files.remove(entry.getKey());
				files.put(destinationPath + entry.getKey().substring(sourcePath.length()), entry.getValue());
			}
		}

		@Override
		public void close() {
			closed = true;
			for (Stored stored : files.values()) {
				Arrays.fill(stored.bytes, (byte) 0);
			}
			files.clear();
			directories.clear();
		}

		private void ensureOpen() throws IOException {
			if (closed) {
				throw new IOException("Store is closed");
			}
		}

		private static String parent(String path) {
			int slash = path.lastIndexOf('/');
			return slash < 0 ? "" : path.substring(0, slash);
		}

		private static boolean isDirectChild(String parent, String path) {
			if (path.equals(parent) || !path.startsWith(parent.isEmpty() ? "" : parent + "/")) {
				return false;
			}
			String remainder = path.substring(parent.isEmpty() ? 0 : parent.length() + 1);
			return !remainder.isEmpty() && remainder.indexOf('/') < 0;
		}

		private static final class Stored {
			final byte[] bytes;
			final String etag;

			Stored(byte[] bytes, String etag) {
				this.bytes = bytes;
				this.etag = etag;
			}
		}
	}
}
