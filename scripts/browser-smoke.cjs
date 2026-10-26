// Server-rendered acceptance runs with browser JavaScript disabled.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../.local/browser/node_modules/playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');

/** Verifies SSR login, CSRF forms, account management, session rotation and logout without JavaScript. */
async function main() {
  const base = process.env.APP_URL || 'http://localhost:8080';
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.on('response', response => { if (response.status() >= 400) console.error(response.status(), new URL(response.url()).pathname); });
  try {
    await page.goto(base);
    await page.getByLabel('用户名').fill(process.env.ADMIN_USERNAME || 'admin');
    await page.getByLabel('密码', { exact: true }).fill(process.env.ADMIN_PASSWORD || fs.readFileSync('.local/admin-password', 'utf8').trim());
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('heading', { name: 'Docker 仓库' }).waitFor();
    const before = (await context.cookies()).find(cookie => cookie.name === 'grallum-refresh').value;
    const name = 'browser/demo-' + Date.now();
    await page.getByLabel('Registry 别名').fill('private');
    await page.getByLabel('仓库名称').fill(name);
    await page.getByRole('button', { name: '注册', exact: true }).click();
    await page.getByRole('heading', { name: 'private/' + name }).waitFor();
    await page.getByRole('link', { name: '清理策略' }).click();
    await page.getByLabel('保留最新标签数').fill('5');
    await page.getByRole('button', { name: '保存策略' }).click();
    assert.equal(await page.getByLabel('保留最新标签数').inputValue(), '5');
    await page.getByRole('button', { name: '预览候选' }).click();
    await page.getByRole('heading', { name: '运行结果' }).waitFor();
    await page.getByRole('link', { name: '用户', exact: true }).click();
    const username = 'browser-' + Date.now();
    const form = page.locator('form[action="/users"]');
    await form.getByLabel('用户名').fill(username);
    await form.getByLabel('密码', { exact: true }).fill(require('node:crypto').randomBytes(18).toString('hex'));
    await form.getByRole('button', { name: '创建用户' }).click();
    await page.getByRole('heading', { name: username, exact: true }).waitFor();
    await page.getByRole('button', { name: '续期登录' }).click();
    await page.getByRole('heading', { name: 'Docker 仓库' }).waitFor();
    const after = (await context.cookies()).find(cookie => cookie.name === 'grallum-refresh').value;
    assert.notEqual(before, after, 'refresh must rotate the opaque credential');
    await page.screenshot({ path: '.local/dashboard-desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: '.local/dashboard-mobile.png', fullPage: true });
    await page.getByRole('button', { name: '退出登录' }).click();
    await page.waitForURL('**/login');
    assert.equal((await context.cookies()).some(cookie => cookie.name.endsWith('grallum-access') || cookie.name === 'grallum-refresh'), false);
    console.log('PASS SSR with JavaScript disabled: login, repository/user creation, policy, preview, refresh rotation and logout');
  } catch (error) { console.error((await page.locator('body').innerText()).slice(0,1000)); throw error; } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
