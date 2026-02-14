# トラ音声IME（Android/Kotlin）

ローカル専用の音声入力IMEです。

## 特徴
- `InputMethodService` ベースで、どのアプリ入力欄にも直接 `commitText`
- 録音はマイク押下中のみ（長押し運用）
- SpeechRecognizer + ローカル補正（LLM/APIなし）
- 単語帳（単語 + 読みかな必須）をDataStore保存
- JSONインポート/エクスポート対応（同一wordは上書き）
- 短文モード: 即入力
- 長文モード: プレビュー -> 箇条書き/送信用整形 -> 挿入
- 文脈が取れないアプリ向けに、IME側アプリ別履歴バッファ（500文字）

## プロジェクト構成
- `app/src/main/java/com/toraonsei/ime/VoiceImeService.kt`
- `app/src/main/java/com/toraonsei/speech/SpeechController.kt`
- `app/src/main/java/com/toraonsei/suggest/SuggestionEngine.kt`
- `app/src/main/java/com/toraonsei/dict/UserDictionaryRepository.kt`
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
### 短文（遅延ゼロ優先）
1. 入力欄でキーボード表示
2. `押して録音` を押下中だけ話す
3. 離すと認識停止、結果を即挿入
4. 候補バー（上位5件）をタップして差し替え可能

### 長文（プレビュー整形）
1. `長文` モードに切替
2. 録音後、プレビュー確認
3. `箇条書き(最大10)` または `送信用` を実行
4. `そのまま挿入` or `整形を挿入`

## 単語帳運用
- キーボードの `+単語` から最短追加
- `単語` と `読み(かな)` を入力して保存
- `SettingsActivity -> 単語帳を開く` で管理
- JSONでバックアップ/復元

## プライバシー
- 音声の外部送信や外部ログ送信は実装しない方針
- 端末内のDataStoreのみ利用

## 注意
- SpeechRecognizerは端末/Google音声入力実装に依存します
- アプリによっては `getTextBeforeCursor/getTextAfterCursor` が空になるため、IME履歴バッファで補完します
