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
