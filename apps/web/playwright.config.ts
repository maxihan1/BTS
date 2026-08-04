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
    // ★`pnpm dev` 가 아니라 바이너리 직접 호출이다. worktree(`.worktrees/<slug>`)의
    // `node_modules` 는 main 트리에서 심볼릭 링크되는데, pnpm 래퍼는 실행 전 의존성 검사에서
    // 그 경로 불일치를 감지해 `pnpm install` 을 자동 트리거하고 무-TTY 환경에서
    // `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다. 아래 재사용 금지 설정 때문에
    // Playwright 는 **항상 자기 서버를 띄우므로**, 이 명령이 죽으면 worktree 에서 E2E 를
    // 돌릴 방법이 원천적으로 없어진다. CLAUDE.md §핵심 패턴이 "worktree per 작업"을 강제하므로
    // 그건 곧 모든 작업의 E2E 가 막힌다는 뜻이다 (FR-UX-12 F4 Task 4 가 실측으로 적발).
    // `apps/web` 의 `dev` 스크립트가 정확히 `vite` 라 기능은 동일하다 — 판별식이 그 등가를
    // 강제한다. 계열 메모리. `worktree-pnpm-verify-deps-symlink`
    command: 'node_modules/.bin/vite',
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
