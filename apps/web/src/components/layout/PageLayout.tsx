// 페이지 콘텐츠 공통 컨테이너 — 문서 <main>은 ShellLayout이 소유하므로 이 컴포넌트는 <div>만 렌더한다 (FR-UX-06 PR13 Task 2, PL-1)
import { type ReactNode, type JSX } from 'react'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** {@link PageLayout} 컨테이너 최대 폭 프리셋 */
type PageLayoutMaxWidth = '2xl' | '4xl' | '7xl'

export interface PageLayoutProps {
  readonly children: ReactNode
  /** 컨테이너 최대 폭 프리셋. 기본값 `'4xl'` */
  readonly maxWidth?: PageLayoutMaxWidth
  /** 컨테이너에 병합할 추가 클래스 */
  readonly className?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `maxWidth` 프리셋 → Tailwind `max-w-*` 클래스 매핑.
 * 매직스트링 조합(`` `max-w-${maxWidth}` ``) 대신 명시적 상수 객체로 관리해
 * Tailwind JIT의 정적 클래스 스캔 대상에서 누락되지 않도록 한다.
 */
const MAX_WIDTH_CLASS: Record<PageLayoutMaxWidth, string> = {
  '2xl': 'max-w-2xl',
  '4xl': 'max-w-4xl',
  '7xl': 'max-w-7xl',
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 페이지 레벨 공통 컨테이너. 좌우 중앙 정렬 + 최대 폭 제한 + 기본 패딩을 제공한다.
 *
 * ⚠️ **구조 계약**: 이 컴포넌트는 `<div>`만 렌더하며 `<main>`을 렌더하지 않는다.
 * 문서의 `<main>` 랜드마크는 `ShellLayout`이 단독 소유한다(C3). 페이지 콘텐츠가
 * 자체 `<main>`을 추가로 렌더하면 문서당 main 2개가 되어 WCAG 1.3.1(랜드마크 유일성)을
 * 위반한다 — 이 계약은 `PageLayout.test.tsx`의 구조 회귀 가드(`querySelector('main')`이
 * `null`)로 봉인되어 있다.
 */
export function PageLayout({ children, maxWidth = '4xl', className }: PageLayoutProps): JSX.Element {
  return (
    <div className={cn('mx-auto w-full px-4 py-8', MAX_WIDTH_CLASS[maxWidth], className)}>
      {children}
    </div>
  )
}
