// Vite 6 빌드 설정 — React SWC 플러그인 + Tailwind v4 + dev 서버 (5173 포트)
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

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
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // STOMP WebSocket 프록시 — dev 서버(5173)에서 백엔드(8080)로 WS 업그레이드 (C4)
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
