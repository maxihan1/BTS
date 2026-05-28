// 워크플로우 스킴 상세 페이지 — 3컬럼 레이아웃 (사이드바/매핑 테이블/메타 패널)
import type { JSX } from 'react'
import { useNavigate, useParams } from '@tanstack/react-router'
import { WorkflowSchemeSidebar } from '@/components/admin/WorkflowSchemeSidebar'
import { MappingTable } from '@/components/admin/MappingTable'
import { SchemeMetaPanel } from '@/components/admin/SchemeMetaPanel'
import { useWorkflowSchemeDetail } from '@/hooks/use-workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// 스켈레톤 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스킴 상세 로딩 중 표시되는 스켈레톤 UI.
 */
function SchemeDetailSkeleton(): JSX.Element {
  return (
    <div
      role="status"
      aria-label="로딩 중"
      data-testid="detail-skeleton"
      className="flex flex-1 flex-col gap-4 p-6"
    >
      {/* 헤더 스켈레톤 */}
      <div className="h-7 w-48 animate-pulse rounded-md bg-muted" />
      {/* 테이블 스켈레톤 */}
      <div className="flex flex-col gap-2">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-10 animate-pulse rounded-md bg-muted" />
        ))}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 카드 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface SchemeNotFoundCardProps {
  schemeKey: string
}

/**
 * 스킴을 찾을 수 없을 때 표시되는 안내 카드.
 */
function SchemeNotFoundCard({ schemeKey }: SchemeNotFoundCardProps): JSX.Element {
  return (
    <div
      role="alert"
      className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center"
    >
      <div className="text-base font-semibold text-foreground">
        스킴을 찾을 수 없습니다
      </div>
      <p className="text-sm text-muted-foreground">
        스킴 키 &quot;{schemeKey}&quot;에 해당하는 워크플로우 스킴이 없습니다.
      </p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** WorkflowSchemeDetailPage 컴포넌트 props */
export interface WorkflowSchemeDetailPageProps {
  /** 조회할 스킴 키 */
  schemeKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 상세 페이지.
 *
 * 3컬럼 레이아웃:
 * - 좌 (240px): WorkflowSchemeSidebar — 스킴 목록 + 선택 강조
 * - 중 (1fr): MappingTable — 이슈 타입-워크플로우 매핑 CRUD
 * - 우 (320px): SchemeMetaPanel — name/description 편집 + 삭제 버튼 + 통계
 *
 * router.ts 등록은 T11에서 수행한다. 이 task에서는 RouteAdapter export만 제공.
 */
export function WorkflowSchemeDetailPage({
  schemeKey,
}: WorkflowSchemeDetailPageProps): JSX.Element {
  const navigate = useNavigate()
  const { data: detail, isPending, isError } = useWorkflowSchemeDetail(schemeKey)

  const handleSelect = (key: string) => {
    void navigate({ to: `/admin/workflow-schemes/${key}` })
  }

  const handleAddNew = () => {
    void navigate({ to: '/admin/workflow-schemes/new' })
  }

  return (
    <div className="flex h-full min-h-0">
      {/* 좌 사이드바 */}
      <WorkflowSchemeSidebar
        selectedSchemeKey={schemeKey}
        onSelect={handleSelect}
        onAddNew={handleAddNew}
      />

      {/* 중앙 매핑 테이블 */}
      <main className="flex min-w-0 flex-1 flex-col">
        {isPending && <SchemeDetailSkeleton />}
        {isError && <SchemeNotFoundCard schemeKey={schemeKey} />}
        {detail !== undefined && (
          <MappingTable schemeKey={schemeKey} mappings={detail.mappings} />
        )}
      </main>

      {/* 우 메타 패널 */}
      {detail !== undefined && <SchemeMetaPanel scheme={detail} />}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (T11에서 router.ts 등록 시 사용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TanStack Router code-based 패턴 RouteAdapter.
 * useParams()로 schemeKey를 추출해 WorkflowSchemeDetailPage에 전달한다.
 * T11에서 createRoute({ path: '/admin/workflow-schemes/$schemeKey' })에 등록한다.
 */
export function WorkflowSchemeDetailRouteAdapter(): JSX.Element {
  const { schemeKey } = useParams({ strict: false }) as { schemeKey: string }
  return <WorkflowSchemeDetailPage schemeKey={schemeKey} />
}
