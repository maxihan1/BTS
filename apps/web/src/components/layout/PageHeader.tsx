// 페이지 상단 헤더 — 탐색경로+제목+설명+액션, h1은 페이지당 1개 (FR-UX-06 PR13 Task 3, PL-2)
import { type ReactNode, type JSX } from 'react'
import { Breadcrumb, type BreadcrumbItem } from './Breadcrumb'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

export interface PageHeaderProps {
  /** 페이지 제목. `<h1>`로 렌더된다 */
  readonly title: string
  /** 제목 아래 부가 설명(선택). 있으면 `<p>`로 렌더된다 */
  readonly description?: string
  /** 탐색 경로 항목(선택). 있으면 제목 위에 {@link Breadcrumb}를 렌더한다 */
  readonly breadcrumbs?: readonly BreadcrumbItem[]
  /** 제목 우측 정렬 액션 슬롯(선택, 버튼 등) */
  readonly actions?: ReactNode
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 페이지 레벨 공통 헤더. 탐색 경로(breadcrumb) → 제목행(제목+액션) → 설명 순서로 렌더한다.
 *
 * ⚠️ **h1 소유 계약**: 이 컴포넌트가 `<h1>`을 단독 소유한다. 문서 구조상 페이지당
 * `<h1>`은 1개여야 하므로(WCAG 2.4.6 근사 관례), `PageHeader`를 사용하는 페이지는
 * 별도의 `<h1>`을 추가로 렌더하지 않는다.
 *
 * 탐색 경로 렌더는 {@link Breadcrumb}에 위임한다 — `breadcrumbs`가 없거나 빈 배열이면
 * nav 자체를 렌더하지 않는다(Breadcrumb의 null 반환 계약을 그대로 물려받는다).
 */
export function PageHeader({ title, description, breadcrumbs, actions }: PageHeaderProps): JSX.Element {
  const hasBreadcrumbs = breadcrumbs !== undefined && breadcrumbs.length > 0

  return (
    <div className="mb-6">
      {hasBreadcrumbs && (
        <div className="mb-2">
          <Breadcrumb items={breadcrumbs} />
        </div>
      )}
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{title}</h1>
        {actions !== undefined && (
          <div className="flex shrink-0 items-center gap-2">{actions}</div>
        )}
      </div>
      {description !== undefined && (
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      )}
    </div>
  )
}
