// 멤버 역할 변경 Select 컴포넌트 — PROJECT_ADMIN / MEMBER 전환 (FR-PM-01)
import type { JSX } from 'react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useChangeRole } from '@/hooks/use-project-members'
import type { ProjectRole } from '@/api/project-members'
import { projectMemberLabels } from '@/i18n/project-member-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface RoleSelectProps {
  /** 프로젝트 식별 키 */
  readonly projectKey: string
  /** 역할을 변경할 사용자 UUID */
  readonly userId: string
  /** 현재 할당된 역할 */
  readonly currentRole: ProjectRole
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멤버 역할을 변경하는 Select 컴포넌트.
 *
 * - PROJECT_ADMIN / MEMBER 두 옵션을 제공한다.
 * - 값 변경 즉시 useChangeRole.mutate를 호출한다 (낙관적 업데이트).
 * - 진행 중(isPending)이면 Select를 비활성화한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param userId 역할 변경 대상 사용자 UUID
 * @param currentRole 현재 역할
 */
export function RoleSelect({ projectKey, userId, currentRole }: RoleSelectProps): JSX.Element {
  const changeRole = useChangeRole(projectKey)

  function handleValueChange(value: string): void {
    const role = value as ProjectRole
    changeRole.mutate({ userId, role })
  }

  return (
    <Select
      value={currentRole}
      onValueChange={handleValueChange}
      disabled={changeRole.isPending}
    >
      <SelectTrigger
        className="w-28"
        aria-label={projectMemberLabels.roleSelect.triggerAriaLabel}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="PROJECT_ADMIN">
          {projectMemberLabels.roleSelect.adminOption}
        </SelectItem>
        <SelectItem value="MEMBER">
          {projectMemberLabels.roleSelect.memberOption}
        </SelectItem>
      </SelectContent>
    </Select>
  )
}
