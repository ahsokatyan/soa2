import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
const base = process.env.CLIENT_URL || 'https://localhost:8444';
const worker = process.env.WORKER_URL || 'https://localhost:8443/workers/api';
const output = new URL('../target/browser/', import.meta.url);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'chrome', headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
const errors = []; page.on('pageerror', error => errors.push(error.message));
page.on('requestfailed', request => console.error('Request failed:', request.url(), request.failure()?.errorText));
const checks = [], ids = [];
const name = 'Браузер-' + Date.now();
const field = (key) => page.locator(`[id="edit-${key}"]`);
const filter = (key) => page.locator(`[id="filter-${key}"]`);
const xml = (salary, suffix) => `<worker><name>${name}-${suffix}</name><coordinates><x>12</x><y>34</y></coordinates><salary>${salary}</salary><startDate>2025-09-01</startDate><organizationId>20</organizationId></worker>`;
async function waitText(selector, text) { await page.locator(selector).filter({ hasText: text }).waitFor(); }
async function create(salary, suffix) {
  const response = await context.request.post(worker + '/workers', { data: xml(salary, suffix), headers: { 'Content-Type': 'application/xml', Accept: 'application/xml' } });
  assert.equal(response.status(), 201, await response.text()); const id = (await response.text()).match(/<id>(\d+)<\/id>/)[1]; ids.push(id); return id;
}
try {
  await page.goto(base); await waitText('#page-info', 'Страница 1'); checks.push('Client loads and fetches Worker API with CORS');
  await field('name').fill(name); await field('coordinates.x').fill('9223372036854775807'); await field('coordinates.y').fill('-100');
  await field('salary').fill('0'); await field('startDate').fill('2025-09-01'); await field('organizationId').fill('10');
  await page.locator('#save-worker').click(); await waitText('#notice', '422'); await waitText('#field-errors', 'salary'); checks.push('Server validation errors shown next to form');
  await field('salary').fill('100'); await field('position').selectOption('MANAGER'); await field('status').selectOption('REGULAR');
  await page.locator('#has-person').check(); await field('person.height').fill('180.5'); await field('person.eyeColor').selectOption('GREEN');
  await page.locator('#save-worker').click(); await waitText('#notice', 'создан');
  const id = (await page.locator('#notice').textContent()).match(/Работник (\d+)/)[1]; ids.push(id);
  const ownRow = () => page.locator('#workers-body tr').filter({ has: page.locator('td:first-child').filter({ hasText: new RegExp('^' + id + '$') }) });
  await ownRow().waitFor(); assert.match(await ownRow().textContent(), /9223372036854775807/); checks.push('Create worker with optional person and exact int64 value');
  await page.locator('#lookup-form input[name=id]').fill(id); await page.locator('#lookup-form button').click(); await waitText('#lookup-result', name); checks.push('Get worker by ID');
  await ownRow().getByRole('button', { name: 'Изменить', exact: true }).click(); await waitText('#editor-title', id);
  await field('name').fill(name + '-edited'); await page.locator('#save-worker').click(); await waitText('#notice', 'обновлён'); await waitText('#workers-body', name + '-edited'); checks.push('Edit worker');
  await create(300, 'one'); await create(400, 'two'); await create(500, 'three');
  await page.locator('#query-form details').nth(0).locator('summary').click(); await filter('organizationId').fill('20');
  await page.locator('#query-form details').nth(1).locator('summary').click(); await page.locator('#add-sort').click(); await page.locator('#add-sort').click();
  const sorts = page.locator('.sort-row'); await sorts.nth(0).locator('select').nth(0).selectOption('salary'); await sorts.nth(0).locator('select').nth(1).selectOption('desc');
  await sorts.nth(1).locator('select').nth(0).selectOption('name'); await page.locator('#size').fill('2');
  await page.locator('#query-form button[type=submit]').click(); await waitText('#page-info', 'всего страниц 2');
  assert.match(await page.locator('#workers-body tr').first().textContent(), /500/); await page.locator('#next').click(); await waitText('#page-info', 'Страница 2');
  assert.equal(await page.locator('#workers-body tr').count(), 1); await page.locator('#previous').click(); await waitText('#page-info', 'Страница 1'); checks.push('Combined filters, two sort rules and page navigation');
  await page.locator('#reset-query').click(); await waitText('#page-info', 'всего страниц 1');
  await page.locator('#salary-sum').click(); await waitText('#salary-result', 'Сумма зарплат:'); await page.locator('#salary-unique').click(); await waitText('#salary-result', 'Уникальные зарплаты:');
  await page.locator('#count-form input').fill('450'); await page.locator('#count-form button').click(); await waitText('#salary-result', 'Работников с зарплатой меньше порога:'); checks.push('All salary statistics');
  await page.locator('#move-form input[name=worker]').fill(id); await page.locator('#move-form input[name=from]').fill('10'); await page.locator('#move-form input[name=to]').fill('30');
  await page.locator('#move-form button').click(); await waitText('#notice', 'переведён');
  await page.locator('#index-form input[name=org]').fill('30'); await page.locator('#index-form input[name=coeff]').fill('1.1'); await page.locator('#index-form button').click(); await waitText('#notice', 'Обновлено работников: 1');
  const updated = await context.request.get(worker + '/workers/' + id); assert.match(await updated.text(), /<salary>110\.0<\/salary>/); checks.push('HR move and salary index through real services');
  await page.locator('#lookup-form input[name=id]').fill('2147483647'); await page.locator('#lookup-form button').click(); await waitText('#notice', '404'); checks.push('Server not-found error shown to user');
  await page.screenshot({ path: new URL('desktop.png', output).pathname.slice(process.platform === 'win32' ? 1 : 0), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({ path: new URL('mobile.png', output).pathname.slice(process.platform === 'win32' ? 1 : 0), fullPage: true }); checks.push('Desktop and mobile layout');
  page.once('dialog', dialog => dialog.accept()); await ownRow().getByRole('button', { name: 'Удалить', exact: true }).click(); await waitText('#notice', 'удалён'); await ownRow().waitFor({ state: 'detached' }); ids.splice(ids.indexOf(id), 1); checks.push('Delete worker with confirmation');
  await page.goto(base + '/docs/index.html'); await page.locator('.opblock').nth(9).waitFor(); assert.equal(await page.locator('.opblock').count(), 10); checks.push('Local Swagger renders all 10 public operations');
  assert.deepEqual(errors, []); await writeFile(new URL('report.json', output), JSON.stringify({ passed: checks, errors }, null, 2));
  console.log(JSON.stringify({ passed: checks.length, checks, errors }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ errors, notice: await page.locator('#notice').textContent().catch(() => ''), title: await page.title() }, null, 2));
  throw error;
} finally {
  for (const id of ids) await context.request.delete(worker + '/workers/' + id).catch(() => {});
  await browser.close();
}
