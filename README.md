# Delta Vault

> **開発終了・アーカイブ（2026-10-04）**
>
> MaterialFilesの利用へ移行する方針により、このプロジェクトの継続開発を終了しました。
> 暗号化ストリーム、平文ファイルを保存しない閲覧、隔離プロセスによる画像・PDF描画の参考実装としてソースを保存します。
> 初期実装のままであり、実機・実サーバーの相互運用検証と外部セキュリティ監査は未実施です。
> 今後の保守・不具合修正・セキュリティ更新は予定していません。
> APKの配布リリースは作成せず、ソース・テスト・検証記録・ライセンス通知を保存しています。

Cryptomator形式8の保管庫にアクセスする、AGPL-3.0で公開するAndroidクライアントの初期実装です。
Android 8.0（API 26）以降に対応します。ライセンス購入・Google Play Services・広告・解析SDKを必要としません。
Cryptomator公式のプロジェクトとは独立したアプリです。

## 現在できること

- ローカルフォルダーとHTTPS WebDAV上の既存保管庫を複数登録し、個別に解除する。
- Androidのフォルダー選択（SAF）で端末ストレージ・SDカードにアクセスする。広範なストレージ権限は要求しない。
- パスワード保護された形式8の `SIV_GCM` / `SIV_CTRMAC` 保管庫を読み書きする。
- ファイルとフォルダーの一覧表示、階層移動、フォルダー作成。
- 任意のファイルをAndroidのファイル選択画面から取り込み、暗号化してアップロードする。
- UTF-8テキストの新規作成・閲覧・編集。対応拡張子：txt、md、csv、json、xml、log、yaml、yml。
- PNG、JPEG、WebP、AVIFの静止画をアプリ内で閲覧し、拡大縮小・移動する。
- PDFをアプリ内で閲覧し、ページを切り替え、拡大縮小・移動する。
- LibVLCを組み込み、MP4/M4V/MOV、MKV/WebM、AVI、TS/M2TS、OGVの動画をアプリ内で再生・一時停止・シークする。
- `.c9s` の長いファイル名と長いフォルダー名に対応する。
- 新規ファイルの重複を拒否し、編集時はWebDAVのETagまたはローカル暗号文のSHA-256で変更を検出する。

画像・通常の読み書き・取り込みの上限は1ファイル16 MiB、テキスト編集1 MiB、1フォルダー10,000エントリーです。
PDF閲覧は256 MiB・10,000ページまでの部分読み込みに対応します。PDFの取り込みは16 MiBまでです。
動画閲覧は暗号文1 TiBまでの部分読み込みに対応し、全体をRAMへ展開しません。動画の取り込みは16 MiBまでです。
新規作成・取り込みのファイル名はUTF-8で255バイトまでです。
保管庫の新規作成、形式7以前、Cryptomator Hub、シンボリックリンク、名前変更・削除、
音声・Office文書のビューアー、外部アプリへの平文共有、オフライン同期は未実装です。
対応形式以外は拒否します。既存保管庫の形式を自動で変換しません。

## 使用方法

1. デスクトップ版Cryptomatorで、形式8のパスワード保護された保管庫を作成する。
2. その保管庫の暗号化されたフォルダー全体を端末・SDカードまたはWebDAVサーバーに置く。
3. ローカルの場合は「ローカルフォルダーを登録」で名前を入力し、`vault.cryptomator` と
   `masterkey.cryptomator` があるフォルダーを選択して読み書きを許可する。
   WebDAVの場合は「WebDAV保管庫を登録」で名前、WebDAVユーザー名、保管庫ルートのHTTPS URLを入力する。
   例：`https://server.example/remote.php/dav/files/alice/PrivateVault/`
   このURLに `vault.cryptomator` と `masterkey.cryptomator` がある必要があります。
4. 「解除する」で保管庫パスワードを入力する。WebDAVの場合はWebDAVパスワードも入力する。
5. ファイルを閲覧・編集・取り込む。すべての暗号化・復号を端末で行う。

パスワードは保存しません。アプリを離れてからデフォルト30秒で自動ロックします。
30秒以内に戻ると解除状態・編集中の内容を維持します。ファイル選択も同じ扱いで、期限切れの場合は取り込み前に再解除します。
保管庫一覧の「自動ロック設定」で、すぐに・15秒・30秒・60秒・120秒・300秒を選択できます。
前面での無操作では自動ロックしません。「すべてロック」は待ち時間なしでロックします。
ローカルフォルダーのアクセス権は再起動後も保持します。Android側で取り消した場合は再登録が必要です。
保管庫の「登録を削除」は端末の接続設定を削除し、保管庫のファイルには触れません。
同じフォルダーの登録がなくなった際に、このアプリの永続アクセス権も解放します。

ローカルのフォルダー提供元には、通常ファイルの読み書き・作成・名前変更が必要です。
ステージの後片付けには削除機能も使います。仮想文書や読み取り専用の提供元は利用できません。
Androidの制限により、内部ストレージのルートや `Android/data` などを選択できない場合があります。
その場合は選択可能なサブフォルダーに保管庫を置いてください。

ローカル保存は暗号文をステージに書き込んで検証し、名前変更して公開します。
上書き時は旧暗号文を `元の暗号化名.16桁のランダム16進数.backup` として残します。
このコピーは通常の一覧には表示しません。容量を使用するため、必要に応じて外部のファイル管理で整理してください。
手動復旧する場合は全体をバックアップして全クライアントを閉じ、元の暗号化名のファイルを退避してから
回復用コピーの `.16桁.backup` 部分を外して元の名前に戻します。
SAFには原子的な条件付き更新がないため、他のクライアント・同期アプリと同時に書き込まないでください。
提供元が名前変更を完了して応答に失敗する場合など、再読み込み・手動復旧が必要になることがあります。

WebDAVサーバーはPROPFIND、GET、PUT、MKCOL、MOVEと条件付きリクエストを正しく実装している必要があります。
HTTPS証明書はAndroidの通常の信頼検証を通る必要があります。認証はBasic認証です。
リダイレクトは追従しないため、最終的な保管庫URLを直接登録してください。
上書き編集には強いETagが必要です。弱いETagやETagがないサーバーでは編集を拒否します。
フォルダー・長い名前のファイルはステージ領域で完成させ、`MOVE` と `Overwrite: F` で公開します。
通信失敗時に暗号化された `.upload` ステージや未参照のデータディレクトリーが残ることがあります。
通常のファイルPUTの原子性とHTTP条件の実施はサーバーに依存します。

## 平文と鍵の扱い

平文ファイルを「一時フォルダーに保存して後で削除」する方式は採用していません。
復号・閲覧・編集・取り込みはメモリー内で行い、アプリのストレージには平文ファイルやサムネイルを保存しません。
WebDAVのHTTPディスクキャッシュも無効です。永続化するのは保管庫の名前・接続先・種類・ユーザー名と自動ロックの待ち時間だけです。
ローカル接続先は選択したフォルダーのURIです。平文を置く一時ファイルは作成しません。
登録情報はAndroidのバックアップ対象外の領域に保存し、アプリ全体のバックアップと端末移行も除外しています。

背景移行後の設定時間が経過するとロックします。通信をキャンセルし、取り込みストリームを閉じ、
画面と保持している平文バッファーを消去し、公式ライブラリーの鍵破棄処理を呼びます。
猶予中は鍵と編集内容をメモリーに保持し、背景で完了した処理の画面表示は復帰まで保留します。
スリープやOSによるプロセスの凍結でタイマー実行が遅れた場合も、復帰時に経過時間を確認してから解除状態を使います。
正確な時刻での物理メモリー消去は保証しません。画面の再作成・アプリ終了後に解除状態は復元しません。
画面・ダイアログにFLAG_SECUREを設定し、コピー・切り取り・共有とUI状態の保存を抑制します。
パスワードの自動入力とIMEの学習も抑制します。

**フォレンジック分析によるデータ回収を絶対に防ぐ保証はありません。**
解除中の鍵・平文はRAMに存在します。Javaの不変文字列、UI・IME・暗号プロバイダー・GPUが持つコピー、
OSのメモリー管理までアプリが完全に消去することはできません。
root化、侵害されたOSやキーボード、プロセスメモリーの取得、外部カメラによる撮影などは保護対象外です。
取り込み元のファイルと外部のファイル提供アプリが保持するデータも、このアプリでは消去できません。
詳細は [docs/SECURITY.md](docs/SECURITY.md) を参照してください。

## 画像・PDFの閲覧

ファイルを選ぶと組み込みビューアーが開きます。ピンチで拡大縮小、ドラッグで移動できます。
ダブルタップで拡大し、再度ダブルタップすると全体表示へ戻ります。PDFには前後のページへ移動するボタンがあります。
背景へ移ると表示中の画像を消去し、ロック期限内に戻ると同じページを再描画します。
ロックすると復号の供給を取り消し、画像と描画プロセスを解放します。

画像・PDFの解析は、保管庫の鍵・接続情報・ストレージ権限・ネットワーク権限を持たないAndroidの隔離プロセスで行います。
画像は認証済みのRAMバッファーを私有proxy FDで渡し、PDFは必要な範囲を認証・復号して渡します。
PDFにはAndroidのPdfRenderer、AVIFにはFLOSSのlibavif／dav1dを組み込み、外部ビューアーやWebViewは使いません。
平文の一時ファイル、サムネイル、HTTP配信先は作成しません。

画像の入力寸法は各辺16,384・3,200万画素まで、表示用画像は各辺2,048までに縮小します。
PDFも各ページを最大2,048ピクセルの辺で描画します。描画は約20秒でタイムアウトします。
これらはファイル・メタデータ・表示出力の上限であり、ネイティブデコーダー内部の全メモリー使用量の上限を保証するものではありません。
対応拡張子でも破損状態や構成によって表示できない場合があります。
AVIFとWebPは先頭の静止フレームを表示します。AVIFの回転・鏡像メタデータ、ICC/HDRの正確な色再現、
JPEGのEXIF回転は未対応です。PDFのパスワード解除、文字検索・選択、フォーム入力・注釈編集は未実装です。

## 動画の閲覧

動画ファイルを選ぶと内蔵プレイヤーが開きます。再生・一時停止ボタンと再生位置バーを使えます。
アプリを離れると一時停止し、期限内に戻った場合は「再生」で続けます。
手動ロック・自動ロック・画面の破棄でプレイヤー、復号ストリーム、記述子を解放します。
外部VLCアプリ、HTTP配信サーバー、外部公開ContentProviderは使いません。

公式cryptolibでヘッダーと必要なチャンクを認証し、最大32 KiBの平文チャンクをRAMに保持します。
読み取り専用のAndroid proxy FDからLibVLCへ渡すため、平文の一時ファイルは不要です。
LibVLCには別途デコード用のRAMがあります。ソフトウェアデコードを指定し、字幕・テキストレンダラー・外部メタデータ取得・
自動プリパースを無効にしています。内蔵カバー画像を通常のLibVLCがディスクへ保存する経路も、
HOMEとXDG保存先を `/dev/null` に設定して書き込みを拒否します。
Java側のバッファーは消去しますが、LibVLC・OS・画面出力が保持するコピーの完全消去は保証しません。

WebDAV動画閲覧にはHTTP Range（206、正しいContent-Range）、強いETag、If-Matchへの対応が必要です。
非対応サーバーは再生を拒否します。途中でファイル版やサイズが変わった場合も停止します。
ローカル動画はSAF提供元がシーク可能な通常ファイルを返す必要があります。パイプを返す提供元は未対応です。
暗号認証は読み取ったチャンクごとに実行し、未読部分まで事前検査する方式ではありません。
対応する拡張子でも、収録コーデックや破損状態によって再生できない場合があります。

## ビルドと検証

JDK 17以上、Android SDK Platform 36、Android SDK Build Toolsを用意し、`ANDROID_HOME` を設定します。
Gradle 8.14.3のラッパーを同梱し、公式配布物のSHA-256を固定しています。

Windows：

```powershell
$env:ANDROID_HOME = 'C:\Users\YOUR_NAME\AppData\Local\Android\Sdk'
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

Linux/macOS：

```sh
sh ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

生成されるAPK：`app/build/outputs/apk/debug/org.deltavault.android-debug.apk`
APK名はビルド対象のパッケージ名（applicationId）とバリアント名から生成します。
デバッグAPKは開発用です。一般配布用の署名・リリース設定は別途必要です。
ネイティブ表示テストは、エミュレーターまたは実機を接続し、対象クラスを指定して実行します。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.deltavault.android.VideoPlaybackTest' --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.deltavault.android.NativePreviewTest' --no-daemon
```

実機・実サーバーでの相互運用検証と外部セキュリティ監査は未実施です。
重要な保管庫で使い始める前に、バックアップした検証用保管庫で動作を確認してください。

構成は `app/`（Android UI・SAF・接続設定）と `core/`（暗号・WebDAV・ローカル保存・JVMテスト）です。
暗号アルゴリズム自体は再実装せず、`org.cryptomator:cryptolib:2.2.0` を使用します。
これは公式Android版が指定している版に合わせています。
動画エンジンは `org.videolan.android:libvlc-all:3.7.7` を固定しています。
AVIFデコーダーは `org.aomedia.avif.android:avif:1.3.0.841110fd` を固定しています。

## ライセンスと参照資料

このプロジェクトのソースコードは [AGPL-3.0](LICENSE) です。
依存ライブラリーとビルド環境については [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。

- [Cryptomator desktop](https://github.com/cryptomator/cryptomator)
- [Cryptomator cryptolib](https://github.com/cryptomator/cryptolib)
- [公式Android版の依存関係](https://github.com/cryptomator/android/blob/develop/buildsystem/dependencies.gradle)
- [保管庫形式・暗号仕様](https://docs.cryptomator.org/security/vault/)
- [Cryptomatorの保護範囲](https://docs.cryptomator.org/security/security-target/)
- [Androidの機密画面の保護](https://developer.android.com/security/fraud-prevention/activities)
- [Androidバックアップ仕様](https://developer.android.com/identity/data/autobackup)
- [Androidのフォルダー選択とアクセス権](https://developer.android.com/training/data-storage/shared/documents-files)
