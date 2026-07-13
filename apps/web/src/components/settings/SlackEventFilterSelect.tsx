// Slack 채널 매핑 이벤트 필터 다중선택 컴포넌트 — 그룹(이슈/스프린트/자동화)별 체크박스 (FR-SL-06 D6 Task 2)
import type { JSX } from 'react'
import type { SlackEventFilterGroup } from '@/lib/slack-event-types'
import { SLACK_EVENT_TYPE_CATALOG, SLACK_EVENT_FILTER_GROUPS } from '@/lib/slack-event-types'

/** SlackEventFilterSelect props */
export interface SlackEventFilterSelectProps {
  /** 현재 선택된 이벤트 wireValue 목록 — props 파생, 내부 useState로 복제하지 않는다(controlled) */
  readonly value: string[]
  /** 선택 변경 콜백 — 토글 후의 다음 wireValue 배열을 전달한다 */
  readonly onChange: (next: string[]) => void
  /** 비활성화 여부 — true이면 모든 체크박스를 disabled 처리한다(fail-closed 게이트는 상위에서 결정) */
  readonly disabled?: boolean
}

/**
 * Slack 채널 매핑 `event_filter` 다중선택 컴포넌트.
 *
 * - {@link SLACK_EVENT_TYPE_CATALOG} 10종을 {@link SLACK_EVENT_FILTER_GROUPS} 순서대로
 *   그룹(이슈/스프린트/자동화) `<fieldset>`으로 렌더한다 — `<legend>`가 접근성 group 이름이 된다.
 * - 완전 controlled — 선택 상태는 `value` prop이 유일한 진실 출처이며, 변경은 `onChange`로만
 *   상위에 통지한다(내부 useState 없음, `ComponentMultiSelect` 선례 동형).
 * - `disabled=true`이면 모든 체크박스를 비활성화하고 클릭도 무시한다.
 */
export function SlackEventFilterSelect({
  value,
  onChange,
  disabled = false,
}: SlackEventFilterSelectProps): JSX.Element {
  function handleToggle(wireValue: string): void {
    if (value.includes(wireValue)) {
      onChange(value.filter((selected) => selected !== wireValue))
    } else {
      onChange([...value, wireValue])
    }
  }

  return (
    <div className="space-y-4">
      {SLACK_EVENT_FILTER_GROUPS.map((group) => (
        <SlackEventFilterGroupFieldset
          key={group}
          group={group}
          selected={value}
          onToggle={handleToggle}
          disabled={disabled}
        />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 단위 fieldset — legend 텍스트가 곧 getByRole('group', { name }) 접근성 이름이 된다
// ─────────────────────────────────────────────────────────────────────────────

interface SlackEventFilterGroupFieldsetProps {
  readonly group: SlackEventFilterGroup
  readonly selected: string[]
  readonly onToggle: (wireValue: string) => void
  readonly disabled: boolean
}

/** 단일 그룹(이슈/스프린트/자동화)에 속한 이벤트 체크박스 목록을 렌더하는 fieldset. */
function SlackEventFilterGroupFieldset({
  group,
  selected,
  onToggle,
  disabled,
}: SlackEventFilterGroupFieldsetProps): JSX.Element {
  const options = SLACK_EVENT_TYPE_CATALOG.filter((option) => option.group === group)

  return (
    <fieldset className="space-y-1.5">
      <legend className="text-sm font-medium">{group}</legend>
      {options.map((option) => (
        <label key={option.wireValue} className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={selected.includes(option.wireValue)}
            onChange={() => { onToggle(option.wireValue) }}
            disabled={disabled}
          />
          <span>{option.label}</span>
        </label>
      ))}
    </fieldset>
  )
}
