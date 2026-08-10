# 修正プラン：アシスタント起動時のマイク入力停止処理の追加

アシスタントモード（AssistScreen）で、音声入力が完了しAIの応答が開始された後もマイクが有効なままになっており、入力内容が上書きされたりAIの声を拾ったりする問題を修正します。

## 発生している問題
`LocalVoiceInputState`（Voskを使用）において、確定した音声認識結果（`onResult`）が得られた後もループが継続し、リスニング状態が維持されています。これにより、AIが応答している最中もマイクが動作し続け、新たな音声（AIの声など）を拾って入力内容を上書きしてしまいます。

## 提案される変更

### [Component: ViewModel]

#### [MODIFY] [AssistViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistViewModel.kt)
- `onFinalTranscript` および `onPartialTranscript` メソッドにおいて、既に AI が応答中（`isStreaming` が `true`）の場合は入力を無視するようにガード処理を追加します。これにより、マイク停止のタイミングによる予期せぬ上書きを防ぎます。

### [Component: UI Components]

#### [MODIFY] [VoiceInput.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/VoiceInput.kt)
- `LocalVoiceInputState` の `start` メソッド内のループ処理において、確定結果（`recognizer.acceptWaveForm` が `true` を返した場合）が得られた際に `isListening = false` を設定し、自動的にリスニングを停止するように変更します。
- これにより、システム標準の `SpeechRecognizer` と同様の「一区切りついたら停止する」挙動に合わせます。

## 検証計画

### 自動テスト
- 現状、Voskのリポジトリや認識処理自体のモックテストは困難ですが、コードの論理的な変更（フラグの更新）を確認します。

### 手動検証
1. アシスタントを起動する。
2. 音声で質問を入力する。
3. AIが応答を開始したときに、マイクアイコンの波紋アニメーション（リスニング中を示す）が停止していることを確認する。
4. AIの応答中にマイクが再度有効にならないことを確認する。
