// 보드 설정 — 작업일 탭 본문 (표준 근무일 · 비근무일 · 타임존 · 부채 177 Task 18 · J38·J39·J40)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { XIcon } from 'lucide-react'
import type { BoardDetail, BoardWorkingDays } from '@/api/boards'
import { ApiError } from '@/api/client'
import { saveWorkingDays } from '@/api/board-settings'
import type { WorkingDaysInput } from '@/api/board-settings'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Combobox } from '@/components/ui/combobox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** 화면 문구 — 정본은 `i18n/board-labels.ts` 다. 화면에 문자열을 박지 않는다. */
const labels = boardLabels.settings.workingDays

/**
 * 요일 키 — 백엔드 `WorkingDaysSettingsService.WEEK_ORDER` 미러(`VARCHAR(3)[]` 표기).
 *
 * 순서가 곧 주의 순서다. 서버가 저장 시 이 순서로 정렬해 돌려주므로 화면도 같은 순서로 그린다.
 */
const WEEK_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const

/**
 * 요일 키의 주 순서 위치. 모르는 키는 `-1` 이라 앞으로 온다(서버가 거를 값이라 화면에서 숨기지 않는다).
 *
 * @param day 요일 키.
 * @returns [WEEK_ORDER] 안의 위치.
 */
function weekIndex(day: string): number {
  return (WEEK_ORDER as readonly string[]).indexOf(day)
}

/**
 * 「근무일 고르기」를 눌렀을 때 미리 켜 두는 요일 — 월~금 (J38).
 *
 * ★**이 값이 저장되는 것은 사용자가 「저장」을 누른 뒤뿐이다.** 마운트만으로 채우면 설정을
 * 만지지 않은 보드가 조용히 월~금으로 저장될 길이 열리고, 그것이 백엔드가 `working_days` 를
 * `NOT NULL DEFAULT` 로 두지 않은 이유 그 자체다(스펙 R6). 사용자가 **명시로 시작한** 편집의
 * 출발점일 뿐이다.
 */
const DEFAULT_WORKING_DAYS: readonly string[] = ['MON', 'TUE', 'WED', 'THU', 'FRI']

/**
 * 고를 수 있는 IANA 타임존 전량 — **브라우저 tzdb 가 원천이다.**
 *
 * 목록을 손으로 추리지 않는 이유는 그 목록이 곧 「우리가 지원한다고 주장하는 지역」이 되기
 * 때문이다. 서버는 `ZoneId.getAvailableZoneIds()` 멤버십으로 재므로 임의 목록은 두 판정이
 * 서로를 검사하지 않는 상태를 만든다(이 저장소가 이름 붙인 지배 결함 양식).
 */
const ALL_ZONES = Intl.supportedValuesOf('timeZone')

/** 지역 목록 (J40 — *"select a Region"*). `Asia/Seoul` 의 `Asia` 부분이다. */
const REGIONS: readonly string[] = [...new Set(ALL_ZONES.map((zone) => regionOf(zone) ?? zone))]

/**
 * 타임존의 지역 부분을 뽑는다.
 *
 * @param zone IANA 타임존. null 이면 null(미설정).
 * @returns `Asia/Seoul` → `Asia`. 슬래시가 없는 표기는 그 자체를 지역으로 본다.
 */
function regionOf(zone: string | null): string | null {
  if (zone === null) return null
  return zone.split('/')[0] ?? zone
}

/** 지역 드롭다운 항목 — 컴포넌트 밖에서 한 번만 만든다(리렌더마다 새 배열을 만들지 않는다). */
const REGION_OPTIONS = REGIONS.map((name) => ({ value: name, label: name }))

/** WorkingDaysPanel props */
export interface WorkingDaysPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회를 만들지 않는다(N1). */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 조작만 잠기고 값은 그대로 보인다(S7). */
  canConfigure: boolean
}

/** 한 번의 저장 시도. 재시도가 **같은 값**을 다시 보내려면 보낸 것을 들고 있어야 한다. */
interface WorkingDaysFailure {
  /** 실패한 요청 바디. */
  input: WorkingDaysInput
  /** 상태 코드. `ApiError` 가 아니면 null(네트워크 등). */
  status: number | null
}

/**
 * 지라 Board settings 의 **Working days 탭** 본문 (J38·J39·J40).
 *
 * 표준 근무일(J38) · 비근무일(J39) · 타임존(J40) 세 값을 고르고 **버튼으로 한 번에** 저장한다.
 *
 * ### 왜 형제 탭과 달리 「저장」 버튼이 있나
 * `CardLayoutPanel`·`EstimationPanel` 은 고르는 즉시 저장한다. 이 탭은 그럴 수 없다 —
 * 저장이 **PUT 교체**라 요일을 하나씩 끄는 도중 반드시 「근무일 0개」를 지나가고, 그 값은
 * 서버가 400 으로 막는다(스펙 E1 · ideal 선 0 나눗셈). 즉시 저장이면 사용자는 월~금을
 * 화~금으로 바꾸는 동안 아무 잘못 없이 400 을 본다. 버튼 저장이라야 **0개를 지나가되
 * 0개로 저장되지는 않는** 편집이 가능하다.
 *
 * ### ★★「미설정(null)」과 「근무일 0개(`[]`)」는 뜻이 정반대다 (스펙 R6 · E1)
 * | 상태 | 뜻 | 이 화면 |
 * |---|---|---|
 * | `null` | **미설정** — 번다운이 달력일 전부를 센다(현행 유지) | 저장 **가능**. `standardDays: null` 을 보낸다 |
 * | `[]` | 근무일이 하나도 없다 | 저장 **불가**. 서버가 400 이다 |
 * | `['MON', …]` | 근무일 지정 | 저장 가능 |
 *
 * **둘을 뭉개면 안 된다.** 빈 값을 null 로 바꿔 보내면 「근무일 0개」를 고른 사용자의 뜻이
 * 조용히 「미설정」으로 저장되고, 반대로 미설정까지 막으면 **설정을 한 번도 만지지 않은 보드를
 * 아무도 설정할 수 없다.** 백엔드가 `working_days` 를 `NOT NULL DEFAULT` 로 두지 않은 이유가
 * 그 구분 자체다 — 기본값을 월~금으로 채우면 기존 모든 스프린트의 번다운이 배포 순간 바뀐다.
 * 판정은 `WorkingDaysPanel.test.tsx` 의 **T-WD-1 · T-WD-2** 가 **짝으로** 진다.
 *
 * ### ★★저장돼 있던 값을 **초기값으로 읽는다** (Task 31 · N1)
 * 보드 조회 응답이 `workingDays` 를 실어 온다 — 별도 GET 을 만들지 않는 것이 N1 이다.
 * **표시 문제가 아니라 데이터 보존 문제다.** 저장이 PUT 교체라, 빈 상태에서 시작하면 첫 저장이
 * 서버에 있던 비근무일과 타임존을 **통째로 지운다.** `SettingsTabs` 가 `forceMount` 없는 Radix
 * `TabsContent` 라 탭을 옮겼다 돌아오기만 해도 다시 마운트되므로 한 번 지나가고 끝이 아니다
 * (T16 이 카드 레이아웃에서 실제로 밟은 자리다). 판정은 **T-WD-3 · T-WD-5 · T-WD-6 · T-WD-7**.
 *
 * 저장이 정착하면 **보드 조회를 무효화**한다 — 다음 마운트가 서버 값에서 다시 시작하게 하는
 * 유일한 근거다. 캐시를 `setQueryData` 로 덮지 않는다(응답에 없는 파생 필드가 null 로 덮여
 * 화면이 플리커한 사고가 있다 — PR #46).
 *
 * ### ★「미설정」을 화면에서 읽히게 한다 (스펙 R6)
 * 미설정 보드는 요일 체크박스를 **그리지 않고** 「아직 설정하지 않았다 · 달력일 전부를 센다」고
 * 말한다. 7개가 다 꺼진 화면은 「근무일 0개」와 구분되지 않는데 그 둘은 뜻이 정반대다.
 * 편집을 시작하는 버튼([labels.configureDays])과 되돌리는 버튼([labels.resetToUnset])이
 * 두 상태를 오가는 유일한 문이고, 뒤엣것은 **근무일 0개라는 막다른 골목의 출구**이기도 하다.
 *
 * ### ★비근무일은 스프린트 기간으로 거르지 않는다 (스펙 E8)
 * 보드는 스프린트 기간을 모르고, 화면이 걸러 버리면 스프린트가 바뀔 때마다 설정이 소실된다.
 * 백엔드도 같은 이유로 거르지 않는다(`WorkingDaysSettingsService` KDoc). 판정은 **T-WD-11**.
 *
 * ### 상태 3종
 * - **로딩** — 이 패널은 스스로 조회하지 않는다. 보드 조회의 로딩은 부모 라우트가
 *   `ColumnsSkeleton` 으로 그린다(`projects.$projectKey.board.settings.tsx`).
 * - **에러** — 저장 실패는 `role="alert"` 로 **화면에 남는다.** 토스트 단독은 쓰지 않는다.
 * - **빈** — 비근무일이 없을 때 [labels.noDates] 를 그린다. 회색 빈칸으로 두면 조회 실패로 읽힌다.
 */
export function WorkingDaysPanel({ board, canConfigure }: WorkingDaysPanelProps): JSX.Element {
  const domId = useId()
  const stored = board.workingDays

  // ★보드 조회가 실어 온 값에서 시작한다. `null` 과 `[]` 를 여기서 뭉개면 그 뒤 어디서도
  //   되살릴 수 없다. `SettingsTabs` 가 `key={board.boardId}` 로 재마운트를 걸어 주므로
  //   보드가 바뀌면 이 초기값도 다시 잡힌다.
  const [days, setDays] = useState<readonly string[] | null>(stored?.standardDays ?? null)
  const [dates, setDates] = useState<readonly string[]>(stored?.nonWorkingDates ?? [])
  const [timezone, setTimezone] = useState<string | null>(stored?.timezone ?? null)
  const [region, setRegion] = useState<string | null>(regionOf(stored?.timezone ?? null))
  const [draftDate, setDraftDate] = useState('')
  const [failure, setFailure] = useState<WorkingDaysFailure | null>(null)
  const [justSaved, setJustSaved] = useState(false)
  const queryClient = useQueryClient()

  const mutation = useMutation<BoardWorkingDays, unknown, WorkingDaysInput>({
    mutationFn: (input) => saveWorkingDays(board.boardId, input),
    onSuccess: (saved) => {
      // 응답은 요청 echo 가 아니라 **정규화된 저장값**이다(중복 날짜 제거 · 요일 주 순서).
      // 자기가 보낸 값을 화면 상태로 삼으면 사용자가 실제 저장된 집합을 보지 못한다.
      setDays(saved.standardDays === null ? null : [...saved.standardDays])
      setDates([...saved.nonWorkingDates])
      setTimezone(saved.timezone)
      setRegion(regionOf(saved.timezone))
      setFailure(null)
      setJustSaved(true)
    },
    onError: (error, input) => {
      // ★편집 중이던 값을 **되돌리지 않는다.** 형제 탭은 즉시 저장이라 낙관 반영을 되돌리지만,
      //   이 탭은 버튼 저장이라 화면의 값이 아직 「사용자가 만들던 초안」이다. 되돌리면 사용자가
      //   방금 한 편집을 잃는다 — 고쳐서 다시 누를 수 있어야 한다.
      // ★오류 **본문**을 읽지 않는다. 탭마다 봉투가 달라(부채 177 Task 29 가 통일 예정)
      //   본문 구조에 기대면 통일되는 날 조용히 어긋난다. 상태 코드로만 가른다.
      setFailure({ input, status: error instanceof ApiError ? error.status : null })
      setJustSaved(false)
    },
    onSettled: async () => {
      // ★실패해도 재조회한다. 실패의 흔한 원인이 동시 편집이고, 그때야말로 화면이 아니라
      //   서버가 정본이다. 이 무효화가 없으면 탭을 옮겼다 돌아왔을 때 **저장 전 캐시**가
      //   초기값이 되고, 그 다음 저장이 방금 저장한 것을 도로 덮는다.
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(board.boardId) })
    },
  })

  // 근무일 0개는 화면이 먼저 막는다(E1). **미설정(null)은 막지 않는다** — 뜻이 다르다(R6).
  const zeroDays = days !== null && days.length === 0
  const locked = !canConfigure || mutation.isPending

  function submit(input: WorkingDaysInput): void {
    setFailure(null)
    setJustSaved(false)
    mutation.mutate(input)
  }

  function toggleDay(key: string, checked: boolean): void {
    setDays((prev) => {
      const base = prev ?? []
      const next = checked ? [...base, key] : base.filter((entry) => entry !== key)
      // 서버가 주 순서로 정렬해 돌려주므로 화면도 같은 순서로 둔다(응답과 초안이 갈리지 않게).
      return [...next].sort((a, b) => weekIndex(a) - weekIndex(b))
    })
    setJustSaved(false)
  }

  function addDate(): void {
    if (draftDate === '') return
    // ★스프린트 기간으로 거르지 않는다(E8). 중복만 막는다 — 목록에 같은 날짜가 두 줄이면
    //   삭제 버튼의 접근성 이름이 겹친다.
    setDates((prev) => (prev.includes(draftDate) ? prev : [...prev, draftDate]))
    setDraftDate('')
    setJustSaved(false)
  }

  // 400 은 같은 값을 다시 보내도 같은 400 이다 — 재시도 버튼을 주지 않는다
  // (형제 `EstimationPanel` 이 409 에 세운 규칙과 같은 결).
  const isInvalid = failure?.status === 400
  const failureMessage =
    failure === null
      ? null
      : isInvalid
        ? labels.saveInvalid
        : failure.status === 403
          ? labels.saveForbidden
          : labels.saveFailed

  const zoneOptions = ALL_ZONES.filter((zone) => regionOf(zone) === region).map((zone) => ({
    value: zone,
    label: zone.slice((region?.length ?? 0) + 1).replaceAll('_', ' '),
  }))

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.standardHeading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.standardDescription}</p>
      </header>

      {/* 실패는 **화면에 남는다.** 토스트만 띄우면 사용자가 저장된 줄 알고 화면을 떠난다. */}
      {failure !== null && failureMessage !== null && (
        <div
          role="alert"
          className="border-destructive/40 flex items-center gap-3 rounded-md border p-3 text-sm"
        >
          <p className="text-destructive flex-1">{failureMessage}</p>
          {!isInvalid && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={locked}
              onClick={() => {
                submit(failure.input)
              }}
            >
              {labels.saveRetry}
            </Button>
          )}
        </div>
      )}

      <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
        {days === null ? (
          // ★★**미설정을 「요일 7개가 다 꺼진 화면」으로 그리지 않는다**(스펙 R6).
          //   그 모습은 「근무일 0개」와 구분되지 않는데 두 상태의 뜻은 정반대다 —
          //   미설정은 달력일 **전부**이고 0개는 근무일이 **하나도 없음**이다. 설정을 한 번도
          //   만지지 않은 보드가 「0개」로 보이면 사용자는 자기가 아무것도 안 했는데 무언가
          //   잘못됐다고 읽는다. 판정은 T-WD-18 · T-WD-19 가 **짝으로** 진다.
          <div className="space-y-3">
            <p className="text-muted-foreground text-sm">{labels.unsetNotice}</p>
            <Button
              type="button"
              variant="outline"
              disabled={locked}
              onClick={() => {
                setDays([...DEFAULT_WORKING_DAYS])
                setJustSaved(false)
              }}
            >
              {labels.configureDays}
            </Button>
          </div>
        ) : (
          <>
            <ul aria-label={labels.daysGroupLabel}>
              {WEEK_ORDER.map((key) => {
                const controlId = `${domId}-${key}`
                return (
                  <li key={key} className="flex items-center gap-2">
                    <Checkbox
                      id={controlId}
                      checked={days.includes(key)}
                      disabled={locked}
                      onCheckedChange={(next) => {
                        toggleDay(key, next === true)
                      }}
                    />
                    {/* 행 전체를 라벨로 만들어 터치 타깃을 44px 로 넓힌다(체크박스 자체는 16px).
                        데스크톱은 목록 밀도를 위해 낮춘다 — 형제 패널과 같은 결. */}
                    <label
                      htmlFor={controlId}
                      className="text-foreground flex min-h-11 flex-1 cursor-pointer items-center text-sm md:min-h-8"
                    >
                      {labels.dayLabels[key]}
                    </label>
                  </li>
                )
              })}
            </ul>

            {/* ★**막다른 골목의 출구다.** 근무일 0개는 저장이 막히므로, 「근무일을 쓰지 않겠다」는
                뜻은 값을 비우는 것이 아니라 미설정으로 되돌리는 것이다 — 백엔드가 400 응답에
                적어 보내는 안내와 같은 문장이다(`WorkingDaysSettingsService`). */}
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={locked}
              onClick={() => {
                setDays(null)
                setJustSaved(false)
              }}
            >
              {labels.resetToUnset}
            </Button>
          </>
        )}

        {/* 저장이 막힌 이유와 다음 행동을 함께 말한다 — 사유 없이 비활성만 하면 「고장」으로 읽힌다. */}
        {zeroDays && <p className="text-muted-foreground text-xs">{labels.zeroDaysHint}</p>}
      </div>

      <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
        <h3 className="text-sm font-semibold">{labels.nonWorkingHeading}</h3>
        <p className="text-muted-foreground text-sm">{labels.nonWorkingDescription}</p>

        <div className="flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${domId}-date`}>{labels.dateInputLabel}</Label>
            <Input
              id={`${domId}-date`}
              type="date"
              className="w-44"
              value={draftDate}
              disabled={locked}
              onChange={(event) => {
                setDraftDate(event.target.value)
              }}
            />
          </div>
          <Button type="button" variant="outline" disabled={locked} onClick={addDate}>
            {labels.addDate}
          </Button>
        </div>

        {dates.length === 0 ? (
          // 안심 문구다 — 「없음」을 회색으로 비워 두면 사용자가 조회 실패로 읽는다.
          <p className="text-muted-foreground text-sm">{labels.noDates}</p>
        ) : (
          <ul aria-label={labels.nonWorkingHeading} className="space-y-1">
            {dates.map((date) => (
              <li key={date} data-date={date} className="flex items-center gap-2">
                <span className="text-foreground flex-1 text-sm">{date}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={labels.removeDate(date)}
                  disabled={locked}
                  onClick={() => {
                    setDates((prev) => prev.filter((entry) => entry !== date))
                    setJustSaved(false)
                  }}
                >
                  <XIcon aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
        <h3 className="text-sm font-semibold">{labels.timezoneHeading}</h3>
        <p className="text-muted-foreground text-sm">{labels.timezoneDescription}</p>

        {/* J40 — *"select a Region, then Timezone from the dropdowns"*. 지역을 먼저 고르는
            것이 지라의 조작이자, 400여 개를 한 목록에 쏟지 않는 필터이기도 하다. */}
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${domId}-region`}>{labels.regionLabel}</Label>
            <Combobox
              id={`${domId}-region`}
              options={REGION_OPTIONS}
              value={region}
              onChange={(next) => {
                setRegion(next)
                // 지역이 바뀌면 이전 타임존은 그 지역에 없다 — 남겨 두면 트리거가 값 없음으로
                // 보이면서 저장 요청에는 옛 값이 실린다(보이는 것과 보내는 것이 어긋난다).
                setTimezone(null)
                setJustSaved(false)
              }}
              ariaLabel={labels.regionLabel}
              placeholder={labels.regionSearch}
              emptyText={labels.comboboxEmpty}
              triggerPlaceholder={labels.regionPlaceholder}
              disabled={locked}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${domId}-timezone`}>{labels.timezoneLabel}</Label>
            <Combobox
              id={`${domId}-timezone`}
              options={zoneOptions}
              value={timezone}
              onChange={(next) => {
                setTimezone(next)
                setJustSaved(false)
              }}
              ariaLabel={labels.timezoneLabel}
              placeholder={labels.timezoneSearch}
              emptyText={labels.comboboxEmpty}
              triggerPlaceholder={labels.timezonePlaceholder}
              disabled={locked || region === null}
            />
          </div>
        </div>

        <p className="text-muted-foreground text-xs">{labels.timezoneUnset}</p>
      </div>

      <div className="flex items-center gap-3">
        <Button
          type="button"
          disabled={locked || zeroDays}
          onClick={() => {
            submit({ standardDays: days, nonWorkingDates: dates, timezone })
          }}
        >
          {labels.save}
        </Button>
        {/* 버튼 저장이라 결과를 화면에 남긴다 — 즉시 저장 탭과 달리 「눌렀는데 아무 일도
            안 일어난 것 같은」 자리가 생기기 때문이다. */}
        {justSaved && (
          <p role="status" className="text-muted-foreground text-sm">
            {labels.saved}
          </p>
        )}
      </div>
    </section>
  )
}
