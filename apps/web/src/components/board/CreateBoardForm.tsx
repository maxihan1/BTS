// 보드 생성 폼 컴포넌트 — 이름 입력 + 생성 + 422 워크플로우 안내 (FR-BD-01 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
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
 */
export function CreateBoardForm({ projectKey }: CreateBoardFormProps): JSX.Element {
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
      <div className="text-center space-y-2 max-w-sm">
        <p className="text-lg font-medium">보드가 없습니다</p>
        <p className="text-sm text-muted-foreground">
          이 프로젝트에 보드를 만들어 이슈를 칸반 방식으로 관리해 보세요.
        </p>
      </div>

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
            placeholder="스프린트 보드"
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
