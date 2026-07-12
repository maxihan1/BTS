// 프로젝트 멤버 선택 드롭다운 — ASSIGN 담당자/rule actor 공용 피커 (FR-AT-02 D6 Task 3)
import type { ChangeEvent, JSX } from 'react'
import { useProjectMembers } from '@/hooks/use-project-members'
import type { ProjectMember } from '@/api/project-members'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — HTML select는 문자열 value만 허용하므로 null(해제/미설정)을 나타낼 sentinel이 필요하다
// ─────────────────────────────────────────────────────────────────────────────

/** "담당자 해제" 선택(value=null)을 나타내는 sentinel — 실제 userId(UUID)와 충돌하지 않는다 */
const UNASSIGN_VALUE = '__unassign__'
/** allowUnassign=false에서 value=null(미설정)을 나타내는 placeholder 값 */
const PLACEHOLDER_VALUE = ''

/** 컴포넌트 내 고정 한국어 문구 (automation BC 관례 — i18n 미도입) */
const TEXT = {
  loading: '멤버 목록을 불러오는 중...',
  error: '멤버 목록을 불러오지 못했습니다.',
  unassign: '담당자 해제',
  unset: '선택 안 함',
  unknown: '알 수 없는 사용자',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 멤버 표시명을 해석한다 — displayName → username → userId 순 fallback */
function resolveMemberLabel(member: ProjectMember): string {
  return member.displayName ?? member.username ?? member.userId
}

/**
 * 현재 value를 select의 selected value 문자열로 변환한다.
 * value=null은 allowUnassign 여부에 따라 sentinel(UNASSIGN_VALUE) 또는 placeholder로 매핑한다.
 */
function resolveSelectedValue(value: string | null, allowUnassign: boolean): string {
  if (value !== null) return value
  return allowUnassign ? UNASSIGN_VALUE : PLACEHOLDER_VALUE
}

/**
 * value가 멤버 목록에 없는(EC4) "알 수 없는 사용자" 케이스인지 판정하고, 그 값을 반환한다.
 * 목록에 있거나 value가 null이면 null을 반환한다 — 임의 옵션을 추가하지 않는다.
 */
function resolveUnknownValue(value: string | null, knownIds: ReadonlySet<string>): string | null {
  if (value === null || knownIds.has(value)) return null
  return value
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ProjectMemberSelect props */
export interface ProjectMemberSelectProps {
  /** 멤버 목록을 조회할 프로젝트 식별 키 */
  readonly projectKey: string
  /** 현재 선택된 사용자 ID. null=미선택 또는 담당자 해제 */
  readonly value: string | null
  /** 선택 변경 콜백 — userId 또는 null(해제/미설정) */
  readonly onChange: (userId: string | null) => void
  /** true면 "담당자 해제"(value=null) 옵션을 노출한다 (ASSIGN 액션 전용, EC3) */
  readonly allowUnassign?: boolean
  /** select에 연결할 라벨 텍스트 */
  readonly label?: string
  /** select id — 한 화면에 여러 인스턴스를 렌더할 때 고유하게 지정한다 */
  readonly id?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// select 공통 셸 — 로딩/에러 상태에서 재사용
// ─────────────────────────────────────────────────────────────────────────────

interface DisabledSelectShellProps {
  readonly selectId: string
  readonly label: string
  readonly optionText: string
}

function DisabledSelectShell({ selectId, label, optionText }: DisabledSelectShellProps): JSX.Element {
  return (
    <select
      id={selectId}
      aria-label={label}
      disabled
      className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm text-muted-foreground disabled:opacity-50 disabled:cursor-not-allowed"
    >
      <option>{optionText}</option>
    </select>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 선택 드롭다운.
 *
 * `use-project-members`로 멤버 목록을 조회해 select 옵션으로 렌더한다.
 * ASSIGN 액션의 담당자 피커와 자동화 룰의 실행 주체(actor) 피커가 이 컴포넌트를 공유한다.
 *
 * - `allowUnassign=true`면 "담당자 해제"(value=null) 옵션을 추가로 노출한다(EC3, ASSIGN 전용).
 *   `allowUnassign`이 없으면(actor 용도) value=null은 미설정을 뜻하며, 그 문구/기본 처리는
 *   호출부(폼)가 담당한다 — 이 컴포넌트는 placeholder 옵션만 렌더한다.
 * - `value`가 현재 멤버 목록에 없으면(예: 프로젝트에서 제외된 멤버) "알 수 없는 사용자"로
 *   표시하되 값은 그대로 보존한다 — 임의로 다른 값으로 정정하지 않는다(EC4).
 * - 로딩/에러 상태는 select를 비활성화하고 안내 문구를 보여준다(빈 목록으로 은폐하지 않음).
 */
export function ProjectMemberSelect({
  projectKey,
  value,
  onChange,
  allowUnassign = false,
  label = '담당자',
  id,
}: ProjectMemberSelectProps): JSX.Element {
  const { data: members, isLoading, isError } = useProjectMembers(projectKey)
  const selectId = id ?? 'project-member-select'

  function handleChange(event: ChangeEvent<HTMLSelectElement>): void {
    const raw = event.target.value
    if (raw === UNASSIGN_VALUE || raw === PLACEHOLDER_VALUE) {
      onChange(null)
      return
    }
    onChange(raw)
  }

  if (isLoading) {
    return (
      <div>
        <label htmlFor={selectId} className="block text-sm font-medium mb-1">
          {label}
        </label>
        <DisabledSelectShell selectId={selectId} label={label} optionText={TEXT.loading} />
      </div>
    )
  }

  if (isError) {
    const errorId = `${selectId}-error`
    return (
      <div>
        <label htmlFor={selectId} className="block text-sm font-medium mb-1">
          {label}
        </label>
        <select
          id={selectId}
          aria-label={label}
          aria-describedby={errorId}
          disabled
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm text-muted-foreground disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <option>{TEXT.error}</option>
        </select>
        <p id={errorId} className="text-xs text-destructive mt-1" role="alert">
          {TEXT.error}
        </p>
      </div>
    )
  }

  const memberList = members ?? []
  const knownIds = new Set(memberList.map((member) => member.userId))
  const unknownValue = resolveUnknownValue(value, knownIds)
  const selectedValue = resolveSelectedValue(value, allowUnassign)

  return (
    <div>
      <label htmlFor={selectId} className="block text-sm font-medium mb-1">
        {label}
      </label>
      <select
        id={selectId}
        aria-label={label}
        value={selectedValue}
        onChange={handleChange}
        className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
      >
        {value === null && !allowUnassign && <option value={PLACEHOLDER_VALUE}>{TEXT.unset}</option>}
        {allowUnassign && <option value={UNASSIGN_VALUE}>{TEXT.unassign}</option>}
        {unknownValue !== null && <option value={unknownValue}>{TEXT.unknown}</option>}
        {memberList.map((member) => (
          <option key={member.userId} value={member.userId}>
            {resolveMemberLabel(member)}
          </option>
        ))}
      </select>
    </div>
  )
}
