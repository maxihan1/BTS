// Playwright E2E 테스트 설정 — chromium 단일 프로젝트 (Phase 0)
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env['CI'],
  retries: 0,
  use: {
    baseURL: 'http://localhost:5173',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    // ★재사용 금지. `vite preview`(실 백엔드 손검증 경로)가 dev 와 **같은 5173** 을 쓰므로,
    // preview 를 켜 둔 채 E2E 를 돌리면 Playwright 가 그것을 잡는다. 프로덕션 빌드는
    // `import.meta.env.DEV` 가 false 라 MSW(가짜 응답)가 꺼져 있어 전 테스트가 API 호출부터
    // 깨진다 — 거짓 초록은 아니지만 원인이 전혀 보이지 않는 실패다.
    // false 면 항상 자기 dev 서버를 띄우고, 포트가 점유돼 있으면 vite 의 `strictPort` 가
    // 즉시 명시적으로 실패시킨다. 속도 이유로 되돌리지 말 것 — 판별식.
    // `scripts/workflow/preview-cors-origin-alignment.test.ts`
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
