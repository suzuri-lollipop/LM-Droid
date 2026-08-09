# OpenAI TTS モデル・ボイスの動的取得機能の実装計画

OpenAI TTS プロファイルにおいて、モデルとボイスを固定リストから選択するのではなく、APIから取得して選択できるようにします。これにより、自作APIやOpenAI互換のカスタムTTSサーバーを利用しているユーザーが、独自のモデルやボイスを簡単に設定できるようになります。

## ユーザーレビューが必要な事項

- **入力方法の変更:** 取得に失敗した場合や、APIが取得エンドポイントをサポートしていない場合に備え、モデル名とボイス名を手動で直接入力（タイピング）できるようにします。
- **取得先:** モデルは `/v1/models`、ボイスは `/v1/audio/voices`（多くの OpenAI 互換サーバーで採用されている非公式な慣習）からの取得を試みます。

## Proposed Changes

### データ・ネットワーク層

#### [MODIFY] [ApiModelDao.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/db/ApiModelDao.kt)
- `observeEnabledModelOptions` のクエリを更新し、`PROVIDER_OPENAI_COMPATIBLE`（LLMプロファイル）のみを対象にするようにします。これにより、TTSプロファイルで取得したモデルがチャット画面のモデル選択肢に混ざるのを防ぎます。

#### [MODIFY] [OpenAiTtsClient.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/tts/OpenAiTtsClient.kt)
- `listModels()` メソッドを追加し、`/v1/models` からモデル一覧を取得できるようにします。
- `listVoices()` メソッドを追加し、`/v1/audio/voices` からボイス一覧を取得できるようにします（ベストエフォート）。

### UI層

#### [MODIFY] [OpenAiTtsProfileEditViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/OpenAiTtsProfileEditViewModel.kt)
- `uiState` に `availableModels: List<String>` と `availableVoices: List<String>`、および取得中の状態を追加します。
- `onFetchOptions()` メソッドを追加し、APIからモデルとボイスを取得して `uiState` を更新するようにします。

#### [MODIFY] [OpenAiTtsProfileEditScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/OpenAiTtsProfileEditScreen.kt)
- モデルとボイスの `OutlinedTextField` を `readOnly = false` に変更し、直接入力できるようにします。
- 取得した選択肢がある場合はドロップダウンで表示し、ハードコードされていたデフォルト値（tts-1, alloy等）も初期候補として含めます。
- 「接続テスト（およびオプション取得）」ボタンを追加します。

## Verification Plan

### 自動テスト
- `OpenAiTtsClient` の新メソッドが、モックされたレスポンスを正しくパースできるか確認します。

### 手動確認
1. OpenAI TTS の編集画面を開きます。
2. 「接続テスト」または「オプションを取得」ボタンをタップします。
3. 自作APIからモデルやボイスが取得され、ドロップダウンの選択肢に反映されることを確認します。
4. ドロップダウンから選択できること、およびリストにない名前を手動で入力できることを確認します。
5. 保存後、アシスタントでそのモデル/ボイスが使用されることを確認します。
