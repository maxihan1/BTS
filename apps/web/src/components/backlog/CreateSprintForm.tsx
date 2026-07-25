// 스프린트 생성 폼 컴포넌트 — 이름 필수, 제출 시 useCreateSprint 호출 (FR-BL-02 D6/D7)
import { useState } from 'react'
import type { JSX, FormEvent } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { backlogLabels } from '@/i18n/backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** CreateSprintForm 컴포넌트 Props */
export interface CreateSprintFormProps {
  /** 프로젝트 키 — 생성 시 백엔드에 전달 */
  projectKey: string
  /** 제출 콜백. 이름을 받아 useCreateSprint.mutate를 호출하는 로직을 부모가 주입 */
  onSubmit: (name: string) => void
  /** 폼 비활성화 여부 (권한 없음 또는 mutation 진행 중) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 생성 폼.
 *
 * - 이름 입력(필수) + 생성 버튼으로 구성된 단순 인라인 폼.
 * - 이름이 비어 있으면 제출을 막는다.
 * - 제출 성공 후 입력 필드를 초기화한다.
 */
export function CreateSprintForm({ onSubmit, disabled = false }: CreateSprintFormProps): JSX.Element {
  const [name, setName] = useState('')

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length === 0) return
    onSubmit(trimmed)
    setName('')
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="flex items-center gap-2"
      aria-label={backlogLabels.createSprintFormLabel}
    >
      <input
        type="text"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder={backlogLabels.sprintNamePlaceholder}
        disabled={disabled}
        className={cn(
          'flex-1 rounded-md border border-border bg-background px-3 py-1.5 text-sm',
          'placeholder:text-muted-foreground',
          'focus:outline-none focus:ring-2 focus:ring-primary',
          disabled && 'cursor-not-allowed opacity-50',
        )}
        aria-label={backlogLabels.sprintNamePlaceholder}
      />
      {/* PR22 — type="submit" verbatim 유지. type="button" 으로 바꾸면 폼 전송이 죽는다.
          disabled 시 opacity/cursor 조건부 클래스는 프리미티브의 disabled:opacity-50 +
          disabled:pointer-events-none 이 같은 조건으로 대체한다. */}
      <Button
        type="submit"
        variant="default"
        size="default"
        disabled={disabled || name.trim().length === 0}
        className="rounded-md"
        aria-label={backlogLabels.createSprint}
      >
        {backlogLabels.createSprint}
      </Button>
    </form>
  )
}
