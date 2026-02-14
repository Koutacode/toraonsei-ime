# Gemini CLI向け: ToraOnsei APKアップロード手順

以下を Gemini CLI にそのまま渡してください。

```text
あなたはこのリポジトリの作業エージェントです。目的は Android APK を GitHub Release に再アップロードすることです。

## 前提
- 作業ディレクトリ: C:\Users\Public\Desktop\NativeApps\ToraOnsei
- リポジトリ: https://github.com/Koutacode/toraonsei-ime
- 既存リリースタグ: android-debug-20260214
- アップロードするアセット名: toraonsei-debug.apk

## 実行手順（PowerShell）
1) リポジトリ直下へ移動
cd C:\Users\Public\Desktop\NativeApps\ToraOnsei

2) デバッグAPKをビルド
.\gradlew.bat clean assembleDebug

3) 配布用ファイル名へコピー
New-Item -ItemType Directory -Force output | Out-Null
Copy-Item app\build\outputs\apk\debug\app-debug.apk output\toraonsei-debug.apk -Force

4) ハッシュ確認（ログ用）
Get-FileHash output\toraonsei-debug.apk -Algorithm SHA256

5) GitHub Releaseへ上書きアップロード
gh release upload android-debug-20260214 output\toraonsei-debug.apk --clobber --repo Koutacode/toraonsei-ime

6) 反映確認
gh release view android-debug-20260214 --repo Koutacode/toraonsei-ime --json tagName,url,assets

## 完了条件
- リリース `android-debug-20260214` の assets に `toraonsei-debug.apk` が存在する
- 直接DLリンクが有効:
  https://github.com/Koutacode/toraonsei-ime/releases/latest/download/toraonsei-debug.apk

## 失敗時の対処
- gh未ログイン: gh auth login
- タグ未作成の場合は次で新規作成:
  gh release create android-debug-YYYYMMDD output\toraonsei-debug.apk --title "Android Debug YYYYMMDD" --notes "Debug APK upload" --repo Koutacode/toraonsei-ime
```

