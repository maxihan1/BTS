// 전역 토스트 알림 컴포넌트 — sonner Toaster를 BTS 디자인 토큰에 맞춰 래핑
import { Toaster as SonnerToaster } from 'sonner'

/**
 * BTS 디자인 토큰에 정렬된 Toaster 래퍼.
 *
 * - `richColors`: 오류/경고/성공 시맨틱 색상 활성화
 * - `theme="light"`: DESIGN.md 라이트 모드 기준
 * - `closeButton`: 키보드/스크린리더 사용자가 토스트를 직접 닫을 수 있도록 보장
 *
 * 사용처: apps/web/src/main.tsx — QueryClientProvider 인접에 마운트.
 *
 * @example
 * ```tsx
 * import { Toaster } from '@/components/ui/sonner'
 * // main.tsx QueryClientProvider 바로 아래에 위치
 * <Toaster />
 * ```
 */
export const Toaster = () => (
  <SonnerToaster
    theme="light"
    richColors
    closeButton
    position="bottom-right"
    toastOptions={{
      classNames: {
        // rounded-lg = --radius-lg = var(--radius) = 0.625rem (DESIGN.md 토큰)
        toast: 'rounded-lg font-sans text-sm',
      },
    }}
  />
)
