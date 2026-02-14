const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  // 1. 更新メモの内容を読み込み
  const updateNotePath = path.join(__dirname, '../tmp/notion_update.md');
  if (!fs.existsSync(updateNotePath)) {
    console.error("エラー: 更新メモ (tmp/notion_update.md) が見つかりません。");
    process.exit(1);
  }
  const updateContent = fs.readFileSync(updateNotePath, 'utf8');

  console.log("--- Notion 自動更新スクリプト ---");
  console.log("1. ブラウザを起動します...");

  // 2. ブラウザを「ヘッドレスモードOFF（見える状態）」で起動
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext();
  const page = await context.newPage();

  // 3. Notionログインページへ移動
  console.log("2. Notionのログインページを開きます。");
  await page.goto('https://www.notion.so/login');

  // 4. ユーザー操作待ち
  console.log("
==========【ユーザー操作のお願い】==========");
  console.log("1) ブラウザで Notion にログインしてください。");
  console.log("2) 配布先のページ（ToraOnsei）を開いてください。");
  console.log("3) 追記したい場所（ページ末尾など）をクリックしてカーソルを置いてください。");
  console.log("4) 準備ができたら、このターミナルで [Enter] キーを押してください。");
  console.log("==========================================
");

  await new Promise(resolve => process.stdin.once('data', resolve));

  // 5. AIによる書き込み操作
  console.log("3. 更新情報を書き込んでいます...");
  
  // クリップボード権限の回避やDOM構造の複雑さを避けるため、キーストロークで入力します
  // NotionはMarkdown記法を受け付けるため、そのまま入力すれば整形されます
  
  // 長いテキストを一気に入力するとNotionが処理しきれないことがあるため、行ごとに処理
  const lines = updateContent.split('
');
  for (const line of lines) {
    await page.keyboard.type(line);
    await page.keyboard.press('Enter');
    // 少し待機（NotionのMarkdown変換待ち）
    await page.waitForTimeout(100);
  }

  console.log("
[完了] 更新情報の追記が完了しました。");
  console.log("ブラウザはそのままにしておきます。確認後、手動で閉じてください。");
  
  // スクリプト終了（ブラウザは閉じない、またはユーザーに任せる）
  // browser.close(); 
  process.exit(0);
})();
