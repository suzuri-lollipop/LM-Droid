# 音声認識モデル拡張（Whisper対応完了）の報告

ローカル音声認識において、Whisperエンジンの統合が完了しました。これにより、Voskに加えてWhisperの各モデル（Tiny/Base/Small）を使用して、高精度な音声入力が可能になりました。

## 主な変更内容

### 1. Whisperエンジンの統合
- **`whisper.cpp` の導入**: ネイティブ層（C++）に `whisper.cpp` を統合しました。既存の `ggml` 基盤を共有することで、効率的な推論を実現しています。
- **JNIブリッジの実装**: KotlinからWhisperの機能を呼び出すための `WhisperNative` を実装しました。

### 2. 音声入力エンジンの動的切り替え
- **`VoiceInput.kt`**: 設定で選択されたモデルに応じて、`VoskEngine` または `WhisperEngine` を自動的に選択して音声認識を開始します。

### 3. Whisper専用の実装
- **`WhisperEngine.kt`**: `SpeechRecognizerEngine` インターフェースを実装し、PCM音声をWhisperで処理します。
- **言語自動検出**: 日本語を含む多言語の自動検出、または日本語への固定設定に対応しています。

## 実装されたコンポーネント

| ファイル | 役割 |
| :--- | :--- |
| [whisper_jni.cpp](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/whisper_jni.cpp) | WhisperのJNI実装（C++） |
| [WhisperNative.kt](file:///home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperNative.kt) | JNI関数宣言 |
| [WhisperEngine.kt](file:///home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperEngine.kt) | Whisperエンジンのロジック |
| [CMakeLists.txt](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/CMakeLists.txt) | ネイティブライブラリのビルド構成更新 |

## 使い方
1.  **設定画面へ**: 「設定」→「音声」を開きます。
2.  **Whisperモデルを選択**: `Whisper Tiny` などのモデルを選択します。未ダウンロードの場合は「ダウンロード」ボタンをタップしてください。
3.  **音声入力**: チャット画面またはアシスタント画面でマイクボタンをタップ（または電源キー長押し）して話しかけてください。

> [!IMPORTANT]
> Whisperモデルは高精度ですが、Voskに比べてCPU負荷が高いです。初めて使用する際は `Whisper Tiny` または `Whisper Base` から試すことをお勧めします。
