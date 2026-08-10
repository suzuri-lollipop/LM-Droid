# 実装プラン - アシスタント起動時の音声認識失敗の修正

ウェイクアップワードが **オフ** の状態でアシスタントを起動すると、必ず「音声認識に失敗しました」と表示される問題を修正します。

## ユーザーレビューが必要な事項

> [!IMPORTANT]
> コードベース内に「音声認識に失敗しました」という正確な文字列は見つかりませんでした。これは、Android システムまたは使用しているライブラリ（Vosk）が、マイクの初期化失敗や認識サービスのビジー状態に対して返しているローカライズされたエラーメッセージである可能性が高いです。
> このエラーは、`LocalVoiceInputState` (Vosk) または `SpeechRecognizer` (システム) がマイク権限の取得やレコーダーの初期化に失敗していることに起因すると想定して対応します。

## 提案される変更

### 1. `AssistScreen` におけるマイク競合の回避
システムジェスチャー（電源キー長押しなど）でアシスタントが起動される際、システムが一時的にマイクを使用していたり、遷移状態にある場合があります。起動直後に音声認識を開始すると、初期化失敗（`AudioRecord.STATE_UNINITIALIZED`）が発生しやすくなります。

#### [MODIFY] [AssistScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistScreen.kt)
- `LaunchedEffect` 内で `beginListening()` を呼び出す前に、短い遅延（例：300ms）を追加し、システムがマイクを解放し安定するのを待ちます。
- マイクの初期化に失敗した場合の再試行ロジックを検討します。

### 2. アシスタントの全エントリポイントでのウェイクアップワード一時停止の徹底
現在、`AssistActivity` は `WakeWordService` に `ACTION_PAUSE` を送信していますが、システムアシスタントとして起動された際に使用される `LmDroidVoiceInteractionSession` では送信されていません。ウェイクアップワードがオフの場合でも、動作の整合性を保ち、オンにした際の競合を防ぐために追加します。

#### [MODIFY] [LmDroidVoiceInteractionSession.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionSession.kt)
- セッションが表示される（`onShow`）際に `WakeWordService.ACTION_PAUSE` を送信します。
- セッションが非表示または破棄される際に `WakeWordService.ACTION_RESUME` を送信します。

### 3. `LocalVoiceInputState` のエラーメッセージの改善
現在 `LocalVoiceInputState` のエラーメッセージは英語（"Mic init failed" など）になっています。これらを `strings.xml` を使用した日本語メッセージに更新し、ユーザーの報告と一致するようにします。

#### [MODIFY] [VoiceInput.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/VoiceInput.kt)
- `LocalVoiceInputState` が `strings.xml` からローカライズされた文字列を使用するように更新します。

#### [MODIFY] [strings.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/res/values/strings.xml)
- 音声入力失敗時の不足しているエラー用文字列を追加します。

## 検証プラン

### 自動テスト
- システムレベルのマイク競合やハードウェアインタラクションが絡むため、ユニットテストには限界があります。`AssistViewModel` が `onListeningError` 呼び出し時に正しく状態を更新することを確認します。

### 手動検証
1. **ウェイクアップワード OFF**: 設定でオフにし、システムジェスチャーでアシスタントを起動。エラーが表示されず、正常に聞き取りが開始されることを確認。
2. **ウェイクアップワード ON**: 設定でオンにし、同様に起動。バックグラウンドサービスが一時停止され、アシスタントがマイクを正常に奪えることを確認。
3. **連続起動**: 短時間に何度もアシスタントを起動し、`LaunchedEffect` や `triggerCount` のロジックでレースコンディションが発生しないか確認。
