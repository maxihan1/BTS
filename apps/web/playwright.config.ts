// Playwright E2E 테스트 설정 — chromium(기능) + visual(시각 회귀 스냅샷) 2 프로젝트
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env['CI'],
  retries: 0,
  // ★기본 템플릿
  // (`{snapshotDir}/{testFileDir}/{testFileName}-snapshots/{arg}{-projectName}{-snapshotSuffix}{ext}`)
  // 을 쓰지 않는다. `{-snapshotSuffix}` 가 OS 이름(`-darwin`)으로 채워지므로, 러너를 바꾸면
  // 기존 baseline 과 비교하지 못하고 **조용히 새 파일을 만들며 초록**이 된다 — 회귀를 못 보는
  // 가짜초록이다. 접미를 없애면 환경이 달라진 순간 diff 로 드러난다(baseline 은 러너와 동일
  // 환경에서만 생성한다는 계약과 짝을 이룬다 — e2e/visual/visual-regression.spec.ts 상단 주석).
  snapshotPathTemplate: 'e2e/visual/__screenshots__/{arg}{ext}',
  /**
   * 리포터 3종. 종전에는 **미설정**이라 기본 `list` 만 돌았고, 결과가 콘솔 로그로만 남아
   * 젠킨스에서 「어느 테스트가 왜 실패했는지」를 볼 수 없었다 — 수만 줄 로그를 뒤져야 했다.
   *
   * - `list`  — 사람이 콘솔에서 진행을 본다.
   * - `junit` — **젠킨스가 읽는 형식**이다. 테스트별 성공/실패가 빌드 화면에 표로 뜨고,
   *   실패가 언제 처음 생겼는지(회귀 지점)를 젠킨스가 추적한다.
   * - `html`  — 실패의 재현 화면·클릭 기록·네트워크 로그를 담은 보고서. 젠킨스가
   *   아티팩트로 보관한다.
   *
   * ★`open: 'never'` 가 필수다. 기본값은 실패 시 브라우저를 띄우려 하는데 CI 에는 브라우저가
   *   없고, 그 시도가 무한 대기로 이어질 수 있다.
   */
  reporter: [
    ['list'],
    ['junit', { outputFile: 'test-results/junit.xml' }],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: 'http://localhost:5173',
    /**
     * ★실패 증거를 남긴다. E2E 는 「왜 실패했는지」가 로그로 안 보인다 — 화면이 증거다.
     *
     * 계획서 P3 가 「브라우저 눈확인은 원리적으로 못 옮긴다 → 스크린샷 아티팩트로 대체」라고
     * 적어 뒀는데, 보관 설정이 없으면 그 대체가 성립하지 않는다.
     *
     * `retain-on-failure` 는 통과한 테스트의 것은 버린다 — 전량 167파일에서 전부 남기면
     * 수 GB 가 되고 2코어 머신의 디스크·시간을 먹는다.
     */
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      // ★`e2e/visual` 은 아래 `visual` 프로젝트 전용이다. 제외하지 않으면 루트 testDir
      // 때문에 같은 spec 이 두 프로젝트에서 각각 돌고, 두 실행이 **같은 baseline 파일 1개**를
      // 놓고 비교한다(위 경로 템플릿에서 `{-projectName}` 을 뺐다). 뷰포트가 서로 달라
      // 한쪽은 반드시 빨간불이 되고, `--update-snapshots` 는 같은 파일을 두 번 덮어쓴다.
      testIgnore: '**/e2e/visual/**',
    },
    {
      // 시각 회귀 스냅샷 전용 (파일럿 — 이슈 목록·이슈 상세 2화면).
      name: 'visual',
      testDir: './e2e/visual',
      // 뷰포트를 여기서 고정한다. Desktop Chrome 기본값(1280×720)과 다르므로 spec 쪽에서
      // 다시 선언하지 않는다 — 두 곳에 적으면 갈라진다.
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
      // ★재시도 0 을 프로젝트에서도 못박는다. 재시도로 덮으면 파일럿이 재려는 값
      // (무변경 상태 20회 중 diff 회수)이 사라져 채택 판정 자체가 불가능해진다.
      retries: 0,
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
