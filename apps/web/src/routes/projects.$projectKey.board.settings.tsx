// 보드 설정 화면 — 지라 Board settings 의 Columns 탭 (부채 177 · FR-BD-01/03)
import type { JSX } from 'react'
import { Link, useParams, useSearch } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useBoard } from '@/hooks/use-boards'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { boardLabels } from '@/i18n/board-labels'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { ColumnSettingsPanel } from '@/components/board/settings/ColumnSettingsPanel'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts 에 등록되는 라우트 어댑터.
 *
 * `router.ts` 는 `.ts` 라 JSX 를 담을 수 없다 — 어댑터 컴포넌트로 우회하는 것이 이 저장소의
 * code-based 라우팅 관용구다(2026-05-22 learning). 형제 13개 설정 라우트가 같은 모양이다.
 */
export function BoardSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  const search = useSearch({ strict: false }) as { board?: string }
  return <BoardSettingsPage projectKey={projectKey ?? ''} boardId={search.board} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────────────────────────────────────

/** BoardSettingsPage props */
export interface BoardSettingsPageProps {
  /** URL params 의 프로젝트 키 */
  projectKey: string
  /**
   * `?board=` 로 지목된 보드 UUID.
   *
   * **없으면 화면을 그리지 않는다.** 기본 보드를 스스로 고르지 않는 이유는
   * [NoBoardSelected] 의 주석에 있다.
   */
  boardId: string | undefined
}

/** 화면 상단 헤더 — 정상·오류·미지목 분기가 공통으로 쓴다. */
function PageHeader({ projectKey }: { projectKey: string }): JSX.Element {
  return (
    <header className="space-y-1">
      <h1 className="text-2xl font-semibold">{boardLabels.settings.pageHeading}</h1>
      <p className="text-muted-foreground text-sm">{boardLabels.settings.pageDescription}</p>
      <Button asChild variant="ghost" size="sm" className="-ml-2">
        <Link to="/projects/$projectKey/board" params={{ projectKey }}>
          <ArrowLeft aria-hidden="true" />
          {boardLabels.settings.backToBoard}
        </Link>
      </Button>
    </header>
  )
}

/**
 * `?board=` 없이 들어온 경우.
 *
 * ★**기본 보드를 스스로 고르지 않는다.** 「기본 보드」가 무엇인지에 대한 규칙이 이미 세 곳에서
 * 서로 다르고(부채 164 — 보드 탭은 `boards[0]`, 백로그는 첫 SCRUM, 백엔드는 또 다른 것),
 * 여기서 네 번째를 만들면 그 부채가 커진다. 보드를 지목하게 하고 보드 화면으로 돌려보낸다.
 */
function NoBoardSelected({ projectKey }: { projectKey: string }): JSX.Element {
  return (
    <EmptyState
      title={boardLabels.settings.boardNotSelected}
      action={
        <Button asChild>
          <Link to="/projects/$projectKey/board" params={{ projectKey }}>
            {boardLabels.settings.backToBoard}
          </Link>
        </Button>
      }
    />
  )
}

/**
 * 보드 설정 화면 — 이 PR 은 **Columns 탭 하나**만 그린다.
 *
 * 나머지 6탭(Swimlanes · Quick filters · Card layout · Estimation · Working days ·
 * Issue detail view)을 비활성 골격으로 미리 만들지 않는다. 「누를 수 있는데 아무 일도 안 일어나는」
 * 화면을 6개 배포하는 것이고, 그것이 장부가 경계한 「도달할 UI 가 없는 기능」의 거울상이다.
 * 탭이 하나라 **탭바 자체를 안 만든다** — 두 번째 탭을 만드는 PR 이 탭바를 도입한다.
 *
 * 스윔레인·퀵필터는 보드 화면 인라인에 그대로 둔다(편차 X3, Maxi 확정) — 오늘 한 번에 되는
 * 조작을 설정 화면 안으로 숨기면 UX 가 나빠진다.
 */
export function BoardSettingsPage({ projectKey, boardId }: BoardSettingsPageProps): JSX.Element {
  const permissions = useProjectPermissions(projectKey)
  // 보드 화면(`:545`)과 **같은 식**을 쓴다. `=== true` 로만 여는 fail-closed 관용구다 —
  // 권한이 아직 안 왔을 때 편집이 열리면 안 된다.
  const canConfigure: boolean = permissions.data?.permissions.CREATE === true
  const boardQuery = useBoard(boardId)

  if (boardId === undefined || boardId === '') {
    return (
      <div className="space-y-6 p-6">
        <PageHeader projectKey={projectKey} />
        <NoBoardSelected projectKey={projectKey} />
      </div>
    )
  }

  return (
    <div className="space-y-6 p-6">
      <PageHeader projectKey={projectKey} />

      {/* 권한이 없어도 화면은 보인다 — 읽기는 BROWSE 로 충분하고, 편집만 잠근다(S7).
          진입 자체를 막으면 「왜 못 들어가지」가 되고, 사유를 보여 주는 편이 낫다. */}
      {!canConfigure && (
        <p className="text-muted-foreground text-sm" role="status">
          {boardLabels.settings.readOnlyReason}
        </p>
      )}

      {boardQuery.isPending && <ColumnsSkeleton />}

      {boardQuery.isError && (
        <EmptyState
          title={boardLabels.settings.loadError}
          action={
            <Button
              onClick={() => {
                void boardQuery.refetch()
              }}
            >
              {boardLabels.settings.retry}
            </Button>
          }
        />
      )}

      {boardQuery.data !== undefined && <ColumnSettingsPanel board={boardQuery.data} />}
    </div>
  )
}

/**
 * 조회 중 골격.
 *
 * 컬럼 3개 자리를 미리 잡아 둔다 — 스피너 하나만 두면 로드 후 화면이 통째로 튄다.
 */
function ColumnsSkeleton(): JSX.Element {
  return (
    <div className="flex gap-4 overflow-x-auto pb-4" aria-hidden="true">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-64 w-72 shrink-0" />
      ))}
    </div>
  )
}
