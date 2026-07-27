// 워크플로우 스킴 좌 사이드바 — 표준/커스텀 group + ＋ 새 스킴 버튼 + 키보드 접근성
import type { JSX, KeyboardEvent } from 'react'
import { Button } from '@/components/ui/button'
import { useWorkflowSchemes } from '@/hooks/use-workflow-schemes'
import type { SchemeListItem } from '@/api/workflow-schemes'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 및 내부 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 그룹 레이블 */
const GROUP_LABEL_STANDARD = workflowSchemeLabels.sidebar.standardGroup
const GROUP_LABEL_CUSTOM = workflowSchemeLabels.sidebar.customGroup

/** 로딩 스켈레톤 행 수 */
const SKELETON_ROW_COUNT = 6

/** 사이드바 표시에 필요한 스킴 필드 — SchemeListItem 별칭 */
type SchemeSummary = SchemeListItem

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** WorkflowSchemeSidebar 컴포넌트 props */
export interface WorkflowSchemeSidebarProps {
  /** 현재 선택된 스킴 키 */
  selectedSchemeKey?: string
  /** 스킴 행 클릭 콜백 */
  onSelect: (schemeKey: string) => void
  /** 「+ 새 스킴」 버튼 클릭 콜백 */
  onAddNew: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 그룹 헤더
// ─────────────────────────────────────────────────────────────────────────────

interface SchemeGroupHeaderProps {
  /** 그룹 레이블 */
  label: string
  /** 그룹 내 스킴 수 */
  count: number
}

/**
 * 사이드바 내 스킴 그룹 헤더.
 * "표준 (4)" / "커스텀 (2)" 형태로 표시한다.
 * label과 count를 별도 span으로 분리해 getByText('표준') 매칭을 보장한다.
 * 시안 fr-wf-02-d6-1.html — nav-group-title 스타일 적용.
 */
function SchemeGroupHeader({ label, count }: SchemeGroupHeaderProps): JSX.Element {
  return (
    <div className="px-2 pb-1 pt-2 flex items-center gap-1">
      <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-muted-foreground">
        {label}
      </span>
      <span className="text-[11px] text-muted-foreground">({count})</span>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 스킴 행
// ─────────────────────────────────────────────────────────────────────────────

interface SchemeRowProps {
  /** 스킴 데이터 */
  scheme: SchemeSummary
  /** 선택 여부 */
  isSelected: boolean
  /** 클릭 콜백 */
  onSelect: (schemeKey: string) => void
}

/**
 * 사이드바 내 스킴 단건 행.
 * 이름 + usedByProjectsCount(count) 표시.
 * 선택 시 카드 배경 + border(시안 active 스타일).
 * 키보드(Enter/Space) 접근성 보장.
 */
function SchemeRow({ scheme, isSelected, onSelect }: SchemeRowProps): JSX.Element {
  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onSelect(scheme.key)
    }
  }

  return (
    // PR22 OUT — P5 옵션 행: aria-current 를 갖는 사이드바 항목이라 w-full text-left 가 필요하고, Button의 inline-flex/justify-center와 충돌한다
    <button
      type="button"
      aria-current={isSelected ? 'true' : undefined}
      aria-label={scheme.name}
      className={[
        'w-full text-left px-[10px] py-2 rounded-md text-[13px] transition-colors',
        'flex items-center justify-between gap-2',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        isSelected
          ? 'bg-card border border-border font-medium shadow-sm'
          : 'hover:bg-accent text-foreground',
      ].join(' ')}
      onClick={() => onSelect(scheme.key)}
      onKeyDown={handleKeyDown}
    >
      <span className="truncate">{scheme.name}</span>
      <span className="shrink-0 text-[11px] text-muted-foreground">
        {scheme.usedByProjectsCount}
      </span>
    </button>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 스킴 그룹
// ─────────────────────────────────────────────────────────────────────────────

interface SchemeGroupProps {
  /** 그룹 레이블 */
  label: string
  /** 그룹 내 스킴 목록 */
  schemes: SchemeSummary[]
  /** 현재 선택된 스킴 키 */
  selectedSchemeKey?: string
  /** 스킴 선택 콜백 */
  onSelect: (schemeKey: string) => void
}

/**
 * 표준 또는 커스텀 스킴 그룹.
 * 그룹 헤더(이름 + 수) + 스킴 행 목록으로 구성된다.
 */
function SchemeGroup({ label, schemes, selectedSchemeKey, onSelect }: SchemeGroupProps): JSX.Element {
  return (
    <section aria-label={`${label} 스킴 그룹`} className="mb-5">
      <SchemeGroupHeader label={label} count={schemes.length} />
      <ul className="space-y-0.5 px-2">
        {schemes.map((scheme) => (
          <li key={scheme.key}>
            <SchemeRow
              scheme={scheme}
              isSelected={selectedSchemeKey === scheme.key}
              onSelect={onSelect}
            />
          </li>
        ))}
      </ul>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 데이터 로드 전 표시되는 스켈레톤 플레이스홀더.
 */
function SidebarSkeleton(): JSX.Element {
  return (
    <div role="status" aria-label={workflowSchemeLabels.sidebar.loadingStatus} className="space-y-2 p-3">
      {Array.from({ length: SKELETON_ROW_COUNT }).map((_, i) => (
        <Skeleton
          key={i}
          className="h-8"
        />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 관리 admin 페이지 좌 사이드바.
 *
 * - useWorkflowSchemes()로 스킴 목록을 조회한다.
 * - isStandard 기준으로 표준/커스텀 그룹을 분리 렌더한다.
 * - 커스텀 그룹 아래 「+ 새 스킴」 버튼을 표시한다.
 * - 선택된 스킴은 aria-current="true"로 강조한다.
 * - 시안 fr-wf-02-d6-1.html의 OKLCH 색상 토큰을 사용한다.
 */
export function WorkflowSchemeSidebar({
  selectedSchemeKey,
  onSelect,
  onAddNew,
}: WorkflowSchemeSidebarProps): JSX.Element {
  const { data: schemes, isPending } = useWorkflowSchemes()

  if (isPending) {
    return (
      <nav aria-label={workflowSchemeLabels.sidebar.nav} className="w-60 shrink-0 bg-muted border-r border-border">
        <SidebarSkeleton />
      </nav>
    )
  }

  const allSchemes = schemes ?? []
  const standardSchemes = allSchemes.filter((s) => s.isStandard)
  const customSchemes = allSchemes.filter((s) => !s.isStandard)

  return (
    <nav aria-label={workflowSchemeLabels.sidebar.nav} className="flex w-60 shrink-0 flex-col bg-muted border-r border-border">
      {/* 사이드바 헤더 */}
      <div className="px-4 py-5">
        <h2 className="text-[13px] font-medium text-muted-foreground uppercase tracking-[0.05em]">
          {workflowSchemeLabels.sidebar.header}
        </h2>
      </div>

      {/* 스킴 그룹 목록 */}
      <div className="flex-1 overflow-y-auto px-2 pb-2">
        {standardSchemes.length > 0 && (
          <SchemeGroup
            label={GROUP_LABEL_STANDARD}
            schemes={standardSchemes}
            selectedSchemeKey={selectedSchemeKey}
            onSelect={onSelect}
          />
        )}
        <SchemeGroup
          label={GROUP_LABEL_CUSTOM}
          schemes={customSchemes}
          selectedSchemeKey={selectedSchemeKey}
          onSelect={onSelect}
        />
      </div>

      {/* 새 스킴 추가 버튼 — 시안 nav-btn-new: dashed border */}
      <div className="p-4">
        <Button
          variant="outline"
          size="sm"
          className="w-full border-dashed"
          onClick={onAddNew}
          aria-label={workflowSchemeLabels.sidebar.addSchemeAriaLabel}
        >
          {workflowSchemeLabels.sidebar.addSchemeButtonText}
        </Button>
      </div>
    </nav>
  )
}
