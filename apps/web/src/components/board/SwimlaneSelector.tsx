// 보드 스윔레인 기준 선택 셀렉터
import type { JSX } from 'react'
import type { SwimlaneField } from '@/api/boards'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { boardLabels } from '@/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 스윔레인 옵션 순서 — 표시 순서 보장용 */
const SWIMLANE_OPTIONS: SwimlaneField[] = ['NONE', 'ASSIGNEE', 'PRIORITY']

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SwimlaneSelector Props */
export interface SwimlaneSelectorProps {
  /** 현재 선택된 스윔레인 기준 */
  value: SwimlaneField
  /** 스윔레인 기준 변경 시 호출되는 콜백 */
  onChange: (field: SwimlaneField) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// SwimlaneSelector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 스윔레인 기준(NONE/ASSIGNEE/PRIORITY)을 선택하는 shadcn Select 래퍼.
 *
 * - value: 현재 서버 저장값(BoardDetail.swimlaneField)을 그대로 받는다.
 * - onChange: 선택 즉시 호출 → 부모에서 PATCH 요청.
 * - 라벨은 board-labels.ts의 swimlane.options에서 가져온다 (i18n 단일 출처).
 *
 * @param value 현재 스윔레인 기준
 * @param onChange 변경 콜백
 */
export function SwimlaneSelector({ value, onChange }: SwimlaneSelectorProps): JSX.Element {
  const { swimlane } = boardLabels

  return (
    <div className="flex items-center gap-2">
      <span className="text-sm font-medium">{swimlane.selectorLabel}</span>
      <Select
        value={value}
        onValueChange={(v) => {
          // Radix Select의 onValueChange는 string을 전달하므로 SwimlaneField로 단언
          // SWIMLANE_OPTIONS 포함 여부를 런타임에 검증해 안전하게 처리
          if ((SWIMLANE_OPTIONS as string[]).includes(v)) {
            onChange(v as SwimlaneField)
          }
        }}
      >
        <SelectTrigger
          className="w-36"
          aria-label={swimlane.selectorLabel}
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {SWIMLANE_OPTIONS.map((field) => (
            <SelectItem key={field} value={field}>
              {swimlane.options[field]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
