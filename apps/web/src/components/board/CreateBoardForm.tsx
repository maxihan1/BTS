// 보드 생성 폼 컴포넌트 — 이름 입력 + 생성 + 422 워크플로우 안내 (FR-BD-01 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { boardLabels } from '@/i18n/board-labels'
import { useCreateBoard } from '@/hooks/use-boards'
import type { BoardCreated } from '@/api/boards'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

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
 * 빈 상태 주 CTA — 보드가 0개일 때 표시되는 첫 보드 생성 폼.
 *
 * - 이름 입력 + 생성 버튼.
 * - 성공 시 navigate({ search: { board: newId } })로 생성된 보드로 이동.
 * - 422 AGILE_UNPROCESSABLE → 워크플로우 스킴 미할당 안내 메시지.
 * - 그 외 에러 → toast.error.
 *
 * @param projectKey 보드를 추가할 프로젝트 식별 키
 * @param showEmptyStateIntro 「보드가 없습니다」 인트로 노출 여부 (기본 true)
 * @param onCreated 생성 성공 신호 — 다이얼로그 소비처가 창을 닫는 근거
 */
export function CreateBoardForm({
  projectKey,
  showEmptyStateIntro = true,
  onCreated,
}: CreateBoardFormProps): JSX.Element {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [unprocessableError, setUnprocessableError] = useState(false)

  const { mutate, isPending } = useCreateBoard(projectKey)

  function handleSubmit(e: React.FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed === '') return

    setUnprocessableError(false)

    mutate(
      { name: trimmed },
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
    <div className="flex flex-col items-center justify-center min-h-48 gap-6 p-8">
      {showEmptyStateIntro && (
        <div className="text-center space-y-2 max-w-sm">
          <p className="text-lg font-medium">보드가 없습니다</p>
          <p className="text-sm text-muted-foreground">
            이 프로젝트에 보드를 만들어 이슈를 칸반 방식으로 관리해 보세요.
          </p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="w-full max-w-sm space-y-3">
        <div className="space-y-1">
          <Label htmlFor="board-name">보드 이름</Label>
          <Input
            id="board-name"
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

        <Button type="submit" disabled={isPending} className="w-full">
          {isPending ? '만드는 중...' : '보드 만들기'}
        </Button>
      </form>
    </div>
  )
}
