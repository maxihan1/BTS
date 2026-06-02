// React 진입점 — DOM 마운트 + StrictMode + TanStack Router + TanStack Query
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { router } from './router'
import { Toaster } from './components/ui/sonner'
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
    // E2E 테스트용 dev 훅 — dev 전용(import.meta.env.DEV 게이트 내부). production 미포함.
    // __msw: MSW worker + http + HttpResponse (per-test 핸들러 오버라이드용)
    // __queryClient: TanStack QueryClient (오버라이드 후 invalidateQueries 강제 refetch용)
    const mswModule = await import('msw')
    const devWindow = window as unknown as { __msw?: unknown; __queryClient?: unknown }
    devWindow.__msw = { worker, http: mswModule.http, HttpResponse: mswModule.HttpResponse }
    devWindow.__queryClient = queryClient
  }

  createRoot(root).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <Toaster />
      </QueryClientProvider>
    </StrictMode>,
  )
}

void mountApp(rootElement)
