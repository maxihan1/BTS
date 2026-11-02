// 이슈 라벨 칩 편집 컴포넌트 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX, RefObject } from 'react'
import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { LabelChipsEditor } from '@/components/issue/meta/LabelChipsEditor'
import { META_SELECT_CLASS } from '@/components/issue/meta/meta-row-class'
import { type FocusHandle, useInlineEdit } from '@/components/issue/meta/use-inline-edit'
import { issueDetailStrings } from '@/i18n/ko'
import { cn } from '@/lib/utils'

// LabelChip 은 LabelChipsEditor 로 이동했다. 기존 import 경로를 깨지 않도록 재수출한다.
export { LabelChip } from '@/components/issue/meta/LabelChipsEditor'
export type { LabelChipProps } from '@/components/issue/meta/LabelChipsEditor'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueLabelsEdit props */
export interface IssueLabelsEditProps {
  /** 현재 라벨 목록 — issue.labels props 파생 */
  value: string[]
  /** 저장 콜백 — 편집된 labels 배열 전달 */
  onSave: (labels: string[]) => void
  /** 수정 권한 — false이면 칸이 열리지 않는다 (fail-closed) */
  canEdit: boolean
  /**
   * 단축키 손잡이 — FR-UX-10 F11 단축키 `l`. 접힌 상태면 편집을 연 뒤 라벨 입력에 포커스한다.
   *
   * 소비처는 routes/issues.$key.tsx(IssueMetaPanel.labelsInputRef 경유).
   * 실제 <input>은 두 단계 아래(LabelChipsEditor → LabelAutocompleteInput)에 있고,
   * `aria-keyshortcuts` 조건부 부착 규칙도 그 최하단이 소유한다(계약 본문은 거기 참조).
   */
  focusRef?: RefObject<FocusHandle | null>
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 라벨 칩 편집 컴포넌트 (이슈 상세 전용 — 「누르면 편집」 + 명시 저장 방식, JV-17).
 *
 * 평소엔 칩만 보이고(없으면 「라벨 추가」), 누르면 칩 편집기 + 취소 · 저장이 열린다.
 * 저장 · 취소 · Esc 로 닫힌다. 바깥 클릭으로는 닫지 않는다 — 고치던 칩을 말없이 잃거나 저장하지 않게.
 *
 * 칩 편집 자체는 [LabelChipsEditor] 가 맡고, 이 컴포넌트는 **로컬 편집 상태 + 저장 버튼**만
 * 얹는다. 생성 폼(FR-UX-09 F2)은 저장 버튼 없이 [LabelChipsEditor] 를 직접 쓴다.
 *
 * - 로컬 상태로 편집(추가/삭제), 저장 버튼 클릭 시 onSave 호출
 * - issue.labels props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - 추가 시 클라이언트 검증: 공백 trim, 50자 초과 거부, 20개 초과 거부, 중복 거부
 *   (검증 본체는 [LabelChipsEditor] 소유)
 * - WCAG AA: aria-label
 */
export function IssueLabelsEdit({
  value,
  onSave,
  canEdit,
  focusRef,
}: IssueLabelsEditProps): JSX.Element {
  const [chips, setChips] = useState<string[]>(value)
  // 닫히면 고치던 칩을 버린다 — 저장했으면 refetch 된 value 가 아래 동기화로 다시 들어온다
  const edit = useInlineEdit({ focusRef, canEdit, closeOnOutsideClick: false, onClose: () => setChips(value) })
  // LabelAutocompleteInput 은 ref 유무로 `aria-keyshortcuts="l"` 을 붙인다 — 손잡이가 있을 때만 넘긴다
  const shortcutMarkRef = useRef<HTMLInputElement>(null)

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setChips(value)
    }
  }, [value])

  if (!edit.isOpen) {
    return (
      <Button
        ref={edit.triggerRef}
        type="button"
        variant="ghost"
        onClick={edit.open}
        disabled={!canEdit}
        aria-label={`${issueDetailStrings.labelsLabel} ${value.length > 0 ? value.join(', ') : issueDetailStrings.labelAddPlaceholder}`}
        aria-keyshortcuts={focusRef !== undefined ? 'l' : undefined}
        className={cn(
          META_SELECT_CLASS,
          'h-auto min-h-8 flex-wrap justify-start gap-1 py-1 font-normal whitespace-normal md:h-auto disabled:opacity-100',
        )}
        data-testid="labels-edit-trigger"
      >
        {value.length === 0 ? (
          <span className="text-muted-foreground">{issueDetailStrings.labelAddPlaceholder}</span>
        ) : (
          value.map((label) => (
            <span key={label} className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium">
              {label}
            </span>
          ))
        )}
      </Button>
    )
  }

  return (
    <div className="flex flex-col gap-1.5" {...edit.containerProps}>
      <LabelChipsEditor
        value={chips}
        onChange={setChips}
        focusRef={focusRef !== undefined ? shortcutMarkRef : undefined}
      />

      {/* 취소 · 저장 — 이슈 상세는 명시 저장. 생성 폼은 이 래퍼를 쓰지 않는다. */}
      <div className="flex justify-end gap-1">
        <Button
          variant="ghost"
          size="xs"
          className="md:min-h-0 max-md:min-h-[44px]"
          onClick={() => edit.close(true)}
          data-testid="labels-cancel"
        >
          {issueDetailStrings.labelsCancelButton}
        </Button>
        <Button
          variant="ghost"
          size="xs"
          className="md:min-h-0 max-md:min-h-[44px]"
          onClick={() => {
            onSave(chips)
            edit.close(true)
          }}
          disabled={!canEdit}
          aria-label={issueDetailStrings.labelsSaveButton}
          data-testid="labels-save"
        >
          {issueDetailStrings.labelsSaveButton}
        </Button>
      </div>
    </div>
  )
}
