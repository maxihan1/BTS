// 보드 컬럼 · 백로그 섹션 끝의 한 줄 이슈 생성 — 「+ 만들기」 → 유형 · 요약 · Enter 연속 입력 (JB-B-1 · JB-L-1)
import type { JSX, KeyboardEvent } from 'react'
import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { createIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { issueCreateStrings } from '@/i18n/ko'

interface InlineCreateIssueProps {
  projectKey: string
  /** 「+ 만들기」 버튼의 접근성 이름 — 화면에 여러 개라 칸마다 달라야 한다 */
  openLabel: string
  placeholder: string
  /** 기본 유형 키 — Jira 는 보드 = 스토리, 백로그 = 작업 */
  defaultTypeKey: string
  /** 만든 뒤 그 칸에 놓기(스프린트 배정 · 컬럼 전환). 실패 안내는 호출부가 맡는다(부분 성공 = 경고 톤). */
  onCreated: (issue: IssueResponse) => void
}

/**
 * 칸 끝의 한 줄 생성 행.
 *
 * 유형은 표준 계층(0)만 — 에픽(1)과 하위 작업(-1)은 뺀다(하위 작업은 부모가 필요하다, JA-C-4).
 * Enter 로 만들고 **열린 채** 요약만 비운다(연속 입력). Esc 로 닫는다.
 */
export function InlineCreateIssue({
  projectKey,
  openLabel,
  placeholder,
  defaultTypeKey,
  onCreated,
}: InlineCreateIssueProps): JSX.Element {
  const { data: allTypes = [] } = useIssueTypes()
  const [open, setOpen] = useState(false)
  const [summary, setSummary] = useState('')
  const [typeId, setTypeId] = useState<number | null>(null)

  const types = allTypes.filter((t) => (t.hierarchyLevel ?? 0) === 0)
  const selectedTypeId = typeId ?? (types.find((t) => t.key === defaultTypeKey) ?? types[0])?.id

  const create = useMutation({
    mutationFn: (title: string) => createIssue({ projectKey, summary: title, typeId: selectedTypeId }),
    // 🛑 놓기는 생성 성공 **뒤** 따로 — 놓기 실패가 「만들지 못했습니다」로 읽히면 사용자가 다시 만들어 중복이 생긴다.
    onSuccess: (issue) => {
      setSummary('')
      onCreated(issue)
    },
    onError: () => toast.error(issueCreateStrings.inlineCreateFailed),
  })

  function submit() {
    const title = summary.trim()
    if (title !== '' && !create.isPending) create.mutate(title)
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      e.stopPropagation()
      setOpen(false)
      setSummary('')
      return
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return
    e.preventDefault()
    submit()
  }

  if (!open) {
    return (
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="w-full justify-start border border-dashed border-border text-muted-foreground"
        aria-label={openLabel}
        onClick={() => setOpen(true)}
      >
        {issueCreateStrings.inlineOpen}
      </Button>
    )
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-primary bg-background p-2">
      <Input
        autoFocus
        aria-label={issueCreateStrings.inlineSummaryLabel}
        placeholder={placeholder}
        value={summary}
        // disabled 는 포커스를 빼앗아 연속 입력이 끊긴다 — readOnly 로 막는다.
        readOnly={create.isPending}
        onChange={(e) => setSummary(e.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="flex items-center gap-2">
        <select
          aria-label={issueCreateStrings.inlineTypeLabel}
          value={selectedTypeId ?? ''}
          onChange={(e) => setTypeId(Number(e.target.value))}
          className="h-7 rounded-md border border-input bg-transparent px-1.5 text-xs"
        >
          {types.map((t) => (
            <option key={t.id} value={t.id}>{t.name}</option>
          ))}
        </select>
        <div className="flex-1" />
        <Button type="button" size="sm" disabled={summary.trim() === '' || create.isPending} onClick={submit}>
          {issueCreateStrings.inlineSubmit}
        </Button>
      </div>
    </div>
  )
}
