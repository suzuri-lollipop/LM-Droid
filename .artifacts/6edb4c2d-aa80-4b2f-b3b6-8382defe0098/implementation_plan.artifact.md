# 実装プラン - ウェイクアップワード（ホットワード）対応（任意文字列設定版）

このプランでは、アプリがバックグラウンドにある状態でも、ユーザーが設定した任意の言葉でアシスタントを起動できる「ウェイクアップワード」機能を導入します。オフラインで動作する **Vosk** ライブラリを使用し、ユーザーの入力に合わせて認識語彙を動的に構成します。

## ユーザーによる確認事項

> [!IMPORTANT]
> - **権限**: ユーザーは「録音（マイク）」および「通知」の権限を許可する必要があります。Android 14以降では、マイクを使用するフォアグラウンドサービスには永続的な通知とマニフェストでの特定の宣言（`microphone`）が必要です。
> - **入力のコツ**: 任意の文字列を設定可能ですが、短すぎる言葉（例：「はい」）や日常会話で頻出する言葉は誤検知の原因となります。3〜5音節程度の、特徴的な言葉を設定することを推奨します。
> - **モデルサイズ**: Voskの動作にはオフラインモデル（日本語の軽量モデルで約40〜50MB）が必要です。アセットに含めて配布します。

## 提案される変更点

### 依存関係と権限

#### [MODIFY] [libs.versions.toml](file:///C:/home/suzuri/projects/lm-droid/gradle/libs.versions.toml)
- Voskの依存関係を追加: `com.alphacephei:vosk-android:0.3.47`。

#### [MODIFY] [build.gradle.kts](file:///C:/home/suzuri/projects/lm-droid/app/build.gradle.kts)
- Voskライブラリをプロジェクトに適用します。

#### [MODIFY] [AndroidManifest.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml)
- `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED` の権限を追加します。
- `WakeWordService` を `android:foregroundServiceType="microphone"` を持つフォアグラウンドサービスとして宣言します。
- `BootReceiver` を追加し、起動時にサービスを自動再開できるようにします。

---

### データ層

#### [MODIFY] [SettingsRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/settings/SettingsRepository.kt)
- `wakeWordEnabled` (Boolean)
- `wakeWord` (String, デフォルト: 「エルエムドロイド」)
- `wakeWordSensitivity` (Float, 任意)
これらをDataStoreに追加します。

---

### バックグラウンドサービス

#### [NEW] `WakeWordService.kt`
- Voskを使用してバックグラウンドでマイクを監視するフォアグラウンドサービス。
- ユーザーが設定した `wakeWord` を `VoskRecognizer` のグラマー（認識対象リスト）として登録します。
- 文字列が検出されたら `AssistActivity` を起動します。
- **マイク競合の回避**: `AssistActivity` が起動して本格的な音声認識を開始する直前に、本サービスはマイクを解放し、アシスタントが閉じられたら再開する制御を行います。

---

### UIと設定

#### [MODIFY] [AssistantSettingsViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/AssistantSettingsViewModel.kt)
- ウェイクアップワードの有効状態と、設定文字列を編集・保存するロジックを追加します。

#### [MODIFY] [AssistantSettingsScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/AssistantSettingsScreen.kt)
- 「ウェイクアップワード設定」セクションを追加。
- 有効/無効のスイッチ。
- 好きなワードを入力できるテキストフィールド（ひらがな/カタカナ推奨の注釈付き）。

#### [MODIFY] [strings.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/res/values/strings.xml)
- 「ウェイクアップワード」「バックグラウンドで待機」「認識する言葉を入力してください」などの文字列を追加。

---

### アプリケーション統合

#### [MODIFY] [LmDroidApplication.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/LmDroidApplication.kt)
- `SettingsRepository` を監視し、有効フラグが変わった際や、設定されたワードが変わった際に `WakeWordService` の開始/停止/更新を行います。

## 検証プラン

### 自動テスト
- `SettingsRepository` のテスト：任意の文字列が正しく保存されること。

### 手動検証
1. **設定 > アシスタント** で「ウェイクアップワード」を有効にする。
2. 入力欄に「ねえドロイド」など好きな言葉を入力して保存。
3. アプリをバックグラウンドへ。
4. 設定した言葉を発話し、アシスタント画面が即座に立ち上がることを確認。
5. 言葉を「おーけーえるえむ」に変更し、新しい言葉で反応することを確認。

## 未解決の質問・懸念
- **Voskの認識精度**: 日本語モデルにおいて、ひらがな/カタカナ以外の漢字混じりの入力があった場合、Voskが正しく語彙として登録できるかを検証し、必要に応じてカナ変換の案内をUIに出します。
