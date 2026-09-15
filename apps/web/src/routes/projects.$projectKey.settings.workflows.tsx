// 프로젝트 워크플로우 목록 — 전역 템플릿 + 이 프로젝트 전용만 보여주고 복제로 소유를 만든다 (FR-WF-08)
import type { JSX } from 'react'
import { useNavigate, useParams } from '@tanstack/react-router'
import { CopyIcon, PencilIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/api/client'
import { useProjectWorkflows } from '@/hooks/use-workflows'
import { useDuplicateWorkflow } from '@/hooks/use-workflows-admin'
import { workflowEditorLabels } from '@/i18n/workflow-editor-labels'
import type { WorkflowView } from '@/api/workflows'

const labels = workflowEditorLabels.projectSettings

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/** router.ts 가 등록하는 어댑터. URL 의 `$projectKey` 만 뽑아 넘긴다. */
export function ProjectWorkflowsSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectWorkflowsSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 안내 카드 — 조회 실패는 빈 표로 방치하지 않는다
// ─────────────────────────────────────────────────────────────────────────────

/** 안내 카드 한 장. 제목과 본문만 다르다. */
function NoticeCard({ title, message }: { title: string; message: string }): JSX.Element {
  return (
    <Card className="border-destructive/40 bg-destructive/10">
      <CardHeader>
        <CardTitle className="text-destructive">{title}</CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        <p>{message}</p>
      </CardContent>
    </Card>
  )
}

/** 페이지 머리 — 정상/실패 분기가 공통으로 쓴다. */
function PageHeader(): JSX.Element {
  return (
    <header className="space-y-1">
      <h2 className="text-xl font-semibold">{labels.heading}</h2>
      <p className="text-muted-foreground text-sm">{labels.description}</p>
    </header>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectWorkflowsSettingsPageProps {
  /** URL params 에서 뽑은 프로젝트 식별 키 */
  projectKey: string
}

/**
 * 프로젝트가 쓸 수 있는 워크플로우 목록.
 *
 * ## 왜 전역/전용을 갈라 보여주는가
 * 전역 공유 워크플로우는 시스템 관리자만 고칠 수 있다(스펙 §4 D6). 목록이 둘을 같아 보이게
 * 그리면 프로젝트 관리자가 전역 행에 편집을 눌러 **403 을 받고서야** 알게 된다. 그래서 소유
 * 배지를 달고, 전역 행의 주 액션을 「내 프로젝트로 복제」로 둔다 — Jira 가 권장하는 우회로가
 * 그대로 화면의 기본 동선이 된다.
 *
 * ## 왜 전역 목록 훅을 쓰지 않는가
 * `useWorkflows` 는 **권한 게이트가 없는 전량 목록**이라 남의 프로젝트 전용 워크플로우가
 * 이름째 온다. 서버가 좁힌 창구(`useProjectWorkflows`)를 탄다.
 *
 * ## 권한은 백엔드가 판정한다
 * 프론트에 프로젝트 어드민 가드를 새로 만들지 않는다 — 프로젝트 설정 라우트 전부가
 * `requireAuthAndPasswordChanged` 하나이고, 권한은 403 응답 + 안내 카드로 받는 것이 이 저장소의
 * 관례다(`ForbiddenSchemeCard` 선례).
 */
export function ProjectWorkflowsSettingsPage({
  projectKey,
}: ProjectWorkflowsSettingsPageProps): JSX.Element {
  const navigate = useNavigate()
  const { data: workflows, isPending, error } = useProjectWorkflows(projectKey)
  const duplicate = useDuplicateWorkflow()

  if (isPending) {
    return (
      <div className="p-8 space-y-6">
        <PageHeader />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (error != null) {
    const status = error instanceof ApiError ? error.status : null
    return (
      <div className="p-8 space-y-6 max-w-3xl">
        <PageHeader />
        {status === 403 && <NoticeCard title={labels.forbiddenTitle} message={labels.forbiddenMessage} />}
        {status === 404 && <NoticeCard title={labels.notFoundTitle} message={labels.notFoundMessage} />}
        {status !== 403 && status !== 404 && (
          <NoticeCard title={labels.loadErrorTitle} message={labels.loadErrorMessage} />
        )}
      </div>
    )
  }

  const list = workflows ?? []

  return (
    <div className="p-8 space-y-6">
      <PageHeader />

      {list.length === 0 ? (
        <EmptyState title={labels.emptyTitle} description={labels.emptyDescription} />
      ) : (
        <Table aria-label={labels.table}>
          <TableHeader>
            <TableRow>
              <TableHead>{workflowEditorLabels.list.columnName}</TableHead>
              <TableHead>{workflowEditorLabels.list.columnKey}</TableHead>
              <TableHead>{labels.columnOwner}</TableHead>
              <TableHead>{workflowEditorLabels.list.columnStatusCount}</TableHead>
              <TableHead>{workflowEditorLabels.list.columnTransitionCount}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.map((workflow) => (
              <TableRow key={workflow.key}>
                <TableCell className="font-medium">{workflow.name}</TableCell>
                <TableCell className="text-(--text-subtle)">{workflow.key}</TableCell>
                <TableCell>
                  <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded-full">
                    {workflow.projectId === null ? labels.ownerGlobal : labels.ownerProject}
                  </span>
                </TableCell>
                <TableCell>{workflow.states.length}</TableCell>
                <TableCell>{workflow.transitions.length}</TableCell>
                <TableCell className="flex justify-end gap-1">
                  {workflow.projectId === null ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`${labels.copyToProject} ${workflow.name}`}
                      disabled={duplicate.isPending}
                      onClick={() => duplicate.mutate(copyInput(workflow, projectKey))}
                    >
                      <CopyIcon aria-hidden="true" className="size-4" />
                    </Button>
                  ) : (
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`${workflowEditorLabels.list.edit} ${workflow.name}`}
                      onClick={() =>
                        void navigate({
                          to: `/projects/${projectKey}/settings/workflows/${workflow.key}`,
                        })
                      }
                    >
                      <PencilIcon aria-hidden="true" className="size-4" />
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}

/**
 * 전역 템플릿을 이 프로젝트 사본으로 만드는 입력.
 *
 * ★`projectKey` 를 반드시 싣는다. 빠뜨리면 사본도 전역이 되어 여전히 못 고친다 — 이 화면이
 * 존재하는 이유가 그 자리에서 사라진다.
 *
 * key 는 원본과 같게 둔다. V209 가 key 유일성을 소유별로 갈라, 전역에 같은 key 가 있어도
 * 프로젝트 사본이 만들어진다 — 사용자가 이름을 다시 짓지 않아도 된다.
 */
/**
 * 복제 요청 바디를 만든다.
 *
 * ★★2026-09-14 운영 실측. 종전 판본은 `key: workflow.key` 로 **사본 key 를 원본과
 *   똑같이** 보냈다. V209 가 key 유일성을 소유별로 갈라 두어 생성은 성공하지만
 *   (전역 1 + 프로젝트 1), 조회는 아직 key 만 본다 — `findLiveIdByKey` 가 `fetchOne`
 *   이라 2행에서 `TooManyRowsException` 으로 죽는다. 그 순간부터 그 key 의
 *   **수정·삭제·복제·전환 CRUD 가 전부 500** 이 됐다.
 *
 *   서버가 아니라 여기서 막는 이유. 백엔드는 소유별 중복을 **정당하게 허용**한다 —
 *   「전역 템플릿을 같은 이름으로 내 프로젝트에」가 Jira 권장 우회로이고 그 계약이
 *   `WorkflowCrudIntegrationTest` 에 못 박혀 있다. 생성이 잘못된 것이 아니라,
 *   이 화면이 **사용자 의도 없이** 그 상태를 만든 것이 잘못이다.
 */
function copyInput(workflow: WorkflowView, projectKey: string) {
  // ★`-copy` 접미사를 고른 근거.
  //
  //   · **읽힌다.** key 는 URL 에 그대로 들어간다(/projects/{p}/settings/workflows/{key}).
  //     난수나 타임스탬프를 섞으면 충돌은 사라지지만 주소창이 읽을 수 없게 된다.
  //   · **URL 안전이 공짜다.** 원본 key 가 이미 안전하므로 접미사만 붙이면 그대로 안전하다.
  //   · **두 번 복제하면 409 다.** 조용히 다른 것을 만들지 않고 백엔드가 거부하며,
  //     사용자는 그것을 화면에서 본다. 예측 가능한 key 의 대가로 받아들인다 —
  //     조용히 맞는 것보다 시끄럽게 틀리는 쪽이 낫다.
  const copyKey = `${workflow.key}-copy`
  return {
    sourceKey: workflow.key,
    key: copyKey,
    name: `${workflow.name} (${labels.copySuffix})`,
    projectKey,
  }
}
