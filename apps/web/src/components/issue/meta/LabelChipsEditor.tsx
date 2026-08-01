// 라벨 칩 편집기 — 저장 버튼 없는 순수 제어 컴포넌트 (FR-UX-09 F2)
import type { JSX } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 백엔드 CreateIssueRequest.isLabelsValid(@AssertTrue) 와 짝
// ─────────────────────────────────────────────────────────────────────────────

/** 라벨 최대 개수 */
export const MAX_LABELS = 20
/** 라벨 최대 길이 */
export const MAX_LABEL_LENGTH = 50

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** LabelChipsEditor props */
export interface LabelChipsEditorProps {
  /** 현재 라벨 목록 (controlled) */
  value: string[]
  /** 변경 콜백 — 추가·제거 즉시 발화한다 (저장 버튼 없음) */
  onChange: (labels: string[]) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 라벨 칩 편집기.
 *
 * `IssueLabelsEdit` 에서 **칩 편집 부분만** 떼어낸 컴포넌트다.
 * 이슈 상세는 「편집 후 저장 버튼」 이지만 생성 폼은 「폼 제출 전까지 로컬 상태」라,
 * 저장 버튼을 가진 채로 생성 폼에 넣으면 **같은 화면에 `저장` 과 `만들기` 가 공존**해
 * 사용자가 혼란스럽고 E2E 의 이름 기반 셀렉터가 strict mode 로 깨진다
 * (learnings 2026-05-31 — 같은 화면에 '저장' 버튼이 늘어 기존 E2E 가 깨진 사고).
 *
 * - 추가/제거 시 `onChange` 를 **즉시** 호출한다.
 * - 클라이언트 검증 4종. 공백 trim · 50자 초과 거부 · 20개 초과 거부 · 중복 거부.
 * - WCAG AA. 제거 버튼에 `aria-label`.
 */
export function LabelChipsEditor({ value, onChange }: LabelChipsEditorProps): JSX.Element {
  const [inputValue, setInputValue] = useState('')

  /**
   * 라벨 확정 핸들러 — LabelAutocompleteInput onCommit 에서 호출.
   * trim, 길이, 개수, 중복 검증 후 추가.
   * label 은 LabelAutocompleteInput 이 trim 완료한 값이다.
   */
  function handleCommitLabel(label: string): void {
    const trimmed = label.trim()
    if (trimmed === '') return
    if (trimmed.length > MAX_LABEL_LENGTH) return
    if (value.length >= MAX_LABELS) return
    if (value.includes(trimmed)) return
    onChange([...value, trimmed])
    setInputValue('')
  }

  function removeLabel(label: string): void {
    onChange(value.filter((c) => c !== label))
  }

  const isAtMax = value.length >= MAX_LABELS

  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 라벨 칩 목록 */}
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {value.map((chip) => (
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
        existingLabels={value}
        placeholder={issueDetailStrings.labelAddPlaceholder}
      />
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
