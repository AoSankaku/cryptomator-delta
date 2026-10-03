package org.deltavault.core;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.FileHeader;
import org.cryptomator.cryptolib.api.Masterkey;
import org.junit.Test;

import javax.security.auth.Destroyable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VaultStreamTest {
	private static final int CHUNK_SIZE = 32 * 1024;

	@Test
	public void seeksAcrossAuthenticatedChunkBoundariesForBothSupportedSchemes() throws Exception {
		for (CryptorProvider.Scheme scheme : List.of(CryptorProvider.Scheme.SIV_GCM, CryptorProvider.Scheme.SIV_CTRMAC)) {
			byte[] expected = patterned(CHUNK_SIZE * 2 + 79);
			try (Fixture fixture = new Fixture(scheme, expected); VaultStream stream = fixture.session.openStream(fixture.node)) {
				assertEquals(expected.length, stream.size());
				byte[] crossing = new byte[23];
				assertEquals(crossing.length, stream.read(CHUNK_SIZE - 7L, crossing, 0, crossing.length));
				assertArrayEquals(Arrays.copyOfRange(expected, CHUNK_SIZE - 7, CHUNK_SIZE + 16), crossing);

				byte[] last = new byte[expected.length - CHUNK_SIZE * 2 + 4];
				Arrays.fill(last, (byte) 0x6a);
				assertEquals(79, stream.read(CHUNK_SIZE * 2L, last, 2, last.length - 2));
				assertArrayEquals(Arrays.copyOfRange(expected, CHUNK_SIZE * 2, expected.length),
						Arrays.copyOfRange(last, 2, 2 + 79));
				assertEquals(0, stream.read(expected.length, new byte[4], 0, 4));
				assertTrue(fixture.store.maxCipherRead <= RemoteStore.MAX_RANGE_BYTES);
			}
		}
	}

	@Test
	public void seeksLargeSparseVaultFilesUsingLongOffsetsAndBoundedCiphertextReads() throws Exception {
		long clearSize = 20L * 1024 * 1024 * 1024 + 23;
		try (LargeFixture fixture = new LargeFixture(CryptorProvider.Scheme.SIV_CTRMAC, clearSize);
				VaultStream stream = fixture.session.openStream(fixture.node)) {
			long position = (1L << 32) + 13;
			byte[] actual = new byte[117];
			assertEquals(actual.length, stream.read(position, actual, 0, actual.length));
			assertArrayEquals(expectedSparseBytes(position, actual.length), actual);
			assertTrue(position > Integer.MAX_VALUE);
			assertTrue(fixture.store.lastCipherPosition > Integer.MAX_VALUE);
			assertTrue(fixture.store.maxCipherRead <= RemoteStore.MAX_RANGE_BYTES);
		}
	}

	@Test
	public void rejectsTamperedSivGcmHeader() throws Exception {
		assertTamperedHeaderRejected(CryptorProvider.Scheme.SIV_GCM);
	}

	@Test
	public void rejectsTamperedSivCtrmacHeader() throws Exception {
		assertTamperedHeaderRejected(CryptorProvider.Scheme.SIV_CTRMAC);
	}

	@Test
	public void rejectsTrailingCiphertext() throws Exception {
		try (Fixture fixture = new Fixture(CryptorProvider.Scheme.SIV_CTRMAC, new byte[0])) {
			fixture.store.append((byte) 3);
			assertThrows(IOException.class, () -> fixture.session.openStream(fixture.node));
		}
	}

	@Test
	public void rejectsTamperedSivGcmChunkAndWipesOutput() throws Exception {
		assertTamperedFirstChunkRejected(CryptorProvider.Scheme.SIV_GCM);
	}

	@Test
	public void rejectsTamperedSivCtrmacChunkAndWipesOutput() throws Exception {
		assertTamperedFirstChunkRejected(CryptorProvider.Scheme.SIV_CTRMAC);
	}

	@Test
	public void rejectsReorderedSivGcmCiphertextChunks() throws Exception {
		assertReorderedChunkRejected(CryptorProvider.Scheme.SIV_GCM);
	}

	@Test
	public void rejectsReorderedSivCtrmacCiphertextChunks() throws Exception {
		assertReorderedChunkRejected(CryptorProvider.Scheme.SIV_CTRMAC);
	}

	@Test
	public void rejectsTamperedSivGcmLastChunkAndWipesOutput() throws Exception {
		assertTamperedLastChunkRejected(CryptorProvider.Scheme.SIV_GCM);
	}

	@Test
	public void rejectsTamperedSivCtrmacLastChunkAndWipesOutput() throws Exception {
		assertTamperedLastChunkRejected(CryptorProvider.Scheme.SIV_CTRMAC);
	}

	@Test
	public void enforcesReadLimitAndWipesTheRequestedOutputRegionOnErrors() throws Exception {
		try (Fixture fixture = new Fixture(CryptorProvider.Scheme.SIV_CTRMAC, patterned(300_000))) {
			VaultStream stream = fixture.session.openStream(fixture.node);
			byte[] output = new byte[RemoteStore.MAX_RANGE_BYTES + 7];
			Arrays.fill(output, (byte) 0x42);
			assertThrows(IOException.class, () -> stream.read(0, output, 3, RemoteStore.MAX_RANGE_BYTES + 1));
            assertArrayEquals(new byte[RemoteStore.MAX_RANGE_BYTES + 1], Arrays.copyOfRange(output, 3, 4 + RemoteStore.MAX_RANGE_BYTES));
            assertEquals((byte) 0x42, output[0]);
            assertEquals((byte) 0x42, output[output.length - 1]);
			assertThrows(IOException.class, () -> stream.size());
			stream.close();
		}
	}

	@Test
	public void closingStreamAndSessionWipesCachesDestroysHeaderAndPreventsReuse() throws Exception {
		Fixture fixture = new Fixture(CryptorProvider.Scheme.SIV_GCM, patterned(CHUNK_SIZE + 9));
		VaultStream stream = fixture.session.openStream(fixture.node);
		assertEquals(1, stream.read(0, new byte[1], 0, 1));
		byte[] cache = (byte[]) field(stream, "plaintextCache");
		byte[] cipher = (byte[]) field(stream, "cipherBuffer");
		FileHeader header = (FileHeader) field(stream, "header");
		assertNotNull(cache);
		assertNotNull(cipher);
		stream.close();
		assertArrayEquals(new byte[cache.length], cache);
		assertArrayEquals(new byte[cipher.length], cipher);
		assertTrue(((Destroyable) header).isDestroyed());
		assertNull(field(stream, "header"));
		assertThrows(IOException.class, () -> stream.read(0, new byte[1], 0, 1));

		VaultStream sessionOwned = fixture.session.openStream(fixture.node);
		byte[] sessionCache = (byte[]) field(sessionOwned, "plaintextCache");
		fixture.session.close();
		assertArrayEquals(new byte[sessionCache.length], sessionCache);
		assertTrue(fixture.masterkey.isDestroyed());
		assertTrue(fixture.store.readers.get(1).closed);
		assertThrows(IOException.class, () -> sessionOwned.read(0, new byte[1], 0, 1));
		fixture.closed = true;
	}

	@Test
	public void sessionCloseCancelsAnInFlightRangeBeforeAnyPlaintextCanEscape() throws Exception {
		Fixture fixture = new Fixture(CryptorProvider.Scheme.SIV_CTRMAC, patterned(CHUNK_SIZE + 17));
		VaultStream stream = fixture.session.openStream(fixture.node);
		MemoryReader reader = fixture.store.readers.get(0);
		reader.blockNextPayloadRange = true;
		byte[] output = new byte[32];
		Arrays.fill(output, (byte) 0x31);
		AtomicReference<Throwable> result = new AtomicReference<>();
		Thread readThread = new Thread(() -> {
			try {
				stream.read(0, output, 0, output.length);
			} catch (Throwable e) {
				result.set(e);
			}
		}, "vault-stream-cancel-test");
		readThread.start();
		assertTrue("reader did not reach blocked range", reader.rangeEntered.await(3, TimeUnit.SECONDS));
		fixture.session.close();
		readThread.join(3000);
		assertFalse("read returned plaintext after session close", readThread.isAlive());
		assertTrue(result.get() instanceof IOException);
		assertArrayEquals(new byte[output.length], output);
		assertTrue(reader.closed);
		assertTrue(fixture.masterkey.isDestroyed());
		fixture.closed = true;
	}

	private static void assertTamperedHeaderRejected(CryptorProvider.Scheme scheme) throws Exception {
		try (Fixture fixture = new Fixture(scheme, patterned(CHUNK_SIZE + 19))) {
			fixture.store.ciphertext[0] ^= 0x40;
			assertThrows(IOException.class, () -> fixture.session.openStream(fixture.node));
		}
	}

	private static void assertTamperedFirstChunkRejected(CryptorProvider.Scheme scheme) throws Exception {
		try (Fixture fixture = new Fixture(scheme, patterned(CHUNK_SIZE + 19));
				VaultStream stream = fixture.session.openStream(fixture.node)) {
			int headerSize = fixture.cryptor.fileHeaderCryptor().headerSize();
			fixture.store.ciphertext[headerSize + 5] ^= 1;
			byte[] destination = new byte[70];
			Arrays.fill(destination, (byte) 0x55);
			assertThrows(IOException.class, () -> stream.read(0, destination, 3, 64));
			assertArrayEquals(new byte[64], Arrays.copyOfRange(destination, 3, 67));
			assertArrayEquals(new byte[] {0x55, 0x55, 0x55}, Arrays.copyOfRange(destination, 0, 3));
			assertArrayEquals(new byte[] {0x55, 0x55, 0x55}, Arrays.copyOfRange(destination, 67, 70));
			assertThrows(IOException.class, () -> stream.read(0, new byte[1], 0, 1));
			assertTrue(fixture.store.readers.get(0).closed);
		}
	}

	private static void assertTamperedLastChunkRejected(CryptorProvider.Scheme scheme) throws Exception {
		try (Fixture fixture = new Fixture(scheme, patterned(CHUNK_SIZE + 19));
				VaultStream stream = fixture.session.openStream(fixture.node)) {
			int headerSize = fixture.cryptor.fileHeaderCryptor().headerSize();
			int cipherChunkSize = fixture.cryptor.fileContentCryptor().ciphertextChunkSize();
			int lastCipherLength = Math.toIntExact(fixture.cryptor.fileContentCryptor().ciphertextSize(19));
			int lastByte = headerSize + cipherChunkSize + lastCipherLength - 1;
			fixture.store.ciphertext[lastByte] ^= 1;
			byte[] destination = new byte[21];
			Arrays.fill(destination, (byte) 0x23);
			assertThrows(IOException.class, () -> stream.read(CHUNK_SIZE, destination, 1, 19));
			assertArrayEquals(new byte[19], Arrays.copyOfRange(destination, 1, 20));
			assertEquals((byte) 0x23, destination[0]);
			assertEquals((byte) 0x23, destination[20]);
		}
	}

	private static void assertReorderedChunkRejected(CryptorProvider.Scheme scheme) throws Exception {
		try (Fixture fixture = new Fixture(scheme, patterned(CHUNK_SIZE * 2 + 19));
				VaultStream stream = fixture.session.openStream(fixture.node)) {
			int headerSize = fixture.cryptor.fileHeaderCryptor().headerSize();
			int cipherChunkSize = fixture.cryptor.fileContentCryptor().ciphertextChunkSize();
			byte[] temporary = Arrays.copyOfRange(fixture.store.ciphertext,
					headerSize, headerSize + cipherChunkSize);
			System.arraycopy(fixture.store.ciphertext, headerSize + cipherChunkSize,
					fixture.store.ciphertext, headerSize, cipherChunkSize);
			System.arraycopy(temporary, 0, fixture.store.ciphertext, headerSize + cipherChunkSize,
					cipherChunkSize);
			Arrays.fill(temporary, (byte) 0);
			byte[] destination = new byte[32];
			Arrays.fill(destination, (byte) 0x7a);
			assertThrows(IOException.class, () -> stream.read(0, destination, 0, destination.length));
			assertArrayEquals(new byte[destination.length], destination);
		}
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static byte[] patterned(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
		return bytes;
	}

	private static byte[] expectedSparseBytes(long position, int length) {
		byte[] result = new byte[length];
		for (int i = 0; i < length; i++) {
			long absolute = position + i;
			long chunk = absolute / CHUNK_SIZE;
			int withinChunk = (int) (absolute % CHUNK_SIZE);
			result[i] = sparseByte(chunk, withinChunk);
		}
		return result;
	}

	private static byte sparseByte(long chunk, int offset) {
		return (byte) (chunk * 17 + offset * 3 + 9);
	}

	private static byte[] encrypt(Cryptor cryptor, byte[] cleartext) throws Exception {
		FileHeader header = cryptor.fileHeaderCryptor().create();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			ByteBuffer headerBytes = cryptor.fileHeaderCryptor().encryptHeader(header);
			byte[] headerCopy = new byte[headerBytes.remaining()];
			headerBytes.get(headerCopy);
			output.write(headerCopy);
			Arrays.fill(headerCopy, (byte) 0);
			int clearChunkSize = cryptor.fileContentCryptor().cleartextChunkSize();
			long chunk = 0;
			for (int start = 0; start < cleartext.length; start += clearChunkSize, chunk++) {
				int count = Math.min(clearChunkSize, cleartext.length - start);
				ByteBuffer plaintext = ByteBuffer.wrap(cleartext, start, count);
				ByteBuffer encrypted = cryptor.fileContentCryptor().encryptChunk(plaintext, chunk, header);
				byte[] chunkBytes = new byte[encrypted.remaining()];
				encrypted.get(chunkBytes);
				output.write(chunkBytes);
				Arrays.fill(chunkBytes, (byte) 0);
			}
			return output.toByteArray();
		} finally {
			if (header instanceof Destroyable destroyable) destroyable.destroy();
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Masterkey masterkey;
		final Cryptor cryptor;
		final MemoryStore store;
		final VaultSession session;
		final VaultSession.Node node;
		boolean closed;

		Fixture(CryptorProvider.Scheme scheme, byte[] cleartext) throws Exception {
			SecureRandom random = new SecureRandom();
			masterkey = Masterkey.generate(random);
			cryptor = CryptorProvider.forScheme(scheme).provide(masterkey, random);
			store = new MemoryStore(encrypt(cryptor, cleartext));
			Constructor<VaultSession> sessionConstructor = VaultSession.class.getDeclaredConstructor(
					RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
			sessionConstructor.setAccessible(true);
			session = sessionConstructor.newInstance(store, masterkey, cryptor, 220);
			Constructor<VaultSession.Node> nodeConstructor = VaultSession.Node.class.getDeclaredConstructor(
					VaultSession.class, String.class, String.class, boolean.class, String.class,
					String.class, String.class, String.class);
			nodeConstructor.setAccessible(true);
			node = nodeConstructor.newInstance(session, "movie.mp4", "movie.c9r", false, null,
					"movie.c9r", "", "movie");
		}

		@Override public void close() {
			if (!closed) {
				session.close();
				closed = true;
			}
		}
	}

	private static final class MemoryStore implements RemoteStore {
		byte[] ciphertext;
		final List<MemoryReader> readers = new ArrayList<>();
		int maxCipherRead;
		boolean closed;

		MemoryStore(byte[] ciphertext) { this.ciphertext = ciphertext; }
		void append(byte value) {
			ciphertext = Arrays.copyOf(ciphertext, ciphertext.length + 1);
			ciphertext[ciphertext.length - 1] = value;
		}

		@Override public synchronized Reader openReader(String path) throws IOException {
			if (closed) throw new IOException("Closed store");
			MemoryReader reader = new MemoryReader(this);
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
			for (MemoryReader reader : readers) reader.close();
		}
	}

	private static final class MemoryReader implements RemoteStore.Reader {
		final MemoryStore store;
		final CountDownLatch rangeEntered = new CountDownLatch(1);
		final CountDownLatch continueRange = new CountDownLatch(1);
		volatile boolean closed;
		volatile boolean blockNextPayloadRange;

		MemoryReader(MemoryStore store) { this.store = store; }
		@Override public long size() { return store.ciphertext.length; }
		@Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
			RemoteStore.checkRange(size(), position, destination, offset, length);
			if (position > 0 && blockNextPayloadRange) {
				blockNextPayloadRange = false;
				rangeEntered.countDown();
				try {
					if (!continueRange.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for cancellation");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted range read", e);
				}
			}
			if (closed) throw new IOException("Reader was cancelled");
			synchronized (store) {
				RemoteStore.checkRange(store.ciphertext.length, position, destination, offset, length);
				System.arraycopy(store.ciphertext, (int) position, destination, offset, length);
				store.maxCipherRead = Math.max(store.maxCipherRead, length);
			}
		}
		@Override public void close() {
			closed = true;
			continueRange.countDown();
		}
	}

	private static final class LargeFixture implements AutoCloseable {
		final Masterkey masterkey;
		final Cryptor cryptor;
		final SparseStore store;
		final VaultSession session;
		final VaultSession.Node node;
		boolean closed;

		LargeFixture(CryptorProvider.Scheme scheme, long clearSize) throws Exception {
			SecureRandom random = new SecureRandom();
			masterkey = Masterkey.generate(random);
			cryptor = CryptorProvider.forScheme(scheme).provide(masterkey, random);
			FileHeader header = cryptor.fileHeaderCryptor().create();
			ByteBuffer encryptedHeader = cryptor.fileHeaderCryptor().encryptHeader(header);
			byte[] headerBytes = new byte[encryptedHeader.remaining()];
			encryptedHeader.get(headerBytes);
			store = new SparseStore(cryptor, header, headerBytes, clearSize);
			Constructor<VaultSession> sessionConstructor = VaultSession.class.getDeclaredConstructor(
					RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
			sessionConstructor.setAccessible(true);
			session = sessionConstructor.newInstance(store, masterkey, cryptor, 220);
			Constructor<VaultSession.Node> nodeConstructor = VaultSession.Node.class.getDeclaredConstructor(
					VaultSession.class, String.class, String.class, boolean.class, String.class,
					String.class, String.class, String.class);
			nodeConstructor.setAccessible(true);
			node = nodeConstructor.newInstance(session, "large.bin", "large.c9r", false, null,
					"large.c9r", "", "large");
		}

		@Override public void close() {
			if (!closed) {
				session.close();
				closed = true;
			}
		}
	}

	private static final class SparseStore implements RemoteStore {
		final Cryptor cryptor;
		final FileHeader header;
		final byte[] encryptedHeader;
		final long cleartextSize;
		final long ciphertextSize;
		final int clearChunkSize;
		final int cipherChunkSize;
		int maxCipherRead;
		long lastCipherPosition;
		boolean closed;
		SparseReader reader;

		SparseStore(Cryptor cryptor, FileHeader header, byte[] encryptedHeader, long cleartextSize) {
			this.cryptor = cryptor;
			this.header = header;
			this.encryptedHeader = encryptedHeader;
			this.cleartextSize = cleartextSize;
			this.clearChunkSize = cryptor.fileContentCryptor().cleartextChunkSize();
			this.cipherChunkSize = cryptor.fileContentCryptor().ciphertextChunkSize();
			this.ciphertextSize = encryptedHeader.length + cryptor.fileContentCryptor().ciphertextSize(cleartextSize);
		}

		@Override public synchronized Reader openReader(String path) throws IOException {
			if (closed) throw new IOException("Closed sparse store");
			reader = new SparseReader(this);
			return reader;
		}
		@Override public Blob get(String path, int maxBytes) throws IOException { throw new IOException("Unused"); }
		@Override public List<Entry> list(String path) throws IOException { throw new IOException("Unused"); }
		@Override public void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException { throw new IOException("Unused"); }
		@Override public void mkdir(String path) throws IOException { throw new IOException("Unused"); }
		@Override public void move(String sourcePath, String destinationPath) throws IOException { throw new IOException("Unused"); }
		@Override public synchronized void close() {
			closed = true;
			if (reader != null) reader.close();
			if (header instanceof Destroyable destroyable) {
				try { destroyable.destroy(); } catch (Exception ignored) { }
			}
			Arrays.fill(encryptedHeader, (byte) 0);
		}
	}

	private static final class SparseReader implements RemoteStore.Reader {
		final SparseStore store;
		volatile boolean closed;
		SparseReader(SparseStore store) { this.store = store; }
		@Override public long size() { return store.ciphertextSize; }
		@Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
			RemoteStore.checkRange(size(), position, destination, offset, length);
			if (closed || store.closed) throw new IOException("Sparse reader is closed");
			if (position == 0) {
				if (length != store.encryptedHeader.length) throw new IOException("Unexpected header range");
				System.arraycopy(store.encryptedHeader, 0, destination, offset, length);
			} else {
				long payloadOffset = position - store.encryptedHeader.length;
				if (payloadOffset < 0 || payloadOffset % store.cipherChunkSize != 0) {
					throw new IOException("Sparse reader requires aligned chunk ranges");
				}
				long chunkIndex = payloadOffset / store.cipherChunkSize;
				long clearStart = chunkIndex * store.clearChunkSize;
				if (clearStart >= store.cleartextSize) throw new IOException("Chunk outside sparse file");
				int clearLength = (int) Math.min(store.clearChunkSize, store.cleartextSize - clearStart);
				int expectedCipherLength = Math.toIntExact(store.cryptor.fileContentCryptor().ciphertextSize(clearLength));
				if (length != expectedCipherLength) throw new IOException("Unexpected sparse chunk range");
				byte[] clearBytes = new byte[clearLength];
				for (int i = 0; i < clearLength; i++) clearBytes[i] = sparseByte(chunkIndex, i);
				ByteBuffer encrypted = store.cryptor.fileContentCryptor().encryptChunk(
						ByteBuffer.wrap(clearBytes), chunkIndex, store.header);
				if (encrypted.remaining() != length) throw new IOException("Cryptolib returned an unexpected chunk size");
				encrypted.get(destination, offset, length);
				Arrays.fill(clearBytes, (byte) 0);
			}
			synchronized (store) {
				store.maxCipherRead = Math.max(store.maxCipherRead, length);
				store.lastCipherPosition = position;
			}
		}
		@Override public void close() { closed = true; }
	}
}
