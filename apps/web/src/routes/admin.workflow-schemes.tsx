// 워크플로우 스킴 admin 관리 페이지 — 사이드바 + 빈 상태 placeholder
import type { JSX } from 'react'
import { useState } from 'react'
import { WorkflowSchemeSidebar } from '@/components/admin/WorkflowSchemeSidebar'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 admin 관리 페이지.
 *
 * - 좌: WorkflowSchemeSidebar (표준/커스텀 그룹 + 「+ 새 스킴」 버튼)
 * - 우: 스킴 미선택 시 빈 상태 안내 카드 / 선택 시 T11에서 상세 렌더 예정
 *
 * router.ts 등록은 T11에서 수행한다. 이 task에서는 Route export만 제공.
 *
 * code-based 패턴 (PR #26 컨벤션):
 *   import { AdminWorkflowSchemesRouteAdapter } from './routes/admin.workflow-schemes'
 *   const adminWorkflowSchemesRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/admin/workflow-schemes',
 *     component: AdminWorkflowSchemesRouteAdapter,
 *   })
 */
export function AdminWorkflowSchemesPage(): JSX.Element {
  const [selectedSchemeKey, setSelectedSchemeKey] = useState<string | undefined>(undefined)

  const handleSelect = (schemeKey: string) => {
    setSelectedSchemeKey(schemeKey)
  }

  const handleAddNew = () => {
    // T11 라우팅 등록 후 /admin/workflow-schemes/new 로 이동 예정
    setSelectedSchemeKey(undefined)
  }

  return (
    <div className="flex h-full min-h-0">
      <WorkflowSchemeSidebar
        selectedSchemeKey={selectedSchemeKey}
        onSelect={handleSelect}
        onAddNew={handleAddNew}
      />
      <main className="flex flex-1 items-center justify-center p-8">
        {selectedSchemeKey === undefined ? (
          <EmptyStatePlaceholder onAddNew={handleAddNew} />
        ) : (
          // T11 스킴 상세 컴포넌트 렌더 예정
          <div className="text-sm text-muted-foreground">
            {selectedSchemeKey} 상세 (T11 구현 예정)
          </div>
        )}
      </main>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 placeholder
// ─────────────────────────────────────────────────────────────────────────────

interface EmptyStatePlaceholderProps {
  /** 「+ 새 스킴」 CTA 클릭 콜백 */
  onAddNew: () => void
}

/**
 * 스킴 미선택 시 표시되는 빈 상태 안내 카드.
 * 사이드바에서 스킴을 선택하거나 새 스킴을 생성하도록 안내한다.
 */
function EmptyStatePlaceholder({ onAddNew }: EmptyStatePlaceholderProps): JSX.Element {
  return (
    <div className="flex flex-col items-center gap-4 text-center">
      <div className="rounded-full bg-muted p-4">
        <svg
          className="h-8 w-8 text-muted-foreground"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25zM6.75 12h.008v.008H6.75V12zm0 3h.008v.008H6.75V15zm0 3h.008v.008H6.75V18z"
          />
        </svg>
      </div>
      <div className="space-y-1">
        <h2 className="text-base font-semibold">{workflowSchemeLabels.emptyState.heading}</h2>
        <p className="text-sm text-muted-foreground">
          {workflowSchemeLabels.emptyState.message}
        </p>
      </div>
      <button
        type="button"
        onClick={onAddNew}
        className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-background px-3 py-1.5 text-sm font-medium hover:bg-muted transition-colors"
      >
        {workflowSchemeLabels.emptyState.addSchemeButton}
      </button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (T11에서 router.ts 등록 시 사용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * T11에서 createRoute 패턴으로 등록한다.
 */
export function AdminWorkflowSchemesRouteAdapter(): JSX.Element {
  return <AdminWorkflowSchemesPage />
}
