// 워크플로우 스킴 admin 관리 페이지 — 사이드바 + 빈 상태 placeholder + 상세/생성 라우팅
import type { JSX } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { WorkflowSchemeSidebar } from '@/components/admin/WorkflowSchemeSidebar'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 admin 관리 페이지 (`/admin/workflow-schemes` 인덱스).
 *
 * - 좌: WorkflowSchemeSidebar (표준/커스텀 그룹 + 「+ 새 스킴」 버튼)
 * - 우: 빈 상태 안내 카드 — 이 라우트에는 선택된 스킴이 없다(선택하면 상세로 나간다)
 *
 * ## 여기서 선택 상태를 들고 있으면 안 된다
 * 스킴 선택은 이 화면 안에서 끝나는 상태 변경이 아니라 **상세 라우트로의 이동**이다. 예전에는
 * `selectedSchemeKey` 를 로컬 state 로 두고 "상세 (T11 구현 예정)" 문구를 띄웠는데,
 * 그때 이미 `/admin/workflow-schemes/$schemeKey` 와 `/new` 는 router.ts 에 등록돼 있었다.
 * 그 결과 관리 허브에서 들어온 사용자는 스킴을 열 수도, 새로 만들 수도 없었고 —
 * 커스텀 스킴 생성 경로가 UI 에서 사라지면서 DB 에는 마이그레이션이 시드한 표준 스킴
 * 4건만 남아, 프로젝트 설정의 스킴 드롭다운이 「템플릿만 고를 수 있는」 화면이 됐다.
 * 상태를 되살리지 말 것 — 이 인덱스의 유일한 본문은 빈 상태 카드다.
 */
export function AdminWorkflowSchemesPage(): JSX.Element {
  const navigate = useNavigate()

  const handleSelect = (schemeKey: string) => {
    void navigate({ to: '/admin/workflow-schemes/$schemeKey', params: { schemeKey } })
  }

  const handleAddNew = () => {
    void navigate({ to: '/admin/workflow-schemes/new' })
  }

  return (
    <div className="flex h-full min-h-0">
      <WorkflowSchemeSidebar
        selectedSchemeKey={undefined}
        onSelect={handleSelect}
        onAddNew={handleAddNew}
      />
      <div className="flex flex-1 items-center justify-center p-8">
        <EmptyStatePlaceholder onAddNew={handleAddNew} />
      </div>
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
      <Button
        type="button"
        variant="outline"
        size="default"
        onClick={onAddNew}
        className="gap-1.5 px-3"
      >
        {workflowSchemeLabels.emptyState.addSchemeButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 */
export function AdminWorkflowSchemesRouteAdapter(): JSX.Element {
  return <AdminWorkflowSchemesPage />
}
