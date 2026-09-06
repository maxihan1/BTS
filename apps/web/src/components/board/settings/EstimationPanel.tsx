// 보드 설정 — 추정 탭 본문 (시간 추적 · 칸반 잠금 · 부채 177 Task 17 · J36·J37)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { BoardDetail, TimeTracking } from '@/api/boards'
import { ApiError } from '@/api/client'
import { updateTimeTracking } from '@/api/board-settings'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import { Button } from '@/components/ui/button'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'

/**
 * 화면 문구 — 정본은 `i18n/board-labels.ts` 다.
 *
 * ★**화면에 문자열을 박지 않는다.** `CardLayoutPanel` 이 문구를 자기 파일에 둔 것은 그 task 의
 * 허용 파일에 `board-labels.ts` 가 없었기 때문이고(`BacklogEpicPanel` 선례), 예외였다.
 * 이 task 는 그 파일이 허용 범위 안이라 관례대로 i18n 자리에 둔다.
 */
const labels = boardLabels.settings.estimation

/** 고를 수 있는 값 — 백엔드 `TimeTracking` 미러 (J36 · V509 CHECK 가 두 값으로 닫는다). */
const TIME_TRACKING_OPTIONS: readonly { value: TimeTracking; label: string }[] = [
  { value: 'NONE', label: labels.optionNone },
  { value: 'REMAINING_AND_SPENT', label: labels.optionRemainingAndSpent },
]

/** 한 번의 저장 시도. 실패 되돌림과 재시도가 같은 값을 쓴다. */
interface EstimationSave {
  /** 보내려던 값. */
  next: TimeTracking
  /** 실패했을 때 되돌릴 직전 값. */
  previous: TimeTracking
}

/** EstimationPanel props */
export interface EstimationPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회를 만들지 않는다(N1). */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 고를 수만 없고 값은 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Estimation 탭** 본문 (J36 · J37).
 *
 * 시간 추적을 `없음` ↔ `잔여 추정 + 소요 시간` 중에서 고른다(R4 · S2). 고르는 즉시 저장한다 —
 * 「저장」 버튼을 따로 두지 않는 것은 형제 `CardLayoutPanel` 과 같은 결정이다.
 *
 * ### ★칸반은 사유를 보이며 잠근다 — 값은 지우지 않는다 (J37 · 스펙 E6)
 * *"This setting can only be changed for company-managed scrum teams."*(J37) 그런데 백엔드는
 * 칸반으로 바꿔도 `time_tracking` 을 **지우지 않는다** — 스크럼으로 되돌리면 살아난다(E6).
 * 그래서 이 화면은 **잠그되 저장된 값을 그대로 보인다.** 잠그면서 `없음` 으로 되돌려 그리면
 * 사용자는 값이 지워졌다고 읽고, 그것이 E6 계약을 화면에서 깨는 자리다.
 * 판정은 `EstimationPanel.test.tsx` 의 **T-ES-4** 가 진다.
 *
 * ### ★★저장돼 있던 값을 **초기값으로 읽는다** (Task 31 · N1)
 * 보드 조회 응답이 `timeTracking` 을 실어 온다 — 별도 GET 을 만들지 않는 것이 N1 이다.
 * 빈 상태에서 시작해 PATCH 응답으로만 채우면, 서버에 `잔여 추정 + 소요 시간` 이 저장돼 있어도
 * 화면은 `없음` 을 선택된 것으로 그린다. `SettingsTabs` 가 `forceMount` 없는 Radix `TabsContent`
 * 라 **탭을 옮겼다 돌아오기만 해도** 다시 그렇게 되고, 그 상태에서의 첫 조작이 저장돼 있던 값을
 * 덮는다(T16 이 카드 레이아웃에서 실제로 밟은 자리다). 판정은 **T-ES-5 · T-ES-7** 이 진다.
 *
 * 그래서 저장이 정착하면 **보드 조회를 무효화**한다 — 다음 마운트가 서버 값에서 다시 시작하게
 * 하는 유일한 근거다. 캐시를 `setQueryData` 로 덮지 않는다(응답에 없는 파생 필드가 null 로
 * 덮여 화면이 플리커한 사고가 있다 — PR #46).
 *
 * ### 상태 3종
 * - **로딩** — 이 패널은 스스로 조회하지 않는다. 보드 조회의 로딩은 부모 라우트가
 *   `ColumnsSkeleton` 으로 그린다(`projects.$projectKey.board.settings.tsx`).
 * - **에러** — 저장 실패는 `role="alert"` 로 **화면에 남는다.** 토스트 단독은 쓰지 않는다.
 * - **빈** — 없다. 고를 값이 열거형 2종으로 고정이라 목록이 비는 경우가 원리적으로 없다.
 */
export function EstimationPanel({ board, canConfigure }: EstimationPanelProps): JSX.Element {
  const domId = useId()
  const isScrum = board.boardType === 'SCRUM'

  // ★보드 조회가 실어 온 값에서 시작한다. `'NONE'` 으로 시작하면 저장돼 있던 값이 화면에서
  //   사라지고(E6), 그 상태의 첫 조작이 서버 값을 덮는다. `SettingsTabs` 가 `key={board.boardId}`
  //   로 재마운트를 걸어 주므로 보드가 바뀌면 이 초기값도 다시 잡힌다.
  const [timeTracking, setTimeTracking] = useState<TimeTracking>(board.timeTracking ?? 'NONE')
  const [failure, setFailure] = useState<{ save: EstimationSave; status: number | null } | null>(
    null,
  )
  const queryClient = useQueryClient()

  const mutation = useMutation<TimeTracking, unknown, EstimationSave>({
    mutationFn: (save) => updateTimeTracking(board.boardId, save.next),
    onSuccess: (saved) => {
      // 응답은 **서버가 확정한 값**이다. 자기가 보낸 값을 화면 상태로 삼으면 저장이 안 돼도
      // 성공처럼 보인다.
      setTimeTracking(saved)
      setFailure(null)
    },
    onError: (error, save) => {
      // 낙관 반영을 되돌린다. 그대로 두면 사용자는 저장된 줄 안다.
      setTimeTracking(save.previous)
      // ★오류 **본문**을 읽지 않는다. 탭마다 봉투가 달라(Task 29 가 통일 예정) 본문 구조에
      //   기대면 통일되는 날 조용히 어긋난다. 상태 코드로만 가른다.
      setFailure({ save, status: error instanceof ApiError ? error.status : null })
    },
    onSettled: async () => {
      // ★실패해도 재조회한다. 실패의 흔한 원인이 동시 편집(보드 종류가 바뀌어 409)이고,
      //   그때야말로 화면이 아니라 서버가 정본이다. 이 무효화가 없으면 탭을 옮겼다 돌아왔을 때
      //   **저장 전 캐시**가 초기값이 된다.
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(board.boardId) })
    },
  })

  // 칸반은 서버가 409 로 막는다(E5). 화면도 같은 판정을 미리 낸다 — 눌러 봐야 실패하는 조작을
  // 열어 두지 않는다. 저장 중 잠금은 연속 조작 lost update 방어다(`#452` E8 과 같은 처방).
  const locked = !isScrum || !canConfigure || mutation.isPending

  // 409 는 「지금 이 보드에서는 못 바꾼다」는 뜻이라 칸반 잠금과 **같은 사유**다.
  // 재시도로 풀리지 않으므로 재시도 버튼을 주지 않는다(`stateConflict` 가 세운 규칙과 같은 결).
  const isConflict = failure?.status === 409
  const failureMessage =
    failure === null
      ? null
      : isConflict
        ? labels.kanbanLocked
        : failure.status === 403
          ? labels.saveForbidden
          : labels.saveFailed

  function submit(save: EstimationSave): void {
    setTimeTracking(save.next)
    setFailure(null)
    mutation.mutate(save)
  }

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.estimationHeading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.estimationDescription}</p>
      </header>

      {/* 사유 없이 비활성만 하면 사용자는 「고장」으로 읽는다(J37 을 화면에서 말한다). */}
      {!isScrum && (
        <p id={`${domId}-locked`} className="text-muted-foreground text-sm">
          {labels.kanbanLocked}
        </p>
      )}

      {/* 실패는 **화면에 남는다.** 토스트만 띄우면 사용자가 저장된 줄 알고 화면을 떠난다. */}
      {failure !== null && failureMessage !== null && (
        <div
          role="alert"
          className="border-destructive/40 flex items-center gap-3 rounded-md border p-3 text-sm"
        >
          <p className="text-destructive flex-1">{failureMessage}</p>
          {!isConflict && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={mutation.isPending}
              onClick={() => {
                submit(failure.save)
              }}
            >
              {labels.saveRetry}
            </Button>
          )}
        </div>
      )}

      <div className="ring-foreground/10 rounded-lg p-4 ring-1">
        <RadioGroup
          aria-label={labels.groupLabel}
          // 잠겨도 **저장된 값은 그대로 보인다**(E6) — `value` 는 잠금과 무관하다.
          value={timeTracking}
          disabled={locked}
          aria-describedby={isScrum ? undefined : `${domId}-locked`}
          onValueChange={(next) => {
            submit({ next: next as TimeTracking, previous: timeTracking })
          }}
        >
          {TIME_TRACKING_OPTIONS.map((option) => (
            <div key={option.value} className="flex items-center gap-2">
              <RadioGroupItem id={`${domId}-${option.value}`} value={option.value} />
              {/* 행 전체를 라벨로 만들어 터치 타깃을 44px 로 넓힌다(라디오 자체는 16px).
                  데스크톱은 목록 밀도를 위해 낮춘다 — `CardLayoutPanel` 과 같은 결. */}
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
