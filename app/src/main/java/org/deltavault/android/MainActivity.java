package org.deltavault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.deltavault.core.VaultSession;
import org.deltavault.core.WebDavStore;
import org.deltavault.core.LocalStore;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** All vault IO is serialized. Epoch changes discard results of work started before a lock. */
public final class MainActivity extends Activity {
    private static final int IMPORT_REQUEST = 100;
    private static final int LOCAL_FOLDER_REQUEST = 101;
    private static final int TEXT_LIMIT = 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger epoch = new AtomicInteger();
    private final Object connectionGate = new Object();
    private final BackgroundAutoLock autoLock = new BackgroundAutoLock(main, SystemClock::elapsedRealtime, () -> lock(false));
    private AutoLockSettings autoLockSettings;
    private Completion pendingCompletion;
    private SecureViews views;
    private VaultProfiles storage;
    private List<VaultProfiles.Profile> profiles = new ArrayList<>();
    private volatile VaultSession session;
    private volatile RemoteStore remote;
    private VaultProfiles.Profile activeProfile;
    private String directoryId = "";
    private final List<String> parents = new ArrayList<>();
    private TextView status;
    private LinearLayout content;
    private AlertDialog dialog;
    private Playback video;
    private DocumentViewer document;
    private boolean busy;
    private boolean stopped;
    private boolean importPicker;
    private String importDirectory;
    private VaultProfiles.Profile importProfile;
    private InputStream importStream;
    private byte[] importPlain;
    private String localProfileName;

    @Override public void onCreate(Bundle state) {
        super.onCreate(null); // Never restore plaintext from Activity instance state.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        views = new SecureViews(this); storage = new VaultProfiles(this); autoLockSettings = new AutoLockSettings(this);
        try { profiles = storage.load(); } catch (IOException e) { error(e.getMessage()); }
        showProfiles();
    }
    @Override protected void onStart() {
        super.onStart();
        // Check elapsed time before allowing retained content or IO results back onto the screen.
        autoLock.returnToApp(); stopped = false;
        Completion completion = pendingCompletion; pendingCompletion = null;
        if (completion != null) deliver(completion);
        try { if (document != null) document.setForeground(true); }
        catch (RuntimeException | LinkageError ignored) { lock(false); }
    }
    @Override protected void onStop() {
        stopped = true;
        try { if (document != null) document.setForeground(false); }
        catch (RuntimeException | LinkageError ignored) { lock(false); }
        autoLock.leaveApp(autoLockSettings.seconds() * 1000L);
        super.onStop();
    }
    @Override protected void onPause() {
        try { if (video != null) video.pause(); }
        catch (RuntimeException | LinkageError ignored) { lock(false); }
        finally { super.onPause(); }
    }
    @Override protected void onDestroy() { lock(false); io.shutdown(); cleanup.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { /* No UI text, credentials, names or bitmap state. */ }
    private static final class Completion {
        final int generation;
        final Runnable apply, discard;
        Completion(int generation, Runnable apply, Runnable discard) {
            this.generation = generation; this.apply = apply; this.discard = discard;
        }
    }
    private void deliver(Completion completion) {
        autoLock.expireIfDue();
        if (epoch.get() != completion.generation || isFinishing() || isDestroyed()) {
            completion.discard.run();
        } else if (stopped) {
            if (pendingCompletion != null) pendingCompletion.discard.run();
            pendingCompletion = completion;
        } else {
            completion.apply.run();
        }
    }
    private void whenStarted(Runnable action) {
        main.post(() -> {
            autoLock.expireIfDue();
            deliver(new Completion(epoch.get(), action, () -> { }));
        });
    }

    private void screen(String title, String subtitle) {
        LinearLayout shell = views.column();
        shell.setPadding(views.dp(20), views.dp(36), views.dp(20), views.dp(24));
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(views.dp(20), Math.max(views.dp(16), insets.getSystemWindowInsetTop()), views.dp(20), Math.max(views.dp(16), insets.getSystemWindowInsetBottom()));
            return insets;
        });
        shell.addView(views.text("DELTA / VAULT", 12, SecureViews.ACCENT));
        shell.addView(views.title(title));
        shell.addView(views.text(subtitle, 14, SecureViews.MUTED));
        if (session != null) shell.addView(views.button("すべてロック", () -> lock(true)));
        status = views.text(busy ? "処理中…" : "", 14, SecureViews.MUTED); shell.addView(status);
        ScrollView scroll = new ScrollView(this);
        content = views.column(); content.setPadding(0, 0, 0, 0);
        scroll.addView(content); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
    }
    private void showProfiles() {
        screen("保管庫", "FLOSS · ローカル / HTTPS WebDAV · 平文ファイルを端末に保存しません");
        content.addView(views.button("ローカルフォルダーを登録", this::addLocalProfile));
        content.addView(views.button("WebDAV保管庫を登録", this::addProfile));
        content.addView(views.button("自動ロック設定（" + AutoLockSettings.label(autoLockSettings.seconds()) + "）", this::configureAutoLock));
        if (profiles.isEmpty()) content.addView(views.text("端末内またはWebDAV上にあるCryptomator形式8の保管庫を登録してください。保管庫の作成はデスクトップ版で行えます。", 16, SecureViews.MUTED));
        for (VaultProfiles.Profile profile : profiles) {
            LinearLayout card = views.column(); card.setBackground(views.card());
            card.addView(views.text(profile.name, 20, SecureViews.INK));
            card.addView(views.text(profile.isLocal() ? "ローカルフォルダー" : "HTTPS WebDAV", 13, SecureViews.ACCENT));
            card.addView(views.text(profile.url, 13, SecureViews.MUTED));
            card.addView(views.button("解除する", () -> unlock(profile, null)));
            card.addView(views.button("登録を削除", () -> removeProfile(profile)));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.setMargins(0, views.dp(12), 0, 0); content.addView(card, layout);
        }
        String lockDescription = autoLockSettings.seconds() == 0 ? "アプリを離れるとすぐにロックします。"
                : "アプリを離れてから" + AutoLockSettings.label(autoLockSettings.seconds()) + "でロックします。";
        content.addView(views.text("テキスト・画像・PDF・動画をアプリ内で閲覧できます。画像と取り込みは16 MiBまで。" + lockDescription, 14, SecureViews.MUTED));
    }
    private void configureAutoLock() {
        if (busy || stopped) return;
        int[] seconds = AutoLockSettings.choices();
        String[] labels = new String[seconds.length]; int selected = 0;
        for (int i = 0; i < seconds.length; i++) {
            labels[i] = AutoLockSettings.label(seconds[i]);
            if (seconds[i] == autoLockSettings.seconds()) selected = i;
        }
        dialog = new AlertDialog.Builder(this).setTitle("アプリを離れてから自動ロック")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    try { autoLockSettings.setSeconds(seconds[which]); d.dismiss(); showProfiles(); }
                    catch (IOException e) { error("自動ロック設定を保存できません。"); }
                }).setNegativeButton("閉じる", null).create();
        showDialog();
    }
    private void addLocalProfile() {
        if (busy) return;
        LinearLayout form = views.column();
        EditText name = views.input("保管庫の名前", false);
        form.addView(name);
        form.addView(views.text("vault.cryptomator があるフォルダーを選択します。端末ストレージ・SDカードなどを利用できます。", 14, SecureViews.MUTED));
        form.addView(views.text("上書き時は暗号化された旧版を回復用に残します。他のアプリとの同時書き込みは避けてください。", 14, SecureViews.MUTED));
        dialog = new AlertDialog.Builder(this).setTitle("ローカル保管庫を登録").setView(form)
                .setNegativeButton("キャンセル", null).setPositiveButton("フォルダーを選択", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { error("保管庫の名前を入力してください。"); return; }
            localProfileName = label; dialog.dismiss();
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            try { startActivityForResult(picker, LOCAL_FOLDER_REQUEST); }
            catch (android.content.ActivityNotFoundException e) { localProfileName = null; error("フォルダー選択に対応したファイルアプリが必要です。"); }
        }));
        dialog.setOnDismissListener(d -> name.setText("")); showDialog();
    }
    private void registerLocalFolder(Uri uri, int returnedFlags, String name) {
        if (stopped || isFinishing()) return;
        int flags = returnedFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        boolean alreadyGranted = false;
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri)) { alreadyGranted = true; break; }
        }
        try {
            VaultProfiles.Profile profile = storage.createLocal(name, uri.toString());
            if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                throw new IOException("読み書きが許可されたフォルダーを選択してください。");
            }
            getContentResolver().takePersistableUriPermission(uri, flags);
            List<VaultProfiles.Profile> next = new ArrayList<>(profiles); next.add(profile); storage.save(next); profiles = next;
            showProfiles();
        } catch (Exception e) {
            if (!alreadyGranted) releaseFolderPermission(uri);
            error("ローカル保管庫を登録できません。フォルダーの読み書き権限を確認してください。");
        }
    }
    private void releaseFolderPermission(Uri uri) {
        try {
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(uri)) {
                    int flags = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                            | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                    getContentResolver().releasePersistableUriPermission(uri, flags);
                    return;
                }
            }
        } catch (SecurityException ignored) { /* Grant was already revoked by Android or the provider. */ }
    }
    private void addProfile() {
        if (busy) return;
        LinearLayout form = views.column();
        EditText name = views.input("保管庫の名前", false), url = views.input("https://server.example/dav/vault/", false), user = views.input("WebDAVユーザー名", false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name); form.addView(url); form.addView(user);
        form.addView(views.text("vault.cryptomator が置かれているフォルダーのURLです。パスワードは保存しません。", 14, SecureViews.MUTED));
        dialog = new AlertDialog.Builder(this).setTitle("保管庫を登録").setView(form).setNegativeButton("キャンセル", null).setPositiveButton("登録", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                VaultProfiles.Profile profile = storage.create(name.getText().toString(), url.getText().toString().trim(), user.getText().toString());
                List<VaultProfiles.Profile> next = new ArrayList<>(profiles); next.add(profile); storage.save(next); profiles = next;
                dialog.dismiss(); showProfiles();
            } catch (Exception e) { error("入力内容または保存先を確認してください。"); }
        }));
        showDialog();
    }
    private void removeProfile(VaultProfiles.Profile profile) {
        if (busy) return;
        dialog = new AlertDialog.Builder(this).setTitle("登録を削除").setMessage("「" + profile.name + "」の接続情報をこの端末から削除します。保管庫のファイルは保持されます。")
                .setNegativeButton("キャンセル", null).setPositiveButton("削除", (d, w) -> {
                    List<VaultProfiles.Profile> next = new ArrayList<>(profiles); next.remove(profile);
                    try {
                        storage.save(next); profiles = next;
                        if (profile.isLocal() && next.stream().noneMatch(p -> p.isLocal() && p.url.equals(profile.url))) {
                            releaseFolderPermission(Uri.parse(profile.url));
                        }
                        showProfiles();
                    } catch (IOException e) { error(e.getMessage()); }
                }).create(); showDialog();
    }
    private void unlock(VaultProfiles.Profile profile, Runnable afterUnlock) {
        if (busy || stopped) return;
        LinearLayout form = views.column();
        EditText davPassword = views.input("WebDAVパスワード", true), vaultPassword = views.input("保管庫パスワード", true);
        if (!profile.isLocal()) form.addView(davPassword);
        form.addView(vaultPassword);
        if (profile.isLocal()) form.addView(views.text("他のクライアントや同期アプリによる書き込みを停止してから解除してください。", 14, SecureViews.MUTED));
        dialog = new AlertDialog.Builder(this).setTitle(profile.name).setView(form).setNegativeButton("キャンセル", null).setPositiveButton("解除", null).create();
        dialog.setOnDismissListener(d -> { davPassword.setText(""); vaultPassword.setText(""); });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] dav = chars(davPassword), password = chars(vaultPassword);
            dialog.dismiss(); busy = true; status.setText("保管庫を解除しています…");
            int started = epoch.get();
            io.execute(() -> {
                RemoteStore connection = null;
                VaultSession opened = null;
                try {
                    connection = profile.isLocal() ? new LocalStore(new SafDocumentTree(getContentResolver(), Uri.parse(profile.url)))
                            : new WebDavStore(profile.url, profile.user, dav);
                    synchronized (connectionGate) {
                        if (epoch.get() != started) { connection.close(); return; }
                        remote = connection;
                    }
                    opened = VaultSession.unlock(connection, password);
                    VaultSession result = opened;
                    main.post(() -> deliver(new Completion(started, () -> {
                        session = result; activeProfile = profile; directoryId = ""; parents.clear(); busy = false;
                        if (afterUnlock != null) afterUnlock.run(); else refresh();
                    }, result::close)));
                } catch (Exception e) {
                    if (opened != null) opened.close(); else if (connection != null) connection.close();
                    main.post(() -> deliver(new Completion(started, () -> {
                        busy = false;
                        error(profile.isLocal() ? "保管庫を解除できません。フォルダーのアクセス権・パスワード・対応形式を確認してください。"
                                : "保管庫を解除できません。接続・パスワード・対応形式を確認してください。");
                        showProfiles();
                    }, () -> { })));
                } finally { Arrays.fill(dav, '\0'); Arrays.fill(password, '\0'); }
            });
        })); showDialog();
    }
    private static char[] chars(EditText input) {
        char[] chars = new char[input.length()]; input.getText().getChars(0, chars.length, chars, 0); input.setText(""); return chars;
    }

    private interface Work<T> { T run(VaultSession vault) throws Exception; }
    private interface Done<T> { void accept(T result); }
    private <T> void perform(Work<T> work, Done<T> done) {
        if (busy || session == null || stopped) return;
        busy = true; status.setText("処理中…"); int started = epoch.get(); VaultSession selected = session;
        io.execute(() -> {
            try {
                T result = work.run(selected);
                main.post(() -> deliver(new Completion(started, () -> {
                    busy = false; status.setText(""); done.accept(result);
                }, () -> dispose(result))));
            } catch (Exception e) {
                main.post(() -> deliver(new Completion(started, () -> {
                    busy = false; status.setText(""); error("操作を完了できません。通信、競合、ファイルの完全性、サイズ上限を確認してください。");
                }, () -> { })));
            }
        });
    }
    private static void dispose(Object result) {
        if (result instanceof AutoCloseable) try { ((AutoCloseable) result).close(); } catch (Exception ignored) { }
    }
    private void refresh() {
        if (session == null || busy) return;
        screen(activeProfile.name, parents.isEmpty() ? "保管庫のルート" : "フォルダー · 深さ " + parents.size());
        if (!parents.isEmpty()) content.addView(views.button("上のフォルダー", () -> { if (busy) return; directoryId = parents.remove(parents.size() - 1); refresh(); }));
        content.addView(views.button("ファイルを取り込む", this::chooseImport));
        content.addView(views.button("テキストを作成", () -> edit(null, null)));
        content.addView(views.button("フォルダーを作成", this::createDirectory));
        content.addView(views.button("再読み込み", this::refresh));
        String selectedDirectory = directoryId;
        perform(vault -> vault.list(selectedDirectory), nodes -> {
            if (nodes.isEmpty()) content.addView(views.text("このフォルダーは空です。", 16, SecureViews.MUTED));
            for (VaultSession.Node node : nodes) {
                content.addView(views.button((node.directory ? "▸  " : "•  ") + node.name, () -> open(node)));
            }
        });
    }
    private void open(VaultSession.Node node) {
        if (busy) return;
        if (node.directory) { parents.add(directoryId); directoryId = node.directoryId; refresh(); return; }
        if (DocumentPreview.isPdf(node.name)) { perform(vault -> vault.openStream(node), stream -> previewDocument(node, stream)); return; }
        if (VideoPreview.demux(node.name) != null) { perform(vault -> vault.openStream(node), stream -> previewVideo(node, stream)); return; }
        perform(vault -> vault.read(node), file -> preview(node, file));
    }
    private void previewVideo(VaultSession.Node node, VaultStream stream) {
        if (dialog != null) dialog.dismiss();
        int generation = epoch.get();
        try {
            VideoPreview preview = new VideoPreview(this, views, stream, node.name, () -> {
                if (epoch.get() == generation) {
                    if (dialog != null) dialog.dismiss();
                    error("動画を再生できません。対応形式、通信、ファイルの完全性を確認してください。");
                }
            });
            video = preview;
            dialog = new AlertDialog.Builder(this).setTitle(node.name).setView(preview.view).setPositiveButton("閉じる", null).create();
            dialog.setOnDismissListener(d -> { preview.close(); if (video == preview) video = null; });
            showDialog();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -2);
        } catch (IOException e) { stream.close(); error(e.getMessage()); }
    }
    private void preview(VaultSession.Node node, VaultSession.ReadFile file) {
        String extension = node.name.toLowerCase(Locale.ROOT);
        if (DocumentPreview.isImage(node.name)) { previewDocument(node, file); return; }
        if (file.bytes.length > TEXT_LIMIT || !textExtension(extension)) { file.close(); error("この形式のアプリ内閲覧は未対応です。テキストは1 MiB、画像・取り込みは16 MiBまでです。"); return; }
        try {
            // Reject malformed UTF-8, preventing lossy write-back of non-text files.
            java.nio.CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.bytes));
            if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
            edit(node, file);
        } catch (CharacterCodingException e) { file.close(); error("UTF-8テキストとして読み取れません。"); }
    }
    private void previewDocument(VaultSession.Node node, AutoCloseable input) {
        if (dialog != null) dialog.dismiss();
        int generation = epoch.get();
        DocumentPreview created = null;
        AlertDialog createdDialog = null;
        try {
            Runnable failed = () -> {
                if (epoch.get() == generation) {
                    if (dialog != null) dialog.dismiss();
                    error("画像・PDFを表示できません。対応形式、サイズ、通信、ファイルの完全性を確認してください。");
                }
            };
            DocumentPreview preview = input instanceof VaultStream
                    ? new DocumentPreview(this, views, (VaultStream) input, node.name, failed)
                    : new DocumentPreview(this, views, (VaultSession.ReadFile) input, node.name, failed);
            created = preview;
            document = preview;
            dialog = new AlertDialog.Builder(this).setTitle(node.name).setView(preview.view)
                    .setPositiveButton("閉じる", null).create();
            createdDialog = dialog;
            dialog.setOnDismissListener(d -> { preview.close(); if (document == preview) document = null; });
            showDialog();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        } catch (IOException | RuntimeException | LinkageError e) {
            if (createdDialog != null) try { createdDialog.dismiss(); } catch (RuntimeException ignored) { }
            if (dialog == createdDialog) dialog = null;
            if (document == created) document = null;
            if (created != null) try { created.close(); } catch (RuntimeException | LinkageError ignored) { }
            try { input.close(); } catch (Exception ignored) { }
            error("画像・PDFを表示できません。");
        }
    }
    private static boolean textExtension(String name) {
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".log") || name.endsWith(".yaml") || name.endsWith(".yml");
    }
    private void edit(VaultSession.Node node, VaultSession.ReadFile file) {
        if (busy) { if (file != null) file.close(); return; }
        LinearLayout form = views.column(); EditText name = views.input("ファイル名（例: note.txt）", false);
        EditText text = views.input("本文（UTF-8）", false); text.setSingleLine(false); text.setMinLines(6);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setText(node == null ? "note.txt" : node.name); name.setEnabled(node == null);
        if (file != null) text.setText(new String(file.bytes, StandardCharsets.UTF_8));
        String etag = file == null ? null : file.etag; if (file != null) file.close();
        form.addView(name); form.addView(text);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        dialog = new AlertDialog.Builder(this).setTitle(node == null ? "テキストを作成" : "テキストを編集").setView(scroll).setNegativeButton("閉じる", null).setPositiveButton("暗号化して保存", null).create();
        dialog.setOnDismissListener(d -> { text.setText(""); name.setText(""); });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            byte[] plain = text.getText().toString().getBytes(StandardCharsets.UTF_8);
            if (plain.length > TEXT_LIMIT) { Arrays.fill(plain, (byte) 0); error("テキストは1 MiBまでです。"); return; }
            String filename = name.getText().toString(); String destination = directoryId;
            dialog.dismiss();
            perform(vault -> { try { if (node == null) vault.writeNew(destination, filename, plain); else vault.overwrite(node, plain, etag); return null; } finally { Arrays.fill(plain, (byte) 0); } }, ignored -> refresh());
        })); showDialog();
    }
    private void createDirectory() {
        if (busy) return;
        EditText name = views.input("フォルダー名", false);
        dialog = new AlertDialog.Builder(this).setTitle("フォルダーを作成").setView(name).setNegativeButton("キャンセル", null).setPositiveButton("作成", (d, w) -> {
            String filename = name.getText().toString(); String parent = directoryId;
            perform(vault -> { vault.createDirectory(parent, filename); return null; }, ignored -> refresh());
        }).create(); dialog.setOnDismissListener(d -> name.setText("")); showDialog();
    }
    private void chooseImport() {
        if (busy || session == null) return;
        importProfile = activeProfile; importDirectory = directoryId; importPicker = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT_REQUEST);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == LOCAL_FOLDER_REQUEST) {
            String name = localProfileName == null ? "ローカル保管庫" : localProfileName;
            localProfileName = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData(); int flags = data.getFlags();
                whenStarted(() -> registerLocalFolder(uri, flags, name));
            }
            return;
        }
        if (request != IMPORT_REQUEST || !importPicker) return;
        importPicker = false;
        VaultProfiles.Profile profile = importProfile; String destination = importDirectory; importProfile = null; importDirectory = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        whenStarted(() -> {
            if (profile == null) return;
            if (session != null && activeProfile != null && activeProfile.id.equals(profile.id)) {
                directoryId = destination; upload(uri, destination);
            } else {
                unlock(profile, () -> { directoryId = destination; upload(uri, destination); });
            }
        });
    }
    private void upload(Uri uri, String destination) {
        int importEpoch = epoch.get();
        perform(vault -> {
            String name;
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("取り込むファイル名が不明です。");
                name = cursor.getString(0);
            }
            byte[] plain = new byte[16 * 1024 * 1024]; int used = 0;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("ファイルを開けません。");
                synchronized (connectionGate) {
                    if (epoch.get() != importEpoch) throw new IOException("保管庫はロックされました。");
                    importStream = input; importPlain = plain;
                }
                while (used < plain.length) {
                    if (epoch.get() != importEpoch) throw new IOException("保管庫はロックされました。");
                    int count = input.read(plain, used, plain.length - used); if (count == -1) break; used += count;
                }
                if (used == plain.length && input.read() != -1) throw new IOException("ファイルは16 MiBまでです。");
                if (epoch.get() != importEpoch) throw new IOException("保管庫はロックされました。");
                byte[] exact = Arrays.copyOf(plain, used);
                try { vault.writeNew(destination, name, exact); } finally { Arrays.fill(exact, (byte) 0); }
                return null;
            } finally {
                Arrays.fill(plain, (byte) 0);
                synchronized (connectionGate) { if (importPlain == plain) { importPlain = null; importStream = null; } }
            }
        }, ignored -> { parents.clear(); directoryId = ""; refresh(); });
    }

    private void lock(boolean visible) {
        RemoteStore connection;
        InputStream input;
        synchronized (connectionGate) {
            epoch.incrementAndGet(); connection = remote; remote = null;
            input = importStream; importStream = null;
            if (importPlain != null) Arrays.fill(importPlain, (byte) 0);
            importPlain = null;
        }
        autoLock.cancel(); busy = false;
        Completion completion = pendingCompletion; pendingCompletion = null;
        if (completion != null) completion.discard.run();
        Playback oldVideo = video; video = null;
        if (oldVideo != null) try { oldVideo.close(); } catch (RuntimeException | LinkageError ignored) { /* Continue revoking storage and keys. */ }
        DocumentViewer oldDocument = document; document = null;
        if (oldDocument != null) try { oldDocument.close(); } catch (RuntimeException | LinkageError ignored) { /* Continue revoking storage and keys. */ }
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        VaultSession old = session; session = null;
        if (connection != null) connection.close();
        if (old != null && !cleanup.isShutdown()) cleanup.execute(old::close);
        if (input != null) try { input.close(); } catch (IOException ignored) { }
        directoryId = ""; parents.clear(); activeProfile = null;
        if (views != null && !isFinishing()) showProfiles();
        if (visible && status != null) status.setText("保管庫をロックしました。");
    }
    private void error(String message) {
        if (stopped || isFinishing()) return;
        android.widget.Toast.makeText(this, message == null ? "操作を完了できません。" : message, android.widget.Toast.LENGTH_LONG).show();
    }
    private void showDialog() {
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (android.os.Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
        dialog.show();
    }
    @Override public void onBackPressed() {
        if (session != null) {
            if (busy) lock(true);
            else if (!parents.isEmpty()) { directoryId = parents.remove(parents.size() - 1); refresh(); }
            else lock(true);
        } else super.onBackPressed();
    }
}

