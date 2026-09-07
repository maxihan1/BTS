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
