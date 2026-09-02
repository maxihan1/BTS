// 보드 생성 폼 컴포넌트 — 1단계 종류 선택 + 2단계 이름 입력 wizard (FR-BD-01 Task 7 · FR-BD-04 D6)
import type { JSX } from 'react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { boardLabels } from '@/i18n/board-labels'
import { cn } from '@/lib/utils'
import { useCreateBoard } from '@/hooks/use-boards'
import type { BoardCreated, BoardType } from '@/api/boards'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수
// ─────────────────────────────────────────────────────────────────────────────

const AGILE_UNPROCESSABLE = 'AGILE_UNPROCESSABLE'

/** ProblemDetail body에서 errorCode를 추출하는 헬퍼 */
function extractErrorCode(body: unknown): string | undefined {
  if (body !== null && typeof body === 'object' && 'errorCode' in body) {
    const code = (body as Record<string, unknown>)['errorCode']
    return typeof code === 'string' ? code : undefined
  }
  return undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 종류 선택 — 문구·순서·기본값 (FR-BD-04 D6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 종류별 라벨과 보조 설명. 문구 정본은 `i18n/board-labels.ts` 다.
 *
 * `Record<BoardType, …>` 로 두는 이유 — 라디오 목록과 2단계의 선택 표시(D1)가 **같은 출처**를
 * 봐야 한다. 배열 `find` 로 찾으면 결과가 `| undefined` 라 없는 경우를 다루는 죽은 가지가 생기고,
 * 종류가 늘 때 한쪽만 갱신돼도 컴파일이 잡지 못한다. Record 는 값이 빠지면 컴파일이 막는다.
 */
const BOARD_TYPE_TEXT: Record<
  BoardType,
  { readonly label: string; readonly description: string }
> = {
  SCRUM: {
    label: boardLabels.createForm.typeStep.scrumLabel,
    description: boardLabels.createForm.typeStep.scrumDescription,
  },
  KANBAN: {
    label: boardLabels.createForm.typeStep.kanbanLabel,
    description: boardLabels.createForm.typeStep.kanbanDescription,
  },
}

/**
 * 라디오가 그려지는 순서 — **스크럼이 먼저**다.
 *
 * J1 원문(*"Create a Scrum board" 또는 "Create a Kanban board"*)과 ADR §D4 다이어그램의 순서를
 * 그대로 따른다. `Object.keys(BOARD_TYPE_TEXT)` 에 기대지 않는 이유는 그 순서가 객체 리터럴의
 * 작성 순서라는 우연에 걸려 있기 때문이다 — 순서가 계약이면 계약을 따로 적는다.
 */
const BOARD_TYPE_ORDER: readonly BoardType[] = ['SCRUM', 'KANBAN']

/**
 * 처음 선택돼 있는 종류 — **칸반**(plan 의도적 편차 X4).
 *
 * 백엔드 `BoardCreateRequest.boardType` 이 생략되면 KANBAN 으로 채워지므로(#421), 기본을 칸반에
 * 두면 종류를 모르고 지나가는 기존 사용자의 결과가 이전과 같다. 늘어나는 것은 「다음」 클릭 1회뿐이다.
 * 라디오가 항상 하나 선택돼 있어 「아무것도 안 고르고 다음」(E1)은 도달할 수 없는 상태가 된다.
 */
const DEFAULT_BOARD_TYPE: BoardType = 'KANBAN'

/** 라디오 그룹 레이블의 DOM id — `aria-labelledby` 로 `role="radiogroup"` 의 이름이 된다 */
const TYPE_GROUP_LABEL_ID = 'board-type-group-label'

/**
 * 빈 상태 전용 여백 — 화면 한복판을 채우는 자리라 최소 높이와 큰 안쪽 여백을 준다.
 *
 * 다이얼로그에는 붙이지 않는다. `DialogContent` 가 이미 `p-6` 을 갖고 있어 `p-8` 이 겹치면
 * 안쪽 여백이 두 겹이 되고 `min-h-48` 이 창을 세로로 늘려 답답해진다(장부 147). 인트로
 * **문구**만 껐던 것이 그 부채였다 — 여백을 만든 것은 문구가 아니라 이 컨테이너다.
 *
 * 조건이 인트로와 같은 `showEmptyStateIntro` 인 이유 — 이 여백은 그 문구를 감싸려고 생겼으니
 * 문구를 끄는 소비처에서는 존재 이유도 함께 사라진다.
 * 🛑 `step` 은 섞지 않는다. 2단계에서 문구가 빠질 때 여백까지 걷으면 「다음」을 누른 순간 폼이
 * 위로 튄다 — 빈 상태는 두 단계 모두 같은 자리에 서 있어야 한다.
 */
const EMPTY_STATE_SPACING = 'justify-center min-h-48 gap-6 p-8'

/**
 * Radix `onValueChange` 가 주는 `string` 을 [BoardType] 으로 좁힌다.
 *
 * `as BoardType` 캐스팅으로 대신하면 오타 난 `value` 가 그대로 요청 바디에 실려 400 이 된다 —
 * 화면에서는 아무 일도 안 일어난 것처럼 보인다. 좁히기에 실패하면 선택을 바꾸지 않는다.
 *
 * @param value 라디오가 돌려준 값
 * @returns 알려진 보드 종류이면 true
 */
function isBoardType(value: string): value is BoardType {
  return value === 'SCRUM' || value === 'KANBAN'
}

// ─────────────────────────────────────────────────────────────────────────────
// 종류 라디오 한 줄
// ─────────────────────────────────────────────────────────────────────────────

/** BoardTypeRadio Props */
interface BoardTypeRadioProps {
  /** 이 줄이 나타내는 보드 종류 */
  readonly value: BoardType
}

/**
 * 종류 라디오 한 줄 — 라디오 · 라벨 · 보조 설명.
 *
 * 접근성 이름을 `aria-labelledby` 로 라벨 요소에 건다. `RadioGroupItem` 의 내용은 표시용 원
 * (indicator) 뿐이라 그대로 두면 **이름 없는 라디오**가 되고 스크린리더는 「라디오 버튼」이라고만
 * 읽는다 — `auth/AddAccountDialog.tsx` 가 같은 이유로 항목마다 이름을 건 선례를 따른다.
 * 설명은 `aria-describedby` 로 잇는다. 「스프린트」·「흐름」이 두 종류를 가르는 유일한 판단
 * 근거라 이름만 읽히고 설명이 빠지면 고를 수가 없다.
 *
 * @param value 이 줄이 나타내는 보드 종류
 */
function BoardTypeRadio({ value }: BoardTypeRadioProps): JSX.Element {
  const { label, description } = BOARD_TYPE_TEXT[value]
  const itemId = `board-type-${value.toLowerCase()}`
  const labelId = `${itemId}-label`
  const descriptionId = `${itemId}-description`

  return (
    <div className="flex items-start gap-3 rounded-lg border border-border px-3 py-2.5 transition-colors hover:bg-muted">
      <RadioGroupItem
        id={itemId}
        value={value}
        aria-labelledby={labelId}
        aria-describedby={descriptionId}
        className="mt-0.5"
      />
      <div className="grid gap-0.5">
        <Label id={labelId} htmlFor={itemId} className="cursor-pointer">
          {label}
        </Label>
        <span id={descriptionId} className="text-sm text-muted-foreground">
          {description}
        </span>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** CreateBoardForm Props */
export interface CreateBoardFormProps {
  /** 보드를 추가할 프로젝트 키 */
  projectKey: string
  /**
   * 「보드가 없습니다」 인트로 2줄을 함께 그릴지 여부. 기본 `true`.
   *
   * 이 폼은 빈 상태 전용으로 태어나 인트로를 본문에 갖고 있었는데, 보드가 **있는** 화면의
   * 「새 보드」 다이얼로그가 같은 폼을 재사용하면서 사실이 아닌 문장이 뜨게 됐다.
   * 다이얼로그 쪽에서만 이 값을 `false` 로 내려 인트로를 끈다 — 기본값이 `true` 라
   * 기존 빈 상태 소비처는 호출을 바꿀 필요가 없다.
   *
   * 인트로는 **1단계에만** 붙는다. 「보드를 만들자」는 진입 유도라 이미 만드는 중인 2단계까지
   * 따라오면 화면이 자기 말을 반복한다 — 의도된 동작이다(plan Design 렌즈 D3).
   */
  showEmptyStateIntro?: boolean
  /**
   * 생성이 **성공**했을 때 1회 호출. 기본 없음(no-op).
   *
   * 다이얼로그 소비처가 「이제 닫아도 된다」를 아는 유일한 신호다. 이 신호가 없으면 소비처는
   * 보드 개수 증가로 성공을 추론하게 되는데, 개수는 생성 말고도 변한다 — 목록 refetch 로
   * 남이 만든 보드가 들어오면 입력 중이던 창이 닫히고 사용자는 그것을 「만들어졌다」로 읽는다.
   * 선택적이라 빈 상태 소비처는 호출을 바꿀 필요가 없다(`showEmptyStateIntro` 와 같은 관례).
   */
  onCreated?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// CreateBoardForm 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 생성 폼 — 종류를 먼저 묻고(1단계) 이름을 받는다(2단계).
 *
 * 순서가 계약이다. Jira Cloud 는 보드 생성 모달에서 **J1** *"Create a Scrum board" 또는
 * "Create a Kanban board" 를 고른다* → **J2** *"choose Scrum, enter a board name"* 순으로
 * 묻는다(출처 `support.atlassian.com/jira-software-cloud/docs/create-a-board/` · 조회 2026-09-01).
 * ADR §D4 가 그 순서를 그대로 채택했다.
 *
 * - 1단계. 종류 라디오 2개 + 「다음」. 기본 선택은 칸반([DEFAULT_BOARD_TYPE]).
 * - 2단계. 고른 종류 표시 + 이름 입력 + 「뒤로」/「보드 만들기」.
 * - 성공 시 navigate({ search: { board: newId } })로 생성된 보드로 이동.
 * - 422 AGILE_UNPROCESSABLE → 워크플로우 스킴 미할당 안내 메시지(제출한 2단계에 표시).
 * - 그 외 에러 → toast.error.
 *
 * 단계는 `step` **한 축**으로만 가른다. 이 폼은 빈 상태와 「새 보드」 다이얼로그 둘이 공유하므로
 * 단계별로 컴포넌트를 나누면 소비처가 갈린다.
 *
 * 다시 열면 1단계로 돌아오는 것(E6)은 소비처의 `Dialog` 가 닫힐 때 내용을 **언마운트**하는 데
 * 기댄다 — `step` 초기값이 곧 복귀다. 누군가 `forceMount` 를 붙이면 조용히 깨지므로
 * `CreateBoardForm.test.tsx` 의 재마운트 테스트가 그 가드다.
 *
 * @param projectKey 보드를 추가할 프로젝트 식별 키
 * @param showEmptyStateIntro 「보드가 없습니다」 인트로 노출 여부 (기본 true · 1단계 한정)
 * @param onCreated 생성 성공 신호 — 다이얼로그 소비처가 창을 닫는 근거
 */
export function CreateBoardForm({
  projectKey,
  showEmptyStateIntro = true,
  onCreated,
}: CreateBoardFormProps): JSX.Element {
  const navigate = useNavigate()
  const [step, setStep] = useState<'type' | 'name'>('type')
  const [boardType, setBoardType] = useState<BoardType>(DEFAULT_BOARD_TYPE)
  const [name, setName] = useState('')
  const [unprocessableError, setUnprocessableError] = useState(false)
  const nameInputRef = useRef<HTMLInputElement>(null)

  const { mutate, isPending } = useCreateBoard(projectKey)

  /** 1단계 → 2단계. 고른 종류는 그대로 두고 화면만 넘긴다 */
  function goToNameStep(): void {
    setStep('name')
  }

  /**
   * 2단계 → 1단계. **입력한 이름을 지우지 않는다**(E3).
   *
   * 종류만 갈아끼우러 돌아온 사용자가 이름을 두 번 치게 만들지 않는다.
   */
  function goToTypeStep(): void {
    setStep('type')
  }

  // D2 — 2단계로 넘어가면 이름 입력에 포커스를 준다. 핸들러 안에서는 아직 input 이 DOM 에 없어
  // 잡을 수 없다. 포커스를 옮기지 않으면 사라진 버튼에 포커스가 남아 키보드·스크린리더 사용자는
  // 단계가 바뀐 것을 알지 못한다.
  useEffect(() => {
    if (step === 'name') {
      nameInputRef.current?.focus()
    }
  }, [step])

  function handleSubmit(e: React.FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed === '') return

    setUnprocessableError(false)

    mutate(
      { name: trimmed, boardType },
      {
        onSuccess: (data: BoardCreated) => {
          // 이동보다 먼저 알린다 — 창이 열린 채 뒤에서 화면이 바뀌는 순간을 남기지 않는다.
          onCreated?.()
          void navigate({
            to: '/projects/$projectKey/board',
            params: { projectKey },
            search: { board: data.boardId },
          })
        },
        onError: (err: unknown) => {
          if (err instanceof ApiError) {
            const code = extractErrorCode(err.body)
            if (code === AGILE_UNPROCESSABLE) {
              setUnprocessableError(true)
              return
            }
          }
          toast.error('보드를 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.')
        },
      },
    )
  }

  return (
    <div
      className={cn('flex flex-col items-center', showEmptyStateIntro && EMPTY_STATE_SPACING)}
    >
      {showEmptyStateIntro && step === 'type' && (
        <div className="text-center space-y-2 max-w-sm">
          <p className="text-lg font-medium">보드가 없습니다</p>
          <p className="text-sm text-muted-foreground">
            이 프로젝트에 보드를 만들어 이슈를 칸반 방식으로 관리해 보세요.
          </p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="w-full max-w-sm space-y-3">
        {step === 'type' ? (
          <>
            <div className="space-y-2">
              <p id={TYPE_GROUP_LABEL_ID} className="text-sm font-medium">
                {boardLabels.createForm.typeStep.groupLabel}
              </p>
              <RadioGroup
                value={boardType}
                onValueChange={(value) => {
                  if (isBoardType(value)) setBoardType(value)
                }}
                aria-labelledby={TYPE_GROUP_LABEL_ID}
              >
                {BOARD_TYPE_ORDER.map((type) => (
                  <BoardTypeRadio key={type} value={type} />
                ))}
              </RadioGroup>
            </div>

            {/* type="button" — 1단계에는 제출이 없다. 「보드 만들기」는 2단계 전용 문자열이다 */}
            <Button type="button" onClick={goToNameStep} className="w-full">
              {boardLabels.createForm.next}
            </Button>
          </>
        ) : (
          <>
            {/* D1 — 무엇을 골랐는지 확정 직전에 보여준다. 생성 후에는 종류를 바꿀 수 없다(편차 X3) */}
            <div className="space-y-0.5">
              <p className="text-sm text-muted-foreground">
                {boardLabels.createForm.typeStep.groupLabel}
              </p>
              <p className="text-sm font-medium">{BOARD_TYPE_TEXT[boardType].label}</p>
            </div>

            <div className="space-y-1">
              <Label htmlFor="board-name">보드 이름</Label>
              <Input
                id="board-name"
                ref={nameInputRef}
                value={name}
                onChange={(e) => {
                  setName(e.target.value)
                  setUnprocessableError(false)
                }}
                placeholder={boardLabels.createFormNamePlaceholder}
                disabled={isPending}
                aria-describedby={unprocessableError ? 'board-create-error' : undefined}
              />
            </div>

            {unprocessableError && (
              <p
                id="board-create-error"
                role="alert"
                className="text-sm text-destructive"
              >
                이 프로젝트에 워크플로우 스킴이 할당되지 않아 보드를 만들 수 없습니다.
                먼저 프로젝트 설정에서 워크플로우 스킴을 할당해 주세요.
              </p>
            )}

            <div className="flex gap-2">
              {/* E5 — 진행 중에는 되돌아갈 수 없다. 요청에 실린 종류와 화면의 종류가 갈리면 안 된다 */}
              <Button
                type="button"
                variant="outline"
                onClick={goToTypeStep}
                disabled={isPending}
                className="flex-1"
              >
                {boardLabels.createForm.back}
              </Button>
              <Button type="submit" disabled={isPending} className="flex-1">
                {isPending ? '만드는 중...' : '보드 만들기'}
              </Button>
            </div>
          </>
        )}
      </form>
    </div>
  )
}
