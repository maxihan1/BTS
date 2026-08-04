// 이슈 라벨 칩 편집 컴포넌트 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX, RefObject } from 'react'
import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { LabelChipsEditor } from '@/components/issue/meta/LabelChipsEditor'
import { issueDetailStrings } from '@/i18n/ko'

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
  /** 수정 권한 — false이면 저장 버튼 disabled (fail-closed) */
  canEdit: boolean
  /**
   * 라벨 입력으로 가는 ref — FR-UX-10 F11 단축키 `l`이 여기에 포커스를 준다.
   * 소비처는 routes/issues.$key.tsx(IssueMetaPanel.labelsInputRef 경유).
   *
   * 실제 <input>은 두 단계 아래(LabelChipsEditor → LabelAutocompleteInput)에 있어
   * 그대로 통과시킨다. 이 ref의 유무가 `aria-keyshortcuts` 노출 조건이기도 하다.
   */
  focusRef?: RefObject<HTMLInputElement | null>
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 라벨 칩 편집 컴포넌트 (이슈 상세 전용 — 명시 저장 방식).
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

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setChips(value)
    }
  }, [value])

  return (
    <div className="flex flex-col gap-1.5">
      <LabelChipsEditor value={chips} onChange={setChips} focusRef={focusRef} />

      {/* 저장 버튼 — 이슈 상세는 명시 저장. 생성 폼은 이 래퍼를 쓰지 않는다. */}
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => onSave(chips)}
        disabled={!canEdit}
        aria-label={issueDetailStrings.labelsSaveButton}
        data-testid="labels-save"
      >
        {issueDetailStrings.labelsSaveButton}
      </Button>
    </div>
  )
}
