// 전환 화면 대화상자 — 전환마다 정의된 해결 결과 · 담당자 · 코멘트를 받고 필수는 별표로 표시한다 (FR-WF-01 D11)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { useResolutions } from '@/hooks/use-resolutions'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useDebounce } from '@/hooks/use-debounce'
import { resolutionLabels } from '@/i18n/resolution-labels'
import type { TransitionScreenField, TransitionScreenValues } from '@/api/issues'
import { initialScreenForm, isScreenComplete, toScreenValues } from '@/lib/transition-screen'
import type { TransitionScreenForm, TransitionScreenPrefill } from '@/lib/transition-screen'

/** 담당자 검색 debounce — 이슈 생성 폼(`use-assignee-picker`)과 같은 값 */
const ASSIGNEE_SEARCH_DEBOUNCE_MS = 300

/** 화면 필드 키 → 라벨. 그리는 순서는 서버가 정한 고정 순서(해결 결과 → 담당자 → 코멘트)를 따른다. */
const FIELD_LABELS: Record<string, string> = { resolution: '해결 결과', assignee: '담당자', comment: '코멘트' }

/** 라벨 + 필수 별표 */
function FieldLabel({ htmlFor, field, required }: { htmlFor?: string; field: string; required: boolean }): JSX.Element {
  return (
    <label htmlFor={htmlFor} className="text-sm font-medium mb-1 block">
      {FIELD_LABELS[field] ?? field}
      {required && (
        <span className="ml-0.5 text-destructive" aria-label="필수">
          *
        </span>
      )}
    </label>
  )
}

/** 담당자 칸 — 사용자 조회 훅을 이 칸이 화면에 있을 때만 돌리려고 따로 둔다 */
function ScreenAssigneeField({
  assigneeId,
  onChange,
}: {
  assigneeId: string | null
  onChange: (id: string) => void
}): JSX.Element {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebounce(query, ASSIGNEE_SEARCH_DEBOUNCE_MS)
  const { data: found = [] } = useUsers(debouncedQuery)
  // 표시용 담당자는 id 로 따로 읽는다 — 검색 결과에서 찾으면 검색어가 바뀌는 순간 「미지정」으로 뒤집힌다(C1).
  const { data: current = [] } = useUsersByIds(assigneeId !== null ? [assigneeId] : [])

  return (
    <IssueAssigneeSelect
      value={assigneeId}
      currentAssignee={current.find((u) => u.id === assigneeId) ?? null}
      users={debouncedQuery.trim() === '' ? [] : found}
      onSearch={setQuery}
      // 해제 버튼을 숨겼으므로 null 은 오지 않는다 — 와도 무시한다(null = 그대로 둔다가 서버 계약)
      onAssigneeChange={(id) => id !== null && onChange(id)}
      canEdit
      allowClear={false}
    />
  )
}

/** TransitionScreenFields props */
export interface TransitionScreenFieldsProps {
  screen: TransitionScreenField[]
  form: TransitionScreenForm
  onChange: (form: TransitionScreenForm) => void
}

/**
 * 전환 화면의 입력 칸들. 대화상자와 일괄 전환 대화상자가 함께 쓴다.
 * 모르는 필드 키는 그리지 않는다 — 서버 목록이 늘어도 화면이 깨지지 않게.
 */
export function TransitionScreenFields({ screen, form, onChange }: TransitionScreenFieldsProps): JSX.Element {
  const { data: resolutions = [], isLoading } = useResolutions()

  return (
    <div className="space-y-4">
      {screen.map(({ field, required }) => {
        switch (field) {
          case 'resolution':
            return (
              <div key={field}>
                <FieldLabel htmlFor="screen-resolution" field={field} required={required} />
                {isLoading ? (
                  <p className="text-sm text-muted-foreground py-2" aria-live="polite">
                    결의안 목록을 불러오는 중...
                  </p>
                ) : (
                  <Select value={form.resolutionId} onValueChange={(v) => onChange({ ...form, resolutionId: v })}>
                    <SelectTrigger id="screen-resolution" className="w-full" aria-label="해결 결과 선택">
                      <SelectValue placeholder={resolutionLabels.selectPlaceholder} />
                    </SelectTrigger>
                    <SelectContent>
                      {resolutions.map((r) => (
                        <SelectItem key={r.id} value={r.id}>
                          {r.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </div>
            )
          case 'assignee':
            return (
              <div key={field}>
                <FieldLabel field={field} required={required} />
                <ScreenAssigneeField
                  assigneeId={form.assigneeId}
                  onChange={(id) => onChange({ ...form, assigneeId: id })}
                />
              </div>
            )
          case 'comment':
            return (
              <div key={field}>
                <FieldLabel htmlFor="screen-comment" field={field} required={required} />
                <Textarea
                  id="screen-comment"
                  value={form.comment}
                  onChange={(e) => onChange({ ...form, comment: e.target.value })}
                  rows={4}
                />
              </div>
            )
          default:
            return null
        }
      })}
    </div>
  )
}

/** TransitionScreenDialog props */
export interface TransitionScreenDialogProps {
  /** 대화상자 열림 여부 */
  open: boolean
  /** 전환 이름 — 제목과 확인 버튼에 쓴다(Jira 와 같다) */
  transitionName: string
  /** 전환 화면 필드 */
  screen: TransitionScreenField[]
  /** 이슈의 현재 값. 일괄 전환처럼 이슈가 여럿이면 둘 다 null. */
  prefill: TransitionScreenPrefill
  /** 확인 — 화면에 있는 칸의 값만 싣는다 */
  onConfirm: (values: TransitionScreenValues) => void
  /** 취소 */
  onCancel: () => void
}

/**
 * 전환 화면 대화상자.
 *
 * 화면이 있는 전환을 고르면 호출부가 연다. 필수 칸이 비어 있으면 확인 버튼이 꺼진다 — 서버도 같은 규칙으로
 * 409 를 낸다. 하드코딩돼 있던 DONE 해결 모달(`ResolutionModal` · `ResolutionPickerModal`)을 대체한다.
 */
export function TransitionScreenDialog({
  open,
  transitionName,
  screen,
  prefill,
  onConfirm,
  onCancel,
}: TransitionScreenDialogProps): JSX.Element {
  const [form, setForm] = useState<TransitionScreenForm>(() => initialScreenForm(prefill))

  // 열릴 때마다 이슈의 현재 값으로 다시 채운다 — 같은 인스턴스가 재사용될 때 이전 입력이 남지 않게.
  // 의존성은 원시값이다 — 호출부가 prefill 객체를 렌더마다 새로 만들어도 입력 중에 지워지지 않는다.
  const { resolutionId, assigneeId } = prefill
  useEffect(() => {
    if (open) setForm(initialScreenForm({ resolutionId, assigneeId }))
  }, [open, resolutionId, assigneeId])

  const canConfirm = isScreenComplete(screen, form)

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{transitionName}</DialogTitle>
          <DialogDescription>
            {screen.some((f) => f.required) ? '* 표시는 채워야 전환할 수 있습니다.' : '전환과 함께 저장할 값을 입력하세요.'}
          </DialogDescription>
        </DialogHeader>

        <TransitionScreenFields screen={screen} form={form} onChange={setForm} />

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onCancel}>
            취소
          </Button>
          <Button size="sm" disabled={!canConfirm} onClick={() => onConfirm(toScreenValues(screen, form))}>
            {transitionName}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
