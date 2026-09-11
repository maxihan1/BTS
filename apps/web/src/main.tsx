// React 진입점 — DOM 마운트 + StrictMode + TanStack Router + TanStack Query
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { router } from './router'
import { Toaster } from './components/ui/sonner'
import { TooltipProvider } from './components/ui/tooltip'
import { PreferencesProvider } from './components/preferences/PreferencesProvider'
import './index.css'

const rootElement = document.getElementById('root')
if (rootElement === null) {
  throw new Error('Root element #root not found in index.html')
}

// T10 useLoginMutation / T11 useLogoutMutation 가 useMutation 사용 — 최상위 QueryClientProvider 필수
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
})

async function mountApp(root: HTMLElement) {
  // dev 모드에서 MSW browser worker 활성화 — backend 없이 API mock 처리
  if (import.meta.env.DEV) {
    const { worker } = await import('./mocks/browser')
    await worker.start({
      onUnhandledRequest: 'bypass', // API 외 정적 자산 요청은 그대로 통과
    })
    /*
     * ★E2E 가 기다릴 수 있는 **관측 가능한 신호**를 남긴다 (2026-09-11).
     *
     * MSW 의 mocking 은 **문서 단위**다. 서비스워커가 그 문서의 clientId 를 등록한 뒤에만
     * 요청을 가로채고(`mockServiceWorker.js:249`), 마지막 클라이언트가 닫히면 워커가 스스로
     * unregister 한다(`:75-82`). 그래서 네비게이션 경계마다 **재기동 창**이 열리는데,
     * Playwright 의 `goto`·`waitForURL`·`reload` 중 **어느 것도 그 핸드셰이크를 안 기다린다.**
     *
     * 그 창에 걸린 요청은 vite 프록시로 새고 백엔드가 없으니 ECONNREFUSED 가 된다.
     * 무해할 때가 많지만 화면이 그 응답을 기다리면 테스트가 무작위로 빨개진다 —
     * 2026-09-11 E2E 전량 실행에서 샤드가 실패↔성공을 오간 원인이 이것이다.
     *
     * `onUnhandledRequest` 로는 못 잡는다. 그 옵션은 MSW 가 **받아 본** 요청에만 적용되는데,
     * 이 요청들은 핸들러 해석에 닿기 전에 passthrough 로 빠진다.
     */
    ;(window as unknown as { __MSW_READY__?: boolean }).__MSW_READY__ = true
  }

  createRoot(root).render(
    <StrictMode>
      <PreferencesProvider>
        {/* 앱 1회 마운트 — `ui/tooltip.tsx` 가 「소비처가 앱 1회 감싼다」를 계약으로 적어 두었다.
            각 Tooltip 이 Provider 를 self-wrap 하면 전역 delayDuration 이 내부 기본값으로
            조용히 덮인다(C6). 에디터 툴바(J24)가 첫 소비처다. */}
        <TooltipProvider>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
            <Toaster />
          </QueryClientProvider>
        </TooltipProvider>
      </PreferencesProvider>
    </StrictMode>,
  )
}

void mountApp(rootElement)
