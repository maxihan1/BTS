// 보드 설정 — 추정 탭 본문 (시간 추적 · 부채 177 Task 17 · J36·J37)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import type { BoardDetail, TimeTracking } from '@/api/boards'
import { timeTrackingSchema } from '@/api/boards'
import { apiFetch, ApiError } from '@/api/client'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'

/** 화면 문구. Task 17 REFACTOR 가 `i18n/board-labels.ts` 로 옮긴다. */
const labels = {
  heading: '시간 추적',
  description: '번다운이 진행을 계산하는 방식입니다.',
  groupLabel: '시간 추적 방식',
  optionNone: '없음',
  optionRemainingAndSpent: '잔여 추정 + 소요 시간',
}

/** 고를 수 있는 값 — 백엔드 `TimeTracking` 미러 (J36). */
const TIME_TRACKING_OPTIONS: readonly { value: TimeTracking; label: string }[] = [
  { value: 'NONE', label: labels.optionNone },
  { value: 'REMAINING_AND_SPENT', label: labels.optionRemainingAndSpent },
]

/** PATCH 응답 봉투 — 백엔드 `DataResponse<EstimationSettingsResponse>`. */
const estimationResponseSchema = z.object({
  data: z.object({ timeTracking: timeTrackingSchema }),
})

/**
 * 시간 추적 설정을 갱신한다 (J36 · J37).
 *
 * PATCH `/api/v1/boards/{boardId}/estimation` → 200 + `{ data: { timeTracking } }`.
 *
 * @param boardId 대상 보드 UUID.
 * @param timeTracking 저장할 값.
 * @returns 저장된 값.
 * @throws ApiError 비-2xx.
 * @throws ZodError 응답 스키마 불일치.
 */
async function updateTimeTracking(
  boardId: string,
  timeTracking: TimeTracking,
): Promise<TimeTracking> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/estimation`, {
    method: 'PATCH',
    body: { timeTracking },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return estimationResponseSchema.parse(data).data.timeTracking
}

/** EstimationPanel props */
export interface EstimationPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회를 만들지 않는다(N1). */
  board: BoardDetail
  /** 편집 권한 (CREATE). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Estimation 탭** 본문 (J36·J37).
 */
export function EstimationPanel({ board, canConfigure }: EstimationPanelProps): JSX.Element {
  const domId = useId()
  const [timeTracking, setTimeTracking] = useState<TimeTracking>('NONE')

  const mutation = useMutation<TimeTracking, unknown, TimeTracking>({
    mutationFn: (next) => updateTimeTracking(board.boardId, next),
    onSuccess: (saved) => {
      setTimeTracking(saved)
    },
  })

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.heading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.description}</p>
      </header>

      <div className="ring-foreground/10 rounded-lg p-4 ring-1">
        <RadioGroup
          aria-label={labels.groupLabel}
          value={timeTracking}
          disabled={!canConfigure}
          onValueChange={(next) => {
            const value = next as TimeTracking
            setTimeTracking(value)
            mutation.mutate(value)
          }}
        >
          {TIME_TRACKING_OPTIONS.map((option) => (
            <div key={option.value} className="flex items-center gap-2">
              <RadioGroupItem id={`${domId}-${option.value}`} value={option.value} />
              <label
                htmlFor={`${domId}-${option.value}`}
                className="text-foreground flex min-h-11 flex-1 cursor-pointer items-center text-sm md:min-h-8"
              >
                {option.label}
              </label>
            </div>
          ))}
        </RadioGroup>
      </div>
    </section>
  )
}
