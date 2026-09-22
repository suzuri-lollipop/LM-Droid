# 「送信 → 考え中… が出るまで」が長い問題の切り分け（クライアント側要因の棚卸し）

対象経路: Chat 画面で送信ボタン → アシスタントバブルに `考え中…`（思考タイムライン見出し）が出るまで。
結論を先に書くと:

- **アプリ側にも明確な要因が複数ある。** 特に「新規会話の1通目」は、アプリが同時に発する**タイトル生成リクエスト**が同じサーバーの推論枠を埋め、本体リクエストがスターバインドする設計上の問題がある。
- ただし**遅延の実体の大半はサーバー側（＝アプリの外）である可能性が高い**。どのくらいがどちら側かは、既存ログのタイムスタンプだけで一意に切り分けられる（§4）。

---

## 1. 「考え中…」が実際には何待ちで出るのか

表示条件は `MessageBubble.kt:372` の `"考え中…"` ラベルで、これは **思考タイムラインが1件以上 DB に書かれたとき**だけ出る。つまり待ち受けは次の2段階。

| 段階 | 何が表示されるか | 出る条件 | 実装 |
|---|---|---|---|
| ① | 三点リーダ（`TypingIndicator`） | assistant のプレースホルダ行が Room に入った瞬間（content 空・timeline 空） | `MessageBubble.kt:99,111` / 行の挿入は `ConversationRepository.kt:283-290` |
| ② | **`考え中…`** ＋（展開すると思考テキスト） | サーバーから最初の `reasoning_content` デルタが届き、Room に flush された瞬間 | 受信 `OpenAiApiClient.kt:178-181`, 保存 `ConversationRepository.kt:430-436,743-746`, 表示 `MessageBubble.kt:93-98,372` |

Global なスピナーは存在しない（`uiState.isStreaming` は入力バーの「生成を停止」ボタンのみ: `ChatScreen.kt:293,377`）。
**なので「待たされている間」の可視化はすべて Room 経由**であり、DB ラウンドトリップと UI 再構築の時間もそのまま体感遅延に乗る。

---

## 2. 送信からリクエスト発出までに起きていること（順不同・全て送信前）

`ChatViewModel.performSend` (`ChatViewModel.kt:152-166`) → `launchGeneration` は `viewModelScope`＝**Main** ディスパッチャで走る（`ChatViewModel.kt:363-383`）。

1. `settingsRepository.currentChatSettings()` — DataStore → Room `observeById` → **Android Keystore AES-GCM 復号**（`SettingsRepository.kt:95,544-575,595-620`）。この map/復号は収集者のコンテキスト、つまり **Main スレッドで実行される**。
2. `messageDao.getMessages(conversationId)` — 会話の**全メッセージをフル読み**（`ConversationRepository.kt:205`）。使われ方は `isEmpty()` の判定だけ。
3. ユーザー行 insert → 添付 insert → `conversationDao.touch`。
4. **`generateTitleInBackground(...)`**（`ConversationRepository.kt:228,1087-1100`）— ここで**別の chat/completions リクエストが本体より先に飛ぶ**（下記 §3-①）。
5. assistant プレースホルダ insert → **ここで初めて三点リーダが出うる**。
6. `getMessagesWithAttachments(conversationId)` — 全履歴を**再度**フル読み（`ConversationRepository.kt:292`、SQL に LIMIT なし: `MessageDao.kt`）。
7. 履歴 DTO 化。**履歴内の全添付を毎回 `readBytes()` + Base64 し直し**てリクエストに載せる（`ConversationRepository.kt:833-856`, `AttachmentFileStore.kt:91-101`）。
8. プロンプト／スキル読み込み、`availableTools()` 内で **設定の直列 `.first()` 読みが十数回**（`ConversationRepository.kt:867-1030`：brave 有効フラグ＋キー復号、location、alarm、notes＋既定アプリ、messaging＋既定アプリ、music、YouTube、maxToolRounds…）。全て逐次 await。
9. `streamChatCompletion` 内で全履歴の JSON シリアライズ → `call.execute()`（`OpenAiApiClient.kt:87-129`）。接続がプールに無ければ TCP（＋https なら TLS）ハンドシェイクと DNS が乗る。

## 3. クライアント側要因（大きさの見積もりと直しかた）

### ① 会話1通目のタイトル生成が本体リクエストと競合する ★最有力
`generateTitle` は **非ストリーム・`max_tokens=500`**（`OpenAiApiClient.kt:477-549`, 特に `493`）で、しかも **`enable_thinking` を落としていない**（`chatTemplateKwargs` を送っていない＝`493` 付近）ので、推論モデルなら Chain-of-Thought を吐き切ってからタイトルを返す。
使うモデルは `currentSystemSettings()` で、**システム用モデル未設定ならチャット用そのもの**にフォールバックする（`SettingsRepository.kt:85-89`）。
launch 順は `ConversationRepository.kt:228`（タイトル）→ `231`（本体）なので、**タイトル側が常に先に並ぶ**。llama.cpp 系でスロットが1本（`-np 1` 既定）なら本体リクエストはタイトル完了まで完全に待ち。スロットがあっても GPU を奪われて prefill が遅くなる。
→ **症状（送信後に何も始まらない）と最も整合する。** 会話の2通目以降に遅延が出ないなら、ほぼこれが本体。
直す: (a) 最初のトークン受信後にタイトル発火、(b) タイトルは軽量モデル必須＋`enable_thinking=false`＋`max_tokens` を 64 程度に、(c) 同一サーバーへは本文完了後に回す。

### ② プレースホルダが「設定解決と全履歴読み込みの後」にしか入らない
三点リーダですら送信即時には出ない（§2-1,2,5）。`isStreaming` を UI 側で「送信中プレースホルダ」として DB 経由でなく直接描画するか、insert を設定解決より前に移す。体感では数百 ms ではなく「間隔の始まり」を消す効き方をする。

### ③ 無制限の履歴／添付の再送 → サーバーの prefill を自分で増やしている
`getMessagesWithAttachments` に LIMIT もトークン上限もなく、**画像は毎回 base64 再エンコードして再アップロード**（保存時は 1568px/JPEG85 に縮小されているので1枚あたり数百 KB 規模: `AttachmentFileStore.kt:126-130`）。アップロード時間とサーバー側の prefill が会話長に比例して伸び、**「考え中」が出るまでの時間は会話長とともに悪化する**。
直す: 直近 N 件／トークン予算で切る、添付は (`filePath` → base64) をキャッシュ、可能なら履歴側は画像を降ろす。

### ④ ストリーミング中の Main スレッド圧（表示の遅れ）
flush は 150 ms 間隔で**累積本文＋タイムライン全体の JSON** を Room に書く（`ConversationRepository.kt:430-436,1109`）。その invalidation で `observeMessagesWithAttachments` が**全メッセージを再クエリ**し、収集者側で **全メッセージの `thinkingTimelineJson` を毎回デコード**している（`ChatViewModel.kt:64-70,436-447`＝Main 上）。思考が長くなるほど・会話が長いほど 150 ms ごとに数十 ms 級のはずみがつき、**ラベルの切り替えや本文の描画が遅れて見える**。
直す: ストリーム中はインメモリ（uiState）で描画して完了時のみ永続化、または last message だけを separate Flow に分ける／タイムライン JSON のデコード結果をキャッシュする。

### ⑤ 設定・キーストア読み（数百 ms にはならないが確実に乗る）
`current*` が送信1回で十数回、逐次。`chatSettings` 系は Room flow の購読セットアップ＋Keystore 復号を伴う（§2-1,8）。送信時に必要分を1回で集約するか、リポジトリ側で StateFlow キャッシュにする。復号はプロフィール単位でキャッシュしてよい。

### ⑥ ビルド／ロギング由来
`isMinifyEnabled = false`（`app/build.gradle.kts:44-48`）で **release でも `Log.d` が全て残る**。`HttpLoggingInterceptor`（HEADERS, network interceptor: `AppContainer.kt:61-77`）と、`generateTitle` は**リクエスト JSON 全文**を Logcat に出す（`OpenAiApiClient.kt:495`）。数十 ms 級だが、debug ビルドでの体感差の一因にはなる。

### ⑦ 環境側（アプリの外）だが「送信直後」に効くもの
`normalizeBaseUrl` はスキーム無しだと `http://` にする（`OpenAiApiClient.kt:601-610`）＝自前サーバーは平文 LAN/Tailscale 前提。プール接続が切れた後の**初回リクエストは TCP（＋Tailscale なら peer ハンドシェイク）を払い戻す**。readTimeout を 5 分に振ってある（`AppContainer.kt:72`）ので、ハング時はエラーにならず黙って待つ。起動時に 1 発ウォームアップするか、`connectionPool` の keepAlive を短くして「死んだ接続を踏む」確率を落とす手がある。

---

## 4. 1分でできる切り分け（既存ログだけで足りる、追加計測は2行だけ）

```bash
adb logcat -v usec -s ConversationRepository:V OpenAiApiClient:V OkHttpWire:V
```

送信後に現れるマーカーと、区間の読み替え:

| ログ | 位置 | 意味 |
|---|---|---|
| `generateTitle request:` | `OpenAiApiClient.kt:495` | タイトル生成が飛んだ時刻（§3-①の犯人） |
| `skills: forcedSkillId=` | `ConversationRepository.kt:361` | 履歴構築まで完了 |
| `availableTools: [...]` | `ConversationRepository.kt:1026` | 設定読み込みまで完了 |
| `streamChatCompletion: model=` | `OpenAiApiClient.kt:105` | JSON 化完了・`execute()` 直前 |
| `OkHttpWire: --> POST .../chat/completions` | network interceptor | ソケットへ書き出す直前 |
| `OkHttpWire: <-- 200 OK` | network interceptor | **サーバーの TTFB（=ここがアプリの外側の遅延）** |
| `generateTitle extracted title:` | `OpenAiApiClient.kt:541` | タイトル生成が枠を空けた時刻 |

- **`--> POST` までの合計**がアプリ側の準備時間（§3-①〜⑤、⑦の接続は `--> POST` と `<-- 200` の間に入る）。
- **`<-- 200 OK` が `generateTitle extracted title:` とほぼ同時**に出るなら、§3-①の直列待ちで確定。
- **`<-- 200 OK` 以降**（最初の reasoning delta → `考え中…`）は現状ログが無いので、切り分けの最終区間だけ 2 行足すのが一番確実:

```kotlin
// ConversationRepository の collect 内 (738-746 付近)
is StreamEvent.ReasoningDelta -> {
    if (timeline.isEmpty()) Log.d(TAG, "first reasoning delta at ${System.currentTimeMillis()}")
    appendReasoning(event.text); flushIfDue()
}
```
ついでに `OpenAiApiClient.kt:105` のログに `body=${requestJson.length}` を足すと、送信ペイロードと遅延の相関（§3-③）がその場で確認できる。

**サーバー側の物差しとの A/B**: 同じ履歴を `curl -N` で直接叩いて「最初の reasoning chunk まで」を測る。アプリと同等ならサーバー（＝会話長＝prefill）が主犯、アプリの方が明らかに遅ければ §3-①／⑦ が主犯。

> **実施済み（§6・§7）**: 上の「2行足す」相当は実際に実装済み。`ChatViewModel` に
> `generation requested`（送信の始点）、`ConversationRepository` に `send +Nms: <段階>`
> （設定解決／Room 永続化／履歴読み込み／スキル解決／`request prepared`／初トークン）、
> `OpenAiApiClient` に `first SSE line received` と `body=<bytes>` を追加した。
> 上のコード片は履歴として残す（実装は §7 参照）。

---

## 5. 着手順の提案

1. §4 のログ2行で遅延を区間分解（犯人特定）
2. §3-① タイトル生成の発火タイミング／パラメータ修正（1通目に遅延が出るなら即効性が高い）
3. §3-② 送信即時の UI プレースホルダ
4. §3-③ 履歴のトークン上限と添付 base64 キャッシュ
5. §3-④ ストリーム中はインメモリ描画
6. §3-⑤ 設定読み集約／復号キャッシュ、§3-⑥ ログのデバッグゲート化

---

## 6. 実測（2026-09-21 / エミュレータ + 計装モックサーバー）

環境: `emulator-5554`（x86_64, debug ビルド）／アプリから `http://10.0.2.2:8123/v1` に立つ
単一スロット（`-np 1` 相当）の計装モックサーバー。モックの挙動は
**推論の第一トークンまで 250ms、タイトル生成（非ストリーム）は 4000ms 占有**。
実サーバーの「1スロット＆reasoning モデル」を意図的に大げさに再現した設定。

### 6-1. 会話の1通目：タイトル競合の A/B（同じ条件でコードだけ差し替え）

| | 修正前（タイトルを先に発火） | 修正後（本文の初トークン後に発火） |
|---|---|---|
| サーバー側の本体リクエストの待ち | **QUEUED 3509ms** | **0ms（空き）** |
| `send +…ms: first streamed token` | **+4507ms** | **+1523ms** |
| タイトル要求の到着順 | 本体より**先**（本体を押し倒す） | 本体の初 reasoning chunk **後** |
| タイトル要求の待ち | 0ms（自分が先に枠を確保） | 209ms（本体のストリーム終了待ち） |

**この1件だけで約3.0秒の差**。差は「タイトル生成がサーバーを占有する時間」に比例する
（実サーバーの reasoning モデルなら数秒）。`1通目が特に遅い` という症状と一致。
修正後のタイトル要求本体は `chat_template_kwargs={"enable_thinking": false}` を伴う（§3-①の後半）。

### 6-2. 送信経路の区間分解（`send +Nms` ログ、コールドスタート／履歴1件）

| 区間 | 実測 | 中身 |
|---|---|---|
| 送信 → settings resolved | 138〜161ms | DataStore 読み＋プロフィール／キー解決 |
| → user message persisted | +125ms | Room 書き込み |
| → assistant placeholder inserted | +98ms | Room 書き込み（ここから UI が3点点灯可能） |
| → history rows loaded / built | +41ms / +1ms | 履歴＋添付の再エンコード（0件時の値。枚数に比例） |
| → active system prompts loaded | +51ms | Room |
| → system prompts & skills resolved | +126ms | Room |
| → request prepared | +142ms（`availableTools: … +122ms`） | 設定の逐次読み＋Web検索キーの Keystore 復号 |
| **小計（アプリ側準備）** | **約 +722ms** | `--> POST` 前に終わる部分 |
| → `--> POST` | +200〜435ms | OkHttp（プロセス起動直後の初回接続は高くつく） |
| → `first SSE line received` | サーバー依存（モック250ms＋HTTP） | **ここから先はアプリの外** |
| → first streamed token | ほぼ同時 | パース＆UI 反映のオーバーヘッドは計測範囲で無視できる |

2通目以降（接続・キャッシュ warm）は `request prepared` まで **+277ms**、`--> POST` まで **+50ms**。
定量はエミュレータ＋R8 無効ビルド由来に膨らんだ値だが、**内訳の比率（設定・Room・スキル解決で
アプリ側準備の大半、ストリーム処理そのものは速い）**は実機でも同じ形になる。

### 6-3. 実機で同じ数字を出す手順

```bash
adb logcat -c && adb logcat -v usec \
  -s ChatViewModel:V ConversationRepository:V OpenAiApiClient:V OkHttpWire:V
```

`generation requested` → `send +…ms: …` の列がそのまま上表に対応する。
`request prepared` から `--> POST` が大きい＝接続確立（§3-⑦）、
`<-- 200 OK`／`first SSE line received` までが大きい＝サーバー側のキュー／prefill、
`send +…ms` 群が大きい＝アプリ側の準備（§3-②③⑤）。

---

## 7. 今回の実装で入ったもの／入っていないもの

**入った**（`ConversationRepository.kt` / `OpenAiApiClient.kt` / `ChatViewModel.kt`）

- §3-① タイトル生成を**本文ストリームの初イベント（reasoning / content / ツール実行 / ストリーム終了）まで遅延**、
  かつタイトル本体へ `enable_thinking=false`。編集再生成も同様。
- §3-①の判定 `isFirstMessage` を全行読みから COUNT 照会へ（`getMessages` → `countMessages`）。
- §4 の計測ログ一式（`generation requested` / `send +Nms: …` / `first SSE line received` /
  `availableTools … +Nms` / `body=<bytes>`）。既存ログ（`generateTitle request:` の全 JSON ダンプ）は維持。

**まだ入っていない**（§5 の 3 以降）

- 送信即時の UI プレースホルダ（現状は Room 経由、6-2 の +200ms 相当）
- 履歴トークン上限・添付 base64 キャッシュ（履歴が長い／添付が多い会話で効く）
- ストリーム中のインメモリ描画（`FLUSH_INTERVAL_MS=150ms` ごとに全メッセージの timeline を再デコード）
- 設定読みの集約／復号キャッシュ
- タイトル生成そのものは依然としてサーバーの枠を 1 つ使う：`-np 1` 運用では
  **1通目の直後に続けて送信すると、その 2 通目がタイトル生成と競合する**。
  サーバー側で `-np 2` 以上か、タイトル用に小さく thinking を切ったモデルを別に生やすのが根本解決。
