// Vite 6 빌드 설정 — React SWC 플러그인 + Tailwind v4 + dev·preview 서버 (둘 다 5173 포트)
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

/**
 * 백엔드 프록시 규칙 — dev 서버와 preview 가 **같은 것**을 쓴다.
 *
 * 두 곳에 따로 적으면 한쪽만 자라 갈라진다(이 저장소의 지배적 결함 양식).
 */
const BACKEND_PROXY = {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
  },
  // STOMP WebSocket 프록시 — 프론트에서 백엔드(8080)로 WS 업그레이드 (C4)
  '/ws': {
    target: 'http://localhost:8080',
    ws: true,
    changeOrigin: true,
  },
} as const

export default defineConfig({
  plugins: [tailwindcss(), react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    // ★조용한 포트 이동을 금지한다. 미지정 시 5173 이 점유돼 있으면 vite 는 5174 로 옮겨 붙는데,
    // 그 오리진은 백엔드 CORS 허용목록(`http://localhost:5173`) 밖이라 **POST 만** 403 이 된다.
    // 즉시 명시적으로 실패하는 편이 낫다. 판별식.
    // `scripts/workflow/preview-cors-origin-alignment.test.ts`
    strictPort: true,
    // ★`localhost` 를 IPv4 로 푸는 브라우저에서 접속 불가가 되지 않게 명시한다.
    // 미지정 시 vite 가 `[::1]`(IPv6 루프백)에만 바인딩되는 환경이 있어,
    // 터미널에는 "Local: http://localhost:5173" 이 찍히는데 실제로는 연결이 거부된다.
    host: true,
    // /api/* 요청을 백엔드(8080)로 프록시 — 로컬 개발 시 CORS 없이 통신
    proxy: BACKEND_PROXY,
  },
  /**
   * `vite preview` (프로덕션 빌드 미리보기) 에도 같은 프록시를 건다.
   *
   * ## 왜 필요한가 — 실 백엔드로 손검증할 유일한 경로다
   * `main.tsx` 는 `import.meta.env.DEV` 일 때 **무조건** MSW(가짜 서버 응답) 워커를 켠다.
   * 끄는 스위치가 없으므로 `pnpm dev` 로는 실 백엔드에 절대 닿지 않는다.
   * 프로덕션 빌드는 `DEV=false` 라 MSW 가 꺼지는데, 그동안 preview 에 프록시가 없어
   * `/api` 가 404 였다 — 결국 **실 백엔드로 UI 를 손검증할 방법이 아예 없었다.**
   *
   * 이 8줄로 `pnpm build && pnpm preview` 가 그 경로가 된다.
   *
   * ## ★ 왜 dev 서버와 **같은** 포트인가
   * 백엔드 CORS 허용목록의 기본값이 `http://localhost:5173` **하나**다
   * (`backend/modules/app/src/main/resources/application.yml`). vite 의 기본 preview 포트
   * 4173 을 그대로 쓰면 브라우저가 `Origin` 을 붙이는 **POST 만** `403 Invalid CORS request` 가
   * 되고(GET 에는 Origin 을 안 보내므로 통과), 손검증의 첫 단계인 로그인부터 막힌다.
   *
   * 허용목록을 넓히는 쪽(4173 추가)을 택하지 않은 이유는 그 기본값이 운영 배포에도 딸려가
   * `BTS_CORS_ALLOWED_ORIGINS` 미설정 시 `localhost:4173` 이 허용된 채로 뜨기 때문이다.
   *
   * 대가는 dev 서버와 동시 기동 불가다. `main.tsx` 가 `import.meta.env.DEV` 일 때 무조건 MSW 를
   * 켜므로 두 모드는 애초에 배타적 용도(가짜 응답 vs 실 백엔드)이고, 겹치면 아래 `strictPort` 가
   * 즉시 실패시킨다.
   */
  preview: {
    port: 5173,
    // dev 와 같은 이유 — 조용히 5174 로 옮겨 붙으면 CORS 허용목록 밖이 된다.
    strictPort: true,
    // dev 와 같은 이유 — IPv6 전용 바인딩 회피.
    host: true,
    proxy: BACKEND_PROXY,
  },
})
