## Phase 1: ノベルゲーム風UI基盤
- [x] `AssistUiState` にキャラクター連動用の状態（キャラクター設定・TTS再生中フラグ）を追加
- [x] `AssistScreen` をレイヤー構造（背景/キャラクター/UI）へ再構成（`AssistStage`。キャラクター未設定時は `AssistBottomSheet` にフォールバック）
- [x] `NovelMessageWindow`（タイプ文字演出・タップスキップ・▼継続マーカー・ThinkingDots）を実装
- [x] `NamePlate`（名前枠）を実装
- [x] マイク演出をステージ向けにリスタイル（`ListeningIndicator.onStage`: 暗色ディスク＋リング）

## Phase 2: 静止画キャラクター
- [x] `CharacterRenderer` インターフェースと `CharacterUiState`・状態マッピング（`deriveCharacterState`）を定義
- [x] 静止画立ち絵を実装（`CharacterStage.StaticSprite`。状態別差分画像ではなく、1枚絵＋状態連動アニメーション・タップバウンス方式）
- [x] `CharacterModelStore`（SAFインポート・内部保存）を実装
- [x] キャラクター設定画面（Screen/UiState/ViewModel）と設定ルートへの導線を実装（Live2D/MMDは「準備中」で無効表示）

## Phase 3: Live2D対応
- [ ] Cubism SDK for Native を `app/src/main/cpp` に統合
- [ ] `live2d_jni.cpp`（モデル読み込み・パラメータ更新・描画）を実装
- [ ] `CMakeLists.txt` を更新
- [ ] GLSurfaceView 埋め込みとリップシンク（`ParamMouthOpenY`）連動を確認

## Phase 4: MMD対応（方式B: NDKネイティブに決定）※ Phase 3 に先行して実装
- [x] PMXパーサ・VMDプレイヤーを `app/src/main/cpp/mmd/` に実装（`pmx_parser.cpp` / `vmd_parser.cpp`。VMDのShift-JIS名はKotlin側のCharsetでデコード）
- [x] MMDトゥーンレンダラー（OpenGL ES 3.x）を実装（`mmd_renderer.cpp`: トゥーン＋スフィア合成＋エッジパス。スキニング・CCD IK・モーフは `mmd_engine.cpp`）
- [x] Bullet Physics（剛体・ジョイント）を組み込み（bullet3 3.25 を `app/src/main/cpp/bullet3` にvendoring、`mmd_physics.cpp`）
- [x] `mmd_jni.cpp` と `MmdNativeRenderer`（`CharacterRenderer` 実装）を実装（GLSurfaceView埋め込みは `MmdSurface.kt`、SAFフォルダインポートは `CharacterModelStore.importMmdModel`）
- [ ] PMX表示・口パクモーフ連動（リップシンク）を実機で確認（ビルド・パッケージング検証済み、実機検証待ち）

## 共通
- [x] `AssistSpeechPlayer` からリップシンク用の再生情報を公開（`isSpeaking: StateFlow<Boolean>`）
- [x] 自動テストを追加（`CharacterStateMappingTest`: 状態マッピング9件。既存含む全117件パス）
- [ ] オーバーレイ非表示時の描画停止とメモリ安定性を確認（実機での手動検証待ち）
