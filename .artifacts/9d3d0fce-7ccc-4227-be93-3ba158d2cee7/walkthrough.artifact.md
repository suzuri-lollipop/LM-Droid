# LmDroid 「フルアシスタント（秘書）」化完了

`lm-droid` を Android システムのデジタルアシスタントとして統合するための基盤実装を完了しました。これにより、Googleアシスタントのような「システムの特権を持った秘書」としての動作が可能になります。

## 変更内容

### 1. アシスタントフレームワークへの完全移行
従来の単独アプリとしての動作から、Android 標準の `VoiceInteractionService` アーキテクチャへ移行しました。

- [LmDroidVoiceInteractionService.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionService.kt): アシスタントのバックグラウンド常駐部分。システムからの起動リクエストを受け取ります。
- [LmDroidVoiceInteractionSessionService.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionSessionService.kt): セッション管理。
- [LmDroidVoiceInteractionSession.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionSession.kt): 秘書 UI（オーバーレイ）の制御。Compose UI (`AssistScreen`) を直接オーバーレイとして表示し、他のアプリを使いながら秘書と対話できます。

### 2. ウェイクワードとの高度な連携
既存の Vosk によるウェイクワード検知を、新しいアシスタントフレームワークに統合しました。

- `WakeWordService` がキーワードを検知すると、システムの `VoiceInteractionService` を通じて公式なアシスタントセッションを起動します。これにより、OS レベルでのタスク管理や表示の優先度が最適化されます。

### 3. システム統合のための設定
- [assistant_service.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/res/xml/assistant_service.xml): デジタルアシスタントとしての属性を定義。
- [AndroidManifest.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml): アシスタントサービスの登録と `BIND_VOICE_INTERACTION` 権限の追加。

## 使い方（秘書として有効にする手順）

1.  アプリをビルドして端末にインストールします。
2.  端末の **「設定」** を開きます。
3.  **「アプリ」 > 「デフォルトのアプリ」 > 「デジタルアシスタントアプリ」** を選択します。
4.  「デフォルトのデジタルアシスタントアプリ」を **`lm-droid`** に変更します。

これにより、以下の動作が可能になります：
- **「エルエムドロイド」と呼びかける**: AI 秘書がオーバーレイで表示されます。
- **ホームボタンを長押しする**: 従来の Google アシスタントの代わりに `lm-droid` が起動します。

> [!NOTE]
> サードパーティアプリとしての制限により、独自のウェイクワード待機中はマイクのインジケーターが表示されますが、これは Android のセキュリティ仕様によるものです。

> [!TIP]
> 秘書を呼び出した後は、画面の他の部分をタップせずにそのまま対話を続けることができます。
