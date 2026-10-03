package org.deltavault.core;

import com.google.common.io.BaseEncoding;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel;
import org.cryptomator.cryptolib.common.EncryptingReadableByteChannel;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An unlocked view of a password-protected Cryptomator format-8 vault. */
public final class VaultSession implements AutoCloseable {
	private static final String MASTERKEY_PATH = "masterkey.cryptomator";
	private static final String VAULT_CONFIG_PATH = "vault.cryptomator";
	private static final String DATA_PATH = "d";
	private static final int MAX_MASTERKEY_BYTES = 64 * 1024;
	private static final int MAX_CONFIG_BYTES = 16 * 1024;
	private static final int MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024;
	private static final int MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 1024 * 1024;
	private static final int MAX_PLAINTEXT_NAME_BYTES = 255;
	private static final int MAX_CIPHERTEXT_NAME_BYTES = 16 * 1024;
	private static final int MAX_DIRECTORY_ENTRIES = 10_000;
	private static final BaseEncoding BASE64_URL = BaseEncoding.base64Url();
	private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

	private final RemoteStore store;
	private final Masterkey masterkey;
	private final Cryptor cryptor;
	private final int shorteningThreshold;
	private final Object streamsGate = new Object();
	private final Object closeGate = new Object();
	private final Set<VaultStream> activeStreams = new HashSet<>();
	private volatile boolean closed;

	private VaultSession(RemoteStore store, Masterkey masterkey, Cryptor cryptor, int shorteningThreshold) {
		this.store = store;
		this.masterkey = masterkey;
		this.cryptor = cryptor;
		this.shorteningThreshold = shorteningThreshold;
	}

	/**
	 * Unlocks a format-8 PASSWORD vault. Hub keys and unknown key identifiers are rejected.
	 * Scrypt parameters are bounded before the cryptolib key loader is invoked.
	 */
	public static VaultSession unlock(RemoteStore store, char[] password) throws IOException {
		if (store == null || password == null) {
			throw new IllegalArgumentException("store and password are required");
		}
		Masterkey masterkey = null;
		Cryptor cryptor = null;
		try {
			byte[] masterkeyBytes = readBounded(store, MASTERKEY_PATH, MAX_MASTERKEY_BYTES);
			try {
				VaultConfig.preflightMasterkey(masterkeyBytes);
				MasterkeyFileAccess keyFileAccess = new MasterkeyFileAccess(new byte[0], new SecureRandom());
				masterkey = keyFileAccess.load(new ByteArrayInputStream(masterkeyBytes), java.nio.CharBuffer.wrap(password));
			} finally {
				Arrays.fill(masterkeyBytes, (byte) 0);
			}

			byte[] configBytes = readBounded(store, VAULT_CONFIG_PATH, MAX_CONFIG_BYTES);
			VaultConfig config;
			try {
				config = VaultConfig.authenticate(configBytes, masterkey);
			} finally {
				Arrays.fill(configBytes, (byte) 0);
			}

			CryptorProvider.Scheme scheme = switch (config.cipherCombo) {
				case "SIV_GCM" -> CryptorProvider.Scheme.SIV_GCM;
				case "SIV_CTRMAC" -> CryptorProvider.Scheme.SIV_CTRMAC;
				default -> throw new IOException("Unsupported Cryptomator cipher combo: " + config.cipherCombo);
			};
			cryptor = CryptorProvider.forScheme(scheme).provide(masterkey, new SecureRandom());
			return new VaultSession(store, masterkey, cryptor, config.shorteningThreshold);
		} catch (IOException | RuntimeException e) {
			if (cryptor != null) {
				cryptor.destroy();
			}
			if (masterkey != null && !masterkey.isDestroyed()) {
				masterkey.destroy();
			}
			if (e instanceof IOException ioException) {
				throw ioException;
			}
			throw new IOException("Unable to unlock Cryptomator vault", e);
		}
	}

	/** A decrypted file or directory entry. Directory IDs are present only for directories. */
	public static final class Node {
		public final String name;
		/** Relative encrypted object path in the vault. */
		public final String path;
		public final boolean directory;
		public final String directoryId;
		private final String contentPath;
		private final String parentDirectoryId;
		private final String cipherName;
		private final VaultSession owner;

		private Node(VaultSession owner, String name, String path, boolean directory, String directoryId, String contentPath, String parentDirectoryId, String cipherName) {
			this.owner = owner;
			this.name = name;
			this.path = path;
			this.directory = directory;
			this.directoryId = directoryId;
			this.contentPath = contentPath;
			this.parentDirectoryId = parentDirectoryId;
			this.cipherName = cipherName;
		}
	}

	/** Decrypted file data. Call close when finished to overwrite its plaintext bytes. */
	public static final class ReadFile implements AutoCloseable {
		public final byte[] bytes;
		public final String etag;
		private boolean closed;

		private ReadFile(byte[] bytes, String etag) {
			this.bytes = bytes;
			this.etag = etag;
		}

		@Override
		public void close() {
			if (!closed) {
				Arrays.fill(bytes, (byte) 0);
				closed = true;
			}
		}
	}

	/** Lists one Cryptomator directory using its cleartext directory ID (empty for the root). */
	public List<Node> list(String directoryId) throws IOException {
		ensureOpen();
		validateDirectoryId(directoryId);
		String dataDirectory = dataDirectoryPath(directoryId);
		List<RemoteStore.Entry> entries = store.list(dataDirectory);
		if (entries.size() > MAX_DIRECTORY_ENTRIES) {
			throw new IOException("Directory contains too many encrypted entries");
		}
		List<Node> result = new ArrayList<>();
		Set<String> clearNames = new HashSet<>();
		for (RemoteStore.Entry entry : entries) {
			String basename = directChildName(dataDirectory, entry.path);
            if ("dirid.c9r".equals(basename)) {
                continue; // Optional recovery metadata is not a user-visible node.
            }
            if (LocalStore.isRecoveryName(basename)) {
                continue; // Encrypted, recoverable prior versions from local SAF updates.
            }
		if (basename.matches(UUID_REGEX + "\\.upload")) {
				continue; // Incomplete staged entries are never visible as vault nodes.
			}
			if (basename.endsWith(".c9r")) {
				Node node = decodeRegularEntry(dataDirectory, directoryId, basename, entry);
				if (!clearNames.add(node.name)) {
					throw new IOException("Duplicate decrypted filename in vault directory");
				}
				result.add(node);
			} else if (basename.endsWith(".c9s")) {
				Node node = decodeShortenedEntry(dataDirectory, directoryId, basename, entry);
				if (!clearNames.add(node.name)) {
					throw new IOException("Duplicate decrypted filename in vault directory");
				}
				result.add(node);
			} else {
				throw new IOException("Unsupported object in Cryptomator data directory: " + basename);
			}
		}
		ensureOpen();
		return Collections.unmodifiableList(new ArrayList<>(result));
	}

	/** Downloads and decrypts one file entirely in memory. The returned bytes are wiped by ReadFile.close(). */
	public ReadFile read(Node node) throws IOException {
		ensureOpen();
		validateNode(node);
		if (node.directory) {
			throw new IOException("Cannot read a directory as a file");
		}
		RemoteStore.Blob blob = store.get(node.contentPath, MAX_CIPHERTEXT_BYTES);
		byte[] ciphertext;
		String etag;
		try (blob) {
			if (blob.bytes.length > MAX_CIPHERTEXT_BYTES) {
				throw new IOException("Encrypted file exceeds the size limit");
			}
			ciphertext = blob.bytes.clone();
			etag = blob.etag;
		}
		try {
			byte[] cleartext = decryptContent(ciphertext);
			if (closed) {
				Arrays.fill(cleartext, (byte) 0);
				throw new IOException("Vault session was closed while reading");
			}
			return new ReadFile(cleartext, etag);
		} finally {
			Arrays.fill(ciphertext, (byte) 0);
		}
	}

	/** Opens an authenticated, seekable plaintext stream backed by bounded ciphertext ranges. */
	public VaultStream openStream(Node node) throws IOException {
		ensureOpen();
		validateNode(node);
		if (node.directory) {
			throw new IOException("Cannot stream a directory as a file");
		}
		VaultStream stream = new VaultStream(this, cryptor);
		synchronized (streamsGate) {
			ensureOpen();
			activeStreams.add(stream);
		}
		try {
			stream.initialize(store.openReader(node.contentPath));
			return stream;
		} catch (IOException e) {
			stream.close();
			throw e;
		} catch (RuntimeException e) {
			stream.close();
			throw new IOException("Unable to open encrypted file stream", e);
		}
	}

	/** Encrypts and creates a new file. Existing remote data is never overwritten. */
	public void writeNew(String directoryId, String name, byte[] plain) throws IOException {
		ensureOpen();
		validateDirectoryId(directoryId);
		String safeName = normalizeAndValidateName(name);
		validatePlaintext(plain);
		String encryptedFullName = encryptName(safeName, directoryId);
		String parentPath = dataDirectoryPath(directoryId);
		String entryName = storedEntryName(encryptedFullName);
		ensureAbsent(parentPath, entryName);
		byte[] ciphertext = encryptContent(plain);
		try {
			ensureOpen();
			if (entryName.endsWith(".c9r")) {
				store.put(parentPath + "/" + entryName, ciphertext, null, true);
			} else {
				String stagePath = createUploadStage(parentPath);
				byte[] nameBytes = encryptedFullName.getBytes(StandardCharsets.UTF_8);
				try {
					store.put(stagePath + "/name.c9s", nameBytes, null, true);
					store.put(stagePath + "/contents.c9r", ciphertext, null, true);
					ensureOpen();
					store.move(stagePath, parentPath + "/" + entryName);
				} finally {
					Arrays.fill(nameBytes, (byte) 0);
				}
			}
		} finally {
			Arrays.fill(ciphertext, (byte) 0);
		}
	}

	/** Re-encrypts and conditionally replaces a file using the strong ETag returned by read(). */
	public void overwrite(Node node, byte[] plain, String etag) throws IOException {
		ensureOpen();
		validateNode(node);
		if (node.directory) {
			throw new IOException("Cannot overwrite a directory as a file");
		}
		validatePlaintext(plain);
		validateStrongEtag(etag);
		byte[] ciphertext = encryptContent(plain);
		try {
			ensureOpen();
			store.put(node.contentPath, ciphertext, etag, false);
		} finally {
			Arrays.fill(ciphertext, (byte) 0);
		}
	}

	/** Creates an empty directory and stores its cleartext ID in the format-8 dir.c9r marker. */
	public void createDirectory(String parentDirId, String name) throws IOException {
		ensureOpen();
		validateDirectoryId(parentDirId);
		String safeName = normalizeAndValidateName(name);
		String encryptedFullName = encryptName(safeName, parentDirId);
		String parentPath = dataDirectoryPath(parentDirId);
		String entryName = storedEntryName(encryptedFullName);
		ensureAbsent(parentPath, entryName);

		String newDirectoryId = UUID.randomUUID().toString();
		String newDataDirectory = dataDirectoryPath(newDirectoryId);
		String parentHash = cryptor.fileNameCryptor().hashDirectoryId(newDirectoryId);
		String prefixPath = DATA_PATH + "/" + parentHash.substring(0, 2);
		store.mkdir(DATA_PATH);
		store.mkdir(prefixPath); // Existing hash-prefix collections are expected.
		if (containsChild(prefixPath, parentHash.substring(2))) {
			throw new IOException("Generated directory ID already exists in the vault");
		}
		store.mkdir(newDataDirectory);

		String stagePath = createUploadStage(parentPath);
		if (entryName.endsWith(".c9s")) {
			byte[] nameBytes = encryptedFullName.getBytes(StandardCharsets.UTF_8);
			try {
				store.put(stagePath + "/name.c9s", nameBytes, null, true);
			} finally {
				Arrays.fill(nameBytes, (byte) 0);
			}
		}
		// Publish the folder only after its complete metadata has been uploaded.
		byte[] idBytes = newDirectoryId.getBytes(StandardCharsets.UTF_8);
		try {
			store.put(stagePath + "/dir.c9r", idBytes, null, true);
			ensureOpen();
			store.move(stagePath, parentPath + "/" + entryName);
		} finally {
			Arrays.fill(idBytes, (byte) 0);
		}
	}

	@Override
	public void close() {
		synchronized (closeGate) {
			if (closed) return;
			List<VaultStream> streams;
			synchronized (streamsGate) {
				closed = true;
				streams = new ArrayList<>(activeStreams);
				activeStreams.clear();
			}
			for (VaultStream stream : streams) {
				try {
					stream.close();
				} catch (RuntimeException ignored) {
					// Continue destroying session keys even if a storage provider misbehaves during close.
				}
			}
			try {
				cryptor.destroy();
			} finally {
				try {
					if (!masterkey.isDestroyed()) {
						masterkey.destroy();
					}
				} finally {
					store.close();
				}
			}
		}
	}

	boolean isOpenForStream() {
		return !closed;
	}

	void unregisterStream(VaultStream stream) {
		synchronized (streamsGate) {
			activeStreams.remove(stream);
		}
	}

	private Node decodeRegularEntry(String parentPath, String parentId, String entryName, RemoteStore.Entry entry) throws IOException {
		String cipherBase = stripSuffix(entryName, ".c9r");
		String clearName = decryptName(cipherBase, parentId);
		if (!entry.directory) {
			return new Node(this, clearName, entry.path, false, null, entry.path, parentId, cipherBase);
		}
		List<RemoteStore.Entry> children = listInternal(entry.path);
		if (hasChild(children, "symlink.c9r")) {
			throw unsupportedSymlink(clearName);
		}
		RemoteStore.Entry marker = findChild(children, "dir.c9r");
		if (marker == null || marker.directory || children.size() != 1) {
			throw new IOException("Malformed Cryptomator directory entry: " + entryName);
		}
		String childId = readDirectoryId(marker.path);
		return new Node(this, clearName, entry.path, true, childId, null, parentId, cipherBase);
	}

	private Node decodeShortenedEntry(String parentPath, String parentId, String entryName, RemoteStore.Entry entry) throws IOException {
		if (!entry.directory) {
			throw new IOException("Malformed shortened Cryptomator entry: " + entryName);
		}
		List<RemoteStore.Entry> children = listInternal(entry.path);
		RemoteStore.Entry nameEntry = findChild(children, "name.c9s");
		if (nameEntry == null || nameEntry.directory) {
			throw new IOException("Shortened Cryptomator entry has no name mapping: " + entryName);
		}
		String encryptedFullName = readUtf8Object(nameEntry.path, MAX_CIPHERTEXT_NAME_BYTES);
		if (!encryptedFullName.endsWith(".c9r") || encryptedFullName.length() <= shorteningThreshold) {
			throw new IOException("Invalid shortened Cryptomator filename mapping");
		}
		if (!entryName.equals(shortenedEntryName(encryptedFullName))) {
			throw new IOException("Shortened filename hash does not match its mapping");
		}
		String cipherBase = stripSuffix(encryptedFullName, ".c9r");
		String clearName = decryptName(cipherBase, parentId);
		if (hasChild(children, "symlink.c9r")) {
			throw unsupportedSymlink(clearName);
		}
		RemoteStore.Entry dirMarker = findChild(children, "dir.c9r");
		RemoteStore.Entry contents = findChild(children, "contents.c9r");
		if (dirMarker != null && contents != null) {
			throw new IOException("Malformed shortened Cryptomator entry has multiple node types");
		}
		if (dirMarker != null) {
			if (dirMarker.directory || children.size() != 2) {
				throw new IOException("Malformed directory marker");
			}
			String childId = readDirectoryId(dirMarker.path);
			return new Node(this, clearName, entry.path, true, childId, null, parentId, cipherBase);
		}
		if (contents != null) {
			if (contents.directory || children.size() != 2) {
				throw new IOException("Malformed contents object");
			}
			return new Node(this, clearName, entry.path, false, null, contents.path, parentId, cipherBase);
		}
		throw new IOException("Unsupported or incomplete shortened Cryptomator entry: " + entryName);
	}

	private List<RemoteStore.Entry> listInternal(String path) throws IOException {
		List<RemoteStore.Entry> entries = store.list(path);
		if (entries.size() > MAX_DIRECTORY_ENTRIES) {
			throw new IOException("Encrypted object contains too many children");
		}
		Set<String> names = new HashSet<>();
		List<RemoteStore.Entry> visible = new ArrayList<>();
		for (RemoteStore.Entry child : entries) {
			String name = directChildName(path, child.path);
			if (!names.add(name)) {
				throw new IOException("Duplicate object in encrypted entry");
			}
			if (!child.directory && (LocalStore.isRecoveryName(name) || name.matches(UUID_REGEX + "\\.upload"))) continue;
			visible.add(child);
		}
		return visible;
	}

	private String readDirectoryId(String path) throws IOException {
		String id = readUtf8Object(path, 36);
		if (id.isEmpty()) {
			throw new IOException("Cryptomator child directory ID must not be empty");
		}
		validateDirectoryId(id);
		return id;
	}

	private String readUtf8Object(String path, int maxBytes) throws IOException {
		byte[] bytes = readBounded(store, path, maxBytes);
		try {
			return decodeUtf8(bytes);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	private byte[] decryptContent(byte[] ciphertext) throws IOException {
		WipingByteArrayOutputStream output = new WipingByteArrayOutputStream(Math.min(MAX_PLAINTEXT_BYTES, ciphertext.length), MAX_PLAINTEXT_BYTES);
		byte[] scratch = new byte[8192];
		try (ReadableByteChannel decrypted = new DecryptingReadableByteChannel(Channels.newChannel(new ByteArrayInputStream(ciphertext)), cryptor, true)) {
			ByteBuffer buffer = ByteBuffer.wrap(scratch);
			while (true) {
				buffer.clear();
				int count = decrypted.read(buffer);
				if (count < 0) {
					break;
				}
				if (count == 0) {
					continue;
				}
				if (output.size() + count > MAX_PLAINTEXT_BYTES) {
					throw new IOException("Decrypted file exceeds the plaintext size limit");
				}
				output.write(scratch, 0, count);
			}
			return output.toByteArray();
		} catch (RuntimeException e) {
			throw new IOException("Encrypted file authentication or decryption failed", e);
		} finally {
			Arrays.fill(scratch, (byte) 0);
			output.wipe();
		}
	}

	private byte[] encryptContent(byte[] plaintext) throws IOException {
		WipingByteArrayOutputStream output = new WipingByteArrayOutputStream(Math.min(MAX_CIPHERTEXT_BYTES, plaintext.length + 8192), MAX_CIPHERTEXT_BYTES);
		byte[] scratch = new byte[8192];
		try (ReadableByteChannel encrypted = new EncryptingReadableByteChannel(Channels.newChannel(new ByteArrayInputStream(plaintext)), cryptor)) {
			ByteBuffer buffer = ByteBuffer.wrap(scratch);
			while (true) {
				buffer.clear();
				int count = encrypted.read(buffer);
				if (count < 0) {
					break;
				}
				if (count == 0) {
					continue;
				}
				if (output.size() + count > MAX_CIPHERTEXT_BYTES) {
					throw new IOException("Encrypted file exceeds the ciphertext size limit");
				}
				output.write(scratch, 0, count);
			}
			return output.toByteArray();
		} catch (RuntimeException e) {
			throw new IOException("Unable to encrypt file content", e);
		} finally {
			Arrays.fill(scratch, (byte) 0);
			output.wipe();
		}
	}

	private String encryptName(String clearName, String parentId) throws IOException {
		try {
			String cipher = cryptor.fileNameCryptor().encryptFilename(BASE64_URL, clearName, parentId.getBytes(StandardCharsets.UTF_8));
			String fullName = cipher + ".c9r";
			if (fullName.getBytes(StandardCharsets.UTF_8).length > MAX_CIPHERTEXT_NAME_BYTES) {
				throw new IOException("Filename exceeds the supported encrypted-name limit");
			}
			return fullName;
		} catch (RuntimeException e) {
			throw new IOException("Unable to encrypt filename", e);
		}
	}

	private String decryptName(String cipherName, String parentId) throws IOException {
		if (!cipherName.matches("[A-Za-z0-9_-]+=*")) {
			throw new IOException("Malformed encrypted filename");
		}
		try {
			String clearName = cryptor.fileNameCryptor().decryptFilename(BASE64_URL, cipherName, parentId.getBytes(StandardCharsets.UTF_8));
			return validateDecryptedName(clearName);
		} catch (RuntimeException e) {
			throw new IOException("Encrypted filename authentication failed", e);
		}
	}

	private String dataDirectoryPath(String directoryId) throws IOException {
		String hash = cryptor.fileNameCryptor().hashDirectoryId(directoryId);
		if (hash == null || !hash.matches("[A-Z2-7]{32}")) {
			throw new IOException("Cryptolib returned an invalid directory hash");
		}
		return DATA_PATH + "/" + hash.substring(0, 2) + "/" + hash.substring(2);
	}

	private String storedEntryName(String fullCipherName) {
		return fullCipherName.length() <= shorteningThreshold ? fullCipherName : shortenedEntryName(fullCipherName);
	}

	private static String shortenedEntryName(String fullCipherName) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(fullCipherName.getBytes(StandardCharsets.UTF_8));
			try {
				return BASE64_URL.encode(digest) + ".c9s";
			} finally {
				Arrays.fill(digest, (byte) 0);
			}
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-1 unavailable", e);
		}
	}

	private void ensureAbsent(String parentPath, String entryName) throws IOException {
		if (containsChild(parentPath, entryName)) {
			throw new IOException("An entry with this name already exists");
		}
	}

	private String createUploadStage(String parentPath) throws IOException {
		String stageName = UUID.randomUUID() + ".upload";
		ensureAbsent(parentPath, stageName);
		String stagePath = parentPath + "/" + stageName;
		store.mkdir(stagePath);
		return stagePath;
	}

	private boolean containsChild(String parentPath, String childName) throws IOException {
		List<RemoteStore.Entry> entries = store.list(parentPath);
		if (entries.size() > MAX_DIRECTORY_ENTRIES) {
			throw new IOException("Directory contains too many encrypted entries");
		}
		for (RemoteStore.Entry entry : entries) {
			if (childName.equals(directChildName(parentPath, entry.path))) {
				return true;
			}
		}
		return false;
	}

	private static String directChildName(String parent, String childPath) throws IOException {
		if (childPath == null || childPath.isEmpty()) {
			throw new IOException("Remote store returned an invalid path");
		}
		String prefix = parent + "/";
		if (!childPath.startsWith(prefix)) {
			throw new IOException("Remote store returned an entry outside the requested directory");
		}
		String name = childPath.substring(prefix.length());
		if (name.isEmpty() || name.indexOf('/') >= 0 || ".".equals(name) || "..".equals(name)) {
			throw new IOException("Remote store returned an invalid child path");
		}
		return name;
	}

	private static RemoteStore.Entry findChild(List<RemoteStore.Entry> entries, String name) throws IOException {
		RemoteStore.Entry found = null;
		for (RemoteStore.Entry entry : entries) {
			String entryName = entry.path.substring(entry.path.lastIndexOf('/') + 1);
			if (name.equals(entryName)) {
				if (found != null) {
					throw new IOException("Duplicate metadata object in encrypted entry");
				}
				found = entry;
			}
		}
		return found;
	}

	private static boolean hasChild(List<RemoteStore.Entry> entries, String name) throws IOException {
		return findChild(entries, name) != null;
	}

	private static String stripSuffix(String value, String suffix) throws IOException {
		if (!value.endsWith(suffix) || value.length() == suffix.length()) {
			throw new IOException("Malformed encrypted object name");
		}
		return value.substring(0, value.length() - suffix.length());
	}

	private static byte[] readBounded(RemoteStore store, String path, int maxBytes) throws IOException {
		RemoteStore.Blob blob = store.get(path, maxBytes);
		try (blob) {
			if (blob.bytes.length > maxBytes) {
				throw new IOException("Remote object exceeds the size limit: " + path);
			}
			return blob.bytes.clone();
		}
	}

	private static String decodeUtf8(byte[] bytes) throws IOException {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("Remote object is not valid UTF-8", e);
		}
	}

	private static void validateDirectoryId(String directoryId) throws IOException {
		if (directoryId == null || directoryId.isEmpty()) {
			if (directoryId != null) {
				return; // The empty string is the Cryptomator root directory ID.
			}
			throw new IOException("Directory ID is required");
		}
		if (directoryId.length() > 36) {
			throw new IOException("Cryptomator directory ID exceeds the supported size limit");
		}
		for (int i = 0; i < directoryId.length(); i++) {
			char c = directoryId.charAt(i);
			if (c < 0x20 || c > 0x7e) {
				throw new IOException("Cryptomator directory IDs must contain printable ASCII only");
			}
		}
	}

	private static String normalizeAndValidateName(String name) throws IOException {
		if (name == null) {
			throw new IOException("Filename is required");
		}
		String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
		String safe = validateDecryptedName(normalized);
		if (safe.getBytes(StandardCharsets.UTF_8).length > MAX_PLAINTEXT_NAME_BYTES) {
			throw new IOException("Filename exceeds the 255-byte UTF-8 limit");
		}
		return safe;
	}

	private static String validateDecryptedName(String name) throws IOException {
		if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
				|| name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
			throw new IOException("Vault contains an unsafe filename");
		}
		return name;
	}

	private static void validatePlaintext(byte[] bytes) throws IOException {
		if (bytes == null) {
			throw new IOException("File content is required");
		}
		if (bytes.length > MAX_PLAINTEXT_BYTES) {
			throw new IOException("File exceeds the plaintext size limit");
		}
	}

	private void validateNode(Node node) throws IOException {
		if (node == null || node.owner != this || node.path == null || node.path.isEmpty() || node.contentPath == null && !node.directory) {
			throw new IOException("Invalid vault node");
		}
		if (node.directory) {
			validateDirectoryId(node.directoryId);
		} else {
			validateDirectoryId(node.parentDirectoryId);
			validateDecryptedName(node.name);
		}
	}

	private static void validateStrongEtag(String etag) throws IOException {
		if (etag == null || etag.length() < 2 || etag.charAt(0) != '"' || etag.charAt(etag.length() - 1) != '"') {
			throw new IOException("A strong ETag is required to overwrite this file");
		}
		for (int i = 1; i < etag.length() - 1; i++) {
			char c = etag.charAt(i);
			if (c < 0x21 || c > 0x7e || c == '"') {
				throw new IOException("Invalid strong ETag");
			}
		}
	}

	private static IOException unsupportedSymlink(String name) {
		return new IOException("Cryptomator symlinks are not supported: " + name);
	}

	private void ensureOpen() throws IOException {
		if (closed) {
			throw new IOException("Vault session is closed");
		}
	}

	private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
		private final int maxCapacity;

		WipingByteArrayOutputStream(int initialCapacity, int maxCapacity) {
			super(Math.max(0, Math.min(initialCapacity, maxCapacity)));
			this.maxCapacity = maxCapacity;
		}

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			if (offset < 0 || length < 0 || length > bytes.length - offset || count + length > maxCapacity) {
				throw new IllegalStateException("Output exceeds the configured size limit");
			}
			ensureCapacity(count + length);
			System.arraycopy(bytes, offset, buf, count, length);
			count += length;
		}

		@Override
		public synchronized void write(int value) {
			if (count >= maxCapacity) {
				throw new IllegalStateException("Output exceeds the configured size limit");
			}
			ensureCapacity(count + 1);
			buf[count++] = (byte) value;
		}

		private void ensureCapacity(int minimumCapacity) {
			if (minimumCapacity <= buf.length) {
				return;
			}
			int doubled = Math.max(1, buf.length << 1);
			int newCapacity = Math.min(maxCapacity, Math.max(minimumCapacity, doubled));
			byte[] old = buf;
			buf = Arrays.copyOf(old, newCapacity);
			Arrays.fill(old, (byte) 0);
		}

		void wipe() {
			Arrays.fill(buf, (byte) 0);
			reset();
		}
	}
}
