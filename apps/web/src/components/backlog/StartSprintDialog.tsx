// 스프린트 시작 다이얼로그 — 기간·목표 확인 후 PATCH(변경분만) → start 2단계 (FR-UX-13 F15 FR-3·FR-4)
import type { FormEvent, JSX } from 'react'
import { useId, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { useStartSprint, useUpdateSprint, backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { ApiError } from '@/api/client'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 값 · 변경분 계산 (순수)
// ─────────────────────────────────────────────────────────────────────────────

/** 다이얼로그가 편집하는 값 3종. 컨트롤이 전부 문자열이므로 `null` 은 여기서 쓰지 않는다 */
interface SprintFormValues {
  /** 시작일 (ISO 8601 date). 미지정은 빈 문자열 */
  startDate: string
  /** 종료일 (ISO 8601 date). 미지정은 빈 문자열 */
  endDate: string
  /** 목표. 미지정은 빈 문자열 */
  goal: string
}

/** SprintMeta 를 폼 값으로 옮긴다 (`null` → 빈 문자열) */
function toFormValues(sprint: SprintMeta): SprintFormValues {
  return {
    startDate: sprint.startDate ?? '',
    endDate: sprint.endDate ?? '',
    goal: sprint.goal ?? '',
  }
}

/** 빈 문자열은 「값 삭제」의 뜻이므로 명시 `null` 로 바꾼다 (3-state partial) */
function emptyToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * 기준값과 폼 값을 필드별로 비교해 `PATCH` body 를 만든다.
 *
 * 바뀐 필드만 담고 `version` 은 항상 담는다. **변경분이 0이면 `null` 을 반환한다** —
 * 불필요한 버전 증가가 낙관적 잠금 충돌면을 넓히기 때문이다 (FR-4).
 *
 * @param baseline 다이얼로그가 들고 있는 기준 SprintMeta
 * @param values 현재 폼 값
 * @returns 보낼 body. 변경분이 없으면 `null`
 */
function buildPatchBody(baseline: SprintMeta, values: SprintFormValues): UpdateSprintBody | null {
  const body: UpdateSprintBody = { version: baseline.version }
  let changed = false

  const startDate = emptyToNull(values.startDate)
  if (startDate !== baseline.startDate) {
    body.startDate = startDate
    changed = true
  }
  const endDate = emptyToNull(values.endDate)
  if (endDate !== baseline.endDate) {
    body.endDate = endDate
    changed = true
  }
  const goal = emptyToNull(values.goal)
  if (goal !== baseline.goal) {
    body.goal = goal
    changed = true
  }

  return changed ? body : null
}

/**
 * E8 — 종료일이 시작일보다 빠른지 판정한다.
 *
 * ISO 8601 date(`YYYY-MM-DD`)는 자릿수가 고정이라 **사전순 비교가 곧 시간순 비교**다.
 * `Date` 로 파싱하면 타임존이 끼어들어 하루가 밀린다.
 */
function isEndBeforeStart(values: SprintFormValues): boolean {
  return values.startDate !== '' && values.endDate !== '' && values.endDate < values.startDate
}

/** 낙관적 잠금·상태 전이 충돌(409)인지 */
function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

// ─────────────────────────────────────────────────────────────────────────────
// 실패 갈래 — 넷을 하나로 뭉뚱그리면 거짓말이 된다 (FR-4)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 다이얼로그에 남는 실패 종류.
 *
 * `start` 409(E10)는 여기 없다 — 재시도해도 반드시 409 라 **다이얼로그를 닫고**
 * 토스트로만 알린다. 나머지 셋과 처방이 정반대다.
 */
type FailureKind = 'patch' | 'patch-conflict' | 'start'

/** 실패 종류별 안내 문구 */
const FAILURE_MESSAGE: Record<FailureKind, string> = {
  patch: backlogLabels.startDialog.patchFailed,
  'patch-conflict': backlogLabels.startDialog.patchConflict,
  start: backlogLabels.startDialog.startFailed,
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** StartSprintDialog props */
export interface StartSprintDialogProps {
  /** 열림 여부 (controlled) */
  open: boolean
  /** 열림 상태 변경 콜백 — 취소·Esc·바깥 클릭·시작 성공·E10 에서 발화 */
  onOpenChange: (open: boolean) => void
  /**
   * 대상 스프린트 메타. **초기값의 유일한 출처**다 — 별도 조회를 하지 않는다 (FR-3).
   *
   * ⚠️ 부모는 반드시 `key={sprint.sprintId}` 로 마운트한다. 기준값을 내부 state 에 두므로
   * 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 남는다.
   */
  sprint: SprintMeta
  /**
   * 백로그 queryKey 대상 프로젝트 키.
   *
   * `useUpdateSprint`·`useStartSprint` 가 invalidate 대상을 알아야 해서 필요하다
   * (`use-backlog.ts` 의 모든 mutation 훅이 `projectKey` 를 받는다). 전역 활성 프로젝트를
   * 경유하지 않고 명시로 받는 것은 같은 디렉토리의 선례를 따른 것이다
   * (`SprintColumn`·`CreateSprintForm`·`CreateIssueDialog` 모두 명시 전달).
   */
  projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// StartSprintDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 시작 다이얼로그.
 *
 * ### 2단계 요청 (FR-4)
 * 변경분이 1개 이상이면 `PATCH /sprints/{id}`(바뀐 필드 + `version`) → `POST /sprints/{id}/start`.
 * 변경분이 0이면 `PATCH` 를 보내지 않는다.
 *
 * ### 기준값이 내부 state 인 이유
 * 부모 props 를 기준값으로 쓰면 `PATCH` 성공 후에도 props 가 안 바뀌어(두 요청이 모두
 * 성공해야 부모가 갱신된다) 재시도가 **낡은 `version` 으로 409** 를 받는다.
 * 그래서 `PATCH` 응답의 SprintMeta 로 기준값과 `version` 을 그 자리에서 갈아끼운다.
 *
 * ### 재시도 버튼 이름을 `다시 시도` 로 재사용해도 되는 이유
 * 백로그 **조회 실패** 화면에도 같은 이름의 버튼이 있지만 둘은 공존할 수 없다 —
 * 조회가 실패하면 `BacklogBoard.tsx:125-143` 이 조기 반환해 이 다이얼로그가 통째로
 * 언마운트되기 때문이다. 그래서 새 문자열을 만들지 않는다 (FR-10).
 */
export function StartSprintDialog({
  open,
  onOpenChange,
  sprint,
  projectKey,
}: StartSprintDialogProps): JSX.Element {
  const queryClient = useQueryClient()
  const updateSprint = useUpdateSprint(projectKey)
  const startSprint = useStartSprint(projectKey)

  /** 변경분 계산의 기준. `PATCH` 가 성공할 때마다 응답으로 갈아끼운다 */
  const [baseline, setBaseline] = useState<SprintMeta>(sprint)
  const [values, setValues] = useState<SprintFormValues>(() => toFormValues(sprint))
  const [failure, setFailure] = useState<FailureKind | null>(null)

  /**
   * `PATCH` 만 성공한 상태인지. state 가 아니라 ref 인 이유는 **닫기 직전에 동기로 읽어야**
   * 하기 때문이다 — `setState` 직후의 클로저는 아직 옛 값을 본다.
   */
  const patchAppliedRef = useRef(false)

  const fieldIdPrefix = useId()
  const startDateId = `${fieldIdPrefix}-start-date`
  const endDateId = `${fieldIdPrefix}-end-date`
  const goalId = `${fieldIdPrefix}-goal`
  const rangeErrorId = `${fieldIdPrefix}-range-error`

  const rangeInvalid = isEndBeforeStart(values)
  const pending = updateSprint.isPending || startSprint.isPending

  /** 백로그를 새로 받는다. 두 mutation 훅도 성공 시 같은 일을 하지만 실패 경로에는 없다 */
  async function invalidateBacklog(): Promise<void> {
    await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
  }

  /** 409(E9) 이후 최신 값으로 기준값을 교체한다. 캐시에 없으면 그대로 둔다 */
  async function replaceBaselineFromCache(): Promise<void> {
    await invalidateBacklog()
    const view = queryClient.getQueryData<BacklogView>(backlogKeys.detail(projectKey))
    const fresh = view?.sprints.find((entry) => entry.sprint.sprintId === sprint.sprintId)?.sprint
    if (fresh === undefined) return
    setBaseline(fresh)
    setValues(toFormValues(fresh))
  }

  /** 1단계. 성공하면 기준값을 응답으로 갈아끼우고 `true` 를 반환한다 */
  async function runPatch(body: UpdateSprintBody): Promise<boolean> {
    try {
      const updated = await updateSprint.mutateAsync({ sprintId: sprint.sprintId, body })
      // 응답으로 기준값·폼을 함께 맞춘다 — 이래야 재시도의 변경분이 확실히 0이 된다
      setBaseline(updated)
      setValues(toFormValues(updated))
      patchAppliedRef.current = true
      return true
    } catch (error) {
      if (isConflict(error)) {
        setFailure('patch-conflict')
        await replaceBaselineFromCache()
        return false
      }
      setFailure('patch')
      return false
    }
  }

  /** 2단계. 409(E10)는 재시도가 불가능하므로 토스트만 남기고 닫는다 */
  async function runStart(): Promise<void> {
    try {
      await startSprint.mutateAsync(sprint.sprintId)
      patchAppliedRef.current = false
      onOpenChange(false)
    } catch (error) {
      if (!isConflict(error)) {
        setFailure('start')
        return
      }
      toast.error(backlogLabels.startDialog.startConflict)
      patchAppliedRef.current = false
      await invalidateBacklog()
      onOpenChange(false)
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    // E8 — 에러 문구는 이미 화면에 있다. 백엔드 왕복을 만들지 않는다
    if (rangeInvalid) return

    setFailure(null)
    const body = buildPatchBody(baseline, values)
    if (body !== null && !(await runPatch(body))) return
    await runStart()
  }

  /** 닫힘 경로 단일 창구 — `PATCH` 만 성공한 채 닫히면 목록을 새로 받아야 한다 */
  function handleOpenChange(next: boolean): void {
    if (!next && patchAppliedRef.current) {
      // 안 하면 재개봉 시 기준값이 낡은 `version` 으로 리셋돼 다음 `PATCH` 가 409 다
      patchAppliedRef.current = false
      void invalidateBacklog()
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{backlogLabels.startSprint}</DialogTitle>
          <DialogDescription>{backlogLabels.startDialog.description}</DialogDescription>
        </DialogHeader>

        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            void handleSubmit(event)
          }}
        >
          <div className="flex flex-col gap-2 sm:flex-row sm:gap-4">
            <div className="flex flex-1 flex-col gap-1.5">
              <Label htmlFor={startDateId}>{backlogLabels.startDialog.startDateLabel}</Label>
              <Input
                id={startDateId}
                type="date"
                value={values.startDate}
                disabled={pending}
                onChange={(event) => {
                  setValues((prev) => ({ ...prev, startDate: event.target.value }))
                }}
              />
            </div>
            <div className="flex flex-1 flex-col gap-1.5">
              <Label htmlFor={endDateId}>{backlogLabels.startDialog.endDateLabel}</Label>
              <Input
                id={endDateId}
                type="date"
                value={values.endDate}
                disabled={pending}
                aria-invalid={rangeInvalid}
                aria-describedby={rangeInvalid ? rangeErrorId : undefined}
                onChange={(event) => {
                  setValues((prev) => ({ ...prev, endDate: event.target.value }))
                }}
              />
              {rangeInvalid && (
                <p id={rangeErrorId} className="text-sm text-destructive">
                  {backlogLabels.startDialog.endBeforeStart}
                </p>
              )}
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={goalId}>{backlogLabels.startDialog.goalLabel}</Label>
            <Textarea
              id={goalId}
              rows={3}
              value={values.goal}
              disabled={pending}
              onChange={(event) => {
                setValues((prev) => ({ ...prev, goal: event.target.value }))
              }}
            />
          </div>

          {failure !== null && (
            <div
              role="alert"
              className="flex flex-col items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text"
            >
              <p>{FAILURE_MESSAGE[failure]}</p>
              <Button type="submit" variant="outline" size="sm" disabled={pending}>
                {backlogLabels.retry}
              </Button>
            </div>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => {
                handleOpenChange(false)
              }}
            >
              {issueCreateStrings.cancelButton}
            </Button>
            {/* 기간이 어긋나도 버튼을 **비활성화하지 않는다** — 비활성 버튼은 이유를 말해주지
                않고, 그렇게 하면 제출 가드(`rangeInvalid` 조기 반환)가 도달 불가능한 죽은
                코드가 돼 그것을 지키는 테스트가 공허해진다. 이유는 필드 아래에 이미 있다. */}
            <Button type="submit" disabled={pending}>
              {pending ? backlogLabels.startDialog.pending : backlogLabels.startSprint}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
