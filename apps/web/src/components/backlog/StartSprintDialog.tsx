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

/**
 * 다이얼로그가 편집하는 값 3종. 컨트롤이 전부 문자열이므로 `null` 은 여기서 쓰지 않는다.
 *
 * **편집한 필드만 의미가 있다.** 미편집 필드의 표시는 기준값에서 파생시킨다
 * ({@link resolveDisplayValues}) — 서버 상태를 폼에 복사해 두면 그 사본을 계속 맞춰 줘야 하고,
 * 한 번이라도 어긋나면 그 차이가 그대로 `PATCH` 에 실려 남의 값을 지운다.
 */
interface SprintFormValues {
  /** 시작일 (ISO 8601 date). 미지정은 빈 문자열 */
  startDate: string
  /** 종료일 (ISO 8601 date). 미지정은 빈 문자열 */
  endDate: string
  /** 목표. 미지정은 빈 문자열 */
  goal: string
}

/** 편집 대상 필드 이름. `SprintFormValues` 에서 파생시켜 둘이 어긋날 수 없게 한다 */
type SprintField = keyof SprintFormValues

/** 편집 대상 필드 전수. 순회 순서가 곧 `PATCH` body 의 키 순서다 */
const EDITABLE_FIELDS = ['startDate', 'endDate', 'goal'] as const satisfies readonly SprintField[]

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
 * **사용자가 실제로 편집한 필드**만 골라 `PATCH` body 를 만든다.
 *
 * `version` 은 항상 담는다. **담을 필드가 0이면 `null` 을 반환한다** —
 * 불필요한 버전 증가가 낙관적 잠금 충돌면을 넓히기 때문이다 (FR-4).
 *
 * ### 왜 「기준값과 다른 필드」로 계산하면 안 되나
 * 409(E9) 뒤 {@link StartSprintDialog} 는 기준값만 서버 최신으로 갈아끼운다. 그 순간
 * **「내가 고친 필드」와 「기준값과 다른 필드」가 서로 다른 집합**이 된다 — 남이 방금 저장한
 * 필드는 내가 손대지 않았는데도 내 폼 값(빈 값)과 달라지기 때문이다. 그 차이를 변경분으로
 * 세면 재시도가 `endDate: null`·`goal: null` 을 실어 보내 **남의 저장분을 조용히 지운다**.
 * 편집 집합으로 좁히면 미편집 필드는 키 자체가 빠져 무변경으로 보존된다
 * (`api/backlog.ts` `UpdateSprintBody` 3-state — 미전송 = 무변경, 명시 `null` = 삭제).
 *
 * 편집했더라도 값이 기준값과 같으면 담지 않는다 — 서버가 이미 그 값이라 보낼 이유가 없다.
 *
 * @param baseline 다이얼로그가 들고 있는 기준 SprintMeta
 * @param values 현재 폼 값
 * @param edited 사용자가 편집한 필드 집합
 * @returns 보낼 body. 담을 필드가 없으면 `null`
 */
function buildPatchBody(
  baseline: SprintMeta,
  values: SprintFormValues,
  edited: ReadonlySet<SprintField>,
): UpdateSprintBody | null {
  const body: UpdateSprintBody = { version: baseline.version }
  let changed = false

  for (const field of EDITABLE_FIELDS) {
    if (!edited.has(field)) continue
    const next = emptyToNull(values[field])
    if (next === baseline[field]) continue
    body[field] = next
    changed = true
  }

  return changed ? body : null
}

/**
 * 화면에 그릴 값을 정한다 — **편집한 필드는 사용자가 친 값, 나머지는 기준값**.
 *
 * ### 왜 파생인가 (사본이 아니라)
 * 미편집 필드를 폼 state 에 복사해 두면 409(E9) 로 기준값이 바뀔 때마다 그 사본을 따라
 * 갱신해 줘야 하고, 갱신을 한 번 빠뜨리면 그 차이가 `PATCH` 변경분으로 오인돼 남의 값을
 * 지운다. 파생시키면 맞출 사본이 없어 어긋날 수가 없다.
 *
 * ### 왜 기준값을 보여주나 (사용자가 처음 연 값이 아니라)
 * 409 뒤 기준값은 **남이 방금 저장한 값**이다. 그대로 보여줘야 ① 사용자가 무엇이 바뀌었는지
 * 보고 재확인할 수 있고 ② E8 기간 검증이 서버의 실제 종료일로 판정한다 — 낡은 빈 값을
 * 들고 있으면 「종료일이 시작일보다 빠른」 스프린트를 아무 경고 없이 만들 수 있다.
 *
 * ★ 이렇게 바뀐 표시는 **편집이 아니다**. 편집 집합에 들어가지 않으므로 `PATCH` 에도
 *   실리지 않는다 — 표시 갱신을 편집으로 세는 순간 이 함수가 곧 데이터 손실이 된다.
 */
function resolveDisplayValues(
  values: SprintFormValues,
  baseline: SprintMeta,
  edited: ReadonlySet<SprintField>,
): SprintFormValues {
  const server = toFormValues(baseline)
  return {
    startDate: edited.has('startDate') ? values.startDate : server.startDate,
    endDate: edited.has('endDate') ? values.endDate : server.endDate,
    goal: edited.has('goal') ? values.goal : server.goal,
  }
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
// 날짜 필드 — 시작일·종료일이 같은 모양이라 한 벌로 묶는다
// ─────────────────────────────────────────────────────────────────────────────

/** {@link DateField} props */
interface DateFieldProps {
  /** input id. `Label htmlFor` 와 짝이다 */
  readonly id: string
  /** 필드 라벨 (i18n 문구) */
  readonly label: string
  /** 현재 값 (ISO 8601 date 또는 빈 문자열) */
  readonly value: string
  /** 요청 진행 중 잠금 */
  readonly disabled: boolean
  /** 필드 에러. 있으면 `aria-invalid` + 필드 아래 문구가 함께 붙는다 */
  readonly error?: { readonly id: string; readonly message: string }
  /** 값 변경 콜백 */
  readonly onChange: (value: string) => void
}

/** `Input type="date"` 한 칸. 선례 `AuditLogFilters.tsx` · `WorklogAggregateReport.tsx` */
function DateField({ id, label, value, disabled, error, onChange }: DateFieldProps): JSX.Element {
  return (
    <div className="flex flex-1 flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="date"
        value={value}
        disabled={disabled}
        aria-invalid={error !== undefined}
        aria-describedby={error?.id}
        onChange={(event) => {
          onChange(event.target.value)
        }}
      />
      {error !== undefined && (
        <p id={error.id} className="text-sm text-destructive">
          {error.message}
        </p>
      )}
    </div>
  )
}

/** {@link SprintFields} props */
interface SprintFieldsProps {
  /** 화면에 그릴 값 ({@link resolveDisplayValues} 결과) */
  readonly values: SprintFormValues
  /** 요청 진행 중 잠금 */
  readonly disabled: boolean
  /** E8 — 종료일이 시작일보다 빠른가. 종료일 칸에 필드 에러로 붙는다 */
  readonly rangeInvalid: boolean
  /**
   * 필드 편집 콜백.
   *
   * ★ **사용자 조작만** 이 경로를 지난다. 409 뒤 기준값이 바뀌어 표시가 달라지는 것은
   *   여기를 지나지 않으므로 편집으로 세지 않는다 — 그 구분이 남의 저장분을 지키는 근거다.
   */
  readonly onEdit: (field: SprintField, next: string) => void
}

/**
 * 기간·목표 3칸.
 *
 * 별도 컴포넌트인 이유는 두 가지다 — ① 편집 경로가 `onEdit` 하나로 좁혀져 「무엇이 편집인가」가
 * 한눈에 보이고 ② `StartSprintDialog` 본체가 200줄 천장을 넘지 않는다 (§2.2).
 */
function SprintFields({ values, disabled, rangeInvalid, onEdit }: SprintFieldsProps): JSX.Element {
  const prefix = useId()
  const goalId = `${prefix}-goal`
  const rangeErrorId = `${prefix}-range-error`

  return (
    <>
      <div className="flex flex-col gap-2 sm:flex-row sm:gap-4">
        <DateField
          id={`${prefix}-start-date`}
          label={backlogLabels.startDialog.startDateLabel}
          value={values.startDate}
          disabled={disabled}
          onChange={(next) => {
            onEdit('startDate', next)
          }}
        />
        <DateField
          id={`${prefix}-end-date`}
          label={backlogLabels.startDialog.endDateLabel}
          value={values.endDate}
          disabled={disabled}
          error={
            rangeInvalid
              ? { id: rangeErrorId, message: backlogLabels.startDialog.endBeforeStart }
              : undefined
          }
          onChange={(next) => {
            onEdit('endDate', next)
          }}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={goalId}>{backlogLabels.startDialog.goalLabel}</Label>
        <Textarea
          id={goalId}
          rows={3}
          value={values.goal}
          disabled={disabled}
          onChange={(event) => {
            onEdit('goal', event.target.value)
          }}
        />
      </div>
    </>
  )
}

/** {@link FailureAlert} props */
interface FailureAlertProps {
  /** 실패 종류 */
  readonly kind: FailureKind
  /** 요청 진행 중 재시도 잠금 */
  readonly disabled: boolean
}

/**
 * 중간 실패 안내 + 재시도.
 *
 * 재시도 버튼은 `type="submit"` 이라 푸터의 제출과 **같은 핸들러**를 지난다 —
 * 재시도 전용 경로를 따로 만들면 두 경로가 갈라져 한쪽만 고쳐지는 날이 온다.
 * 무엇을 다시 보낼지는 그때의 변경분이 정한다(`PATCH` 가 이미 성공했으면 0이므로 `start` 만).
 */
function FailureAlert({ kind, disabled }: FailureAlertProps): JSX.Element {
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text"
    >
      <p>{FAILURE_MESSAGE[kind]}</p>
      <Button type="submit" variant="outline" size="sm" disabled={disabled}>
        {backlogLabels.retry}
      </Button>
    </div>
  )
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

  /** 서버가 들고 있다고 아는 값. `PATCH` 성공·409 때마다 최신으로 갈아끼운다 */
  const [baseline, setBaseline] = useState<SprintMeta>(sprint)
  const [values, setValues] = useState<SprintFormValues>(() => toFormValues(sprint))
  const [failure, setFailure] = useState<FailureKind | null>(null)

  /**
   * `PATCH` 만 성공한 상태인지. state 가 아니라 ref 인 이유는 **닫기 직전에 동기로 읽어야**
   * 하기 때문이다 — `setState` 직후의 클로저는 아직 옛 값을 본다.
   */
  const patchAppliedRef = useRef(false)

  /**
   * 사용자가 손댄 필드. **전송 대상의 유일한 출처**이자 표시 갈림길이다.
   *
   * 화면 출력이 여기서 갈리므로(`resolveDisplayValues`) ref 가 아니라 state 다.
   */
  const [edited, setEdited] = useState<ReadonlySet<SprintField>>(() => new Set())

  const display = resolveDisplayValues(values, baseline, edited)
  const rangeInvalid = isEndBeforeStart(display)
  const pending = updateSprint.isPending || startSprint.isPending

  /** 사용자 조작으로 필드가 바뀌었다 — 값과 「편집했다」는 사실을 함께 기록한다 */
  function editField(field: SprintField, next: string): void {
    setValues((prev) => ({ ...prev, [field]: next }))
    setEdited((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
  }

  /** 백로그를 새로 받는다. 두 mutation 훅도 성공 시 같은 일을 하지만 실패 경로에는 없다 */
  async function invalidateBacklog(): Promise<void> {
    await queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
  }

  /**
   * 409(E9) 이후 기준값(`version` 포함)을 최신으로 교체한다. 캐시에 없으면 그대로 둔다.
   *
   * ★ 폼 값도, 편집 집합도 건드리지 않는다. 사용자가 친 값은 그대로 남고(덮으면 재시도가
   *   그 필드를 안 보내 **남의 값으로 스프린트가 시작된다**), 미편집 필드의 표시는
   *   기준값에서 파생되므로 이 한 줄만으로 자동으로 최신이 된다
   *   ({@link resolveDisplayValues}) — 맞춰 줄 사본이 없다.
   */
  async function replaceBaselineFromCache(): Promise<void> {
    await invalidateBacklog()
    const view = queryClient.getQueryData<BacklogView>(backlogKeys.detail(projectKey))
    const fresh = view?.sprints.find((entry) => entry.sprint.sprintId === sprint.sprintId)?.sprint
    if (fresh !== undefined) setBaseline(fresh)
  }

  /** 1단계. 성공하면 기준값을 응답으로 갈아끼우고 `true` 를 반환한다 */
  async function runPatch(body: UpdateSprintBody): Promise<boolean> {
    try {
      const updated = await updateSprint.mutateAsync({ sprintId: sprint.sprintId, body })
      // 편집분이 서버에 반영됐으니 편집 집합을 비운다 — 재시도의 변경분이 0이 되고,
      // 세 칸 모두 응답값 표시로 돌아간다(파생이라 따로 폼을 맞출 필요가 없다)
      setBaseline(updated)
      setEdited(new Set())
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
      // 훅이 이미 invalidate 했다 — 닫기 게이트가 한 번 더 부르지 않도록 깃발을 내린다
      patchAppliedRef.current = false
      closeDialog()
    } catch (error) {
      if (!isConflict(error)) {
        setFailure('start')
        return
      }
      toast.error(backlogLabels.startDialog.startConflict)
      patchAppliedRef.current = false
      await invalidateBacklog()
      closeDialog()
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    // E8 — 에러 문구는 이미 화면에 있다. 백엔드 왕복을 만들지 않는다
    if (rangeInvalid) return

    setFailure(null)
    const body = buildPatchBody(baseline, values, edited)
    if (body !== null && !(await runPatch(body))) return
    await runStart()
  }

  /**
   * 닫힘 경로 **단일 창구**. Esc·바깥 클릭·취소·성공·E10 이 전부 여기를 지난다.
   *
   * `PATCH` 만 성공한 채 닫히면 목록을 새로 받는다 — 안 하면 재개봉 시 기준값이
   * 낡은 `version` 으로 리셋돼 다음 `PATCH` 가 409 다.
   */
  function handleOpenChange(next: boolean): void {
    if (!next && patchAppliedRef.current) {
      patchAppliedRef.current = false
      void invalidateBacklog()
    }
    onOpenChange(next)
  }

  /** 닫기 — 게이트를 우회하는 경로를 만들지 않기 위한 얇은 별칭 */
  function closeDialog(): void {
    handleOpenChange(false)
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
          <SprintFields
            values={display}
            disabled={pending}
            rangeInvalid={rangeInvalid}
            onEdit={editField}
          />

          {failure !== null && <FailureAlert kind={failure} disabled={pending} />}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={closeDialog}
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
