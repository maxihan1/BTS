// 프로젝트 멤버 목록 컴포넌트 — 4분기(로딩/에러/빈/목록) + ADMIN 판정 (FR-PM-01)
import type { JSX } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useProjectMembers, useRemoveMember } from '@/hooks/use-project-members'
import { useAuthStore } from '@/auth/authStore'
import { MemberRow } from './MemberRow'
import { AddMemberDialog } from './AddMemberDialog'
import { projectMemberLabels } from '@/i18n/project-member-labels'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface MemberListProps {
  /** 멤버를 표시할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멤버 목록 로딩 중 스켈레톤 UI.
 */
function MemberListSkeleton(): JSX.Element {
  return (
    <ul
      role="status"
      aria-label={projectMemberLabels.list.loadingStatus}
      className="space-y-2"
    >
      {[1, 2, 3].map((i) => (
        <li key={i}>
          <Skeleton className="h-14 w-full border" />
        </li>
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 목록 컴포넌트.
 *
 * - useProjectMembers로 멤버 목록 조회.
 * - 4분기: 로딩 → 스켈레톤, 에러 → 에러 메시지, 빈 → 빈 상태, 목록 → MemberRow 렌더.
 * - 현재 사용자 ADMIN 판정 (C-1 리뷰):
 *   - whoami.userId가 null → 비ADMIN (비노출)
 *   - userId가 멤버 목록에 없음 → 비ADMIN (비노출)
 *   - userId의 role이 PROJECT_ADMIN → ADMIN (액션 노출)
 * - ADMIN일 때만 "멤버 추가" 버튼(AddMemberDialog)과 각 행의 역할 변경/제거 컨트롤 노출.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function MemberList({ projectKey }: MemberListProps): JSX.Element {
  const { data: members, isLoading, isError } = useProjectMembers(projectKey)
  const removeMember = useRemoveMember(projectKey)
  const whoami = useAuthStore((s) => s.user)

  // ── ADMIN 판정 (C-1): userId null 또는 목록 부재 시 false
  const currentUserId: string | null = whoami?.userId ?? null
  const isCurrentUserAdmin: boolean = (() => {
    if (currentUserId === null) return false
    if (members === undefined) return false
    const found = members.find((m) => m.userId === currentUserId)
    if (found === undefined) return false
    return found.role === 'PROJECT_ADMIN'
  })()

  if (isLoading) {
    return (
      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle className="text-base">{projectMemberLabels.list.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <MemberListSkeleton />
        </CardContent>
      </Card>
    )
  }

  if (isError) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{projectMemberLabels.list.heading}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive">{projectMemberLabels.list.errorMessage}</p>
        </CardContent>
      </Card>
    )
  }

  const memberList = members ?? []

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-base">{projectMemberLabels.list.heading}</CardTitle>
        {isCurrentUserAdmin && (
          <AddMemberDialog projectKey={projectKey} />
        )}
      </CardHeader>
      <CardContent>
        {memberList.length === 0 ? (
          <p className="text-sm text-muted-foreground">{projectMemberLabels.list.emptyMessage}</p>
        ) : (
          <ul className="space-y-2">
            {memberList.map((member) => (
              <MemberRow
                key={member.userId}
                projectKey={projectKey}
                member={member}
                isCurrentUserAdmin={isCurrentUserAdmin}
                onRemove={removeMember.mutate}
                isRemoving={removeMember.isPending}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
