// 프로젝트 멤버 단일 행 컴포넌트 — 이름/역할 배지/ADMIN 전용 액션 (FR-PM-01)
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { RoleSelect } from './RoleSelect'
import type { ProjectMember } from '@/api/project-members'
import { projectMemberLabels } from '@/i18n/project-member-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멤버 표시 이름을 우선순위에 따라 반환한다.
 *
 * 우선순위: displayName → username → "(알 수 없는 사용자)" (orphan EC-1/C-3)
 *
 * @param member 프로젝트 멤버
 * @returns 표시할 이름 문자열
 */
function resolveMemberName(member: ProjectMember): string {
  if (member.displayName !== null) return member.displayName
  if (member.username !== null) return member.username
  return projectMemberLabels.row.unknownUser
}

// ─────────────────────────────────────────────────────────────────────────────
// RoleBadge — 인라인 Tailwind (shadcn badge 미존재)
// ─────────────────────────────────────────────────────────────────────────────

interface RoleBadgeProps {
  readonly isAdmin: boolean
}

/**
 * 역할 배지 컴포넌트.
 *
 * shadcn badge.tsx가 없으므로 SessionList.tsx의 CurrentSessionBadge 패턴을 따른다.
 */
function RoleBadge({ isAdmin }: RoleBadgeProps): JSX.Element {
  if (isAdmin) {
    return (
      <span className="inline-flex items-center rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">
        {projectMemberLabels.row.adminBadge}
      </span>
    )
  }
  return (
    <span className="inline-flex items-center rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
      {projectMemberLabels.row.memberBadge}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface MemberRowProps {
  /** 프로젝트 식별 키 */
  readonly projectKey: string
  /** 표시할 멤버 데이터 */
  readonly member: ProjectMember
  /** 현재 로그인 사용자가 PROJECT_ADMIN인지 여부 */
  readonly isCurrentUserAdmin: boolean
  /** 제거 버튼 클릭 시 호출되는 콜백 */
  readonly onRemove: (userId: string) => void
  /** 제거 mutation 진행 중 여부 */
  readonly isRemoving: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 단일 행 컴포넌트.
 *
 * - 이름 표시: displayName → username → "(알 수 없는 사용자)" 폴백
 * - 역할 배지: 관리자(PROJECT_ADMIN) / 멤버(MEMBER)
 * - isCurrentUserAdmin true인 경우에만 RoleSelect + 제거 버튼을 렌더한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param member 멤버 데이터
 * @param isCurrentUserAdmin 현재 사용자의 ADMIN 여부
 * @param onRemove 제거 콜백
 * @param isRemoving 제거 진행 중 여부
 */
export function MemberRow({
  projectKey,
  member,
  isCurrentUserAdmin,
  onRemove,
  isRemoving,
}: MemberRowProps): JSX.Element {
  const displayName = resolveMemberName(member)
  const isAdmin = member.role === 'PROJECT_ADMIN'

  function handleRemove(): void {
    onRemove(member.userId)
  }

  return (
    <li
      className="flex items-center justify-between gap-3 rounded-md border px-4 py-3"
    >
      <div className="flex items-center gap-2 min-w-0">
        <span className="truncate text-sm font-medium">{displayName}</span>
        <RoleBadge isAdmin={isAdmin} />
      </div>

      {isCurrentUserAdmin && (
        <div className="flex items-center gap-2 shrink-0">
          <RoleSelect
            projectKey={projectKey}
            userId={member.userId}
            currentRole={member.role}
          />
          <Button
            variant="destructive"
            size="sm"
            disabled={isRemoving}
            aria-label={projectMemberLabels.row.removeAriaLabel(displayName)}
            onClick={handleRemove}
          >
            {projectMemberLabels.row.removeButton}
          </Button>
        </div>
      )}
    </li>
  )
}
