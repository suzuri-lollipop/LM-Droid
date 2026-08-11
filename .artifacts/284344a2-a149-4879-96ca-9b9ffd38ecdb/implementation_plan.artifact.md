# Whisper音声認識エンジンの実装

このプランでは、以前のアップデートで基盤のみ作成したWhisper音声認識エンジンを実際に動作するように実装します。`whisper.cpp` をネイティブ層に統合し、JNIを介してAndroidアプリから利用可能にします。

## ユーザーレビューが必要な事項

> [!IMPORTANT]
> WhisperモデルはCPU負荷が高いため、デバイスのスペックによっては認識に時間がかかる場合があります。特にLargeモデルなどはモバイル端末では動作が困難な可能性があります（Tiny/Base/Small推奨）。

> [!WARNING]
> ネイティブコードのビルド時間が大幅に増加する可能性があります。

## 計画の概要

1.  **whisper.cppのソースコード統合**:
    - `whisper.h` および `whisper.cpp` を `app/src/main/cpp` に配置します。
    - 既存の `ggml` (stable-diffusion.cpp同梱) を活用できるか検討し、必要最小限のファイルを構成します。
2.  **JNIブリッジの実装**:
    - `whisper_jni.cpp` を作成し、モデルのロードと音声データの処理（推論）を行うC++関数を定義します。
3.  **Kotlin側の実装**:
    - `WhisperEngine.kt` を作成し、`SpeechRecognizerEngine` インターフェースを実装します。
    - `WhisperNative.kt` でJNI関数を宣言します。
4.  **ビルド構成の更新**:
    - `CMakeLists.txt` を更新し、Whisper関連のファイルをコンパイル対象に追加します。

## 提案される変更点

### [ネイティブ層] whisper.cpp 統合

#### [NEW] [whisper.h](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/whisper.h) / [whisper.cpp](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/whisper.cpp)
`whisper.cpp` プロジェクトのコアファイル。

#### [NEW] [whisper_jni.cpp](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/whisper_jni.cpp)
JavaとC++を繋ぐブリッジ。

#### [MODIFY] [CMakeLists.txt](file:///home/suzuri/projects/lm-droid/app/src/main/cpp/CMakeLists.txt)
Whisperライブラリのビルド設定。

### [データ層] Kotlin実装

#### [NEW] [WhisperEngine.kt](file:///home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperEngine.kt)
`SpeechRecognizerEngine` 実装。

#### [NEW] [WhisperNative.kt](file:///home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperNative.kt)
JNI関数のラッパークラス。

### [UI層]

#### [MODIFY] [VoiceInput.kt](file:///home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/VoiceInput.kt)
TODOになっていた箇所を `WhisperEngine` の呼び出しに置き換えます。

## 検証計画

### 自動テスト
- `WhisperNative` のロードテスト。

### 手動検証
- Tinyモデルをダウンロードし、音声入力がテキストに変換されることを確認。
- アシスタント画面からの音声入力が正常に動作することを確認。
