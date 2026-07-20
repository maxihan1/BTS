// 프로젝트 목록 테이블 — 로딩/에러/빈 상태 분기 + 행 클릭 콜백(경로 결정은 resolveProjectPath) (FR-PJ PR-5 Task 4)
import type { JSX } from 'react'
import { FolderOpen } from 'lucide-react'
import type { Project } from '@/api/projects'
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 로컬 라벨 (공유 i18n 파일 미변경, 병렬 충돌 방어)
// ─────────────────────────────────────────────────────────────────────────────

const LABELS = {
  archivedBadge: '아카이브',
  loading: '로딩 중...',
  loadError: '프로젝트 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  emptyTitleActive: '표시할 프로젝트가 없습니다',
  emptyDescriptionActive: '접근 가능한 활성 프로젝트가 없습니다.',
  emptyTitleArchived: '아카이브된 프로젝트가 없습니다',
  newProjectCta: '새 프로젝트 만들기',
} as const

/** 테이블 컬럼 헤더 라벨 — 헤더 행 렌더와 셀 순서의 단일 출처(순서 drift 방지) */
const TABLE_COLUMNS = ['키', '이름', '상태'] as const

/** 프로젝트 생성 라우트 경로 — ProjectListPage 헤더 버튼과 빈 상태 CTA가 공유하는 단일 출처 */
export const NEW_PROJECT_PATH = '/projects/new'

// ─────────────────────────────────────────────────────────────────────────────
// 경로 헬퍼 — 활성 프로젝트는 보드, 아카이브 프로젝트는 설정(해제 경로, G3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 행이 가리켜야 하는 경로를 결정한다.
 *
 * 아카이브된 프로젝트는 보드/백로그 등 활성 뷰가 없으므로(G3), 아카이브 해제
 * 버튼을 보유한 설정 화면(danger zone)으로 보낸다. 활성 프로젝트는 보드로 이동한다.
 */
export function resolveProjectPath(project: Project): string {
  return project.archived === true
    ? `/projects/${project.key}/settings/details`
    : `/projects/${project.key}/board`
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

export interface ProjectListTableProps {
  /** 렌더할 프로젝트 목록 — 정렬은 백엔드 신뢰(재정렬하지 않는다) */
  readonly projects: readonly Project[]
  /** 조회 진행 중 여부 */
  readonly isLoading: boolean
  /** 조회 실패 여부 — true면 빈 상태로 은폐하지 않고 에러 배너를 표시한다 */
  readonly isError: boolean
  /** 현재 아카이브 필터 적용 여부 — 빈 상태 문구 분기에 사용 */
  readonly archived: boolean
  /** 프로젝트 생성 권한 보유 여부 — 빈 상태 CTA 노출 게이팅 */
  readonly canCreateProject: boolean
  /** 행 클릭(또는 키 링크 클릭) 시 호출되는 콜백 — 실제 이동은 호출측(RouteAdapter)이 처리한다 */
  readonly onNavigateToProject: (project: Project) => void
}

/**
 * 프로젝트 목록 테이블 — 로딩/에러/빈 상태/목록 4분기 presentational 컴포넌트.
 *
 * @remarks
 * - 로딩 중에는 role="status" 로딩 문구, 에러 시 role="alert" 배너(빈 상태로 오인되지 않게
 *   isLoading/isError를 목록보다 먼저 분기한다 — admin.webhooks FINDING3 관례).
 * - 빈 배열이면 {@link EmptyState}. 아카이브 필터 중이 아니고 canCreateProject면
 *   "새 프로젝트 만들기" CTA를 함께 노출한다(EC-1).
 * - 각 행의 키 셀은 `<a href={resolveProjectPath(project)}>`(IssueCard 선례 동형) —
 *   실제 href는 검사 가능한 채로 두되, 클릭은 `preventDefault` 후 콜백에 위임해
 *   라우터 의존 없이 단위 테스트 가능하게 한다. 행(tr) 자체의 onClick은 마우스 편의용
 *   전체 행 클릭 보조 수단이다(키보드 접근은 키 셀 링크가 담당).
 * - 아카이브 배지는 `project.archived === true`일 때만 상태 열에 렌더한다(BE-1).
 */
export function ProjectListTable({
  projects,
  isLoading,
  isError,
  archived,
  canCreateProject,
  onNavigateToProject,
}: ProjectListTableProps): JSX.Element {
  if (isLoading) {
    return (
      <div role="status" aria-label={LABELS.loading} className="py-8 text-center text-sm text-muted-foreground">
        {LABELS.loading}
      </div>
    )
  }

  if (isError) {
    return (
      <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
        {LABELS.loadError}
      </div>
    )
  }

  if (projects.length === 0) {
    const showCreateCta = !archived && canCreateProject
    return (
      <EmptyState
        icon={<FolderOpen aria-hidden="true" className="size-8" />}
        title={archived ? LABELS.emptyTitleArchived : LABELS.emptyTitleActive}
        description={archived ? undefined : LABELS.emptyDescriptionActive}
        action={
          showCreateCta ? (
            <Button asChild>
              <a href={NEW_PROJECT_PATH}>{LABELS.newProjectCta}</a>
            </Button>
          ) : undefined
        }
      />
    )
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          {TABLE_COLUMNS.map((column) => (
            <TableHead key={column}>{column}</TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {projects.map((project) => (
          <TableRow
            key={project.id}
            onClick={() => { onNavigateToProject(project) }}
            className="cursor-pointer"
          >
            <TableCell>
              <a
                href={resolveProjectPath(project)}
                aria-label={project.key}
                onClick={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  onNavigateToProject(project)
                }}
                className="rounded font-medium text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {project.key}
              </a>
            </TableCell>
            <TableCell>{project.name}</TableCell>
            <TableCell>
              {project.archived === true && <Badge variant="neutral">{LABELS.archivedBadge}</Badge>}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
