// Vitest 설정 — jsdom 환경 + RTL + msw 셋업 파일 연결 + vite.config의 path alias 동기화
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react-swc'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    /**
     * 워커 풀. vitest 4 기본값은 `forks` 이고 여기서 **일부러** `threads` 로 바꾼다.
     *
     * ## 실측 (2026-09-09 · `src/api` 109파일 · 2,073 테스트 · 둘 다 전량 통과)
     *
     * | | forks | threads |
     * |---|---|---|
     * | 벽시계 | 36.2초 | **29.5초 (−18.5%)** |
     * | environment(jsdom) | 126.5s | 112.9s |
     * | import | 13.6s | 10.1s (−26%) |
     * | tests(실제 테스트) | 14.7s | 14.6s — **동일** |
     * | CPU | 460% | 522% |
     *
     * ★이득의 출처는 프로세스 fork 제거이지 테스트 실행이 아니다. `tests` 가 그대로인 것이
     *   그 증거다 — 이 스위트는 **전체의 12%만 실제 테스트**이고 나머지가 준비 비용이다.
     *
     * ## ★`isolate: false` 는 쓰지 않는다
     *
     * `environment` 126초를 없애려면 격리를 꺼야 하는데, 이 셋업은 `beforeAll(server.listen)` ·
     * `afterAll(server.close)` 가 **파일 단위 생명주기**를 전제하고 `globalThis` 폴리필이
     * 남는다. 게다가 `setup.ts` 가 「전역 afterEach 단언이 RTL cleanup 을 인질로 잡는다」는
     * 사고를 이미 기록해 뒀다 — 격리를 끄면 그 취약한 자리가 파일 간으로 번진다.
     * 얻는 것은 시간이고 잃는 것은 신뢰다.
     */
    pool: 'threads',
    globals: true,
    exclude: ['e2e/**', 'node_modules/**'],
    /**
     * 기본값 5000ms 는 이 스위트 규모(521 파일)에서 **부족하다.**
     *
     * ## 근거 — 「느림」이지 「부재」가 아니다
     * 2026-07-28 실측. `workflows.$key` T5-2 가 전체 실행 8회 중 1회 5초 타임아웃으로 실패했다.
     * - 단독 실행 3/3 통과, 소요 **53ms** — 기본 타임아웃의 1/94 다
     * - 전체 실행에서 **실패 대상이 회차마다 바뀐다** (이전 관측은 `AutomationYamlImportDialog`)
     * - 값이 틀린 게 아니라 제때 못 온다 (단언 실패가 아니라 타임아웃)
     * ⇒ 특정 테스트의 결함이 아니라 **스위트 전역의 워커 기아**다. jsdom 환경 구성만
     *   누적 800초를 넘어, 이벤트 루프가 밀리면 53ms 작업도 벽시계 5초를 넘긴다.
     *
     * ## 왜 타임아웃 증액이 여기서는 옳은가
     * 「아예 안 오는」 종류였다면 증액은 **절대** 안 듣는다 (pgmq 워커 메시지 도둑질 사례).
     * 그때의 판별식은 「단독 실행도 실패하는가」였고, 여기서는 단독이 항상 통과한다.
     *
     * 단언이 틀린 테스트는 여전히 **즉시** 실패한다 — 이 값은 대기에만 영향을 준다.
     */
    testTimeout: 15_000,
    hookTimeout: 15_000,
  },
})
