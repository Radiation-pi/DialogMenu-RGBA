/* 用法：WIKI_URL=http://127.0.0.1:8000/ node checks/wiki-browser.cjs
 * 先安装 playwright 或设置 PLAYWRIGHT_MODULE，可选设置 BROWSER_PATH。
 */
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const url = process.env.WIKI_URL || 'http://127.0.0.1:8000/';
const out = path.resolve(process.env.WIKI_OUT || 'build/wiki-browser');
fs.mkdirSync(out, {recursive: true});
let passed = 0;
const errors = [];
function check(value, message) { assert.ok(value, message); passed++; }

(async () => {
  const browser = await chromium.launch({
    headless: true, ...(process.env.BROWSER_PATH ? {executablePath: process.env.BROWSER_PATH} : {})
  });
  const context = await browser.newContext({viewport:{width:1440,height:960}, acceptDownloads:true});
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
  async function shot(name) {
    await page.screenshot({path:path.join(out, name + '.png'), fullPage:false});
  }
  async function article(id) {
    await page.locator(`#sidebar a[href="#${id}"]`).click();
    await page.waitForFunction(id => document.querySelector('article.current')?.id === id, id);
  }
  async function noOverflow() {
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Page overflows horizontally');
  }
  await page.goto(url);
  check(await page.locator('article.current h1').innerText() === '介绍', 'Initial article');
  check(await page.locator('article:visible').count() === 1, 'Single article mode');
  await shot('01-desktop-intro');
  await noOverflow();
  const articles = await page.locator('main > article').evaluateAll(nodes => nodes.map(n => n.id));
  check(articles.length === 18, 'Complete article navigation');
  for (const id of articles) {
    await article(id);
    check(await page.locator('#breadcrumb').innerText() === '使用文档 / ' + await page.locator('article.current h1').innerText(), 'Breadcrumb: ' + id);
    check(await page.locator('#toc-links a').count() > 0, 'TOC: ' + id);
    await noOverflow();
  }
  await article('quickstart');
  await shot('02-desktop-example');
  const text = await page.locator('#hello-yaml').textContent();
  await page.locator('[data-copy="hello-yaml"]').click();
  await page.waitForFunction(() => document.querySelector('[data-copy="hello-yaml"]').textContent === '已复制');
  await page.evaluate(() => {
    const area = document.createElement('textarea');
    area.id = 'clipboard-check';
    area.setAttribute('aria-label', '测试剪贴板实际内容');
    document.querySelector('article.current').prepend(area);
  });
  await page.locator('#clipboard-check').focus();
  await page.keyboard.press('Control+V');
  check(await page.locator('#clipboard-check').inputValue() === text, 'Real clipboard paste, including YAML line breaks');
  await shot('03-clipboard-paste');
  await page.locator('#clipboard-check').evaluate(node => node.remove());
  await article('quickstart');
  const [download] = await Promise.all([
    page.waitForEvent('download'), page.locator('[data-download="hello-yaml"]').click()
  ]);
  const target = path.join(out, 'downloaded-hello.yml');
  await download.saveAs(target);
  check(download.suggestedFilename() === 'hello.yml', 'Download filename');
  check(fs.readFileSync(target, 'utf8') === text, 'Downloaded YAML equals displayed example');
  await shot('04-download');
  await page.locator('#search').fill('拉伸 wrapper');
  check(await page.locator('.search-result').count() > 0, 'Multiword search');
  await shot('05-search-results');
  await page.locator('.search-result').filter({hasText:'图片与宽高拉伸'}).click();
  check(await page.locator('article.current').getAttribute('id') === 'images', 'Search result navigation');
  const [image] = await Promise.all([
    page.waitForEvent('download'), page.locator('#download-image').click()
  ]);
  const imageFile = path.join(out,'demo-panel.png');
  await image.saveAs(imageFile);
  const png = fs.readFileSync(imageFile);
  check(png.readUInt32BE(16) === 16 && png.readUInt32BE(20) === 16, 'PNG download dimensions');
  await shot('06-images');
  await page.locator('#search').fill('不存在的词xyz123');
  check(await page.locator('#empty').isVisible(), 'Search empty state');
  await shot('07-empty-search');
  await page.locator('#clear-search').click();
  check(await page.locator('article.current').getAttribute('id') === 'images', 'Clear search restores article');
  await article('layout');
  await page.locator('[data-width="420"]').click();
  check(await page.locator('#size-result').innerText() === '420 × 252 · 比例 1.4', 'Coordinate size control');
  await shot('08-coordinate-control');
  await page.locator('article.current .article-nav a').last().click();
  check(await page.locator('article.current').getAttribute('id') === 'elements', 'Next article');
  await page.goBack();
  check(await page.locator('article.current').getAttribute('id') === 'layout', 'Browser back');
  const deepLink = await page.locator('#toc-links a').last().getAttribute('href');
  await page.locator('#toc-links a').last().click();
  await page.reload();
  check(await page.locator('article.current').getAttribute('id') === 'layout', 'Deep-link reload page');
  await page.waitForFunction(hash => {
    const rect = document.querySelector(hash).getBoundingClientRect();
    return rect.top >= 64 && rect.top < innerHeight;
  }, deepLink);
  passed++;
  await shot('09-deep-link');
  await page.evaluate(() => { window.__printed = false; window.print = () => {window.__printed = true;}; });
  await page.locator('#print').click();
  check(await page.evaluate(() => window.__printed), 'Print button');
  await page.emulateMedia({media:'print'});
  check(await page.locator('main > article:visible').count() === 18, 'All articles print');
  await shot('10-print');
  await page.emulateMedia({media:'screen'});
  for (const width of [768,390,320]) {
    await page.setViewportSize({width,height:844});
    if (width < 761) {
      await page.locator('.menu-toggle').click();
      check(await page.locator('#sidebar').isVisible(), 'Mobile menu opens');
      await shot(`11-menu-${width}`);
      await article('quickstart');
      check(!await page.locator('#sidebar').isVisible(), 'Mobile selection closes menu');
    } else {
      await article('quickstart');
    }
    await noOverflow();
    await shot(`12-example-${width}`);
    // 包括宽表格和 Java 长代码在内，所有文章都不能让整页横向溢出。
    for (const id of articles) {
      await page.evaluate(id => { location.hash = id; }, id);
      await page.waitForFunction(id => document.querySelector('article.current')?.id === id, id);
      await noOverflow();
    }
    if (width < 761) {
      await page.locator('.menu-toggle').click();
      await page.keyboard.press('Escape');
      check(!await page.locator('#sidebar').isVisible(), 'Escape closes mobile menu');
    }
  }
  const noJS = await browser.newContext({javaScriptEnabled:false,viewport:{width:390,height:844}});
  const staticPage = await noJS.newPage();
  await staticPage.goto(url);
  check(await staticPage.locator('main > article:visible').count() === 18, 'No-JS readable fallback');
  await staticPage.screenshot({path:path.join(out,'13-no-js.png')});
  await noJS.close();
  check(errors.length === 0, 'Browser console errors: ' + errors.join('\n'));
  fs.writeFileSync(path.join(out,'browser-results.json'), JSON.stringify({passed,failed:0,articles:articles.length,errors},null,2));
  await browser.close();
  console.log(`PASS: browser ${passed} checks, failed=0; screenshots: ${out}`);
})().catch(error => { console.error(error); process.exit(1); });
