# トラ音声IME（Android/Kotlin）

音声入力 + 表記変換に特化したAndroid IMEです。整形・翻訳・候補生成は端末内のローカル処理のみで動作します。

## 最終更新
- 更新日時: 2026-02-21 14:10 (JST)
- 最新APK: `output/toraonsei-debug.apk`
- ビルド: `.\gradlew.bat clean assembleDebug`
- 更新内容: `CONVERT` キーの挙動を見直し、短押しでかな漢字の変換候補、長押しで現在の整形アクション（`変/英`）を実行するよう変更。キー表示も `整形` から `変換` へ統一。

## 特徴
- `InputMethodService` ベースで、どのアプリ入力欄にも直接 `commitText`
- マイクはタップで録音開始、再タップで録音停止（常時録音なし）
- SpeechRecognizer + ローカル補正
- 単語帳（単語 + 読みかな必須）をDataStore保存
- JSONインポート/エクスポート対応（同一wordは上書き）
- `変換` ボタン:
  - 短押しでかな漢字の変換候補を生成
  - 長押しで現在の整形アクション（`変/英`）を実行
- `変` は会話/仕事モードに応じて全文をローカルLLMで再整形（未利用時はルールベースへ自動フォールバック）
- `英` はローカルLLM翻訳（長文はチャンク分割して全文翻訳）
- 変換候補生成・文章整形・英語翻訳はすべてローカル処理（通信なし）
- 候補バーは、かな入力中に変換候補を優先表示（タップで置換）
- 候補バー先頭にクリップボード内容の「貼り付け」チップを表示（タップで即挿入）
- `変換` 候補は最大18件（表示は最大14件）を文脈スコアで並び替え
- かなキー入力 + テンキーフリック入力 + バックスペース
- `小` キーに濁点/半濁点を統合（タップ=`゛/゜`優先、左フリック=`゛`、右フリック=`゜`、上下フリック=`小文字`）
- `変換` 後の候補を候補バーに番号付き表示（タップで候補切替）
- 文脈が取れないアプリ向けに、IME側アプリ別履歴バッファ（500文字）
- ローカル同梱のかな漢字辞書（`assets/kana_kanji_base.tsv`）で変換候補を拡張
- 文脈取得は `getTextBeforeCursor(200)` / `getTextAfterCursor(200)` を利用（取得不可時は履歴補完）
- 録音中は通知を表示し、スクロール時も録音継続（再タップで停止）
- キーボード/画面を閉じると録音は自動終了
- 認識テキスト補正（フィラー/ノイズ記号除去）を設定でON/OFF可能
- ローカルLLMモデル配置検知（`model.gguf` の有無を設定画面で表示）

## プロジェクト構成
- `app/src/main/java/com/toraonsei/ime/VoiceImeService.kt`
- `app/src/main/java/com/toraonsei/speech/SpeechController.kt`
- `app/src/main/java/com/toraonsei/suggest/SuggestionEngine.kt`
- `app/src/main/java/com/toraonsei/dict/UserDictionaryRepository.kt`
- `app/src/main/java/com/toraonsei/dict/LocalKanaKanjiDictionary.kt`
- `app/src/main/java/com/toraonsei/dict/DictionaryActivity.kt`
- `app/src/main/java/com/toraonsei/dict/DictionaryMaintenanceActivity.kt`
- `app/src/main/java/com/toraonsei/dict/DictionaryUpdater.kt`
- `app/src/main/java/com/toraonsei/format/LocalFormatter.kt`
- `app/src/main/java/com/toraonsei/settings/SettingsActivity.kt`
- `app/src/main/res/layout/keyboard_view.xml`
- `app/src/main/res/layout-sw600dp/keyboard_view.xml`

## Android Studioでのビルド
1. Android Studioで `ToraOnsei` フォルダを開く
2. SDK (compile/target 35) をインストール
3. 実機 (Galaxy Z Fold 7) をUSBデバッグ接続
4. `app` を `Run` してAPKをインストール

## 初期セットアップ
1. アプリ起動 (`SettingsActivity`)
2. `パスワード` に `0623` を入力して `解除`
3. `IME有効化を開く` で `Tora Onsei Voice IME` を有効化
4. `入力方式を切替` で本IMEを選択
5. `マイク/通知` 権限を許可
6. 設定画面で「端末内完結モード: 有効」を確認

## 使い方
### 基本入力
1. 入力欄でキーボード表示
2. `録音開始` をタップして話す
3. `録音停止` をタップすると認識結果を即挿入
4. 必要に応じて `変` をタップして文章整形、または `英` で英語翻訳
5. 候補バー（最大7件）や `フリック`/`かなキー`/`⌫` で手修正

## 単語帳運用
- キーボードの `+単語` から最短追加
- `単語` と `読み(かな)` を入力して保存
- `SettingsActivity -> 単語帳を開く` で管理
- `SettingsActivity -> 辞書メンテ（ワンタップ更新）` で同梱辞書をネット更新
- `SettingsActivity -> 変換設定` で
  - 文章整形の強さ（弱め/標準/強め）
  - 英語翻訳スタイル（ナチュラル/カジュアル/フォーマル）
  を切替
- JSONでバックアップ/復元
- `優先度` は `数値が大きいほど優先`（`0` は標準）
- 誤変換対策の例文/辞書ソース: `docs/誤変換対策_文章例_辞書ソース.md`
- 全体見直しメモ: `docs/全体見直し_2026-02-14.md`
- ローカルLLM導入方針: `docs/ローカルLLM導入方針.md`

## 同梱辞書の更新（ネット収集）
1. プロジェクトルートで実行
   - `python scripts/update_kana_kanji_dict.py`
   - Windowsワンクリック: `scripts\\update_kana_kanji_dict.bat`
2. 生成物
   - `app/src/main/assets/kana_kanji_base.tsv` を最新化
3. 候補数を変えたい場合
   - `python scripts/update_kana_kanji_dict.py --max-candidates 8`
4. 取得ソース（既定: 全辞書一括）
   - `SKK-JISYO.S.gz`
   - `SKK-JISYO.M.gz`
   - `SKK-JISYO.L.gz`
   - `SKK-JISYO.fullname.gz`
   - `SKK-JISYO.propernoun.gz`
   - `SKK-JISYO.jinmei.gz`
   - `SKK-JISYO.station.gz`
   - `SKK-JISYO.ML.gz`
   - `SKK-JISYO.assoc.gz`
   - `SKK-JISYO.geo.gz`
   - `SKK-JISYO.law.gz`
   - `SKK-JISYO.okinawa.gz`
   - `SKK-JISYO.china_taiwan.gz`
   - `SKK-JISYO.wrong.gz`
   - `SKK-JISYO.pubdic+.gz`
   - Japan Post `ken_all.zip`（都道府県/市区町村/町域）
5. 取得ソースを変える場合
   - `python scripts/update_kana_kanji_dict.py --sources SKK-JISYO.L.gz,SKK-JISYO.propernoun.gz`

## Notion更新テンプレ（自動化）
1. 例: 標準出力に生成
   - `python scripts/generate_notion_update_template.py --release-url https://github.com/Koutacode/toraonsei-ime/releases/tag/android-debug-YYYYMMDD`
2. 例: ファイルに保存
   - `python scripts/generate_notion_update_template.py --release-url <release_url> --apk-url <apk_url> --highlight "表記変換を改善" --output tmp/notion_update.md`
3. Windowsワンクリック
   - `scripts\\generate_notion_update_template.bat --release-url <release_url>`

## プライバシー
- 音声認識は端末側SpeechRecognizerを利用
- 文章整形・英語変換は端末内ローカル処理のみ（外部送信なし）
- 設定/単語帳は端末内DataStoreへ保存
- アプリロックは `0623` 解除方式（解除しないとIME入力は利用不可）

## 注意
- SpeechRecognizerは端末/Google音声入力実装に依存します
- アプリによっては `getTextBeforeCursor/getTextAfterCursor` が空になるため、IME履歴バッファで補完します
- 同梱辞書の生成元とライセンス情報は `app/src/main/assets/THIRD_PARTY_NOTICES.txt` を参照してください
