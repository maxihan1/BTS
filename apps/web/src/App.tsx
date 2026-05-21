// BTS 앱 루트 컴포넌트 — Tailwind v4 + shadcn/ui 셋업 검증용 (실제 라우터는 W3-T12에서 교체)
import { Button } from '@/components/ui/button'

export const App = () => {
  return (
    <main className="flex min-h-screen items-center justify-center">
      <Button>Test</Button>
    </main>
  )
}
