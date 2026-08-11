# イヤホン起動および音声認識の完全復旧計画

イヤホンからのアシスタント起動が機能しなくなった問題を特定し、音声認識の感度調整と合わせて修正します。

## 特定された原因

1.  **インテントの競合と優先度**:
    イヤホンのボタンは `VOICE_COMMAND` や `WEB_SEARCH` といったインテントを発行しますが、Google アプリもこれらをハンドルするため、システムがどちらを起動するか迷い（または Google を優先し）、結果として LmDroid が起動しない状態になっていました。
2.  **Bluetooth SCO (マイク) の未処理**:
    Bluetooth ヘッドセットのマイクを使用するには SCO プロファイルを明示的に開始する必要がありますが、現在の実装ではこれが欠落しており、イヤホン越しに話しても声が届かない状態でした。
3.  **VAD 閾値の不適合**:
    前回導入した 0.02f というエネルギー閾値が、イヤホンマイクなどの感度が低いデバイスでは高すぎ、話し始めを検知できていない可能性があります。

## 解決策

### 1. 起動トリガーの強化
- `AssistActivity` のインテントフィルターの優先度を最大 (`2147483647`) に設定し、システムが優先的に LmDroid を選択するようにします。
- `LmDroidVoiceInteractionService` に `onLaunchVoiceAssistFromKeyguard` を実装し、ロック画面からのイヤホン起動に対応します。
- 物理ボタンイベントを確実に拾うため、サービス内で `MediaSession` を維持します。

### 2. Bluetooth マイクの有効化
- `AudioManager` を使用し、録音開始時に `startBluetoothSco()` を呼び出すロジックを `WakeWordService` とアシスタント画面 (`VoiceInput.kt`) に追加します。
- `android.permission.BLUETOOTH_CONNECT` 権限を要求するようにします。

### 3. 音声認識感度の調整
- `WhisperEngine` の `energyThreshold` を `0.01f` に下げ、より小さな声も拾えるようにします。

## ユーザーレビューが必要な事項

> [!IMPORTANT]
> Bluetooth イヤホン使用時、マイクが有効になる瞬間に一瞬だけ音楽が止まったり、Android システムの「マイク使用中」のドットが表示されたりしますが、正常な動作です。

## 提案される変更

### サービス / システム

#### [MODIFY] [LmDroidVoiceInteractionService.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionService.kt)
- `MediaSession` によるボタンイベントのキャッチ。
- キーガード（ロック画面）起動への対応。

#### [MODIFY] [WakeWordService.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/WakeWordService.kt)
- 録音中の Bluetooth SCO 制御の追加。

#### [MODIFY] [AndroidManifest.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml)
- 優先度の引き上げと Bluetooth 権限の追加。

### UI / エンジン

#### [MODIFY] [VoiceInput.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/VoiceInput.kt)
- アシスタント画面での Bluetooth SCO 制御の追加。

#### [MODIFY] [WhisperEngine.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperEngine.kt)
- 感度閾値を `0.01f` に変更。

## 検証計画

### 手動検証
1.  Bluetooth イヤホンを接続し、ボタン長押しで LmDroid アシスタントが（選択肢が出ずに）直接起動することを確認。
2.  ロック画面（キーガード）状態からイヤホンボタンで起動することを確認。
3.  イヤホンのマイクに向かってささやき声で話し、正しく認識されることを確認。
