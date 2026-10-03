# セキュリティ設計と未検証事項

## 想定する保護

ロック時やアプリの終了後に、アプリが平文ファイルを端末の永続ストレージへ残さないことを目的とします。
暗号化されているローカル・WebDAV上のファイルを第三者が取得しても、保管庫の鍵なしでは内容を読めない設計です。
フォルダー提供元、WebDAV管理者やネットワークから返されるファイル・名前・設定は信頼せず検証します。
ファイルのメタデータ、サイズ、更新時刻、アクセス先サーバーなどは秘匿されるとは限りません。

## 実装上の対策

- 公式cryptolibのファイル名・ディレクトリーハッシュ・認証付きコンテンツ暗号処理を使う。
- 設定JWTはHMACを定数時間比較で検証してから、形式・暗号方式・名前短縮閾値を使う。
  HS256/HS384/HS512以外を拒否する。鍵IDは `masterkeyfile:masterkey.cryptomator` のみに限定する。
- masterkeyは64 KiB、設定は16 KiBに制限し、JSON重複キー・過大なネストを拒否する。
  scryptはNが2以上の2の冪かつ65,536以下、rが1〜8、pが1に制限する。
  より大きい設定を使う正当な保管庫も、端末の資源保護のために拒否する。
- テキスト・画像は復号を完了して全チャンクの認証を確認してから、平文をUIに渡す。
  動画・PDFはヘッダーと要求範囲の全チャンクを認証してから読み取り専用proxy FDへ渡す。
  失敗時は保持バッファーと要求された出力範囲を消去し、ストリームを閉じる。未読のチャンクは未検査。
- 長い名前は `.c9s` と `name.c9s` の対応ハッシュを確認し、パスや平文名の不正を拒否する。
- DAV応答のXMLはDTDを拒否し、応答サイズとエントリー数を制限する。
  hrefのホスト・ポート・HTTPS・保管庫ルート・直接の子階層を確認する。
- TLS信頼検証を無効化しない。HTTP・URL埋め込み認証情報・リダイレクト・認証ログを使わない。
- 新規PUTは `If-None-Match: *`、更新PUTは読み込み時の強い `If-Match` を使う。
  新規の複数オブジェクトは暗号化ステージからMOVEで公開する。サーバーが条件を守ることは必須。
- ローカル接続はユーザーが選択したSAFツリーのURIを使い、Document IDをパスに変換しない。
  API 29以降では子孫判定も行う。API 26〜28ではツリーURIへのアクセスをAndroidが権限検証し、
  IDはそのツリーの一覧・作成・名前変更の結果からのみ取得する。
- ローカル保存は暗号文を別ファイルに書いてSHA-256を検証し、提供元が要求どおりの名前・種類を返したことを確認する。
  名前変更でDocument IDが変わる提供元にも対応する。重複名・重複ID・危険な相対パスを拒否する。
- ローカル上書きは読み込み時の暗号文SHA-256を複数回確認し、旧暗号文を回復用コピーとして残す。
  外部プロセスの同時編集に対する原子的な比較・交換はSAFが提供しないため保証しない。
- 通常の保管庫IOは1スレッド、動画の部分読み込みは専用の直列コールバックで行い、ロック以前に開始した処理の結果をUIへ戻さない。
  ロック時は本文読み込み中を含むHTTP Callをキャンセルし、SAFの保管庫・取り込みストリームを閉じる。
- 動画のWebDAV取得はRange、Accept-Encoding: identity、If-Matchを使い、各206応答のETag・
  Content-Range・全体長・実受信長を検証する。ローカルは同じ暗号文記述子にpreadし、サイズ変更を拒否する。
- 動画をアプリ外へ公開しない。再生中の画面とSurfaceViewも保護し、背景移行で一時停止する。
  ロックでは復号の供給を取り消してからLibVLCを停止・解放し、ファイルヘッダーの鍵も破棄する。
- LibVLCの一般設定だけでは内蔵カバー画像の保存を抑止できないため、JNIへ渡すHOMEと
  XDG_CACHE_HOME/XDG_DATA_HOME/XDG_CONFIG_HOMEを `/dev/null` とし、配下への作成をENOTDIRで拒否する。
  外部設定・メディアライブラリー・自動プリパース・ネットワークメタデータ・
  字幕・OSD・テキストレンダラーを無効にする。VLCは字幕無効時もレンダラーを作成するため、
  `text-renderer=none` で不要なフォント走査・キャッシュ経路を止める。
  入力はFDのみ、デマルチプレクサーは動画コンテナーごとに固定し、サブアイテムを自動再生しない。
  avcodecのソフトウェアデコードを指定する。LibVLCやGPUの内部バッファーを完全に消去する保証はない。
  このAndroid配布物は静的プラグイン構成で、動的プラグインのディスクキャッシュ設定は存在しない。
- 画像・PDFの解析は非公開のisolatedProcessサービスへ分離し、鍵・接続情報を渡さない。
  隔離プロセスはアプリ本体のUID・ストレージ権限・INTERNET権限を持たず、私有の読み取り専用proxy FDだけを受け取る。
  PDFにはプラットフォームのPdfRenderer、AVIFにはlibavif／dav1dを使い、UIスレッドで解析しない。
- 画像は16 MiB、入力の寸法は各辺16,384・3,200万画素、出力はmutable ARGB_8888で各辺2,048までに制限する。
  AVIFのISO-BMFFはネスト16段・4,096箱までとし、ispe寸法を検証してからJNIへ正しい長さのdirect ByteBufferを渡す。
  PDFは256 MiB・10,000ページ、ページ座標は各辺100,000まで、表示用画像は各辺2,048までに制限する。
  AVIFの圧縮されたAV1フレームなど、ネイティブ解析内部の全割り当て量をこれらの値で制限できる保証はない。
- 描画結果のtoken・ページ番号・画像形式・寸法を本体でも確認する。背景で届いた画像と期限切れの結果を消去し、
  前面への復帰時に再描画する。画像の読み込み完了時は本体の符号化画像バッファーとFDを消去・失効させる。
  BinderのBitmapは読み取り専用の共有メモリーで届く場合があるため、同期callback内で検証して本体所有のmutable画像へコピーし、
  コピー完了後に子プロセスの転送用画像を消去する。UIに共有転送バッファーをそのまま保持させない。
  手動・時間ロックでは画像を消去し、復号の供給を閉じてから子プロセスへ終了を要求する。
  描画は約20秒でタイムアウトし、最後のバインド解除時には終了処理を行う。ネイティブ処理が固まった場合も子プロセスを終了する。
  API 29以降はビューアーごとに独立したサービスインスタンスを使い、API 26〜28はtokenで要求を区別する。
- アプリが見えなくなった `onStop` から、デフォルト30秒の自動ロック期限を設定する。
  待ち時間は0・15・30・60・120・300秒のみ許可し、バックアップ対象外へ保存する。不正な設定は30秒へ戻す。
  期限内の復帰はタイマーを取り消し、解除状態と未保存の編集内容を保持する。前面での無操作タイマーは設けない。
- 背景で完了した操作・解除は復帰まで保留し、期限切れ・手動ロック・Activity破棄時は保留中の平文・セッションも破棄する。
  復帰時は `elapsedRealtime` による期限検証を保留結果の適用より先に行い、スリープ時間も経過時間に含める。
  手動ロックとActivity破棄は猶予なく処理する。プロセス再起動時に鍵・編集中の内容を復元しない。
- パスワード・鍵・復号したコンテンツを永続化しない。パスワードやサーバー応答をログ出力しない。
- 外部への平文共有・FileProvider・DocumentsProvider・平文キャッシュ・WebViewを提供しない。

## 制限

「平文を永続ストレージに保存しない」と「メモリーから一切回収できない」は別の性質です。
アプリが所有する配列は消去しますが、Javaの文字列、AndroidのUI、IME、暗号プロバイダー、画像描画のコピーや
OSが管理する物理メモリーを完全消去する保証はありません。FLAG_SECUREもOSの通常の制限に依存します。
インポート元やキーボードなど、他アプリの保存・転送動作は制御できません。
プロセス終了時のfinally実行も保証できません。平文をディスクへ書かない設計でその影響を減らします。
自動ロックの猶予中には鍵と平文がRAMに存在します。AndroidのHandlerはスリープ・凍結中に実行が遅れるため、
設定時刻ちょうどに鍵を物理メモリーから消去する保証はありません。復帰時には期限を必ず検証し、
期限切れのセッションや保留結果を利用しません。アプリが動作を再開するまでのRAM取得は保護対象外です。

WebDAVサーバーが条件付き書き込みや原子的なMOVEに違反すれば、競合保護は保証できません。
通常の短い名前のPUTに関する原子性もサーバーの実装に依存します。
中断された複数操作では暗号化ステージ・空のデータディレクトリーが残る場合があります。
SAFの名前変更の原子性・永続性は提供元に依存します。保存中にプロセスが終了した場合、
元の名前が一時的に存在せず、旧暗号文の `.backup` と新暗号文の `.upload` からの手動復旧が必要になる場合があります。
回復用コピーは過去の暗号文を保持するため、過去版のサイズや存在も隠せません。
悪意ある提供元のデータ削除や、他クライアントの同時書き込みによる競合は防げません。
アプリは暗号化ステージを表示しませんが、公式クライアントとの運用での扱いは実サーバーで確認が必要です。
既存の不正・破損・未対応のエントリーを見つけた場合、一覧操作を拒否します。

このアプリ自体の外部監査は受けていません。公式暗号ライブラリーの採用は、アプリ全体の監査を意味しません。
実機でのライフサイクル、IME、画面保護、バックアップ除外、メモリー使用量と実WebDAVサーバーでの互換性は
配布前に検証すべき項目です。自動テストはJVMの暗号ラウンドトリップ、HTTPS模擬WebDAV、
ローカル保存の競合・失敗処理と模擬DocumentsProviderを対象とします。
Android 16のエミュレーターでは、実際のproxy FD・LibVLCによる合成動画のデコード・表示・シーク・
一時停止・ロック時の失効、カバー画像付き動画のアプリ保存領域の不変性も検証しています。
同じ環境でPNG・JPEG・WebP・AVIFと2ページPDFの実デコード・画素・ページ移動、隔離UIDとINTERNET権限の拒否、
表示終了時のバッファー破棄・FD失効、ロック時の鍵破棄とアプリ保存領域の不変性を検証しています。
実際の端末ストレージ・SDカード提供元や同期アプリとの組み合わせは実機で確認が必要です。

## 仕様の参照元

- https://docs.cryptomator.org/security/vault/
- https://docs.cryptomator.org/security/security-target/
- https://github.com/cryptomator/cryptolib/tree/2.2.0
- https://github.com/cryptomator/cryptomator/security/advisories/GHSA-5phc-5pfx-hr52
- https://developer.android.com/security/fraud-prevention/activities
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long)
- https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)
- https://code.videolan.org/videolan/vlc/-/blob/3.0.x/src/input/es_out.c
- https://code.videolan.org/videolan/libvlcjni
- https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime()
- https://developer.android.com/guide/components/activities/activity-lifecycle
- https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
- https://developer.android.com/guide/topics/manifest/service-element#isolated
- https://github.com/AOMediaCodec/libavif/tree/841110fd8723abd0d5439cbb2cfb8e9cfa11f808/android_jni
