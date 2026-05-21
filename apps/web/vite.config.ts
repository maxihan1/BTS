// Vite 6 빌드 설정 — React SWC 플러그인 + dev 서버 (5173 포트)
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
})
