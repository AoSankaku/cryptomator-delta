package org.deltavault.core;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.FileContentCryptor;
import org.cryptomator.cryptolib.api.FileHeader;
import org.cryptomator.cryptolib.api.FileHeaderCryptor;

import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Authenticated, seekable plaintext view of one encrypted Cryptomator file. */
public final class VaultStream implements AutoCloseable {
	private static final long MAX_CIPHERTEXT_SIZE = 1L << 40;

	private final VaultSession owner;
	private final Cryptor cryptor;
	private final Object readerGate = new Object();
	private final Object operationGate = new Object();
	private volatile boolean closed;
	private RemoteStore.Reader reader;
	private FileHeader header;
	private byte[] cipherBuffer;
	private byte[] plaintextCache;
	private long cleartextSize = -1;
	private long cachedChunk = -1;
	private int cachedLength;

	VaultStream(VaultSession owner, Cryptor cryptor) {
		this.owner = owner;
		this.cryptor = cryptor;
	}

	/** Returns the cleartext size derived from the pinned ciphertext length. */
	public long size() throws IOException {
		synchronized (operationGate) {
			ensureActive();
			return cleartextSize;
		}
	}

	/**
	 * Reads plaintext at an arbitrary offset. A range is returned only after all chunks
	 * it touches have authenticated successfully. The caller owns and must wipe the output.
	 */
	public int read(long position, byte[] destination, int offset, int length) throws IOException {
		boolean writableRange = destination != null && offset >= 0 && length >= 0
				&& offset <= destination.length && length <= destination.length - offset;
		if (writableRange) {
			Arrays.fill(destination, offset, offset + length, (byte) 0);
		}
		try {
			if (!writableRange) {
				throw new IOException("Invalid plaintext destination range");
			}
			if (length > RemoteStore.MAX_RANGE_BYTES) {
				throw new IOException("Plaintext reads are limited to 256 KiB");
			}
			if (position < 0) {
				throw new IOException("Plaintext position must not be negative");
			}
			synchronized (operationGate) {
				ensureActive();
				if (position > cleartextSize) {
					throw new IOException("Plaintext position is beyond end of file");
				}
				if (length == 0 || position == cleartextSize) {
					return 0;
				}
				long available = cleartextSize - position;
				int wanted = (int) Math.min((long) length, available);
				int copied = 0;
				while (copied < wanted) {
					ensureActive();
					long clearPosition = position + copied;
					int chunkSize = cryptor.fileContentCryptor().cleartextChunkSize();
					long chunkIndex = clearPosition / chunkSize;
					loadChunk(chunkIndex);
					ensureActive();
					int inChunk = (int) (clearPosition - chunkIndex * chunkSize);
					int count = Math.min(wanted - copied, cachedLength - inChunk);
					if (count <= 0) {
						throw new IOException("Invalid decrypted chunk bounds");
					}
					System.arraycopy(plaintextCache, inChunk, destination, offset + copied, count);
					copied += count;
				}
				ensureActive();
				return copied;
			}
		} catch (IOException e) {
			if (writableRange) Arrays.fill(destination, offset, offset + length, (byte) 0);
			close();
			throw e;
		} catch (RuntimeException e) {
			if (writableRange) Arrays.fill(destination, offset, offset + length, (byte) 0);
			close();
			throw new IOException("Unable to authenticate or read encrypted file content", e);
		}
	}

	void initialize(RemoteStore.Reader newReader) throws IOException {
		if (newReader == null) throw new IOException("Storage provider returned no ciphertext reader");
		synchronized (readerGate) {
			if (closed || !owner.isOpenForStream()) {
				newReader.close();
				throw new IOException("Vault session was closed while opening the file");
			}
			reader = newReader;
		}
		synchronized (operationGate) {
			byte[] encryptedHeader = null;
			try {
				ensureActive();
				long ciphertextSize = newReader.size();
				if (ciphertextSize < 0 || ciphertextSize > MAX_CIPHERTEXT_SIZE) {
					throw new IOException("Encrypted file exceeds the 1 TiB streaming limit");
				}
				FileHeaderCryptor headerCryptor = cryptor.fileHeaderCryptor();
				int headerSize = headerCryptor.headerSize();
				if (headerSize <= 0 || ciphertextSize < headerSize) {
					throw new IOException("Encrypted file has an invalid header size");
				}
				encryptedHeader = new byte[headerSize];
				newReader.readFully(0, encryptedHeader, 0, headerSize);
				ensureActive();
				this.header = headerCryptor.decryptHeader(ByteBuffer.wrap(encryptedHeader));
				FileContentCryptor contentCryptor = cryptor.fileContentCryptor();
				int clearChunkSize = contentCryptor.cleartextChunkSize();
				int cipherChunkSize = contentCryptor.ciphertextChunkSize();
				if (clearChunkSize <= 0 || cipherChunkSize < clearChunkSize) {
					throw new IOException("Cryptolib returned invalid chunk sizes");
				}
				long ciphertextPayloadSize = ciphertextSize - headerSize;
				long contentSize;
				try {
					contentSize = contentCryptor.cleartextSize(ciphertextPayloadSize);
					if (contentSize < 0 || contentCryptor.ciphertextSize(contentSize) != ciphertextPayloadSize) {
						throw new IOException("Encrypted file has a malformed final chunk");
					}
				} catch (IllegalArgumentException | ArithmeticException e) {
					throw new IOException("Encrypted file has an invalid ciphertext length", e);
				}
				this.cleartextSize = contentSize;
				this.cipherBuffer = new byte[cipherChunkSize];
				this.plaintextCache = new byte[clearChunkSize];
			} catch (IOException e) {
				close();
				throw e;
			} catch (RuntimeException e) {
				close();
				throw new IOException("Encrypted file header authentication failed", e);
			} finally {
				if (encryptedHeader != null) Arrays.fill(encryptedHeader, (byte) 0);
			}
		}
	}

	private void loadChunk(long chunkIndex) throws IOException {
		if (cachedChunk == chunkIndex) return;
		wipeCache();
		FileContentCryptor contentCryptor = cryptor.fileContentCryptor();
		int clearChunkSize = contentCryptor.cleartextChunkSize();
		int cipherChunkSize = contentCryptor.ciphertextChunkSize();
		long clearStart = chunkIndex * (long) clearChunkSize;
		if (chunkIndex < 0 || clearStart < 0 || clearStart >= cleartextSize) {
			throw new IOException("Invalid encrypted chunk index");
		}
		int clearLength = (int) Math.min((long) clearChunkSize, cleartextSize - clearStart);
		long cipherOffset;
		int cipherLength;
		try {
			cipherOffset = cryptor.fileHeaderCryptor().headerSize() + chunkIndex * (long) cipherChunkSize;
			cipherLength = Math.toIntExact(contentCryptor.ciphertextSize(clearLength));
		} catch (ArithmeticException | IllegalArgumentException e) {
			throw new IOException("Encrypted chunk bounds overflow", e);
		}
		if (cipherLength <= 0 || cipherLength > cipherBuffer.length || cipherOffset < 0
				|| cipherOffset > Long.MAX_VALUE - cipherLength) {
			throw new IOException("Invalid encrypted chunk bounds");
		}
		RemoteStore.Reader current = currentReader();
		current.readFully(cipherOffset, cipherBuffer, 0, cipherLength);
		ensureActive();
		ByteBuffer encrypted = ByteBuffer.wrap(cipherBuffer, 0, cipherLength);
		// cryptolib requires a full chunk-capacity output buffer even for the final partial chunk.
		ByteBuffer clear = ByteBuffer.wrap(plaintextCache);
		try {
			contentCryptor.decryptChunk(encrypted, clear, chunkIndex, header, true);
			if (clear.position() != clearLength) {
				throw new IOException("Cryptolib returned an incomplete plaintext chunk");
			}
			ensureActive();
			cachedChunk = chunkIndex;
			cachedLength = clearLength;
		} catch (RuntimeException e) {
			throw new IOException("Encrypted file chunk authentication failed", e);
		} finally {
			Arrays.fill(cipherBuffer, (byte) 0);
		}
	}

	private RemoteStore.Reader currentReader() throws IOException {
		synchronized (readerGate) {
			if (closed || reader == null) throw new IOException("Encrypted file stream is closed");
			return reader;
		}
	}

	private void ensureActive() throws IOException {
		if (closed || !owner.isOpenForStream()) throw new IOException("Vault session or encrypted file stream is closed");
	}

	private void wipeCache() {
		if (plaintextCache != null) Arrays.fill(plaintextCache, (byte) 0);
		if (cipherBuffer != null) Arrays.fill(cipherBuffer, (byte) 0);
		cachedChunk = -1;
		cachedLength = 0;
	}

	@Override
	public void close() {
		closed = true;
		RemoteStore.Reader current;
		synchronized (readerGate) {
			current = reader;
			reader = null;
		}
		if (current != null) {
			try {
				current.close(); // Cancel an outstanding range read before waiting for it.
			} catch (RuntimeException ignored) {
				// Continue wiping in-memory plaintext even if a provider fails during cancellation.
			}
		}
		synchronized (operationGate) {
			wipeCache();
			if (plaintextCache != null) {
				Arrays.fill(plaintextCache, (byte) 0);
				plaintextCache = null;
			}
			if (cipherBuffer != null) {
				Arrays.fill(cipherBuffer, (byte) 0);
				cipherBuffer = null;
			}
			cleartextSize = -1;
			FileHeader oldHeader = header;
			header = null;
			if (oldHeader instanceof Destroyable destroyable) {
				try {
					destroyable.destroy();
				} catch (DestroyFailedException ignored) {
					// The cryptolib header implementation is destroyable; continue key cleanup if it fails.
				}
			}
		}
		owner.unregisterStream(this);
	}
}
