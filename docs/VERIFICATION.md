# 検証記録

検証日：2026-10-02（Asia/Tokyo）

JDK 21、Gradle 8.14.3、Android Gradle Plugin 8.10.1、Android SDK 36で検証しました。

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug --write-locks --no-daemon
```

結果：成功。VaultSessionTest 5件、WebDavStoreTest 14件、合計19件が成功しました。
Android Lintのエラーは0件、タッチ監視に関するClickableViewAccessibilityの警告は2件です。

対象：

- 公式cryptolibによるSIV_GCM・SIV_CTRMACの暗号化・復号。
- フォルダーのディレクトリーハッシュ配置、長い名前の.c9s対応。
- 間違ったパスワード、設定署名と暗号化コンテンツの改ざん、過大なKDF・JSONネストの拒否。
- 条件付き上書き、別保管庫のNode利用と閉じたセッションの拒否、平文配列の消去。
- MOVE失敗時に不完全な新規エントリーを公開しないこと。
- HTTPS模擬サーバーでのDAV操作、サイズ制限、XML/パスの検証、リダイレクト拒否。
- 本文読み込み開始後にロックしてHTTP操作をキャンセルすること。

ライセンス資産を追加した後、アプリの再生成だけを実行して成功しました。
APK内のcryptolib両方式のServiceLoader登録と、ライセンス同梱を確認しました。
DEX内のByteBuffer.clear呼び出しがAndroid互換のBuffer戻り値に変換されていることも確認しました。
依存関係はapp/gradle.lockfileとcore/gradle.lockfileに固定しています。

この記録は実機での動作、デスクトップ版で作成した実保管庫との相互運用、
実WebDAVサーバーとの互換性、端末のバックアップ・画面保護・RAM消去の実測を保証しません。
暗号テストの保管庫は公式cryptolibからメモリー内に生成したものです。

## 0.2.0：ローカルフォルダー対応

同日、同じビルド環境でローカルフォルダー対応を検証しました。

```powershell
.\gradlew.bat :core:test --tests org.deltavault.core.LocalStoreTest --tests org.deltavault.core.VaultSessionTest :app:testDebugUnitTest --tests org.deltavault.android.LocalFolderTest --write-locks --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests org.deltavault.android.LocalFolderTest :app:assembleDebug :app:lintDebug --no-daemon
```

最終実装ではLocalStoreTest 15件、VaultSessionTest 6件、LocalFolderTest 6件、計27件が成功しました。
core側の成功結果はAndroid側の最終修正後も有効です。WebDavStoreTestはこの変更では再実行していません。
APK生成成功、Lintエラー0件、既存のClickableViewAccessibility警告2件です。
出力APKのバージョンは0.2.0（versionCode 2）です。

追加した検証対象：

- ローカル暗号文のサイズ制限、相対パス・重複名・重複Document IDの拒否。
- ステージ書き込みの検証、提供元による勝手な改名の拒否。
- SHA-256による変更検出、旧暗号文の回復コピー保持、保存失敗時の復元と競合時の保護。
- 名前変更でファイル・フォルダー・子孫のDocument IDが変わる提供元。
- 名前変更完了後にエラーを返す提供元、中断された読み込みとロック後の書き込み拒否。
- 実際の一時ファイルを暗号化データの保存先にした、両暗号方式・長い名前・階層・上書き・再解除の統合テスト。
- RobolectricのAPI 28・29で模擬DocumentsProviderを使ったSAF境界の読み書き・名前変更・ストリーム中断。
- 初回問い合わせ前に接続をロック対象へ登録できること、初回問い合わせのキャンセル。
- 旧WebDAV登録の移行、ローカル登録の保存、フォルダー選択の権限要求、ローカル解除のパスワード欄。
- フォルダー選択から戻った後の登録、同じURIの複数登録、最後の登録削除時の権限解放とファイル保持。

独立レビューで見つかった初回問い合わせのキャンセル、過大なSHA-256読み込み、
長い名前の回復コピーによる一覧エラーを修正し、回帰テストと再レビューで確認しました。
SAF提供元の原子的な比較・交換は実装できないため、同じ保管庫への同時書き込みは対応範囲外です。
実機の端末ストレージ・SDカードによる検証は未実施です。

## 0.3.0：背景移行後のタイマー式自動ロック

同日、同じビルド環境で検証しました。

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests org.deltavault.android.AutoLockTimerTest --tests org.deltavault.android.AutoLockLifecycleTest --tests org.deltavault.android.LocalFolderTest --no-daemon
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

すべて成功しました。AutoLockTimerTest 9件、AutoLockLifecycleTest 7件、LocalFolderTest 6件、
計22件が成功しました。APKは0.3.0（versionCode 3）、Lintはエラー0件・警告0件です。
旧無操作タイマー用のタッチ監視を削除したため、以前のアクセシビリティ警告もなくなりました。
暗号・ストレージのcore実装は変更していないため、この変更ではcoreテストを再実行していません。

検証対象：

- 既定30秒、許可された待ち時間の永続化、不正・巨大な設定の既定値への復帰、保存失敗時の旧設定保持。
- 期限直前の復帰で取り消すこと、30秒時点で一度だけロックすること、再度離れた際の期限の更新。
- Handlerが停止した状態で経過時間だけ進め、復帰時に期限切れを検出すること。
- 実際のcryptolibの鍵を持つ解除済みセッションと未保存の編集内容を、29秒以内の復帰で維持すること。
- 前面で180秒無操作でもロックしないこと、背景で30秒経過すると鍵を破棄し編集欄を消すこと。
- 背景で完了した処理を復帰まで保留し、期限切れでReadFileの平文配列を消去すること。
- 期限切れ後の解除結果がセッションを復活させず、鍵を破棄すること。
- 手動ロック・Activity破棄の即時処理、設定画面から60秒へ変更した際の動作。
- ファイル取り込みが期限内なら同じ保管庫で続行し、期限切れなら保管庫パスワードを再要求すること。
- 既存のローカル登録・永続アクセス権・SAF境界の回帰検証。

独立レビューで、タイマー・ライフサイクル・保留結果の所有権とドキュメントの整合性を確認しました。
OSの凍結やスリープ下で設定時刻ちょうどに物理メモリーを消去する保証はなく、
復帰時には経過時間を確認してからコンテンツを表示します。実機のバックグラウンド制御・スリープ検証は未実施です。

## 0.4.0：LibVLCによる動画ストリーミング

同日、同じビルド環境で、公式LibVLC Android 3.7.7を組み込んだ実装を検証しました。

```powershell
.\gradlew.bat :core:test --tests org.deltavault.core.VaultStreamTest --tests org.deltavault.core.WebDavStoreTest --tests org.deltavault.core.VaultSessionTest --tests org.deltavault.core.LocalStoreTest --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests org.deltavault.android.VideoSecurityTest --tests org.deltavault.android.AutoLockLifecycleTest --tests org.deltavault.android.LocalFolderTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.deltavault.android.VideoPlaybackTest' --no-daemon
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

対象ごとの最終成功結果は、VaultStreamTest 14件、WebDavStoreTest 18件、VaultSessionTest 6件、
LocalStoreTest 15件、VideoSecurityTest 6件、AutoLockLifecycleTest 7件、LocalFolderTest 6件です。
core計53件、Robolectric計19件が成功しました。変更していない対象の成功結果は再利用し、
修正後は影響する対象だけ再実行しています。

ネイティブ検証はヘッドレスのAndroid 16／API 36.1、x86_64エミュレーターで実施しました。
VideoPlaybackTestのSAFテストと動画テストの2件が成功しました。
SAFテストは個別実行時の成功結果を再利用し、最終の動画テストではAVI・カバー画像付きMKV・MP4の3本を検証しています。
全クラス探索はこの環境でテスト開始前にタイムアウトしたため、上記のクラス指定を使用しました。
APKは0.4.0（versionCode 4）、最終ビルド成功、Lintはエラー0件・警告0件です。

追加した検証対象：

- 両暗号方式のヘッダー・チャンク認証、境界をまたぐ読み取り、逆方向シーク、EOF、読み取り上限。
- CTR-MACで認証を省略しないこと、部分チャンクのタグ改ざん、チャンク順序の改ざん、エラー時の出力消去。
- 20 GiBの仮想暗号文を使った4 GiBを超える位置へのシークと、限定された読み取り量。
- ストリーム単独の終了とセッション終了によるキャンセル、平文キャッシュとファイルヘッダーの破棄。
- HTTPS模擬WebDAVのRange／If-Match、ETag・Content-Range・全体長・実受信長・Content-Encodingの検証。
- 一つの動画ストリームの終了が、同じWebDAV接続の別操作を取り消さないこと。
- Androidの実際のOs.preadを使った模擬DocumentsProviderの位置指定読み取り、境界検証、閉じた記述子の拒否。
- 私有proxy FDから実際のLibVLCへ渡し、デコード統計と表示フレーム統計が増えること。
- 読み込み中の一時停止要求、再生再開、シーク、一時停止、画面とダイアログのFLAG_SECURE。
- 再生前後のfiles／cache／noBackup領域の不変性、app_vlcが作られないこと、HOMEとXDG保存先が/dev/nullであること。
- /dev/null配下のカバー画像保存先がENOTDIRで作成を拒否すること。
- 手動ロックによるプレイヤー・復号ストリーム・callbackの失効と、実際のマスターキーの破棄。
- 背景移行での一時停止、既定30秒の自動ロック、プレイヤーの停止・一時停止に例外があっても鍵を破棄すること。

VLCが字幕無効時にもフォントデータベースを作成し、初期表示を遅らせることがネイティブ検証で分かりました。
グローバルのtext-renderer=noneで不要なレンダラーを無効にしてから、上記の動画検証が成功しています。
合成動画の生成スクリプトと出所はapp/src/androidTest/fixturesとassets/README.mdに同梱しました。
独立レビューでチャンク認証、リソース所有権、ロック時の例外処理、WebDAVのキャンセルを確認しました。

この検証は実機・SDカード提供元・実WebDAVサーバー・デスクトップで作成した実保管庫との相互運用を保証しません。
端末ごとのコーデック・長時間再生・消費電力、LibVLC／GPU／OSのメモリー残留の測定は未実施です。
公的なバイナリー配布用の署名と、LibVLCネイティブ・contribの完全な対応ソースアーカイブは別途必要です。

## 0.5.0：隔離プロセスによる画像・AVIF・PDF表示

2026-10-02、同じJDK・SDK環境で、libavif Android 1.3.0.841110fdとプラットフォームのPdfRendererを使う実装を検証しました。

```powershell
.\gradlew.bat :app:compileDebugAndroidTestJavaWithJavac :app:testDebugUnitTest --tests org.deltavault.android.VideoSecurityTest --tests org.deltavault.android.AutoLockLifecycleTest --write-locks --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests org.deltavault.android.PreviewImageBoundsTest --tests org.deltavault.android.DocumentLifecycleTest --tests org.deltavault.android.DocumentPreviewRaceTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.deltavault.android.NativePreviewTest' --write-locks --no-daemon
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

最終実装のPreviewImageBoundsTest 5件、DocumentLifecycleTest 3件、DocumentPreviewRaceTest 7件が成功しました。
同じ変更でVideoSecurityTest 6件、AutoLockLifecycleTest 7件の成功結果も確認し、関連単体テスト計28件が成功しています。
coreの暗号・WebDAV・SAF保存実装は変更していないため、0.4.0の検証結果を再利用しています。
NativePreviewTestはヘッドレスAndroid 16／API 36.1・x86_64エミュレーターで2件が成功しました。
APKは0.5.0（versionCode 5）、最終生成成功、Android Lintはエラー0件・警告0件です。
AVIFの4 ABIと追加した5種類のライセンス資産をAPK内で確認し、同梱の依存関係通知が元ファイルと一致することも確認しました。
今回起動したヘッドレスエミュレーターは検証終了後に停止しました。

追加した検証対象：

- PNG・JPEG・WebP・AVIFの実際のデコード結果の色、表示領域、背景での画像破棄と前面復帰時の再表示。
- 2ページPDFの実際の描画、ページ番号、前後のページ移動とボタンの境界。
- 描画サービスがアプリ本体と異なる隔離UIDで動き、INTERNET権限が拒否されること。
- 私有proxy FDの失効、画像読み込み後の本体RAMバッファーの消去、表示終了時のBitmapの解放。
- PDF表示中の手動ロックでストリームを失効させ、表示画像と実際のcryptolibマスターキーを破棄すること。
- files／cache／noBackup領域の前後のファイル一覧・サイズが変わらないこと。
- 不正なAVIF・PDFを一度だけ拒否し、その入力を閉じること。表示エラーが保管庫の解除状態を復活させないこと。
- AVIFの互換ブランド・alpha参照のFullBox、箱のサイズ・ネスト・数・画像寸法の境界。
- Bitmapのコピーの独立性、不正・古いtoken・背景への結果の破棄。
- 初期読み込み中の背景→復帰でも描画タイムアウトが維持されることと、遅れて届く背景callbackからの再描画。
- ドキュメントビューアーの終了や背景処理が例外を出しても、鍵の破棄と自動ロックを続行すること。

独立レビューでAVIFの互換ブランド・FullBoxと背景callback／タイムアウトの競合を修正しました。
実際のBitmap IPCでは受信画像が可変とは限らないことが分かったため、同期callback内で独立したmutable画像へコピーし、
その完了後に送信側を消去する方式へ修正しました。修正後、全形式の画素と再表示のネイティブ検証が成功しています。
合成画像・PDFの生成スクリプトと出所をapp/src/androidTest/fixturesおよびassets/README.mdに同梱しています。

この検証は実機・Android 8〜9のサービス実行・実WebDAVサーバーとの相互運用を保証しません。
AVIFの回転・鏡像・ICC/HDR・アニメーション、JPEGのEXIF回転は未対応です。
PDFはページ描画のみで、パスワード解除、文字検索・選択、フォーム・注釈編集は未実装です。
ネイティブデコーダー内部の全メモリー使用量や、OS・GPUを含む平文の完全消去を実測・保証したものではありません。
