// Vite 6 빌드 설정 — React SWC 플러그인 + Tailwind v4 + dev 서버 (5173 포트)
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
   */
  preview: {
    port: 4173,
    proxy: BACKEND_PROXY,
  },
})
