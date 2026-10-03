package org.deltavault.android;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores connection metadata only in a directory Android excludes from backup. */
final class VaultProfiles {
    static final class Profile {
        final String id, name, url, user, type;
        Profile(String id, String name, String url, String user) {
            this(id, name, url, user, "webdav");
        }
        Profile(String id, String name, String url, String user, String type) {
            this.id = id; this.name = name; this.url = url; this.user = user;
            this.type = type;
        }
        boolean isLocal() { return "local".equals(type); }
    }
    private final AtomicFile file;
    VaultProfiles(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vaults.json")); }

    List<Profile> load() throws IOException {
        List<Profile> profiles = new ArrayList<>();
        if (!file.getBaseFile().exists()) return profiles;
        try {
            JSONArray rows = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String type = row.optString("type", "webdav");
                if (!"local".equals(type) && !"webdav".equals(type)) throw new IOException("未知の保管庫の種類です。");
                profiles.add(new Profile(row.getString("id"), row.getString("name"), row.getString("url"), row.optString("user", ""), type));
            }
            return profiles;
        } catch (Exception e) { throw new IOException("保管庫の登録情報を読み込めません。", e); }
    }

    Profile create(String name, String url, String user) {
        URI uri = URI.create(url);
        if (name.trim().isEmpty() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || user.contains(":")) {
            throw new IllegalArgumentException("名前とHTTPSの保管庫ルートURLを確認してください。");
        }
        return new Profile(UUID.randomUUID().toString(), name.trim(), url, user);
    }

    Profile createLocal(String name, String treeUri) {
        android.net.Uri uri = android.net.Uri.parse(treeUri);
        if (name.trim().isEmpty() || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                || !android.provider.DocumentsContract.isTreeUri(uri) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("名前と保管庫フォルダーを確認してください。");
        }
        return new Profile(UUID.randomUUID().toString(), name.trim(), treeUri, "", "local");
    }

    void save(List<Profile> profiles) throws IOException {
        FileOutputStream stream = null;
        try {
            JSONArray rows = new JSONArray();
            for (Profile profile : profiles) {
                JSONObject row = new JSONObject();
                row.put("id", profile.id); row.put("name", profile.name); row.put("url", profile.url); row.put("user", profile.user); row.put("type", profile.type);
                rows.put(row);
            }
            stream = file.startWrite();
            stream.write(rows.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("保管庫の登録情報を保存できません。", e);
        }
    }
}
