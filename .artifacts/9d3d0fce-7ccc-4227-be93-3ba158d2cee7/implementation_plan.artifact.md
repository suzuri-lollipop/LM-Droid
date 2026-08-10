# LmDroid 「フルアシスタント（秘書）」化計画 - フェーズ2: サンドボックス検出の導入

## 概要
Googleアシスタントが採用している「プライバシーに配慮したキーワード検出」の仕組みを導入します。Android 12で導入された `HotwordDetectionService` を使用し、メインアプリから隔離された安全なサンドボックス内でウェイクワード判定を行います。これにより、OSに対して「安全なアシスタント」であることを証明し、システムのインジケーター等の挙動を最適化します。

## ユーザーレビューが必要な項目
- **Android 12以降の要件**: この高度な仕組みは Android 12 (API 31) 以上でのみ動作します。それ未満の端末では従来の `WakeWordService` による検知が継続されます。
- **モデルデータのメモリ消費**: サンドボックスサービスに Vosk モデルを渡す際、共有メモリを使用するため、一時的にメモリ消費が増加する可能性があります。

## 提案される変更

### 1. サンドボックスサービスの追加 [Component: Assistant Framework]

#### [NEW] `LmDroidHotwordDetectionService.kt`(file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidHotwordDetectionService.kt)
- `HotwordDetectionService` を継承。
- Vosk の解析ロジックをこのサービス内に封じ込めます。
- インターネットアクセスやファイルアクセスが完全に遮断された状態で動作します。

### 2. インタラクションサービスの更新 [Component: Assistant Framework]

#### [MODIFY] `LmDroidVoiceInteractionService.kt`(file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/service/LmDroidVoiceInteractionService.kt)
- `createSoftwareHotwordDetector` を使用して、上記のサンドボックスサービスを起動・制御します。
- メインアプリ（LmDroidApplication等）から Vosk モデルのファイル記述子を共有メモリ経由で転送するロジックを実装します。

### 3. マニフェストとリソースの更新 [Component: Manifest/Resource]

#### [MODIFY] `AndroidManifest.xml`(file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml)
- `HotwordDetectionService` を登録。

#### [MODIFY] `res/xml/assistant_service.xml`(file:///C:/home/suzuri/projects/lm-droid/app/src/main/res/xml/assistant_service.xml)
- `hotwordDetectionService` 属性を追加し、システムに新しいサービスの存在を知らせます。

## 修正後の動作イメージ
1. 設定でデフォルトアシスタントに設定。
2. アシスタントサービスが `HotwordDetectionService` をバックグラウンドで開始。
3. 隔離された環境で Vosk がマイク入力を常に監視。
4. キーワードが見つかったときのみ、メインの `VoiceInteractionSession` が起動し、ユーザーに応答する。

## 検証プラン
### 自動テスト
- モデルデータが正しく共有メモリを介してサンドボックスに転送されるか。
### 手動確認
- Android 12以降の端末で、マイクのインジケーターがキーワード待機中に抑制されるか（OSの仕様に依存）。
- キーワード検知後にアシスタント画面が正しくオーバーレイ表示されるか。
