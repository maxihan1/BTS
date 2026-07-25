// 필터 결과 0건일 때 안내 문구와 초기화 CTA를 보여주는 공용 빈 상태 컴포넌트 (FR-UX-06 PR22)
import type { JSX } from 'react'

import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

/** FilteredEmptyState props */
export interface FilteredEmptyStateProps {
  /** 안내 1행 — 화면마다 문구가 다르므로 주입받는다(이슈="…이슈가 없습니다." / 보드="…카드가 없습니다") */
  title: string
  /** 안내 2행 — 보드 화면은 1행 계약이라 생략한다 */
  description?: string
  /** 초기화 CTA 라벨 — 보드는 i18n 상수, 이슈는 화면 문구를 그대로 넘긴다 */
  resetLabel: string
  /** "필터 초기화" 클릭 콜백 */
  onReset: () => void
  /**
   * 컨테이너 여백/최소높이 override.
   *
   * `EmptyState` 프리미티브는 `px-4 py-12` 고정이고 min-height가 없다. 흡수 전 두 화면은
   * 이슈=`py-16`, 보드=`min-h-48`로 서로 달랐으므로 그대로 흡수하면 보드에서 필터 0건일 때
   * 칸반 영역이 오그라들며 레이아웃이 튄다. 호출부가 원본 값을 넘겨 시각 no-op을 유지한다.
   */
  className?: string
}

/**
 * 필터가 적용된 상태에서 결과가 0건일 때 표시하는 공용 안내 컴포넌트.
 *
 * PR22 이전에는 `routes/issues.index.tsx`와 `routes/projects.$projectKey.board.tsx`에
 * **같은 이름의 로컬 컴포넌트가 각각 정의**돼 있었다(본문은 서로 다름). 디자인 스펙 §6이
 * `empty-state` 프리미티브의 흡수 대상으로 지목한 중복이라 여기로 통합했고,
 * 화면별로 다른 문구·여백은 prop으로 주입해 DOM 계약을 verbatim 보존한다.
 */
export function FilteredEmptyState({
  title,
  description,
  resetLabel,
  onReset,
  className,
}: FilteredEmptyStateProps): JSX.Element {
  return (
    <EmptyState
      title={title}
      description={description}
      className={className}
      action={
        <Button type="button" variant="outline" size="sm" onClick={onReset}>
          {resetLabel}
        </Button>
      }
    />
  )
}
