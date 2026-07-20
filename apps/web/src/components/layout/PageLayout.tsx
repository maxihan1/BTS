// 페이지 콘텐츠 공통 컨테이너 — 문서 <main>은 ShellLayout이 소유하므로 이 컴포넌트는 <div>만 렌더한다 (FR-UX-06 PR13 Task 2, PL-1)
import { type ReactNode, type JSX } from 'react'
import { cn } from '@/lib/utils'

type PageLayoutMaxWidth = '2xl' | '4xl' | '7xl'

interface PageLayoutProps {
  readonly children: ReactNode
  readonly maxWidth?: PageLayoutMaxWidth
  readonly className?: string
}

function maxWidthClass(maxWidth: PageLayoutMaxWidth): string {
  switch (maxWidth) {
    case '2xl':
      return 'max-w-2xl'
    case '4xl':
      return 'max-w-4xl'
    case '7xl':
      return 'max-w-7xl'
  }
}

// PageLayout — 컨테이너는 <div>만 렌더한다. <main>은 ShellLayout이 단독 소유(C3).
export function PageLayout({ children, maxWidth = '4xl', className }: PageLayoutProps): JSX.Element {
  return (
    <div className={cn('mx-auto w-full px-4 py-8', maxWidthClass(maxWidth), className)}>
      {children}
    </div>
  )
}
