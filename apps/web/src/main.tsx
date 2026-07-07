// React 진입점 — DOM 마운트 + StrictMode + TanStack Router + TanStack Query
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { router } from './router'
import { Toaster } from './components/ui/sonner'
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
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
          <Toaster />
        </QueryClientProvider>
      </PreferencesProvider>
    </StrictMode>,
  )
}

void mountApp(rootElement)
