package org.deltavault.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** A SAF-backed store. The tree adapter exposes document IDs, never filesystem paths. */
public final class LocalStore implements RemoteStore {
    private static final int MAX_LIST_ENTRIES = 10_000;
    private static final int MAX_CIPHERTEXT_BYTES = 17 * 1024 * 1024;
    private static final int MAX_BASENAME_BYTES = 255;
    private static final int MAX_BACKUP_SOURCE_BYTES = 220;
    private static final int BACKUP_SUFFIX_BYTES = 24; // "." + 16 hex + ".backup"
    private static final Pattern UPLOAD_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.upload");
    private static final Pattern RECOVERY_NAME = Pattern.compile("(?:[A-Za-z0-9_-]+={0,}|contents)\\.c9r\\.[0-9a-f]{16}\\.backup");
    private static final Pattern ETAG = Pattern.compile("\\\"sha256-[A-Za-z0-9_-]{43}\\\"");

    /** Immutable result returned by a document tree operation. */
    public static final class Document {
        public final String id;
        public final String name;
        public final boolean directory;

        public Document(String id, String name, boolean directory) {
            this.id = id;
            this.name = name;
            this.directory = directory;
        }
    }

    /** Minimal, Android-free surface implemented by the Storage Access Framework adapter. */
    public interface DocumentTree extends AutoCloseable {
        String rootId();
        List<Document> children(String id) throws IOException;
        InputStream read(String id) throws IOException;
        default Reader openReader(String id) throws IOException {
            throw new IOException("This folder provider does not support seekable files");
        }
        void write(String id, byte[] ciphertext) throws IOException;
        Document create(String parentId, String name, boolean directory) throws IOException;
        Document rename(String id, String newName) throws IOException;
        void delete(String id) throws IOException;
        @Override void close();
    }

    private final DocumentTree tree;
    private final String rootId;
    private final Object stateGate = new Object();
    private final ReentrantLock mutationGate = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    public LocalStore(DocumentTree tree) throws IOException {
        if (tree == null) throw new IllegalArgumentException("A document tree is required.");
        this.tree = tree;
        this.rootId = validId(tree.rootId());
    }

    @Override public Blob get(String path, int maxBytes) throws IOException {
        ensureOpen();
        validatePath(path, false);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative.");
        Document document = resolve(path, false);
        if (document.directory) throw new IOException("A folder cannot be read as a file.");
        byte[] bytes = readBounded(document.id, maxBytes);
        try {
            ensureOpen();
            return new Blob(bytes, etag(bytes));
        } catch (IOException | RuntimeException e) {
            Arrays.fill(bytes, (byte) 0);
            throw e;
        }
    }

    @Override public Reader openReader(String path) throws IOException {
        ensureOpen(); validatePath(path, false);
        Document document = resolve(path, false);
        if (document.directory) throw new IOException("A folder cannot be played as a movie");
        Reader reader = tree.openReader(document.id);
        try {
            ensureOpen();
            return new Reader() {
                @Override public long size() { return reader.size(); }
                @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
                    ensureOpen(); reader.readFully(position, destination, offset, length); ensureOpen();
                }
                @Override public void close() { reader.close(); }
            };
        } catch (IOException | RuntimeException e) { reader.close(); throw e; }
    }

    @Override public List<Entry> list(String path) throws IOException {
        ensureOpen();
        validatePath(path, true);
        Document parent = path.isEmpty() ? new Document(rootId, "", true) : resolve(path, true);
        if (!parent.directory) throw new IOException("The requested parent is not a folder.");
        List<Document> documents = children(parent.id);
        List<Entry> entries = new ArrayList<>(documents.size());
        for (Document document : documents) {
            ensureOpen();
            entries.add(new Entry(path.isEmpty() ? document.name : path + "/" + document.name, document.directory));
        }
        ensureOpen();
        return Collections.unmodifiableList(entries);
    }

    @Override public void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException {
        ensureOpen();
        validatePath(path, false);
        if (bytes == null) throw new IllegalArgumentException("ciphertext is required.");
        mutationGate.lock();
        try {
            ensureOpen();
            String parentPath = parentPath(path);
            String basename = basename(path);
            Document parent = parentPath.isEmpty() ? new Document(rootId, "", true) : resolve(parentPath, true);
            if (!parent.directory) throw new IOException("The requested parent is not a folder.");
            List<Document> before = children(parent.id);
            Document original = findByName(before, basename);
            if (createOnly) {
                if (original != null) throw new IOException("An entry with this name already exists.");
                publishNew(parent, basename, bytes, before);
                return;
            }
            requireExpectedEtag(expectedEtag);
            if (original == null || original.directory) throw new IOException("The file to overwrite no longer exists.");
            if (utf8Length(basename) > MAX_BACKUP_SOURCE_BYTES) {
                throw new IOException("The encrypted filename is too long for a recoverable overwrite.");
            }

            String currentDigest = digest(original.id);
            if (!etagForDigest(currentDigest).equals(expectedEtag)) throw versionConflict();

            String stageName = UUID.randomUUID().toString() + ".upload";
            Document stage = createOwned(parent.id, stageName, false, children(parent.id));
            boolean stagePublished = false;
            boolean originalMoved = false;
            boolean originalRenameInFlight = false;
            boolean stageRenameInFlight = false;
            String backupName = null;
            try {
                writeAndVerify(stage, bytes);
                ensureOpen();
                if (!expectedEtag.equals(etagForDigest(digest(original.id)))) throw versionConflict();

                backupName = newBackupName(basename, parent.id);
                // A provider may complete a rename and then report an I/O failure.
                originalMoved = true;
                originalRenameInFlight = true;
                try {
                    original = renameExact(parent.id, original, backupName);
                } catch (RenameFailure renameFailure) {
                    Document observed = renameFailure.actual;
                    if (observed != null && !observed.directory && expectedEtag.equals(safeDigest(observed.id, renameFailure))) {
                        original = observed;
                    }
                    throw renameFailure;
                }
                originalRenameInFlight = false;
                if (!expectedEtag.equals(etagForDigest(digest(original.id)))) {
                    throw new IOException("The original encrypted file changed while it was being moved to recovery storage.");
                }

                if (findByName(children(parent.id), basename) != null) throw versionConflict();
                stageRenameInFlight = true;
                try {
                    stage = renameExact(parent.id, stage, basename);
                    stagePublished = true;
                } catch (RenameFailure renameFailure) {
                    Document completed = findPublishedCipher(parent.id, basename, etag(bytes), renameFailure);
                    if (completed == null) throw renameFailure;
                    stage = completed;
                    stagePublished = true;
                }
                stageRenameInFlight = false;
            } catch (IOException | RuntimeException failure) {
                if (originalRenameInFlight && failure instanceof RenameFailure renameFailure
                        && renameFailure.actual != null && !renameFailure.actual.directory
                        && expectedEtag.equals(safeDigest(renameFailure.actual.id, failure))) {
                    original = renameFailure.actual;
                }
                if (!stagePublished) {
                    if (stageRenameInFlight && failure instanceof RenameFailure renameFailure && renameFailure.actual != null
                            && !renameFailure.actual.directory && etag(bytes).equals(safeDigest(renameFailure.actual.id, failure))) {
                        stage = renameFailure.actual;
                    }
                    deleteOwned(stage.id, failure);
                }
                if (originalMoved) restoreOriginalIfSafe(parent.id, original, basename, expectedEtag, failure);
                if (failure instanceof IOException io) throw io;
                throw new IOException("Local storage operation failed.", failure);
            }
            // The old ciphertext intentionally remains under its recoverable backup name.
        } finally {
            mutationGate.unlock();
        }
    }

    @Override public void mkdir(String path) throws IOException {
        ensureOpen();
        validatePath(path, true);
        if (path.isEmpty()) return;
        mutationGate.lock();
        try {
            ensureOpen();
            String parentPath = parentPath(path);
            String name = basename(path);
            Document parent = parentPath.isEmpty() ? new Document(rootId, "", true) : resolve(parentPath, true);
            if (!parent.directory) throw new IOException("The requested parent is not a folder.");
            Document existing = findByName(children(parent.id), name);
            if (existing != null) {
                if (!existing.directory) throw new IOException("A file already exists with this name.");
                return;
            }
            createOwned(parent.id, name, true, children(parent.id));
        } finally {
            mutationGate.unlock();
        }
    }

    /** Only same-parent UUID upload folders can be published through this operation. */
    @Override public void move(String sourcePath, String destinationPath) throws IOException {
        ensureOpen();
        validatePath(sourcePath, false);
        validatePath(destinationPath, false);
        String sourceParentPath = parentPath(sourcePath);
        String destinationParentPath = parentPath(destinationPath);
        if (!sourceParentPath.equals(destinationParentPath)) throw new IOException("Only same-folder publication is supported.");
        if (!UPLOAD_NAME.matcher(basename(sourcePath)).matches()) throw new IOException("Only staged upload folders can be published.");
        mutationGate.lock();
        try {
            ensureOpen();
            Document parent = sourceParentPath.isEmpty() ? new Document(rootId, "", true) : resolve(sourceParentPath, true);
            if (!parent.directory) throw new IOException("The requested parent is not a folder.");
            List<Document> siblings = children(parent.id);
            Document source = findByName(siblings, basename(sourcePath));
            if (source == null || !source.directory) throw new IOException("The staged folder no longer exists.");
            if (findByName(siblings, basename(destinationPath)) != null) throw new IOException("The destination already exists.");
            try {
                source = renameExact(parent.id, source, basename(destinationPath));
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof RenameFailure renameFailure && renameFailure.actual != null && renameFailure.actual.directory) {
                    source = renameFailure.actual;
                }
                restoreSourceIfSafe(parent.id, source, basename(sourcePath), failure);
                if (failure instanceof IOException io) throw io;
                throw new IOException("Local storage operation failed.", failure);
            }
        } finally {
            mutationGate.unlock();
        }
    }

    /** Returns true for retained overwrite copies that VaultSession should hide from normal listings. */
    public static boolean isRecoveryName(String basename) {
        return basename != null && RECOVERY_NAME.matcher(basename).matches();
    }

    @Override public void close() {
        synchronized (stateGate) {
            if (closed) return;
            closed = true;
        }
        // Do not wait for a mutation lock: SAF adapters use close to cancel blocked provider I/O.
        try { tree.close(); } catch (RuntimeException ignored) { /* close remains idempotent */ }
    }

    private void publishNew(Document parent, String name, byte[] bytes, List<Document> knownSiblings) throws IOException {
        if (findByName(knownSiblings, name) != null) throw new IOException("An entry with this name already exists.");
        String stageName = UUID.randomUUID().toString() + ".upload";
        Document stage = createOwned(parent.id, stageName, false, children(parent.id));
        boolean published = false;
        try {
            writeAndVerify(stage, bytes);
            ensureOpen();
            if (findByName(children(parent.id), name) != null) throw new IOException("The destination already exists.");
            try {
                stage = renameExact(parent.id, stage, name);
                published = true;
            } catch (RenameFailure renameFailure) {
                Document completed = findPublishedCipher(parent.id, name, etag(bytes), renameFailure);
                if (completed == null) throw renameFailure;
                stage = completed;
                published = true;
            }
        } catch (IOException | RuntimeException failure) {
            if (!published) {
                if (failure instanceof RenameFailure renameFailure && renameFailure.actual != null
                        && !renameFailure.actual.directory && etag(bytes).equals(safeDigest(renameFailure.actual.id, failure))) {
                    stage = renameFailure.actual;
                }
                deleteOwned(stage.id, failure);
            }
            if (failure instanceof IOException io) throw io;
            throw new IOException("Local storage operation failed.", failure);
        }
    }

    private void writeAndVerify(Document stage, byte[] ciphertext) throws IOException {
        ensureOpen();
        byte[] copy = ciphertext.clone();
        try { tree.write(stage.id, copy); }
        finally { Arrays.fill(copy, (byte) 0); }
        ensureOpen();
        if (!digest(stage.id).equals(digest(ciphertext))) throw new IOException("The staged encrypted file did not match the bytes written.");
    }

    private Document createOwned(String parentId, String requestedName, boolean directory, List<Document> before) throws IOException {
        ensureOpen();
        if (findByName(before, requestedName) != null) throw new IOException("The destination already exists.");
        Set<String> previousIds = new HashSet<>();
        for (Document child : before) previousIds.add(child.id);
        Document created = tree.create(parentId, requestedName, directory);
        ensureOpen();
        if (created == null || !requestedName.equals(created.name) || created.directory != directory
                || !validIdForIo(created.id) || previousIds.contains(created.id)) {
            if (created != null && validIdForIo(created.id) && !previousIds.contains(created.id)) deleteOwned(created.id, null);
            throw new IOException("The document provider did not create the requested new document.");
        }
        try {
            return verifyChild(parentId, requestedName, created.id, directory);
        } catch (IOException e) {
            deleteOwned(created.id, e);
            throw e;
        }
    }

    private Document renameExact(String parentId, Document source, String destinationName) throws IOException {
        ensureOpen();
        if (findByName(children(parentId), destinationName) != null) throw new IOException("The destination already exists.");
        Document renamed = null;
        try {
            renamed = tree.rename(source.id, destinationName);
            ensureOpen();
            if (renamed == null || !validIdForIo(renamed.id) || !destinationName.equals(renamed.name)
                    || source.directory != renamed.directory) {
                throw new RenameFailure("The document provider did not rename the document to the exact requested name.", renamed, null);
            }
            return verifyChild(parentId, destinationName, renamed.id, source.directory);
        } catch (RenameFailure e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            Document observed = renamed;
            if (observed == null && !closed) {
                try { observed = findByName(children(parentId), destinationName); }
                catch (IOException lookupFailure) { e.addSuppressed(lookupFailure); }
            }
            throw new RenameFailure("The document provider could not confirm the requested rename.", observed, e);
        }
    }

    private String newBackupName(String originalName, String parentId) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] suffix = new byte[8];
            random.nextBytes(suffix);
            StringBuilder hex = new StringBuilder(16);
            for (byte b : suffix) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            Arrays.fill(suffix, (byte) 0);
            String candidate = originalName + "." + hex + ".backup";
            if (utf8Length(candidate) > MAX_BASENAME_BYTES) throw new IOException("The recovery filename exceeds the provider limit.");
            if (findByName(children(parentId), candidate) == null) return candidate;
        }
        throw new IOException("Unable to allocate a unique recovery filename.");
    }

    private void restoreOriginalIfSafe(String parentId, Document original, String originalName, String expectedEtag, Throwable failure) {
        try {
            ensureOpen();
            Document currentOriginalName = findByName(children(parentId), originalName);
            if (currentOriginalName != null) return;
            if (!expectedEtag.equals(etagForDigest(digest(original.id)))) {
                throw new IOException("The retained recovery copy changed and was left untouched.");
            }
            renameExact(parentId, original, originalName);
        } catch (IOException | RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private void restoreSourceIfSafe(String parentId, Document source, String sourceName, Throwable failure) {
        try {
            ensureOpen();
            Document currentSourceName = findByName(children(parentId), sourceName);
            if (currentSourceName != null) return;
            renameExact(parentId, source, sourceName);
        } catch (IOException | RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private void deleteOwned(String id, Throwable failure) {
        try {
            ensureOpen();
            tree.delete(id);
            ensureOpen();
        } catch (IOException | RuntimeException cleanupFailure) {
            if (failure != null) failure.addSuppressed(cleanupFailure);
        }
    }

    private Document verifyChild(String parentId, String name, String expectedId, boolean directory) throws IOException {
        Document actual = findByName(children(parentId), name);
        if (actual == null || !expectedId.equals(actual.id) || actual.directory != directory) {
            throw new IOException("The document provider returned an unexpected destination.");
        }
        return actual;
    }

    private Document resolve(String path, boolean finalMustBeDirectory) throws IOException {
        validatePath(path, false);
        String currentId = rootId;
        String[] segments = path.split("/", -1);
        Document current = null;
        for (int i = 0; i < segments.length; i++) {
            ensureOpen();
            current = findByName(children(currentId), segments[i]);
            if (current == null) throw new IOException("The requested local file was not found.");
            if (i < segments.length - 1 && !current.directory) throw new IOException("A path component is not a folder.");
            currentId = current.id;
        }
        if (finalMustBeDirectory && current != null && !current.directory) throw new IOException("The requested path is not a folder.");
        ensureOpen();
        return current;
    }

    private List<Document> children(String parentId) throws IOException {
        ensureOpen();
        List<Document> listed = tree.children(parentId);
        ensureOpen();
        if (listed == null || listed.size() > MAX_LIST_ENTRIES) throw new IOException("The local folder contains too many entries or could not be listed.");
        List<Document> copy = new ArrayList<>(listed.size());
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Document child : listed) {
            if (child == null || child.name == null || child.id == null || child.directory && child.name.isEmpty()) {
                throw new IOException("The document provider returned an invalid child.");
            }
            validateBasenameFromProvider(child.name);
            validId(child.id);
            if (!names.add(child.name) || !ids.add(child.id)) throw new IOException("The document provider returned duplicate names or document IDs.");
            copy.add(child);
        }
        ensureOpen();
        return copy;
    }

    private byte[] readBounded(String id, int maxBytes) throws IOException {
        ensureOpen();
        InputStream stream = tree.read(id);
        if (stream == null) throw new IOException("The document provider returned no file stream.");
        WipingOutput output = new WipingOutput(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        try (InputStream input = stream) {
            ensureOpen();
            int count;
            while ((count = input.read(buffer)) != -1) {
                ensureOpen();
                if (count > maxBytes - output.size()) throw new IOException("The encrypted file exceeds the memory limit.");
                output.write(buffer, 0, count);
            }
            ensureOpen();
            return output.copyBytes();
        } catch (IOException | RuntimeException e) {
            output.wipe();
            throw e;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            output.wipe();
        }
    }

    private String digest(String id) throws IOException {
        ensureOpen();
        MessageDigest digest = sha256();
        InputStream stream = tree.read(id);
        if (stream == null) throw new IOException("The document provider returned no file stream.");
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream input = stream) {
            ensureOpen();
            int count;
            while ((count = input.read(buffer)) != -1) {
                ensureOpen();
                if (count > MAX_CIPHERTEXT_BYTES - total) throw new IOException("The encrypted file exceeds the supported size limit.");
                if (count > 0) {
                    digest.update(buffer, 0, count);
                    total += count;
                }
            }
            ensureOpen();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static String digest(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256().digest(bytes));
    }

    private String safeDigest(String id, Throwable failure) {
        try { return etagForDigest(digest(id)); }
        catch (IOException | RuntimeException e) { failure.addSuppressed(e); return null; }
    }

    private Document findPublishedCipher(String parentId, String name, String expectedEtag, RenameFailure failure) {
        Document candidate = failure.actual;
        try {
            if (candidate == null || !name.equals(candidate.name)) candidate = findByName(children(parentId), name);
            if (candidate != null && !candidate.directory && name.equals(candidate.name)
                    && expectedEtag.equals(safeDigest(candidate.id, failure))) return candidate;
        } catch (IOException | RuntimeException lookupFailure) {
            failure.addSuppressed(lookupFailure);
        }
        return null;
    }

    private Document findByName(List<Document> documents, String name) throws IOException {
        Document found = null;
        for (Document document : documents) {
            if (name.equals(document.name)) {
                if (found != null) throw new IOException("The document provider returned a duplicate name.");
                found = document;
            }
        }
        return found;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Local storage is closed.");
    }

    private static void validatePath(String path, boolean allowRoot) {
        if (path == null || (!allowRoot && path.isEmpty()) || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0) throw new IllegalArgumentException("Invalid relative storage path.");
        if (!path.isEmpty()) for (String segment : path.split("/", -1)) validateBasename(segment);
    }

    private static void validateBasename(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || utf8Length(name) > MAX_BASENAME_BYTES) {
            throw new IllegalArgumentException("Invalid storage filename.");
        }
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Invalid storage filename.");
    }

    private static void validateBasenameFromProvider(String name) throws IOException {
        try { validateBasename(name); }
        catch (IllegalArgumentException e) { throw new IOException("The document provider returned an unsafe filename.", e); }
    }

    private static String validId(String id) throws IOException {
        if (!validIdForIo(id)) throw new IOException("The document provider returned an invalid document ID.");
        return id;
    }

    private static boolean validIdForIo(String id) {
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) if (Character.isISOControl(id.charAt(i))) return false;
        return true;
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
    private static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private static int utf8Length(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static String etag(byte[] bytes) { return etagForDigest(Base64.getUrlEncoder().withoutPadding().encodeToString(sha256().digest(bytes))); }
    private static String etagForDigest(String digest) { return "\"sha256-" + digest + "\""; }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable.", e); }
    }

    private static void requireExpectedEtag(String etag) throws IOException {
        if (etag == null || !ETAG.matcher(etag).matches()) throw new IOException("A local ciphertext SHA-256 ETag is required for overwrite.");
    }

    private static IOException versionConflict() { return new IOException("The encrypted file changed. Reload it before saving."); }

    private static final class WipingOutput extends ByteArrayOutputStream {
        WipingOutput(int capacity) { super(Math.max(0, capacity)); }
        byte[] copyBytes() { return Arrays.copyOf(buf, count); }
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }

    private static final class RenameFailure extends IOException {
        final Document actual;
        RenameFailure(String message, Document actual, Throwable cause) {
            super(message, cause);
            this.actual = actual;
        }
    }
}
