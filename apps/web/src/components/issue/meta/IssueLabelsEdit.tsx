// 이슈 라벨 칩 편집 컴포넌트 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 라벨 최대 개수 */
const MAX_LABELS = 20
/** 라벨 최대 길이 */
const MAX_LABEL_LENGTH = 50

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
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 라벨 칩 편집 컴포넌트.
 *
 * - 로컬 상태로 편집(추가/삭제), 저장 버튼 클릭 시 onSave 호출
 * - issue.labels props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - 추가 시 클라이언트 검증: 공백 trim, 50자 초과 거부, 20개 초과 거부, 중복 거부
 * - WCAG AA: aria-label
 */
export function IssueLabelsEdit({ value, onSave, canEdit }: IssueLabelsEditProps): JSX.Element {
  const [chips, setChips] = useState<string[]>(value)
  const [inputValue, setInputValue] = useState('')

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setChips(value)
    }
  }, [value])

  /**
   * 라벨 확정 핸들러 — LabelAutocompleteInput onCommit에서 호출.
   * trim, 길이, 개수, 중복 검증 후 칩 추가.
   * label은 LabelAutocompleteInput이 trim 완료한 값이다.
   */
  function handleCommitLabel(label: string) {
    const trimmed = label.trim()
    if (trimmed === '') return
    if (trimmed.length > MAX_LABEL_LENGTH) return
    if (chips.length >= MAX_LABELS) return
    if (chips.includes(trimmed)) return
    setChips((prev) => [...prev, trimmed])
    setInputValue('')
  }

  function removeLabel(label: string) {
    setChips((prev) => prev.filter((c) => c !== label))
  }

  const isAtMax = chips.length >= MAX_LABELS

  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 라벨 칩 목록 */}
      {chips.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {chips.map((chip) => (
            <LabelChip key={chip} label={chip} onRemove={() => removeLabel(chip)} />
          ))}
        </div>
      )}

      {/* 라벨 추가 입력 — LabelAutocompleteInput으로 자동완성 지원 */}
      <LabelAutocompleteInput
        value={inputValue}
        onChange={setInputValue}
        onCommit={handleCommitLabel}
        disabled={isAtMax}
        existingLabels={chips}
        placeholder={issueDetailStrings.labelAddPlaceholder}
      />

      {/* 저장 버튼 */}
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

// ─────────────────────────────────────────────────────────────────────────────
// LabelChip — 라벨 칩 개별 아이템
// ─────────────────────────────────────────────────────────────────────────────

/** LabelChip props */
export interface LabelChipProps {
  /** 라벨 텍스트 */
  label: string
  /** 제거 콜백 */
  onRemove: () => void
}

/**
 * 라벨 칩 컴포넌트.
 * - 라벨 텍스트 + 제거 버튼
 * - WCAG AA: aria-label on remove button
 */
export function LabelChip({ label, onRemove }: LabelChipProps): JSX.Element {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium">
      {label}
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        onClick={onRemove}
        aria-label={issueDetailStrings.labelRemoveLabel}
        className="ml-0.5 rounded-full hover:bg-muted-foreground/20"
      >
        ×
      </Button>
    </span>
  )
}
