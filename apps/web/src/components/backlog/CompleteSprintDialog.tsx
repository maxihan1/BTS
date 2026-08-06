// 스프린트 완료 다이얼로그 — 미완료 이슈를 먼저 이관한 뒤에만 완료한다 (FR-UX-13 F15 · FR-5·FR-6·FR-7)
//
// ★ 「이관 먼저」는 UX 취향이 아니라 안전 요구다 (ADR C1 — 완료는 되돌릴 수 없다).
//   COMPLETED 스프린트에 남은 이슈는 **영구 동결**된다. 실측 근거 3겹.
//   ① `POST /complete` 는 스프린트 상태만 뒤집고 이슈를 옮기지 않는다.
//   ② `DELETE /sprints/{id}/issues/{key}` 는 스프린트가 COMPLETED 면 `STATUS <> 'COMPLETED'`
//      조건부 DELETE 라 **아무 행도 지우지 않고 204** 를 준다 — 실패가 아니라 침묵이라 호출자가 오해한다.
//   ③ `sprint_issues` 의 `UNIQUE (issue_key)` 때문에 다른 스프린트로도 못 옮긴다(409).
//   부분 실패 시 완료 중단 · truncated 시 제출 차단 · 완료 직전 재검증 · 미완료 판정의 안전측 편향이
//   전부 이 하나의 사실에서 파생된다.
import type { JSX } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { Check, TriangleAlert } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/api/client'
import { assignToSprint, fetchBacklog, unassignFromSprint } from '@/api/backlog'
import type { BacklogIssue, BacklogView, SprintMeta, SprintWithIssues } from '@/api/backlog'
import { backlogKeys, useCompleteSprint } from '@/hooks/use-backlog'
import { useWorkflows } from '@/hooks/use-workflows'
import { buildStateCategoryMap, isIssueIncomplete } from '@/lib/backlog-completion'
import type { StateCategoryMap } from '@/lib/backlog-completion'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'
import { cn } from '@/lib/utils'

const L = backlogLabels.completeDialog

/** 이관 대상 Select 의 「백로그」 센티널. UUID 와 절대 겹치지 않는 형태로 둔다 */
const BACKLOG_TARGET_VALUE = '__backlog__'

/** 이관 대상이 될 수 있는 스프린트 상태. COMPLETED 는 반드시 409 라 뺀다 */
const TRANSFERABLE_SPRINT_STATUSES: readonly string[] = ['PLANNED', 'ACTIVE']

/**
 * `truncated` 상태에서 완료 제출을 차단하는 정책 (E15 · Maxi 재확정 2026-08-05).
 *
 * 타입을 **`boolean` 으로 명시 annotate** 한다. 리터럴 `true` 로 두면 TypeScript 가
 * 반대 분기를 도달 불가로 좁혀 「정책이 꺼진 경우」 테스트가 가짜 그린이 된다.
 */
export const BLOCK_COMPLETE_WHEN_TRUNCATED: boolean = true

/**
 * 제출을 차단해야 하는지 판정한다.
 *
 * 정책을 **인자로 받는다** — 그래야 두 값이 각각 실제로 실행되는 경로가 되고,
 * 상수를 컴포넌트 안에서 직접 읽을 때 생기는 도달 불가 분기를 만들지 않는다.
 *
 * @param truncated 백로그 응답의 `truncated`
 * @param blockWhenTruncated 정책. 기본값은 {@link BLOCK_COMPLETE_WHEN_TRUNCATED}
 * @returns 차단해야 하면 `true`
 */
// eslint-disable-next-line react-refresh/only-export-components -- 정책 술어는 테스트가 두 값을 각각 지나기 위한 named export (BurndownChart·VelocityChart 선례)
export function isSubmitBlockedByTruncation(
  truncated: boolean,
  blockWhenTruncated: boolean = BLOCK_COMPLETE_WHEN_TRUNCATED,
): boolean {
  return truncated && blockWhenTruncated
}

/** 이관 행 상태. 항목이 없으면 아직 시도하지 않은 행이다 */
type RowStatus = 'moved' | 'failed'

/** 완료를 막은 사유. 문구가 서로 다른 처방을 뜻하므로 뭉뚱그리지 않는다 */
type FailureState =
  | { readonly kind: 'partial'; readonly attempted: number; readonly failed: number }
  | { readonly kind: 'forbidden' }
  | { readonly kind: 'stale' }
  | { readonly kind: 'truncated' }

/** 화면에 그릴 미완료 목록 + 완료 건수 한 벌 */
interface CompletionView {
  readonly rows: readonly BacklogIssue[]
  readonly doneCount: number
}

/** 이관 mutation 입력 */
interface TransferInput {
  readonly issueKey: string
  /** `null` 이면 백로그로 뺀다 (DELETE 1회). 그 외에는 DELETE → POST 2회 */
  readonly targetSprintId: string | null
}

/** {@link CompleteSprintDialog} Props */
export interface CompleteSprintDialogProps {
  /** 열림 여부 */
  readonly open: boolean
  /** 열림 상태 변경 요청. 이관 진행 중에는 닫기 요청이 무시된다 (E19) */
  readonly onOpenChange: (open: boolean) => void
  /** 백로그 queryKey 대상 프로젝트 키 */
  readonly projectKey: string
  /** 완료할 스프린트 + 그 스프린트의 이슈 전량 */
  readonly sprint: SprintWithIssues
  /** 프로젝트의 스프린트 메타 전량. 이관 대상 후보를 여기서 고른다 */
  readonly allSprints: readonly SprintMeta[]
  /** 백로그 응답의 `truncated`. true 면 제출을 막는다 (E15) */
  readonly truncated: boolean
  /**
   * 이슈 재정렬·할당·해제 권한(UPDATE).
   *
   * 이관은 `POST /{id}/issues`·`DELETE` 둘 다 **UPDATE** 인데 `complete` 만 CREATE 다 (C-15).
   * CREATE 만 있는 사용자는 전건 403 을 받으므로 이관 UI 자체를 게이팅한다.
   */
  readonly canReorderIssue: boolean
}

/**
 * 실패 상태를 사용자 문구로 옮긴다.
 *
 * `stale` 은 전에 `startDialog.patchConflict` 를 빌려 썼다. 그 문구가 「입력하신 값은
 * 그대로 두었으니」로 바뀌면서 **입력 폼이 없는** 이 다이얼로그에서 거짓이 되어
 * 전용 `staleBlocked` 로 갈랐다.
 */
function failureMessage(failure: FailureState): string {
  switch (failure.kind) {
    case 'partial':
      return L.moveFailedAlert(failure.attempted, failure.failed)
    case 'forbidden':
      return L.moveForbidden
    case 'truncated':
      return L.truncatedBlocked
    case 'stale':
      return L.staleBlocked
  }
}

/**
 * 이관 대상 후보를 만든다.
 *
 * 「백로그」 + PLANNED·ACTIVE 스프린트(자기 자신 제외). **COMPLETED 는 넣지 않는다** —
 * 넣으면 사용자가 고를 수 있는 선택지가 반드시 409 로 끝난다.
 *
 * @param allSprints 프로젝트의 스프린트 메타 전량
 * @param sourceSprintId 완료 대상 스프린트 UUID (후보에서 제외)
 */
function buildMoveTargets(
  allSprints: readonly SprintMeta[],
  sourceSprintId: string,
): readonly SprintMeta[] {
  return allSprints.filter(
    (meta) =>
      meta.sprintId !== sourceSprintId && TRANSFERABLE_SPRINT_STATUSES.includes(meta.status),
  )
}

/** 스프린트 이슈를 미완료/완료로 갈라 화면에 그릴 한 벌로 만든다 */
function buildView(issues: readonly BacklogIssue[], map: StateCategoryMap): CompletionView {
  const rows = issues.filter((issue) => isIssueIncomplete(issue, map))
  return { rows, doneCount: issues.length - rows.length }
}

/**
 * 이관 → 재검증 → 완료 흐름의 상태와 실행을 한곳에 모은다.
 *
 * 요청을 **다이얼로그가 직접** 낸다. 콜백 props 로 밀어내면 「요청 순서·호출 수」를
 * 단위 테스트에서 잴 수 없게 된다.
 *
 * @param projectKey 백로그 queryKey 대상 프로젝트 키
 * @param sprint 완료할 스프린트 + 이슈
 * @param categoryMap 상태 키 → 카테고리 집합 사상 (FR-7)
 * @param open 다이얼로그 열림 여부. 닫히면 회차 상태를 비운다
 * @param onCompleted 완료가 성공했을 때 호출된다
 */
function useSprintCompletion(
  projectKey: string,
  sprint: SprintWithIssues,
  categoryMap: StateCategoryMap,
  open: boolean,
  onCompleted: () => void,
) {
  const queryClient = useQueryClient()
  const completeSprint = useCompleteSprint(projectKey)
  const sourceSprintId = sprint.sprint.sprintId

  const [snapshot, setSnapshot] = useState<CompletionView | null>(null)
  const [rowStatus, setRowStatus] = useState<Readonly<Record<string, RowStatus>>>({})
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(0)
  const [progressTotal, setProgressTotal] = useState(0)
  const [failure, setFailure] = useState<FailureState | null>(null)

  const derived = useMemo(() => buildView(sprint.issues, categoryMap), [sprint.issues, categoryMap])
  const view = snapshot ?? derived

  // 닫힘 전이에서 회차 상태를 비운다 — 같은 스프린트로 다시 열면 처음부터다.
  // 상태를 가진 훅 안에 두어야 setter 만 의존성이 되어 exhaustive-deps 를 그대로 만족한다.
  useEffect(() => {
    if (open) return
    setSnapshot(null)
    setRowStatus({})
    setRunning(false)
    setProgress(0)
    setProgressTotal(0)
    setFailure(null)
  }, [open])

  /** 이관 1건 — 백로그행은 DELETE 1회, 다른 스프린트행은 DELETE → POST 2회 */
  const transfer = useMutation<void, unknown, TransferInput>({
    mutationFn: async ({ issueKey, targetSprintId }) => {
      // DELETE 가 먼저인 것은 선택이 아니다 — `sprint_issues` 의 UNIQUE (issue_key) 때문에
      // 제거 없이 POST 하면 409 다.
      await unassignFromSprint(sourceSprintId, issueKey)
      if (targetSprintId !== null) {
        await assignToSprint(targetSprintId, issueKey)
      }
    },
  })

  /** 이관을 **직렬로**(동시 1) 실행한다. 부분 실패 지점을 결정적으로 만들기 위함이다 */
  async function runTransfers(
    targets: readonly BacklogIssue[],
    targetSprintId: string | null,
    baseStatus: Readonly<Record<string, RowStatus>>,
  ): Promise<{ failed: number; forbidden: boolean }> {
    const status: Record<string, RowStatus> = { ...baseStatus }
    let failed = 0
    let forbidden = false
    let done = 0
    for (const issue of targets) {
      try {
        await transfer.mutateAsync({ issueKey: issue.key, targetSprintId })
        status[issue.key] = 'moved'
      } catch (error) {
        status[issue.key] = 'failed'
        failed += 1
        if (error instanceof ApiError && error.status === 403) forbidden = true
      }
      done += 1
      setProgress(done)
      setRowStatus({ ...status })
    }
    return { failed, forbidden }
  }

  /**
   * ★ C-7 — 되돌릴 수 없는 연산 직전의 재검증.
   *
   * 다이얼로그를 연 스냅샷으로 이관하고 곧장 완료하면 두 가지가 조용히 깨진다.
   * ① 그 사이 남이 이슈를 추가하면 목록에 없어 그대로 **영구 동결**된다.
   * ② 남이 원본을 먼저 완료했으면 `DELETE` 가 조용히 204 를 줘서 프론트가 전 행을
   *    「이관됨」으로 **오판**한다 — 실패가 아니므로 부분 실패 가드에도 걸리지 않는다.
   *
   * @returns 완료를 막아야 하면 그 사유. 진행해도 되면 `null`
   */
  async function revalidate(): Promise<FailureState | null> {
    let fresh: BacklogView
    try {
      fresh = await queryClient.fetchQuery({
        queryKey: backlogKeys.detail(projectKey),
        queryFn: () => fetchBacklog(projectKey),
        staleTime: 0,
      })
    } catch {
      return { kind: 'stale' }
    }
    const freshSprint = fresh.sprints.find((entry) => entry.sprint.sprintId === sourceSprintId)
    const nextView = buildView(freshSprint?.issues ?? [], categoryMap)
    if (isSubmitBlockedByTruncation(fresh.truncated)) {
      setSnapshot(nextView)
      return { kind: 'truncated' }
    }
    if (nextView.rows.length > 0) {
      setSnapshot(nextView)
      setRowStatus({})
      return { kind: 'stale' }
    }
    return null
  }

  /** 재검증을 통과했을 때만 `POST /complete` 를 1회 보낸다 */
  async function finish(): Promise<void> {
    const blocked = await revalidate()
    if (blocked !== null) {
      setRunning(false)
      setFailure(blocked)
      return
    }
    try {
      await completeSprint.mutateAsync(sourceSprintId)
      setRunning(false)
      onCompleted()
    } catch {
      // 완료 자체가 실패했다 — 가장 흔한 갈래가 409(이미 완료됐거나 ACTIVE 가 아니다)이고,
      // 어느 갈래든 사용자가 할 일은 「최신 값을 확인하고 다시 시도」로 같다. 조용히 닫지 않는다.
      setRunning(false)
      setFailure({ kind: 'stale' })
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
    }
  }

  /**
   * 이관을 전부 끝낸 뒤에만 완료한다. 1건이라도 실패하면 `complete` 를 보내지 않는다.
   *
   * @param targetSprintId 이관 대상. `null` 이면 백로그
   * @param retryOnly true 면 **실패한 이슈만** 다시 보낸다 (`DELETE` 가 멱등이라 안전)
   */
  async function submit(targetSprintId: string | null, retryOnly: boolean): Promise<void> {
    const targets = retryOnly ? view.rows.filter((row) => rowStatus[row.key] === 'failed') : view.rows
    setSnapshot(view)
    setFailure(null)
    setProgress(0)
    setProgressTotal(targets.length)
    setRunning(true)

    const outcome = await runTransfers(targets, targetSprintId, retryOnly ? rowStatus : {})
    if (outcome.failed > 0) {
      setRunning(false)
      setFailure(
        outcome.forbidden
          ? { kind: 'forbidden' }
          : { kind: 'partial', attempted: targets.length, failed: outcome.failed },
      )
      await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
      return
    }
    await finish()
  }

  return { view, rowStatus, running, progress, progressTotal, failure, submit }
}

/** 미완료 이슈 한 행 */
function IncompleteIssueRow({
  issue,
  status,
}: {
  readonly issue: BacklogIssue
  readonly status: RowStatus | undefined
}): JSX.Element {
  return (
    <li className="flex items-center gap-2 px-3 py-2 text-sm hover:bg-(--bg-neutral-hover)">
      <span className="shrink-0 font-medium text-foreground">{issue.key}</span>
      <span className="truncate text-muted-foreground">{issue.summary}</span>
      <span className="ml-auto shrink-0">
        {status === 'moved' && (
          <span className="flex items-center gap-1 text-xs text-muted-foreground">
            <Check aria-hidden="true" className="size-3" />
            {L.rowMoved}
          </span>
        )}
        {status === 'failed' && (
          <span className="flex items-center gap-1 text-xs text-danger-text">
            <TriangleAlert aria-hidden="true" className="size-3" />
            {L.rowFailed}
          </span>
        )}
      </span>
    </li>
  )
}

/** 이관 대상 단일 Select — 목록의 모든 미완료 이슈에 같은 대상을 적용한다 (Jira 동형) */
function MoveTargetSelect({
  targets,
  value,
  onChange,
  disabled,
}: {
  readonly targets: readonly SprintMeta[]
  readonly value: string
  readonly onChange: (value: string) => void
  readonly disabled: boolean
}): JSX.Element {
  return (
    <div className="space-y-1.5">
      <Label htmlFor="complete-sprint-move-target" className="text-sm font-medium text-foreground">
        {L.moveTargetLabel}
      </Label>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger
          id="complete-sprint-move-target"
          aria-label={L.moveTargetLabel}
          className="w-full"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={BACKLOG_TARGET_VALUE}>{L.backlogOption}</SelectItem>
          {targets.map((meta) => (
            <SelectItem key={meta.sprintId} value={meta.sprintId}>
              {meta.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * 스프린트 완료 다이얼로그.
 *
 * 부모는 **`key={sprint.sprintId}` 로 마운트한다** — 회차 상태를 내부에 두므로,
 * 대상이 바뀌어도 재마운트되지 않으면 낡은 행 상태가 남는다.
 */
export function CompleteSprintDialog({
  open,
  onOpenChange,
  projectKey,
  sprint,
  allSprints,
  truncated,
  canReorderIssue,
}: CompleteSprintDialogProps): JSX.Element {
  const workflows = useWorkflows()
  const categoryMap = useMemo(() => buildStateCategoryMap(workflows.data), [workflows.data])
  const [moveTarget, setMoveTarget] = useState<string>(BACKLOG_TARGET_VALUE)
  const flow = useSprintCompletion(projectKey, sprint, categoryMap, open, () => onOpenChange(false))

  const moveTargets = buildMoveTargets(allSprints, sprint.sprint.sprintId)
  const blockedByTruncation = isSubmitBlockedByTruncation(truncated)
  const blockedByPermission = !canReorderIssue && flow.view.rows.length > 0
  // ★ 상태 분류를 모르면 완료를 막는다 — `truncated` 와 **같은 처방**이다 (Maxi 확정 2026-08-05).
  //   `isIssueIncomplete` 는 분류가 없으면 안전측으로 전건을 미완료로 본다(FR-7). 그 상태로
  //   제출하면 **완료된 이슈까지 전량** 백로그로 반출되고, 되돌릴 방법이 없다 —
  //   `assignIssue` 가 `STATUS <> 'COMPLETED'` 조건부라 완료된 스프린트에는 다시 넣지 못하고(409),
  //   벨로시티가 `sprint_issues` 멤버십으로 계산되므로 그 스프린트의 성과 기록이 영구히 0이 된다.
  //   백로그 화면은 `/workflows` 를 미리 부르지 않아 창을 여는 순간이 매번 콜드 페치다.
  //   `unavailable` 은 `workflows.data === undefined` 와 동치라 **로딩 중과 조회 실패를 함께** 덮는다
  //   (`isPending` 을 따로 OR 하면 도달 불가 분기가 생긴다).
  const blockedByUnknownWorkflow = categoryMap.unavailable
  const showWorkflowWarning = blockedByUnknownWorkflow && !workflows.isPending
  const showRetry = flow.failure?.kind === 'partial' || flow.failure?.kind === 'forbidden'

  /** E19 — 이관 진행 중에는 Esc·오버레이·닫기 버튼 어느 것으로도 닫히지 않는다 */
  function handleOpenChange(next: boolean): void {
    if (!next && flow.running) return
    onOpenChange(next)
  }

  function handleSubmit(retryOnly: boolean): void {
    const targetSprintId = moveTarget === BACKLOG_TARGET_VALUE ? null : moveTarget
    void flow.submit(targetSprintId, retryOnly)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{backlogLabels.completeSprint}</DialogTitle>
          <DialogDescription>{L.description}</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          {blockedByTruncation && (
            <p
              role="alert"
              className="rounded-md border border-warning bg-warning/10 px-3 py-2 text-sm"
            >
              {L.truncatedBlocked}
            </p>
          )}
          {showWorkflowWarning && (
            <p
              role="alert"
              className="rounded-md border border-warning bg-warning/10 px-3 py-2 text-sm"
            >
              {L.workflowLoadFailed}
            </p>
          )}
          {blockedByPermission && (
            <p
              role="alert"
              className="rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text"
            >
              {L.moveForbidden}
            </p>
          )}

          <p className="text-sm text-muted-foreground">
            {L.summary(flow.view.doneCount, flow.view.rows.length)}
          </p>

          {workflows.isPending ? (
            <div className="space-y-2" aria-hidden="true">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : (
            <CompletionBody
              rows={flow.view.rows}
              rowStatus={flow.rowStatus}
              blockedByTruncation={blockedByTruncation}
              canReorderIssue={canReorderIssue}
              running={flow.running}
              moveTargets={moveTargets}
              moveTarget={moveTarget}
              onMoveTargetChange={setMoveTarget}
            />
          )}

          {flow.failure !== null && (
            <p
              role="alert"
              className="rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text"
            >
              {failureMessage(flow.failure)}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => handleOpenChange(false)} disabled={flow.running}>
            {issueCreateStrings.cancelButton}
          </Button>
          {flow.running && (
            <span aria-live="polite" className="self-center text-sm text-muted-foreground">
              {L.moveProgress(flow.progress, flow.progressTotal)}
            </span>
          )}
          {showRetry && !flow.running && (
            <Button variant="outline" onClick={() => handleSubmit(true)}>
              {backlogLabels.retry}
            </Button>
          )}
          <Button
            className="bg-success text-success-foreground hover:bg-success/90"
            disabled={
              flow.running ||
              blockedByTruncation ||
              blockedByPermission ||
              blockedByUnknownWorkflow
            }
            onClick={() => handleSubmit(false)}
          >
            {flow.running ? L.pending : backlogLabels.completeSprint}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** 목록 + 이관 대상 Select. 미완료 0건이거나 권한이 없으면 그리지 않는다 */
function CompletionBody({
  rows,
  rowStatus,
  blockedByTruncation,
  canReorderIssue,
  running,
  moveTargets,
  moveTarget,
  onMoveTargetChange,
}: {
  readonly rows: readonly BacklogIssue[]
  readonly rowStatus: Readonly<Record<string, RowStatus>>
  readonly blockedByTruncation: boolean
  readonly canReorderIssue: boolean
  readonly running: boolean
  readonly moveTargets: readonly SprintMeta[]
  readonly moveTarget: string
  readonly onMoveTargetChange: (value: string) => void
}): JSX.Element | null {
  // C-8 — 목록이 불완전하면 「0건」이라는 관측 자체를 믿을 수 없다. E15 가 E11 을 이긴다.
  if (blockedByTruncation) return null
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">{L.noIssuesToMove}</p>
  }
  return (
    <>
      <ul
        className={cn(
          'max-h-72 divide-y divide-border overflow-y-auto rounded-md border border-border',
          running && 'opacity-70',
        )}
      >
        {rows.map((issue) => (
          <IncompleteIssueRow key={issue.key} issue={issue} status={rowStatus[issue.key]} />
        ))}
      </ul>
      {canReorderIssue && (
        <MoveTargetSelect
          targets={moveTargets}
          value={moveTarget}
          onChange={onMoveTargetChange}
          disabled={running}
        />
      )}
    </>
  )
}
