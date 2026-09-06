// 보드 설정 — 작업일 탭 본문 (표준 근무일 · 비근무일 · 타임존 · 부채 177 Task 18 · J38·J39·J40)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { BoardDetail, BoardWorkingDays } from '@/api/boards'
import { saveWorkingDays } from '@/api/board-settings'
import type { WorkingDaysInput } from '@/api/board-settings'
import { boardLabels } from '@/i18n/board-labels'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Combobox } from '@/components/ui/combobox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** 화면 문구 — 정본은 `i18n/board-labels.ts` 다. 화면에 문자열을 박지 않는다. */
const labels = boardLabels.settings.workingDays

/** 요일 키 — 백엔드 `WorkingDaysSettingsService.WEEK_ORDER` 미러. 순서가 곧 주의 순서다. */
const WEEK_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const

/** 고를 수 있는 IANA 타임존 전량 — 브라우저 tzdb 가 원천이다(임의 목록을 만들지 않는다). */
const ALL_ZONES = Intl.supportedValuesOf('timeZone')

/** 지역 목록 (J40 — *"select a Region"*). `Asia/Seoul` 의 `Asia` 부분이다. */
const REGIONS = [...new Set(ALL_ZONES.map((zone) => zone.split('/')[0] ?? zone))]

/** WorkingDaysPanel props */
export interface WorkingDaysPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회를 만들지 않는다(N1). */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 조작만 잠기고 값은 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Working days 탭** 본문 (J38·J39·J40).
 *
 * 표준 근무일 · 비근무일 · 타임존 세 값을 고르고 한 번에 저장한다.
 */
export function WorkingDaysPanel({ board, canConfigure }: WorkingDaysPanelProps): JSX.Element {
  const domId = useId()
  const [days, setDays] = useState<string[]>([])
  const [dates, setDates] = useState<string[]>([])
  const [timezone, setTimezone] = useState<string | null>(null)
  const [region, setRegion] = useState<string | null>(null)
  const [draftDate, setDraftDate] = useState('')

  const mutation = useMutation<BoardWorkingDays, unknown, WorkingDaysInput>({
    mutationFn: (input) => saveWorkingDays(board.boardId, input),
    onSuccess: (saved) => {
      setDays([...(saved.standardDays ?? [])])
      setDates([...saved.nonWorkingDates])
      setTimezone(saved.timezone)
    },
  })

  function submit(): void {
    mutation.mutate({
      // 비어 있으면 미설정으로 보낸다.
      standardDays: days.length === 0 ? null : days,
      nonWorkingDates: dates,
      timezone,
    })
  }

  const zoneOptions = ALL_ZONES.filter((zone) => zone.startsWith(`${region ?? ''}/`)).map((zone) => ({
    value: zone,
    label: zone.slice((region ?? '').length + 1).replaceAll('_', ' '),
  }))

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.standardHeading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.standardDescription}</p>
      </header>

      <ul aria-label={labels.daysGroupLabel} className="ring-foreground/10 rounded-lg p-4 ring-1">
        {WEEK_ORDER.map((key) => (
          <li key={key} className="flex items-center gap-2">
            <Checkbox
              id={`${domId}-${key}`}
              checked={days.includes(key)}
              onCheckedChange={(next) => {
                setDays((prev) => (next === true ? [...prev, key] : prev.filter((d) => d !== key)))
              }}
            />
            <label
              htmlFor={`${domId}-${key}`}
              className="text-foreground flex min-h-11 flex-1 cursor-pointer items-center text-sm md:min-h-8"
            >
              {labels.dayLabels[key]}
            </label>
          </li>
        ))}
      </ul>

      <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
        <h3 className="text-sm font-semibold">{labels.nonWorkingHeading}</h3>
        <p className="text-muted-foreground text-sm">{labels.nonWorkingDescription}</p>
        <div className="flex items-end gap-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${domId}-date`}>{labels.dateInputLabel}</Label>
            <Input
              id={`${domId}-date`}
              type="date"
              value={draftDate}
              onChange={(event) => {
                setDraftDate(event.target.value)
              }}
            />
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              if (draftDate === '') return
              setDates((prev) => (prev.includes(draftDate) ? prev : [...prev, draftDate]))
              setDraftDate('')
            }}
          >
            {labels.addDate}
          </Button>
        </div>

        {dates.length === 0 ? (
          <p className="text-muted-foreground text-sm">{labels.noDates}</p>
        ) : (
          <ul aria-label={labels.nonWorkingHeading}>
            {dates.map((date) => (
              <li key={date} data-date={date} className="flex items-center gap-2 text-sm">
                <span className="flex-1">{date}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={labels.removeDate(date)}
                  onClick={() => {
                    setDates((prev) => prev.filter((entry) => entry !== date))
                  }}
                >
                  ✕
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
        <h3 className="text-sm font-semibold">{labels.timezoneHeading}</h3>
        <p className="text-muted-foreground text-sm">{labels.timezoneDescription}</p>
        <Combobox
          options={REGIONS.map((name) => ({ value: name, label: name }))}
          value={region}
          onChange={(next) => {
            setRegion(next)
            setTimezone(null)
          }}
          ariaLabel={labels.regionLabel}
          placeholder={labels.regionSearch}
          emptyText={labels.comboboxEmpty}
          triggerPlaceholder={labels.regionPlaceholder}
        />
        <Combobox
          options={zoneOptions}
          value={timezone}
          onChange={setTimezone}
          ariaLabel={labels.timezoneLabel}
          placeholder={labels.timezoneSearch}
          emptyText={labels.comboboxEmpty}
          triggerPlaceholder={labels.timezonePlaceholder}
          disabled={region === null}
        />
      </div>

      <Button type="button" onClick={submit} disabled={mutation.isPending}>
        {labels.save}
      </Button>
      {!canConfigure && <p className="text-muted-foreground text-sm">{labels.save}</p>}
    </section>
  )
}
