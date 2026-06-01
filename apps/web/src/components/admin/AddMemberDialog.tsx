// 프로젝트 멤버 추가 다이얼로그 — typeahead 검색 + 역할 선택 + addMember mutation (FR-PM-01)
import type { JSX } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useUserSearch } from '@/hooks/use-user-directory'
import { useAddMember } from '@/hooks/use-project-members'
import type { ProjectRole } from '@/api/project-members'
import type { UserSummary } from '@/api/users'
import { projectMemberLabels } from '@/i18n/project-member-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * UserSummary의 표시 이름을 반환한다.
 * displayName이 있으면 우선, 없으면 username을 사용한다.
 */
function resolveUserLabel(user: UserSummary): string {
  return user.displayName ?? user.username
}

// ─────────────────────────────────────────────────────────────────────────────
// 검색 결과 목록
// ─────────────────────────────────────────────────────────────────────────────

interface SearchResultListProps {
  readonly query: string
  readonly selectedUser: UserSummary | null
  readonly onSelect: (user: UserSummary) => void
}

/**
 * 사용자 검색 결과 목록.
 *
 * - query 2자 미만: 렌더하지 않는다 (useUserSearch의 enabled 가드와 일치).
 * - 결과 없음: EC-4 안내 텍스트 표시.
 * - 선택된 사용자는 배경색으로 강조한다.
 */
function SearchResultList({ query, selectedUser, onSelect }: SearchResultListProps): JSX.Element | null {
  const { data: results, isFetching } = useUserSearch(query)

  if (query.length < 2) return null

  if (isFetching) {
    return (
      <p className="text-xs text-muted-foreground py-2 px-1">
        검색 중...
      </p>
    )
  }

  if (results === undefined || results.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-2 px-1">
        {projectMemberLabels.addDialog.noResults}
      </p>
    )
  }

  return (
    <ul className="mt-1 max-h-48 overflow-y-auto rounded-md border divide-y">
      {results.map((user) => {
        const isSelected = selectedUser?.id === user.id
        return (
          <li key={user.id}>
            <button
              type="button"
              className={`w-full text-left px-3 py-2 text-sm hover:bg-accent transition-colors ${isSelected ? 'bg-accent text-accent-foreground font-medium' : ''}`}
              onClick={() => { onSelect(user) }}
            >
              {resolveUserLabel(user)}
              {user.username !== (user.displayName ?? '') && (
                <span className="ml-1 text-xs text-muted-foreground">@{user.username}</span>
              )}
            </button>
          </li>
        )
      })}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface AddMemberDialogProps {
  /** 멤버를 추가할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────명
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 추가 다이얼로그.
 *
 * 1. 트리거 버튼 클릭 → Dialog 열림.
 * 2. 사용자 이름/displayName 검색 (2자 이상 입력 시 활성화).
 * 3. 검색 결과에서 사용자 선택.
 * 4. 역할 선택 (기본: MEMBER).
 * 5. 추가 버튼 → useAddMember.mutate → 성공 시 Dialog 닫힘.
 *
 * Dialog는 shadcn에 없으므로 radix-ui Dialog를 직접 사용한다.
 *
 * @param projectKey 멤버를 추가할 프로젝트 키
 */
export function AddMemberDialog({ projectKey }: AddMemberDialogProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const [selectedRole, setSelectedRole] = useState<ProjectRole>('MEMBER')

  const addMember = useAddMember(projectKey)

  function handleOpenChange(next: boolean): void {
    if (!next) {
      // Dialog 닫힐 때 상태 초기화
      setSearchQuery('')
      setSelectedUser(null)
      setSelectedRole('MEMBER')
    }
    setOpen(next)
  }

  function handleSelectUser(user: UserSummary): void {
    setSelectedUser(user)
  }

  function handleRoleChange(value: string): void {
    setSelectedRole(value as ProjectRole)
  }

  function handleConfirm(): void {
    if (selectedUser === null) return

    addMember.mutate(
      { userId: selectedUser.id, role: selectedRole },
      {
        onSuccess: () => {
          setOpen(false)
          setSearchQuery('')
          setSelectedUser(null)
          setSelectedRole('MEMBER')
        },
      },
    )
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Trigger asChild>
        <Button size="sm">
          {projectMemberLabels.addDialog.triggerButton}
        </Button>
      </DialogPrimitive.Trigger>

      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            {projectMemberLabels.addDialog.title}
          </DialogPrimitive.Title>

          {/* 사용자 검색 */}
          <div className="space-y-3">
            <div>
              <label htmlFor="member-search" className="text-sm font-medium mb-1 block">
                사용자 검색
              </label>
              <input
                id="member-search"
                type="text"
                value={searchQuery}
                onChange={(e) => { setSearchQuery(e.target.value) }}
                placeholder={projectMemberLabels.addDialog.searchPlaceholder}
                className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
                autoComplete="off"
              />
              <SearchResultList
                query={searchQuery}
                selectedUser={selectedUser}
                onSelect={handleSelectUser}
              />
            </div>

            {/* 선택된 사용자 표시 */}
            {selectedUser !== null && (
              <p className="text-sm text-muted-foreground">
                선택됨.{' '}
                <span className="font-medium text-foreground">
                  {resolveUserLabel(selectedUser)}
                </span>
              </p>
            )}

            {/* 역할 선택 */}
            <div>
              <label className="text-sm font-medium mb-1 block">
                역할
              </label>
              <Select
                value={selectedRole}
                onValueChange={handleRoleChange}
              >
                <SelectTrigger
                  className="w-full"
                  aria-label={projectMemberLabels.addDialog.roleSelectAriaLabel}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="PROJECT_ADMIN">
                    {projectMemberLabels.addDialog.roleAdminOption}
                  </SelectItem>
                  <SelectItem value="MEMBER">
                    {projectMemberLabels.addDialog.roleMemberOption}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          {/* 액션 버튼 */}
          <div className="flex justify-end gap-2 mt-6">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm">
                {projectMemberLabels.addDialog.cancelButton}
              </Button>
            </DialogPrimitive.Close>
            <Button
              size="sm"
              disabled={selectedUser === null || addMember.isPending}
              onClick={handleConfirm}
            >
              {projectMemberLabels.addDialog.confirmButton}
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
