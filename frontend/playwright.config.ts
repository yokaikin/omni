import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, devices } from '@playwright/test'

// 加载本地 E2E 凭据文件（含沙箱买家账号，已被 .gitignore 的 .env.* 规则忽略，不入库）
const configDir = path.dirname(fileURLToPath(import.meta.url))
const e2eEnvFile = path.resolve(configDir, '.env.e2e.local')
if (fs.existsSync(e2eEnvFile)) {
  for (const line of fs.readFileSync(e2eEnvFile, 'utf-8').split(/\r?\n/)) {
    const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim())
    if (match && process.env[match[1]] === undefined) {
      process.env[match[1]] = match[2]
    }
  }
}

/**
 * Omni 真实浏览器 E2E 配置
 * 运行前提：本地全栈已启动（frontend:3000 / gateway:8088 / grab-service:3001 / Java 服务 / 中间件）
 * 运行方式：pnpm e2e（或 pnpm e2e:headed）
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  outputDir: './test-results',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'zh-CN',
    viewport: { width: 1366, height: 900 },
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
