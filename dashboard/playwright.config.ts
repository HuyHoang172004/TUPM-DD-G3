import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  outputDir: '../.lab/browser-tests',
  use: { baseURL: 'http://127.0.0.1:8765', channel: 'chrome', viewport: { width: 1600, height: 1100 }, screenshot: 'only-on-failure' },
  reporter: 'list',
});
