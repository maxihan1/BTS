// 워크플로우 스킴 좌 사이드바 — 표준/커스텀 group + ＋ 새 스킴 버튼 + 키보드 접근성
import type { JSX, KeyboardEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { SCHEME_KEYS } from '@/hooks/use-workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 및 내부 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 그룹 레이블 */
const GROUP_LABEL_STANDARD = '표준'
const GROUP_LABEL_CUSTOM = '커스텀'

/**
 * 사이드바 표시에 필요한 스킴 필드.
 * isStandard는 Zod SchemeResponse에 없으나 backend/MSW가 실제로 반환한다.
 * raw fetch 응답에서 직접 파싱해 보존한다.
 */
interface SchemeSummary {
  schemeKey: string
  name: string
  description: string
  isStandard: boolean
  usedByProjectsCount: number
  mappingsCount: number
}

/** GET /api/v1/workflow-schemes 응답 래퍼 */
interface SchemesListResponse {
  data: SchemeSummary[]
}

/**
 * raw fetch로 스킴 목록을 조회한다.
 * isStandard 필드를 보존하기 위해 Zod 파싱을 거치지 않는다.
 */
async function fetchSchemeSummaries(): Promise<SchemeSummary[]> {
  const res = await fetch('/api/v1/workflow-schemes')
  if (!res.ok) {
    throw new Error(`스킴 목록 조회 실패: ${res.status}`)
  }
  const body = (await res.json()) as SchemesListResponse
  return body.data
}

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
}

/**
 * 사이드바 내 스킴 그룹 헤더.
 * 표준 / 커스텀 섹션 구분에 사용한다.
 */
function SchemeGroupHeader({ label }: SchemeGroupHeaderProps): JSX.Element {
  return (
    <div className="px-3 py-1.5">
      <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {label}
      </span>
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
 * 이름 + mappingsCount badge + usedByProjectsCount 표시.
 * 키보드(Enter/Space) 접근성 보장.
 */
function SchemeRow({ scheme, isSelected, onSelect }: SchemeRowProps): JSX.Element {
  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onSelect(scheme.schemeKey)
    }
  }

  return (
    <button
      type="button"
      aria-current={isSelected ? 'true' : undefined}
      aria-label={scheme.name}
      className={[
        'w-full text-left px-3 py-2 rounded-md text-sm transition-colors',
        'flex items-center justify-between gap-2',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        isSelected
          ? 'bg-primary/10 text-primary font-medium'
          : 'hover:bg-muted text-foreground',
      ].join(' ')}
      onClick={() => onSelect(scheme.schemeKey)}
      onKeyDown={handleKeyDown}
    >
      <span className="truncate">{scheme.name}</span>
      <span className="flex shrink-0 items-center gap-1.5">
        {scheme.mappingsCount > 0 && (
          <span className="inline-flex items-center rounded-full bg-secondary px-1.5 py-0.5 text-xs font-medium text-secondary-foreground">
            {scheme.mappingsCount}
          </span>
        )}
        {scheme.usedByProjectsCount > 0 && (
          <span className="text-xs text-muted-foreground">
            {scheme.usedByProjectsCount}개 프로젝트
          </span>
        )}
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
 * 그룹 헤더 + 스킴 행 목록으로 구성된다.
 */
function SchemeGroup({ label, schemes, selectedSchemeKey, onSelect }: SchemeGroupProps): JSX.Element {
  return (
    <section aria-label={`${label} 스킴 그룹`}>
      <SchemeGroupHeader label={label} />
      <ul className="space-y-0.5 px-2">
        {schemes.map((scheme) => (
          <li key={scheme.schemeKey}>
            <SchemeRow
              scheme={scheme}
              isSelected={selectedSchemeKey === scheme.schemeKey}
              onSelect={onSelect}
            />
          </li>
        ))}
      </ul>
    </section>
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
 */
export function WorkflowSchemeSidebar({
  selectedSchemeKey,
  onSelect,
  onAddNew,
}: WorkflowSchemeSidebarProps): JSX.Element {
  const { data: schemes, isPending } = useQuery({
    queryKey: SCHEME_KEYS.list,
    queryFn: fetchSchemeSummaries,
    staleTime: 30_000,
  })

  if (isPending) {
    return (
      <nav aria-label="워크플로우 스킴 목록" className="w-64 shrink-0 border-r">
        <div role="status" aria-label="로딩 중" className="space-y-2 p-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div
              key={i}
              className="h-8 animate-pulse rounded-md bg-muted"
            />
          ))}
        </div>
      </nav>
    )
  }

  const allSchemes = schemes ?? []

  const standardSchemes = allSchemes.filter((s) => s.isStandard)
  const customSchemes = allSchemes.filter((s) => !s.isStandard)

  return (
    <nav aria-label="워크플로우 스킴 목록" className="flex w-64 shrink-0 flex-col border-r">
      <div className="flex-1 overflow-y-auto py-2 space-y-3">
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
      <div className="border-t p-3">
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          onClick={onAddNew}
          aria-label="새 스킴 추가"
        >
          + 새 스킴
        </Button>
      </div>
    </nav>
  )
}
