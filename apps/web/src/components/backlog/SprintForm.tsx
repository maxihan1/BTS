// 스프린트 이름·기간·목표 편집 폼 — 시작/편집 다이얼로그가 공유하는 폼 상태와 필드 (FR-BL-02 D6 NFR-1)
import type { FormEvent, JSX, ReactNode } from 'react'
import { useId, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'

/** 공용 폼 문구. 시작·편집 다이얼로그가 같은 값을 읽는다 */
const L = backlogLabels.sprintForm

/** 날짜를 잠그는 유일한 상태 (FR-2 · J1). `SprintMeta.status` 는 문자열이라 상수로 못박는다 */
const COMPLETED_STATUS = 'COMPLETED'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 값 · 변경분 계산 (순수)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 폼이 편집하는 값 4종. 컨트롤이 전부 문자열이므로 `null` 은 여기서 쓰지 않는다.
 *
 * **편집한 필드만 의미가 있다.** 미편집 필드의 표시는 기준값에서 파생시킨다
 * ({@link resolveDisplayValues}) — 서버 상태를 폼에 복사해 두면 그 사본을 계속 맞춰 줘야 하고,
 * 한 번이라도 어긋나면 그 차이가 그대로 `PATCH` 에 실려 남의 값을 지운다.
 */
interface SprintFormValues {
  /** 스프린트 이름. 백엔드가 `null` 을 허용하지 않으므로 빈 문자열은 「미입력」이다 */
  name: string
  /** 시작일 (ISO 8601 date). 미지정은 빈 문자열 */
  startDate: string
  /** 종료일 (ISO 8601 date). 미지정은 빈 문자열 */
  endDate: string
  /** 목표. 미지정은 빈 문자열 */
  goal: string
}

/** 편집 대상 필드 이름. `SprintFormValues` 에서 파생시켜 둘이 어긋날 수 없게 한다 */
type SprintField = keyof SprintFormValues

/** 편집 대상 필드 전수. 표시 파생이 이 목록을 순회한다 */
const EDITABLE_FIELDS = [
  'name',
  'startDate',
  'endDate',
  'goal',
] as const satisfies readonly SprintField[]

/**
 * 명시 `null` 로 **값 삭제**를 보낼 수 있는 필드 전수.
 *
 * `name` 은 여기 없다 — 백엔드 `UpdateSprintRequest` 가 이름에 `null` 을 허용하지 않는다
 * (`api/backlog.ts` `UpdateSprintBody`). 그래서 이름만 별도 갈래로 담는다.
 */
const NULLABLE_FIELDS = [
  'startDate',
  'endDate',
  'goal',
] as const satisfies readonly SprintField[]

/** SprintMeta 를 폼 값으로 옮긴다 (`null` → 빈 문자열) */
function toFormValues(sprint: SprintMeta): SprintFormValues {
  return {
    name: sprint.name,
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
 * 불필요한 버전 증가가 낙관적 잠금 충돌면을 넓히기 때문이다.
 *
 * ### 왜 「기준값과 다른 필드」로 계산하면 안 되나
 * 409 뒤 폼은 기준값만 서버 최신으로 갈아끼운다. 그 순간 **「내가 고친 필드」와 「기준값과
 * 다른 필드」가 서로 다른 집합**이 된다 — 남이 방금 저장한 필드는 내가 손대지 않았는데도
 * 내 폼 값(빈 값)과 달라지기 때문이다. 그 차이를 변경분으로 세면 재시도가
 * `endDate: null`·`goal: null` 을 실어 보내 **남의 저장분을 조용히 지운다**.
 * 편집 집합으로 좁히면 미편집 필드는 키 자체가 빠져 무변경으로 보존된다
 * (`api/backlog.ts` `UpdateSprintBody` 3-state — 미전송 = 무변경, 명시 `null` = 삭제).
 *
 * 편집했더라도 값이 기준값과 같으면 담지 않는다 — 서버가 이미 그 값이라 보낼 이유가 없다.
 *
 * @param baseline 폼이 들고 있는 기준 SprintMeta
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

  // 이름은 3-state 가 아니다 — 빈 값은 「삭제」가 아니라 「미입력」이라 담지 않는다.
  // 제출 자체는 `nameMissing` 가드가 먼저 막고, 여기서는 키를 빼 무변경으로 남긴다.
  const nextName = values.name.trim()
  if (edited.has('name') && nextName !== '' && nextName !== baseline.name) {
    body.name = nextName
    changed = true
  }

  for (const field of NULLABLE_FIELDS) {
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
 * 미편집 필드를 폼 state 에 복사해 두면 409 로 기준값이 바뀔 때마다 그 사본을 따라
 * 갱신해 줘야 하고, 갱신을 한 번 빠뜨리면 그 차이가 `PATCH` 변경분으로 오인돼 남의 값을
 * 지운다. 파생시키면 맞출 사본이 없어 어긋날 수가 없다.
 *
 * ### 왜 기준값을 보여주나 (사용자가 처음 연 값이 아니라)
 * 409 뒤 기준값은 **남이 방금 저장한 값**이다. 그대로 보여줘야 ① 사용자가 무엇이 바뀌었는지
 * 보고 재확인할 수 있고 ② 기간 검증이 서버의 실제 종료일로 판정한다.
 *
 * ★ 이렇게 바뀐 표시는 **편집이 아니다**. 편집 집합에 들어가지 않으므로 `PATCH` 에도
 *   실리지 않는다 — 표시 갱신을 편집으로 세는 순간 이 함수가 곧 데이터 손실이 된다.
 */
function resolveDisplayValues(
  values: SprintFormValues,
  baseline: SprintMeta,
  edited: ReadonlySet<SprintField>,
): SprintFormValues {
  const display = toFormValues(baseline)
  for (const field of EDITABLE_FIELDS) {
    if (edited.has(field)) display[field] = values[field]
  }
  return display
}

/**
 * 종료일이 시작일보다 빠른지 판정한다.
 *
 * ISO 8601 date(`YYYY-MM-DD`)는 자릿수가 고정이라 **사전순 비교가 곧 시간순 비교**다.
 * `Date` 로 파싱하면 타임존이 끼어들어 하루가 밀린다.
 */
function isEndBeforeStart(values: SprintFormValues): boolean {
  return values.startDate !== '' && values.endDate !== '' && values.endDate < values.startDate
}

/** 여러 서술 id 를 `aria-describedby` 한 칸에 담는다. 전부 비면 속성을 붙이지 않는다 */
function describedBy(...ids: ReadonlyArray<string | undefined>): string | undefined {
  const present = ids.filter((id): id is string => id !== undefined)
  return present.length === 0 ? undefined : present.join(' ')
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
  /** 요청 진행 중 또는 상태 제약으로 잠금 */
  readonly disabled: boolean
  /** 필드 에러. 있으면 `aria-invalid` + 필드 아래 문구가 함께 붙는다 */
  readonly error?: { readonly id: string; readonly message: string }
  /** 잠금 사유처럼 필드 밖에 있는 설명의 id */
  readonly hintId?: string
  /** 값 변경 콜백 */
  readonly onChange: (value: string) => void
}

/** `Input type="date"` 한 칸. 선례 `AuditLogFilters.tsx` · `WorklogAggregateReport.tsx` */
function DateField({
  id,
  label,
  value,
  disabled,
  error,
  hintId,
  onChange,
}: DateFieldProps): JSX.Element {
  return (
    <div className="flex flex-1 flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="date"
        value={value}
        disabled={disabled}
        aria-invalid={error !== undefined}
        aria-describedby={describedBy(error?.id, hintId)}
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

// ─────────────────────────────────────────────────────────────────────────────
// 실패 안내 — 두 다이얼로그가 같은 모양을 쓴다
// ─────────────────────────────────────────────────────────────────────────────

/** {@link SprintFormFailure} props */
export interface SprintFormFailureProps {
  /** 실패 사유. 갈래별 문구는 소비자가 고른다 */
  readonly message: string
  /** 요청 진행 중 재시도 잠금 */
  readonly disabled: boolean
}

/**
 * 중간 실패 안내 + 재시도.
 *
 * 재시도 버튼은 `type="submit"` 이라 푸터의 제출과 **같은 핸들러**를 지난다 —
 * 재시도 전용 경로를 따로 만들면 두 경로가 갈라져 한쪽만 고쳐지는 날이 온다.
 * 무엇을 다시 보낼지는 그때의 변경분이 정한다.
 */
export function SprintFormFailure({ message, disabled }: SprintFormFailureProps): JSX.Element {
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text"
    >
      <p>{message}</p>
      <Button type="submit" variant="outline" size="sm" disabled={disabled}>
        {backlogLabels.retry}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SprintForm
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 결과를 폼의 기준값에 되먹이는 통로.
 *
 * 폼이 기준값(`version` 포함)을 들고 있으므로 **소비자가 응답을 폼에게 돌려줘야** 한다.
 * 돌려주지 않으면 다음 요청이 낡은 `version` 으로 나가 409 를 되풀이한다
 * (`useUpdateSprint` KDoc).
 */
export interface SprintFormActions {
  /**
   * 저장 성공 응답을 기준값으로 흡수한다.
   *
   * 편집 집합도 함께 비운다 — 서버에 반영됐으니 다음 변경분이 0이 되고, 모든 칸이
   * 응답값 표시로 돌아간다(파생이라 따로 폼을 맞출 필요가 없다).
   */
  readonly applyServerResponse: (updated: SprintMeta) => void
  /**
   * 409 뒤 기준값(`version` 포함)을 백로그 캐시의 최신 값으로 갈아끼운다.
   *
   * ★ 폼 값도, 편집 집합도 건드리지 않는다. 사용자가 친 값은 그대로 남고(덮으면 재시도가
   *   그 필드를 안 보내 **남의 값으로 저장된다**), 미편집 필드의 표시는 기준값에서
   *   파생되므로 이 한 줄만으로 자동으로 최신이 된다.
   *
   * ★ 캐시는 **보고 있는 보드의** 키로 읽는다(FR-BD-04). 보드를 빼고 읽으면 어느 캐시와도
   *   완전 일치하지 않아 `undefined` 가 오고, 그러면 이 함수가 **아무 일도 안 한 채**
   *   조용히 끝난다 — 실패가 아니라서 화면에도, 로그에도 흔적이 남지 않는다.
   */
  readonly recoverFromConflict: () => Promise<void>
}

/** {@link SprintForm} props */
export interface SprintFormProps {
  /**
   * 기준값의 **유일한 출처**다 — 별도 조회를 하지 않는다.
   *
   * ⚠️ 부모는 반드시 `key={sprint.sprintId}` 로 마운트한다. 기준값을 내부 state 에 두므로
   * 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 남는다.
   */
  readonly sprint: SprintMeta
  /** 백로그 queryKey 대상 프로젝트 키 */
  readonly projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 409 복구가 백로그 캐시를 **완전 일치 키로** 읽으므로
   * 보드를 빠뜨리면 기준값 교체가 무음으로 멈춘다.
   */
  readonly boardId: string | undefined
  /** 이름 칸을 그릴지. 시작 다이얼로그는 기간·목표 3칸만 쓴다 */
  readonly showName: boolean
  /** 요청 진행 중 잠금 */
  readonly disabled: boolean
  /**
   * 제출 콜백. 변경분이 없으면 `body` 가 `null` 이다.
   *
   * 기간이 어긋나거나 이름이 비면 **여기까지 오지 않는다** — 가드가 폼 안에 있어야
   * 소비자가 늘어도 같은 방어를 받는다.
   */
  readonly onSubmit: (body: UpdateSprintBody | null, actions: SprintFormActions) => void
  /** 실패 안내·푸터 등 필드 아래에 붙는 요소. 폼 안이라 `type="submit"` 이 그대로 동작한다 */
  readonly children: ReactNode
}

/**
 * 스프린트 이름·기간·목표 편집 폼.
 *
 * `StartSprintDialog`(시작 전 기간·목표 확인)와 `EditSprintDialog`(FR-1 편집)가 **같은 폼을
 * 쓴다**. 신규 폼을 만들지 않는 것이 요구다(NFR-1) — 폼이 이미 풀어 둔 부분 저장 유실 방어
 * ({@link buildPatchBody})가 사본을 만드는 순간 한쪽에서만 살아남기 때문이다.
 *
 * 완료된 스프린트는 **날짜만** 잠근다(FR-2 · J1 *"You can only edit the name and goal for a
 * complete sprint"*). 숨기지 않고 비활성 + 사유를 보이는 것은 「있는 조작이 지금은 안 되는
 * 이유를 알아야 한다」는 판단이다(권한 부재는 반대로 렌더하지 않는다).
 */
export function SprintForm({
  sprint,
  projectKey,
  boardId,
  showName,
  disabled,
  onSubmit,
  children,
}: SprintFormProps): JSX.Element {
  const queryClient = useQueryClient()
  const prefix = useId()

  /** 서버가 들고 있다고 아는 값. 저장 성공·409 때마다 최신으로 갈아끼운다 */
  const [baseline, setBaseline] = useState<SprintMeta>(sprint)
  const [values, setValues] = useState<SprintFormValues>(() => toFormValues(sprint))

  /**
   * 사용자가 손댄 필드. **전송 대상의 유일한 출처**이자 표시 갈림길이다.
   *
   * 화면 출력이 여기서 갈리므로({@link resolveDisplayValues}) ref 가 아니라 state 다.
   */
  const [edited, setEdited] = useState<ReadonlySet<SprintField>>(() => new Set())

  const display = resolveDisplayValues(values, baseline, edited)
  const rangeInvalid = isEndBeforeStart(display)
  const nameMissing = showName && edited.has('name') && display.name.trim() === ''
  const datesLocked = baseline.status === COMPLETED_STATUS

  const nameId = `${prefix}-name`
  const nameErrorId = `${prefix}-name-error`
  const rangeErrorId = `${prefix}-range-error`
  const lockedHintId = `${prefix}-dates-locked`
  const goalId = `${prefix}-goal`

  /**
   * 사용자 조작으로 필드가 바뀌었다 — 값과 「편집했다」는 사실을 함께 기록한다.
   *
   * ★ **사용자 조작만** 이 경로를 지난다. 409 뒤 기준값이 바뀌어 표시가 달라지는 것은
   *   여기를 지나지 않으므로 편집으로 세지 않는다 — 그 구분이 남의 저장분을 지키는 근거다.
   */
  function editField(field: SprintField, next: string): void {
    setValues((prev) => ({ ...prev, [field]: next }))
    setEdited((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
  }

  const actions: SprintFormActions = {
    applyServerResponse: (updated) => {
      setBaseline(updated)
      setEdited(new Set())
    },
    recoverFromConflict: async () => {
      await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
      const view = queryClient.getQueryData<BacklogView>(backlogKeys.detail(projectKey, boardId))
      const fresh = view?.sprints.find(
        (entry) => entry.sprint.sprintId === baseline.sprintId,
      )?.sprint
      if (fresh !== undefined) setBaseline(fresh)
    },
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    // 에러 문구는 이미 화면에 있다. 백엔드 왕복을 만들지 않는다
    if (rangeInvalid || nameMissing) return
    onSubmit(buildPatchBody(baseline, values, edited), actions)
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
      {showName && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={nameId}>{L.nameLabel}</Label>
          <Input
            id={nameId}
            value={display.name}
            disabled={disabled}
            aria-invalid={nameMissing}
            aria-describedby={describedBy(nameMissing ? nameErrorId : undefined)}
            onChange={(event) => {
              editField('name', event.target.value)
            }}
          />
          {nameMissing && (
            <p id={nameErrorId} className="text-sm text-destructive">
              {L.nameRequired}
            </p>
          )}
        </div>
      )}

      <div className="flex flex-col gap-2 sm:flex-row sm:gap-4">
        <DateField
          id={`${prefix}-start-date`}
          label={L.startDateLabel}
          value={display.startDate}
          disabled={disabled || datesLocked}
          hintId={datesLocked ? lockedHintId : undefined}
          onChange={(next) => {
            editField('startDate', next)
          }}
        />
        <DateField
          id={`${prefix}-end-date`}
          label={L.endDateLabel}
          value={display.endDate}
          disabled={disabled || datesLocked}
          hintId={datesLocked ? lockedHintId : undefined}
          error={rangeInvalid ? { id: rangeErrorId, message: L.endBeforeStart } : undefined}
          onChange={(next) => {
            editField('endDate', next)
          }}
        />
      </div>

      {datesLocked && (
        <p id={lockedHintId} className="text-sm text-muted-foreground">
          {L.datesLocked}
        </p>
      )}

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={goalId}>{L.goalLabel}</Label>
        <Textarea
          id={goalId}
          rows={3}
          value={display.goal}
          disabled={disabled}
          onChange={(event) => {
            editField('goal', event.target.value)
          }}
        />
      </div>

      {children}
    </form>
  )
}
