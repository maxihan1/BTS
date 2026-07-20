// 페이지 상단 탐색 경로(breadcrumb) 컴포넌트 — nav[aria-label='탐색 경로'] + ol, 마지막 항목은 현재 페이지(비링크) — FR-UX-06 PR13 Task 1 (PL-3)
import { type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { ChevronRight } from 'lucide-react'
import { navLabels } from '@/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 탐색 경로(breadcrumb) 항목 1건.
 *
 * - `to`/`params`가 있어도 목록의 **마지막 항목**은 항상 현재 페이지로 간주되어
 *   링크가 아닌 `<span aria-current="page">`로 렌더된다(ARIA Authoring Practices
 *   breadcrumb 패턴 — 현재 페이지는 상호작용 요소로 노출하지 않는다).
 */
export interface BreadcrumbItem {
  readonly label: string
  readonly to?: string
  readonly params?: Record<string, string>
}

interface BreadcrumbProps {
  readonly items: readonly BreadcrumbItem[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 항목 사이 구분자 아이콘 스타일 — 장식(aria-hidden), 정보 전달 없음 */
const SEPARATOR_ICON_CLASS = 'size-3.5 shrink-0 text-muted-foreground'

/** 링크 항목(현재 페이지 이전) 스타일 */
const ITEM_LINK_CLASS = 'text-muted-foreground hover:text-foreground hover:underline'

/** 현재 페이지 항목 스타일 — 링크가 아니므로 hover 상태 없음 */
const CURRENT_ITEM_CLASS = 'font-medium text-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 페이지 상단 탐색 경로(breadcrumb). ARIA Authoring Practices Guide의 breadcrumb
 * 패턴(https://www.w3.org/WAI/ARIA/apg/patterns/breadcrumb/)을 따른다.
 *
 * - `<nav aria-label={navLabels.breadcrumb}>`("탐색 경로", 🔒 e2e 계약. 기존 nav
 *   4종(mainNav·adminNav·projectNav·projectViewNav·search)과 substring 비충돌 — PL-9).
 * - 항목 목록은 `<ol>`(순서 있는 목록 — 경로는 계층 순서를 가진다).
 * - 마지막 항목은 항상 현재 페이지: `<span aria-current="page">`, 링크 아님.
 * - 마지막 이전 항목 중 `to`가 있으면 TanStack Router `<Link>`로 렌더한다.
 * - `items`가 빈 배열이면 표시할 경로가 없으므로 `null`을 반환한다(nav 자체 미렌더).
 */
export function Breadcrumb({ items }: BreadcrumbProps): JSX.Element | null {
  if (items.length === 0) {
    return null
  }

  const lastIndex = items.length - 1

  return (
    <nav aria-label={navLabels.breadcrumb}>
      <ol className="flex items-center gap-1.5 text-sm">
        {items.map((item, index) => {
          const isLast = index === lastIndex
          return (
            <li key={`${item.label}-${index}`} className="flex items-center gap-1.5">
              {index > 0 && <ChevronRight aria-hidden="true" className={SEPARATOR_ICON_CLASS} />}
              {isLast ? (
                <span aria-current="page" className={CURRENT_ITEM_CLASS}>
                  {item.label}
                </span>
              ) : item.to !== undefined ? (
                <Link to={item.to} params={item.params} className={ITEM_LINK_CLASS}>
                  {item.label}
                </Link>
              ) : (
                <span className={ITEM_LINK_CLASS}>{item.label}</span>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
