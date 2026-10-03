package org.deltavault.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LocalStoreTest {
    @Test public void readsCiphertextWithContentEtagAndWipesBlob() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.file("root", "file.c9r", bytes("ciphertext"));
        LocalStore store = new LocalStore(tree);

        RemoteStore.Blob blob = store.get("file.c9r", 10);
        assertTrue(blob.etag.matches("\\\"sha256-[A-Za-z0-9_-]{43}\\\""));
        assertArrayEquals(bytes("ciphertext"), blob.bytes);
        blob.close();
        assertArrayEquals(new byte[10], blob.bytes);
        store.close();
    }

    @Test public void rejectsTraversalAndUnsafeBasenamesBeforeProviderCalls() throws Exception {
        MemoryTree tree = new MemoryTree();
        LocalStore store = new LocalStore(tree);
        for (String path : new String[]{"/outside", "a/../b", "a//b", "a/", "a\\b", "a/\u0001b", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> store.list(path));
        }
        assertEquals(0, tree.listCalls);
        store.close();
    }

    @Test public void boundsReadsAndRejectsDuplicateProviderEntries() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.file("root", "large.c9r", bytes("12345"));
        LocalStore store = new LocalStore(tree);
        assertThrows(IOException.class, () -> store.get("large.c9r", 4));
        try (RemoteStore.Blob blob = store.get("large.c9r", 5)) { assertArrayEquals(bytes("12345"), blob.bytes); }

        tree.duplicateChildren = true;
        assertThrows(IOException.class, () -> store.list(""));
        tree.duplicateChildren = false;
        tree.duplicateIdDifferentName = true;
        assertThrows(IOException.class, () -> store.list(""));
        store.close();
    }

    @Test public void newPutPublishesOnlyTheVerifiedStagedFile() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        LocalStore store = new LocalStore(tree);
        byte[] cipher = bytes("new-ciphertext");
        store.put("new.c9r", cipher, null, true);
        assertArrayEquals(cipher, tree.dataAt("root", "new.c9r"));
        assertTrue(tree.createdNames.stream().anyMatch(name -> name.matches("[0-9a-f-]{36}\\.upload")));
        assertTrue(tree.writtenDocumentNames.stream().anyMatch(name -> name.matches("[0-9a-f-]{36}\\.upload")));
        assertFalse(tree.children("root").stream().anyMatch(document -> document.name.endsWith(".upload")));
        store.close();
    }

    @Test public void providerAutoRenameDuringStageCreationIsRejectedAndOwnedDocumentRemoved() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.autoRenameNextCreate = true;
        LocalStore store = new LocalStore(tree);
        assertThrows(IOException.class, () -> store.put("requested.c9r", bytes("cipher"), null, true));
        assertNull(tree.dataAtOrNull("root", "requested.c9r"));
        assertTrue(tree.children("root").isEmpty());
        store.close();
    }

    @Test public void newPutRejectsAutoRenamedPublication() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.autoRenameNextRename = true;
        LocalStore store = new LocalStore(tree);
        assertThrows(IOException.class, () -> store.put("requested.c9r", bytes("cipher"), null, true));
        assertNull(tree.dataAtOrNull("root", "requested.c9r"));
        assertTrue(tree.children("root").isEmpty());
        store.close();
    }

    @Test public void completedRenameThatReportsFailureIsVerifiedBeforeReturningSuccess() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        tree.throwAfterRenameName = "requested.c9r";
        LocalStore store = new LocalStore(tree);
        byte[] cipher = bytes("cipher");

        store.put("requested.c9r", cipher, null, true);

        assertArrayEquals(cipher, tree.dataAt("root", "requested.c9r"));
        store.close();
    }

    @Test public void rejectsStaleSameSizeEtagWithoutCreatingStage() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.file("root", "file.c9r", bytes("first"));
        LocalStore store = new LocalStore(tree);
        String etag;
        try (RemoteStore.Blob blob = store.get("file.c9r", 16)) { etag = blob.etag; }
        tree.replace("root", "file.c9r", bytes("other"));
        int createsBefore = tree.createCalls;
        assertThrows(IOException.class, () -> store.put("file.c9r", bytes("next"), etag, false));
        assertEquals(createsBefore, tree.createCalls);
        assertArrayEquals(bytes("other"), tree.dataAt("root", "file.c9r"));
        store.close();
    }

    @Test public void overwriteVersionDigestRejectsCiphertextAboveSupportedLimit() throws Exception {
        MemoryTree tree = new MemoryTree();
        byte[] oversized = new byte[17 * 1024 * 1024 + 1];
        tree.file("root", "large.c9r", oversized);
        LocalStore store = new LocalStore(tree);
        String digest = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(oversized));
        String etag = "\"sha256-" + digest + "\"";
        int createsBefore = tree.createCalls;

        assertThrows(IOException.class, () -> store.put("large.c9r", bytes("new"), etag, false));

        assertEquals(createsBefore, tree.createCalls);
        assertArrayEquals(oversized, tree.dataAt("root", "large.c9r"));
        store.close();
    }

    @Test public void successfulOverwriteKeepsEncryptedRecoveryCopy() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        tree.file("root", "cipher.c9r", bytes("old cipher"));
        LocalStore store = new LocalStore(tree);
        String etag;
        try (RemoteStore.Blob blob = store.get("cipher.c9r", 64)) { etag = blob.etag; }

        store.put("cipher.c9r", bytes("new cipher"), etag, false);

        assertArrayEquals(bytes("new cipher"), tree.dataAt("root", "cipher.c9r"));
        List<LocalStore.Document> backups = tree.children("root").stream()
                .filter(document -> LocalStore.isRecoveryName(document.name)).toList();
        assertEquals(1, backups.size());
        assertArrayEquals(bytes("old cipher"), tree.nodes.get(backups.get(0).id).data);
        assertTrue(LocalStore.isRecoveryName("contents.c9r.0123456789abcdef.backup"));
        assertTrue(LocalStore.isRecoveryName("Ab_c-9=.c9r.0123456789abcdef.backup"));
        assertFalse(LocalStore.isRecoveryName("file.c9r.0123456789ABCDEF.backup"));
        assertFalse(LocalStore.isRecoveryName("../file.c9r.0123456789abcdef.backup"));
        store.close();
    }

    @Test public void failedPublicationRestoresOldFileWhenDestinationRemainsFree() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        tree.file("root", "cipher.c9r", bytes("old cipher"));
        LocalStore store = new LocalStore(tree);
        String etag;
        try (RemoteStore.Blob blob = store.get("cipher.c9r", 64)) { etag = blob.etag; }
        tree.failRenameToName = "cipher.c9r";

        assertThrows(IOException.class, () -> store.put("cipher.c9r", bytes("new cipher"), etag, false));

        assertArrayEquals(bytes("old cipher"), tree.dataAt("root", "cipher.c9r"));
        assertFalse(tree.children("root").stream().anyMatch(document -> LocalStore.isRecoveryName(document.name)));
        assertFalse(tree.children("root").stream().anyMatch(document -> document.name.endsWith(".upload")));
        store.close();
    }

    @Test public void backupRenameThatReportsFailureIsLocatedAndRestoredByItsNewId() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        tree.file("root", "cipher.c9r", bytes("old cipher"));
        LocalStore store = new LocalStore(tree);
        String etag;
        try (RemoteStore.Blob blob = store.get("cipher.c9r", 64)) { etag = blob.etag; }
        tree.throwAfterAnyRename = true;

        assertThrows(IOException.class, () -> store.put("cipher.c9r", bytes("new cipher"), etag, false));

        assertArrayEquals(bytes("old cipher"), tree.dataAt("root", "cipher.c9r"));
        assertFalse(tree.children("root").stream().anyMatch(document -> LocalStore.isRecoveryName(document.name)));
        assertFalse(tree.children("root").stream().anyMatch(document -> document.name.endsWith(".upload")));
        store.close();
    }

    @Test public void concurrentDestinationIsNeverReplacedAndRecoveryCopyIsRetained() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.file("root", "cipher.c9r", bytes("old cipher"));
        LocalStore store = new LocalStore(tree);
        String etag;
        try (RemoteStore.Blob blob = store.get("cipher.c9r", 64)) { etag = blob.etag; }
        tree.afterNextRename = () -> tree.file("root", "cipher.c9r", bytes("external"));

        assertThrows(IOException.class, () -> store.put("cipher.c9r", bytes("new cipher"), etag, false));

        assertArrayEquals(bytes("external"), tree.dataAt("root", "cipher.c9r"));
        List<LocalStore.Document> backups = tree.children("root").stream()
                .filter(document -> LocalStore.isRecoveryName(document.name)).toList();
        assertEquals(1, backups.size());
        assertArrayEquals(bytes("old cipher"), tree.nodes.get(backups.get(0).id).data);
        store.close();
    }

    @Test public void mkdirIsIdempotentOnlyForExistingDirectoriesAndMovePublishesSameParentFolder() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.changeIdsOnRename = true;
        tree.folder("root", "existing");
        tree.file("root", "plain.c9r", bytes("cipher"));
        LocalStore store = new LocalStore(tree);
        store.mkdir("existing");
        assertThrows(IOException.class, () -> store.mkdir("plain.c9r"));
        store.mkdir("new-folder");

        Node stage = tree.folder("root", "123e4567-e89b-12d3-a456-426614174000.upload");
        tree.file(stage.id, "marker.c9r", bytes("inside"));
        store.move(stage.name, "published.c9r");
        assertTrue(tree.children("root").stream().anyMatch(document -> document.name.equals("published.c9r") && document.directory));
        try (RemoteStore.Blob blob = store.get("published.c9r/marker.c9r", 16)) { assertArrayEquals(bytes("inside"), blob.bytes); }
        assertThrows(IOException.class, () -> store.move("published.c9r", "elsewhere.c9r"));

        Node unknownStage = tree.folder("root", "123e4567-e89b-12d3-a456-426614174002.upload");
        assertThrows(IOException.class, () -> store.move("123e4567-e89b-12d3-a456-426614174003.upload", "unknown.c9r"));
        assertTrue(tree.nodes.containsKey(unknownStage.id));
        assertThrows(IOException.class, () -> store.move(unknownStage.name, "existing/child.c9r"));
        assertTrue(tree.nodes.containsKey(unknownStage.id));

        Node collisionStage = tree.folder("root", "123e4567-e89b-12d3-a456-426614174001.upload");
        assertThrows(IOException.class, () -> store.move(collisionStage.name, "published.c9r"));
        assertTrue(tree.nodes.containsKey(collisionStage.id));
        store.close();
    }

    @Test public void closeCancelsBlockedReadWithoutWaitingForMutationLockAndPreventsWrites() throws Exception {
        MemoryTree tree = new MemoryTree();
        tree.file("root", "blocked.c9r", bytes("cipher"));
        tree.blockReads = true;
        LocalStore store = new LocalStore(tree);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RemoteStore.Blob> read = executor.submit(() -> store.get("blocked.c9r", 64));
            assertTrue(tree.readEntered.await(2, TimeUnit.SECONDS));
            store.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> read.get(2, TimeUnit.SECONDS));
            int creates = tree.createCalls;
            assertThrows(IOException.class, () -> store.put("later.c9r", bytes("cipher"), null, true));
            assertEquals(creates, tree.createCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class MemoryTree implements LocalStore.DocumentTree {
        final Map<String, Node> nodes = new java.util.LinkedHashMap<>();
        final List<String> createdNames = new ArrayList<>();
        final List<String> writtenDocumentNames = new ArrayList<>();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch unblockRead = new CountDownLatch(1);
        boolean autoRenameNextCreate;
        boolean autoRenameNextRename;
        boolean duplicateChildren;
        boolean duplicateIdDifferentName;
        boolean blockReads;
        boolean changeIdsOnRename;
        boolean closed;
        int createCalls;
        int listCalls;
        String failRenameToName;
        String throwAfterRenameName;
        boolean throwAfterAnyRename;
        Runnable afterNextRename;

        MemoryTree() { nodes.put("root", new Node("root", "", true, null)); }
        @Override public String rootId() { return "root"; }

        @Override public List<LocalStore.Document> children(String id) throws IOException {
            listCalls++;
            requireOpen();
            Node parent = nodes.get(id);
            if (parent == null || !parent.directory) throw new IOException("not a folder");
            List<LocalStore.Document> result = new ArrayList<>();
            for (Node node : nodes.values()) if (id.equals(node.parent)) result.add(document(node));
            if (duplicateChildren && !result.isEmpty()) result.add(result.get(0));
            if (duplicateIdDifferentName && !result.isEmpty()) {
                LocalStore.Document original = result.get(0);
                result.add(new LocalStore.Document(original.id, original.name + "-duplicate", original.directory));
            }
            return result;
        }

        @Override public InputStream read(String id) throws IOException {
            requireOpen();
            Node node = nodes.get(id);
            if (node == null || node.directory) throw new IOException("not a file");
            byte[] value = node.data.clone();
            if (!blockReads) return new ByteArrayInputStream(value);
            return new InputStream() {
                boolean read;
                @Override public int read() throws IOException {
                    readEntered.countDown();
                    try { unblockRead.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    if (closed) throw new IOException("closed");
                    if (read || value.length == 0) return -1;
                    read = true;
                    return value[0] & 0xff;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    int first = read();
                    if (first < 0) return -1;
                    b[off] = (byte) first;
                    return 1;
                }
            };
        }

        @Override public void write(String id, byte[] ciphertext) throws IOException {
            requireOpen();
            Node node = nodes.get(id);
            if (node == null || node.directory) throw new IOException("not a file");
            writtenDocumentNames.add(node.name);
            node.data = ciphertext.clone();
        }

        @Override public LocalStore.Document create(String parentId, String name, boolean directory) throws IOException {
            requireOpen(); createCalls++;
            String actualName = name;
            if (autoRenameNextCreate) { autoRenameNextCreate = false; actualName += " (1)"; }
            Node node = new Node(UUIDs.next(), actualName, directory, parentId);
            if (!directory) node.data = new byte[0];
            nodes.put(node.id, node);
            createdNames.add(actualName);
            return document(node);
        }

        @Override public LocalStore.Document rename(String id, String newName) throws IOException {
            requireOpen();
            if (newName.equals(failRenameToName)) {
                failRenameToName = null;
                throw new IOException("injected rename failure");
            }
            Node node = nodes.get(id);
            if (node == null) throw new IOException("unknown id");
            String actualName = newName;
            if (autoRenameNextRename) { autoRenameNextRename = false; actualName += " (1)"; }
            node.name = actualName;
            if (changeIdsOnRename) node = changeIdsRecursively(node);
            Runnable hook = afterNextRename;
            afterNextRename = null;
            if (hook != null) hook.run();
            if (throwAfterAnyRename || newName.equals(throwAfterRenameName)) {
                throwAfterAnyRename = false;
                throwAfterRenameName = null;
                throw new IOException("injected post-rename failure");
            }
            return document(node);
        }

        @Override public void delete(String id) throws IOException { requireOpen(); nodes.remove(id); }
        @Override public void close() { closed = true; unblockRead.countDown(); }

        Node file(String parentId, String name, byte[] value) {
            Node node = new Node(UUIDs.next(), name, false, parentId);
            node.data = value.clone(); nodes.put(node.id, node); return node;
        }
        Node folder(String parentId, String name) { Node node = new Node(UUIDs.next(), name, true, parentId); nodes.put(node.id, node); return node; }
        void replace(String parentId, String name, byte[] value) { find(parentId, name).data = value.clone(); }
        byte[] dataAt(String parentId, String name) { Node node = find(parentId, name); return node == null ? null : node.data.clone(); }
        byte[] dataAtOrNull(String parentId, String name) { return dataAt(parentId, name); }
        Node find(String parentId, String name) { for (Node node : nodes.values()) if (parentId.equals(node.parent) && name.equals(node.name)) return node; return null; }
        private Node changeIdsRecursively(Node node) {
            String previousId = node.id;
            List<Node> descendants = nodes.values().stream().filter(child -> previousId.equals(child.parent)).toList();
            nodes.remove(previousId);
            node.id = UUIDs.next();
            nodes.put(node.id, node);
            for (Node child : descendants) {
                child.parent = node.id;
                changeIdsRecursively(child);
            }
            return node;
        }
        void requireOpen() throws IOException { if (closed) throw new IOException("closed"); }
        private static LocalStore.Document document(Node node) { return new LocalStore.Document(node.id, node.name, node.directory); }
    }

    private static final class Node {
        String id;
        String name;
        final boolean directory;
        String parent;
        byte[] data;
        Node(String id, String name, boolean directory, String parent) { this.id = id; this.name = name; this.directory = directory; this.parent = parent; }
    }

    private static final class UUIDs {
        private static int sequence;
        static synchronized String next() { return "doc-" + (++sequence); }
    }
}
