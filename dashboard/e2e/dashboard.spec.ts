import { test, expect } from '@playwright/test';

test('dashboard has real empty metrics and gates measurement on preflight/capture', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Paging 3', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chạy demo A/B' })).toBeDisabled();
  const runs = await page.request.get('/api/runs').then(r => r.json()) as { method_id: string }[];
  if (!runs.some(run => run.method_id === 'paging')) {
    await expect(page.getByText('Chờ dữ liệu thực nghiệm')).toBeVisible();
    await expect(page.getByText('Chưa có phiên thực nghiệm')).toBeVisible();
  }
});

test('methods and subcases can be selected and unimplemented methods stay disabled', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'HTTP cache & ETag', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'HTTP cache & ETag', exact: true })).toBeVisible();
  await page.locator('.setup-controls select').first().selectOption('etag');
  await expect(page.locator('.setup-controls select').first()).toHaveValue('etag');
  await page.getByRole('button', { name: 'Lazy initialization', exact: false }).click();
  await expect(page.getByRole('heading', { name: 'Lazy initialization', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chạy demo A/B' })).toBeDisabled();
});

test('preflight displays actual tool checks and can close', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Kiểm tra môi trường', exact: true }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('Android Platform Tools', { exact: true })).toBeVisible({ timeout: 15000 });
  await expect(dialog.getByText('Dataset tái lập', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Đóng', exact: true }).click();
  await expect(dialog).not.toBeVisible();
});

test('mobile layout keeps method navigation and evidence reachable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Paging 3', exact: true })).toBeVisible();
  const capture = page.getByRole('button', { name: 'Kết nối evidence', exact: true });
  await capture.scrollIntoViewIfNeeded();
  await expect(capture).toBeVisible();
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(390);
});
