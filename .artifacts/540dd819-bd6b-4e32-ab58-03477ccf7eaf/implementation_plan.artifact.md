# アシスタント画面のノベルゲーム風UI化とキャラクター表示対応

アシスタント画面（`AssistActivity` / `AssistScreen`）は現在、Googleアシスタントのようなボトムシート型UIです。このプランでは、これをソーシャルゲーム・ノベルゲーム風の画面構成に改善する案を提示します。あわせて、**キャラクター表示は Live2D および MMD（PMX/VMD）に対応する予定**とし、そのための技術方針と段階的な実装ロードマップを定義します。

## ユーザーレビューが必要な事項

> [!IMPORTANT]
> **ライセンス**: Live2D は Cubism SDK（プロプライエタリライセンス）を利用します。個人・小規模事業者は一定条件下で無償利用できますが、収益規模やリリース形態によっては有償ライセンスやLive2D社への申請が必要です。MMDモデル（PMX）はモデルごとに作者が利用規約を定めており、**アプリへの同梱配布はできません**。ユーザー自身が用意したモデルファイルをインポートする方式にします。

> [!WARNING]
> **性能・バッテリー**: アシスタント画面は他アプリの上にオーバーレイ表示されます（`Theme.LmDroid.Assist`）。キャラクターのリアルタイム描画はGPU/CPU負荷と発熱を伴うため、描画はオーバーレイ表示中のみ行い、非表示時は必ず停止します。

> [!NOTE]
> **APKサイズ**: Cubism SDKや3Dレンダリング関連の追加でAPKが増加します。Live2D/MMDのレンダリング資産を動的に扱う設計にし、キャラクター未使用時は従来UIと同等の動作を保ちます。

## 改善案（ノベルゲーム・ソーシャルゲーム風UI）

ノベルゲームの基本的な画面文法である「背景レイヤー＋立ち絵＋ネームプレート＋メッセージウィンドウ」をアシスタント画面に持ち込み、現在の状態（リスニング／考え中／応答中／エラー）をキャラクターの演出と連動させます。

### 1. レイヤー構造への再構成

現在の `AssistScreen` はスクrim＋ボトムシートの2層ですが、これを以下のレイヤー構成に再編します。

| レイヤー | 内容 |
| --- | --- |
| 背景レイヤー | 任意の背景画像、または時間帯で変わる背景。未設定時は現在のスクrim＋サーフェスにフォールバック |
| キャラクターレイヤー | Live2D / MMD / 静止画立ち絵のいずれか（`CharacterRenderer` で差し替え可能） |
| UIレイヤー | ネームプレート、メッセージウィンドウ、マイク演出、閉じるボタン |

### 2. メッセージウィンドウ（ノベルゲーム風）

- 画面下部に固定の半透明メッセージウィンドウ（角丸＋枠線）を配置し、ユーザー発話（transcript）とアシスタント応答（assistantText）を表示します。
- **タイプ文字演出**: ストリーミング応答に合わせて1文字ずつ表示。タップで即座に全文表示（スキップ）。
- 応答のMarkdown描画（`com.mikepenz.markdown`）は、演出ON時はプレーンテキスト表示、演出OFF時は現行のMarkdown描画とします。

### 3. ネームプレート（名前枠）

- メッセージウィンドウの左上にキャラクター名を表示するネームプレートを配置します。
- 表示名は既存の `uiState.modelProfileName`（実際に応答するプロファイル名）をそのままキャラクター名として利用でき、追加の設定なしで「どのモデルが話しているか」が分かる既存の利点を維持します。

### 4. マイク演出の世界観統一

- 既存の `ListeningIndicator`（脈動するマイク円）の代わりに、世界観に合った演出（例：魔法陣・吹き出し・音符エフェクト）を用意します。
- 操作系は現行どおり「画面上にマイク操作は常に1つだけ」という原則（`AssistScreen.kt` の設計意図）を維持します。

### 5. キャラクターと状態の連動

`AssistUiState` の状態をキャラクター演出にマッピングします。

| 状態 | 条件 | 演出例 |
| --- | --- | --- |
| Idle | 起動直後・待機 | 瞬き・呼吸などの待機モーション |
| Listening | `voiceInputState.isListening` | 聞き取りポーズ・エフェクト |
| Thinking | `hasSent && assistantText が空` | 考え中モーション・「…」吹き出し |
| Speaking | `isStreaming` またはTTS再生中 | 口パク（リップシンク）・話しモーション |
| Error | `errorMessage != null` など | 困り表情など |

### 6. リップシンク（TTS連動）

- TTS再生中（`AssistSpeechPlayer` → VOICEVOX互換 / OpenAI TTS / オンデバイス合成）に、音声の振幅または再生状態から口の開口パラメータを駆動します。
- Live2D は `ParamMouthOpenY`、MMD はモーフ（あ/い/う/え/お）へマッピングします。

### 7. タッチインタラクション

- キャラクター本体をタップすると反応モーション・ボイスを再生する、ソーシャルゲーム的な触れ合い要素を追加します（第2期以降）。

### 8. キャラクター設定画面の追加

既存の設定画面（`ui/settings/*Screen.kt` + UiState + ViewModel の3点セット）の規約に従い「キャラクター設定」を追加します。

- モデル種別: なし / 静止画 / Live2D / MMD
- モデルファイルのインポート（SAFで選択 → アプリ内部ストレージへコピー）
- キャラクターの位置・サイズ、背景画像、演出（タイプ文字・リップシンク）のON/OFF

### 9. ソーシャルゲーム的拡張（将来候補・今回は実装しない）

挨拶演出、好感度・親密度パラメータ、会話イベント、衣装変更などは将来の拡張候補としてのみ記載し、本プランのスコープ外とします。

### 10. アクセシビリティとフォールバック

- 「演出を減らす」設定でタイプ文字演出・キャラクターアニメーションを無効化できるようにします。
- キャラクター未設定・読み込み失敗時は、現行のボトムシートUIそのものにフォールバックし、機能を損ないません。

## キャラクター表示の技術方針（Live2D / MMD）

差し替え可能な共通インターフェースを定義し、バックエンド（静止画 / Live2D / MMD）を段階的に追加します。

```kotlin
interface CharacterRenderer {
    fun loadModel(uri: Uri)
    fun setState(state: CharacterUiState) // Idle / Listening / Thinking / Speaking / Error
    fun setMouthOpen(value: Float)        // 0.0〜1.0（リップシンク）
    fun release()
}
```

### Live2D（Cubism SDK for Native）

- **方式**: Cubism SDK for Native（C++ / OpenGL ES）を既存のNDK/CMake基盤（`app/src/main/cpp`、whisper.cpp / stable-diffusion.cpp と同じ構成）に統合し、JNI経由で利用します。
- **表示**: Compose の `AndroidView` で `GLSurfaceView` をホストし、`AssistScreen` のキャラクターレイヤーに埋め込みます。
- **モデルファイル**: `.model3.json` / `.moc3` / テクスチャをユーザーがインポート（アプリに同梱しない）。
- **パラメータ駆動**: 自動瞬き・呼吸（idle）、`ParamMouthOpenY`（リップシンク）、表情パラメータ（状態連動）。

### MMD（PMX / VMD）

**方式B（NDKネイティブ実装）に決定**。whisper.cpp / stable-diffusion.cpp と同じ既存のネイティブ層（NDK/CMake）方針と整合させ、MMDレンダラーをネイティブ実装します。

- **構成**: PMXパーサ＋VMDモーションプレイヤー＋OpenGL ES 3.x レンダラー（MMDトゥーンシェーディング・エッジ描画）＋Bullet Physics による剛体・ジョイント物理をJNI経由で組み込みます。
- **表示**: Live2D と同様に Compose の `AndroidView` で `GLSurfaceView` をホストして埋め込みます。
- **モデルファイル**: モデル（PMX）とモーション（VMD）はユーザーがインポート（アプリに同梱しない）。
- **リップシンク**: 口パクモーフ（「あ」等）の駆動で行います。
- **リスクと対策**: スキニング・シェーダー・物理チューニングを含め工数が大きいため、PMX/VMD処理の参考に MMDAgent 系のオープンソース実装を活用します。

### 段階的ロードマップ

| Phase | 内容 | 外部依存 |
| --- | --- | --- |
| 1 | ノベルゲーム風UI（レイヤー構成・メッセージウィンドウ・ネームプレート・タイプ文字演出・状態連動の枠組み） | なし |
| 2 | 静止画立ち絵表示＋タッチ反応 | なし |
| 3 | Live2D 表示（Cubism SDK for Native、JNI） | Cubism SDK |
| 4 | MMD 表示（方式B: NDKネイティブレンダラー） | PMX/VMDローダ、Bullet Physics |

Phase 1〜2 はネイティブSDK不要で完結するため、先行して着手できます。

## 提案される変更点

### [UI層]

#### [MODIFY] [AssistScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistScreen.kt)
レイヤー構造（背景/キャラクター/UI）への再構成。キャラクター未設定時は現行ボトムシートにフォールバック。

#### [NEW] [NovelMessageWindow.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/components/NovelMessageWindow.kt)
ノベルゲーム風メッセージウィンドウ。タイプ文字演出・タップスキップ。

#### [NEW] [NamePlate.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/components/NamePlate.kt)
キャラクター名（= `modelProfileName`）を表示する名前枠。

#### [NEW] [CharacterStage.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/character/CharacterStage.kt)
キャラクターレイヤーのホストComposable。選択された `CharacterRenderer` を `AndroidView`（GLSurfaceView）で埋め込み、オーバーレイ非表示時に描画停止。

#### [NEW] [CharacterRenderer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/character/CharacterRenderer.kt)
共通インターフェースと `CharacterUiState`（Idle / Listening / Thinking / Speaking / Error）。

#### [NEW] [StaticSpriteRenderer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/character/StaticSpriteRenderer.kt)
静止画立ち絵レンダラー（Phase 2。State毎の差分画像切り替えに対応）。

#### [NEW] [CharacterSettingsScreen.kt / CharacterSettingsUiState.kt / CharacterSettingsViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/CharacterSettingsScreen.kt)
キャラクター設定画面（既存の3点セット規約に従う）。モデル種別・ファイルインポート・位置/サイズ・演出ON/OFF。

#### [MODIFY] [SettingsRootScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/SettingsRootScreen.kt) / [SettingsRoute.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/SettingsRoute.kt)
キャラクター設定への導線追加。

### [データ層]

#### [MODIFY] [SettingsRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/settings/SettingsRepository.kt)
DataStore にキャラクター設定キー（モデル種別、モデルファイルパス、位置・サイズ、演出ON/OFF）を追加。

#### [NEW] [CharacterModelStore.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/character/CharacterModelStore.kt)
SAFで選択したモデルファイル（.model3.json一式 / .pmx / .vmd / 背景画像）をアプリ内部ストレージへコピー・管理。

#### [MODIFY] [AssistViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistViewModel.kt) / [AssistUiState.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistUiState.kt)
キャラクター連動用の状態（`CharacterUiState`、TTS再生中フラグ、口パク振幅）を公開。

#### [MODIFY] [AssistSpeechPlayer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/tts/AssistSpeechPlayer.kt)
リップシンク用に再生状態・振幅（または再生区間情報）を外部へ公開。

### [ネイティブ層]（Phase 3: Live2D）

#### [NEW] app/src/main/cpp/cubism/
Cubism SDK for Native のコアソース配置。

#### [NEW] [live2d_jni.cpp](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/live2d_jni.cpp)
モデル読み込み・パラメータ更新・描画を行うJNIブリッジ。

#### [MODIFY] [CMakeLists.txt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/CMakeLists.txt)
Cubism SDK のビルド設定追加。

### [MMD層]（Phase 4・方式Bに決定）

#### [NEW] app/src/main/cpp/mmd/
PMXパーサ、VMDモーションプレイヤー、MMDトゥーンレンダラー（OpenGL ES 3.x）、Bullet Physics による剛体・ジョイント物理。

#### [NEW] [mmd_jni.cpp](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/mmd_jni.cpp)
モデル読み込み・モーション更新・描画を行うJNIブリッジ。

#### [MODIFY] [CMakeLists.txt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/CMakeLists.txt)
MMDレンダラー（Bullet Physics を含む）のビルド設定追加。

#### [NEW] [MmdNativeRenderer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/character/MmdNativeRenderer.kt)
`CharacterRenderer` 実装。JNI層を駆動し、GLSurfaceView の埋め込みと口パクモーフ駆動（リップシンク）を行う。

## 検証計画

### 自動テスト
- `SettingsRepository` のキャラクター設定キーの読み書きテスト（既存のDataStoreテスト規約に従う）。
- `CharacterUiState` の状態マッピング（`AssistUiState` → キャラ状態）の単体テスト。

### 手動検証
- キャラクター未設定時: 現行UIと同等に動作すること（回帰確認）。
- 静止画立ち絵: 各状態（リスニング/考え中/応答中/エラー）で演出が切り替わること。
- タイプ文字演出: ストリーミング応答と同期し、タップでスキップできること。
- Live2D: インポートしたモデルが表示され、瞬き・口パク・表情が連動すること。
- MMD: インポートしたPMXが表示され、口パクモーフが連動すること。
- オーバーレイを閉じた後に描画が停止し、メモリ/バッテリーが安定すること。
- 低端機でのフレームレート確認（目安30fps、目標60fps）。
