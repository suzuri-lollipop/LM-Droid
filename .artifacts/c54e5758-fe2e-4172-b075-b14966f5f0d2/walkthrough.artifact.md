# 修正完了：アシスタント起動時のマイク入力制御の改善

アシスタントがAI応答中にマイク入力を継続し、入力が上書きされてしまう問題を修正しました。

## 変更内容

### 1. ローカル音声入力の自動停止
`LocalVoiceInputState` (Vosk) において、確定した音声認識結果が得られたタイミングで自動的にリスニング状態を解除するようにしました。

```kotlin
// VoiceInput.kt
if (recognizer.acceptWaveForm(buffer, read)) {
    val result = JSONObject(recognizer.result).getString("text")
    if (result.isNotBlank()) {
        launch(Dispatchers.Main) { onResult(result) }
        isListening = false // 追加：確定結果が得られたら停止
    }
}
```

### 2. ViewModel での入力ガード
AIが応答中（ストリーミング中）は、音声入力のイベントを受け取らないようにガードを追加しました。これにより、意図しないタイミングでの入力上書きを防ぎます。

```kotlin
// AssistViewModel.kt
fun onFinalTranscript(text: String) {
    if (_uiState.value.isStreaming) return // 追加：応答中は無視
    // ...
}
```

## 検証結果

### ビルド確認
- `:app:assembleDebug` を実行し、正常にビルドできることを確認しました。

### 動作確認（期待される挙動）
- アシスタントで音声入力を行うと、話が終わったタイミングでマイクアイコンのアニメーションが止まり、AIの応答が始まります。
- AIが話している間は、マイクが反応せず、入力したテキストが勝手に書き換わることはありません。
