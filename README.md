# トラ音声IME（Android/Kotlin）

ローカル専用の音声入力IMEです。

## 特徴
- `InputMethodService` ベースで、どのアプリ入力欄にも直接 `commitText`
- マイクはタップで録音開始、再タップで録音停止
- SpeechRecognizer + ローカル補正（LLM/APIなし）
- 単語帳（単語 + 読みかな必須）をDataStore保存
- JSONインポート/エクスポート対応（同一wordは上書き）
- 短文中心: 音声結果を即入力
- `文面整形` ボタンで、前後文脈 + アプリ別履歴を使ったローカル整形
- かなキー入力 + テンキーフリック入力 + バックスペース
- 文脈が取れないアプリ向けに、IME側アプリ別履歴バッファ（500文字）
- ローカル同梱のかな漢字辞書（`assets/kana_kanji_base.tsv`）で変換候補を拡張
- 文脈取得は `getTextBeforeCursor(200)` / `getTextAfterCursor(200)` を利用（取得不可時は履歴補完）

## プロジェクト構成
- `app/src/main/java/com/toraonsei/ime/VoiceImeService.kt`
- `app/src/main/java/com/toraonsei/speech/SpeechController.kt`
- `app/src/main/java/com/toraonsei/suggest/SuggestionEngine.kt`
- `app/src/main/java/com/toraonsei/dict/UserDictionaryRepository.kt`
- `app/src/main/java/com/toraonsei/dict/LocalKanaKanjiDictionary.kt`
- `app/src/main/java/com/toraonsei/dict/DictionaryActivity.kt`
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
2. `IME有効化を開く` で `Tora Onsei Voice IME` を有効化
3. `入力方式を切替` で本IMEを選択
4. `マイク権限を許可`

## 使い方
### 基本入力
1. 入力欄でキーボード表示
2. `録音開始` をタップして話す
3. `録音停止` をタップすると認識結果を即挿入
4. 必要に応じて `文面整形` をタップして整える
5. 候補バー（上位5件）や `フリック`/`かなキー`/`⌫` で手修正

## 単語帳運用
- キーボードの `+単語` から最短追加
- `単語` と `読み(かな)` を入力して保存
- `SettingsActivity -> 単語帳を開く` で管理
- JSONでバックアップ/復元

## 同梱辞書の更新（自動化）
1. プロジェクトルートで実行
   - `python scripts/update_kana_kanji_dict.py`
2. 生成物
   - `app/src/main/assets/kana_kanji_base.tsv` を最新化
3. 候補数を変えたい場合
   - `python scripts/update_kana_kanji_dict.py --max-candidates 8`

## プライバシー
- 音声の外部送信や外部ログ送信は実装しない方針
- 端末内のDataStoreのみ利用

## 注意
- SpeechRecognizerは端末/Google音声入力実装に依存します
- アプリによっては `getTextBeforeCursor/getTextAfterCursor` が空になるため、IME履歴バッファで補完します
- 同梱辞書の生成元とライセンス情報は `app/src/main/assets/THIRD_PARTY_NOTICES.txt` を参照してください
