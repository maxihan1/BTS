// 이슈 보안등급 셀렉터 컴포넌트 — IssuePrioritySelect 동형, native select — FR-PM-06 PR-B
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchProjectSecurityLevels } from '@/api/security-levels'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueSecurityLevelSelect props */
export interface IssueSecurityLevelSelectProps {
  /** 이슈가 속한 프로젝트 키 — 등급 목록 조회에 사용 */
  projectKey: string
  /**
   * 현재 선택된 등급 UUID.
   * null이면 "선택 안 함" 상태.
   * props 파생 — useState 초기화 금지 (stale key prop 회귀 방지)
   */
  value: string | null
  /**
   * 변경 콜백.
   * UUID 선택 시 해당 UUID, "선택 안 함" 선택 시 null을 전달한다.
   */
  onChange: (levelId: string | null) => void
  /** 비활성 여부 — true이면 select disabled (권한 없음 등) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 보안등급 셀렉터 컴포넌트.
 *
 * - projectKey로 fetchProjectSecurityLevels를 호출해 옵션 목록을 로드한다
 * - 첫 옵션: "선택 안 함" (value="") — 선택 시 onChange(null) 호출
 * - 등급 목록: 각 등급 UUID를 value로 — 선택 시 onChange(uuid) 호출
 * - 스킴 미적용 프로젝트는 "선택 안 함"만 표시 (빈 배열 = 빈 목록)
 * - WCAG AA: min-h-[44px], aria-label
 * - DESIGN.md 네이티브 select 스타일: w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px]
 */
export function IssueSecurityLevelSelect({
  projectKey,
  value,
  onChange,
  disabled = false,
}: IssueSecurityLevelSelectProps): JSX.Element {
  const { data: levels = [] } = useQuery({
    queryKey: ['project-security-levels', projectKey],
    queryFn: () => fetchProjectSecurityLevels(projectKey),
    staleTime: 60_000,
    enabled: projectKey.trim() !== '',
  })

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selected = e.target.value
    onChange(selected === '' ? null : selected)
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
      value={value ?? ''}
      onChange={handleChange}
      disabled={disabled}
      aria-label={issueDetailStrings.securityLevelSelectLabel}
    >
      <option value="">{issueDetailStrings.securityLevelNone}</option>
      {levels.map((level) => (
        <option key={level.id} value={level.id}>
          {level.name}
        </option>
      ))}
    </select>
  )
}
