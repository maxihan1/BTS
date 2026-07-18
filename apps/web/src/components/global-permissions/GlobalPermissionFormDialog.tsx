// 전역 권한 부여 다이얼로그 — 권한 select + 대상 종류 토글 + USER 검색/GROUP 드롭다운 (FR-PM-10 D6)
import type { FormEvent, JSX } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { useUserSearch } from '@/hooks/use-user-directory'
import { useGroups } from '@/hooks/use-groups'
import { useGrantGlobalPermission } from '@/hooks/use-global-permissions'
import {
  GLOBAL_PERMISSION_CODES,
  GLOBAL_PERMISSION_LABELS,
  extractGlobalPermissionErrorCode,
} from '@/api/global-permissions'
import type { GranteeType } from '@/api/global-permissions.types'
import type { UserSummary } from '@/api/users'
import type { GroupResponse } from '@/api/groups'

// ─────────────────────────────────────────────────────────────────────────────
// 대상 종류 토글
// ─────────────────────────────────────────────────────────────────────────────

interface GranteeTypeToggleProps {
  readonly value: GranteeType
  readonly onChange: (next: GranteeType) => void
}

function GranteeTypeToggle({ value, onChange }: GranteeTypeToggleProps): JSX.Element {
  return (
    <div role="radiogroup" aria-label="대상 종류" className="flex gap-4">
      <label className="flex items-center gap-1 text-sm">
        <input
          type="radio"
          name="granteeType"
          value="USER"
          checked={value === 'USER'}
          aria-label="사용자"
          onChange={() => { onChange('USER') }}
        />
        사용자
      </label>
      <label className="flex items-center gap-1 text-sm">
        <input
          type="radio"
          name="granteeType"
          value="GROUP"
          checked={value === 'GROUP'}
          aria-label="그룹"
          onChange={() => { onChange('GROUP') }}
        />
        그룹
      </label>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// USER 대상 선택기 — typeahead 검색 (min 2자)
// ─────────────────────────────────────────────────────────────────────────────

interface UserGranteeSelectorProps {
  readonly selectedUserId: string | null
  readonly onSelect: (user: UserSummary) => void
}

function UserGranteeSelector({ selectedUserId, onSelect }: UserGranteeSelectorProps): JSX.Element {
  const [query, setQuery] = useState('')
  const { data: results, isFetching } = useUserSearch(query)

  return (
    <div>
      <label htmlFor="gp-user-search" className="block text-sm font-medium mb-1">
        대상 검색
      </label>
      <input
        id="gp-user-search"
        type="text"
        value={query}
        onChange={(e) => { setQuery(e.target.value) }}
        placeholder="이름 또는 아이디로 검색 (2자 이상)"
        aria-label="대상 검색"
        autoComplete="off"
        className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
      />
      {query.length >= 2 && (
        isFetching ? (
          <p className="text-xs text-muted-foreground py-2 px-1">검색 중...</p>
        ) : results === undefined || results.length === 0 ? (
          <p className="text-sm text-muted-foreground py-2 px-1">검색 결과가 없습니다</p>
        ) : (
          <ul className="mt-1 max-h-40 overflow-y-auto rounded-md border divide-y">
            {results.map((candidate) => {
              const isSelected = selectedUserId === candidate.id
              return (
                <li key={candidate.id}>
                  <button
                    type="button"
                    className={`w-full text-left px-3 py-2 text-sm hover:bg-accent transition-colors ${isSelected ? 'bg-accent text-accent-foreground font-medium' : ''}`}
                    onClick={() => { onSelect(candidate) }}
                  >
                    {candidate.displayName ?? candidate.username}
                  </button>
                </li>
              )
            })}
          </ul>
        )
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GROUP 대상 선택기 — 드롭다운
// ─────────────────────────────────────────────────────────────────────────────

interface GroupGranteeSelectorProps {
  readonly selectedGroupId: string | null
  readonly onSelect: (group: GroupResponse) => void
}

function GroupGranteeSelector({ selectedGroupId, onSelect }: GroupGranteeSelectorProps): JSX.Element {
  const { data: groups, isLoading } = useGroups()

  return (
    <div>
      <label htmlFor="gp-group-select" className="block text-sm font-medium mb-1">
        대상 그룹
      </label>
      <select
        id="gp-group-select"
        aria-label="그룹 선택"
        disabled={isLoading}
        value={selectedGroupId ?? ''}
        onChange={(e) => {
          const group = (groups ?? []).find((candidate) => candidate.id === e.target.value)
          if (group !== undefined) onSelect(group)
        }}
        className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        <option value="">그룹을 선택하세요</option>
        {(groups ?? []).map((group) => (
          <option key={group.id} value={group.id}>
            {group.name}
          </option>
        ))}
      </select>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 한글 메시지 (FR-8)
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_ERROR_MESSAGE = '권한 부여에 실패했습니다'

function resolveErrorMessage(error: unknown): string {
  const code = extractGlobalPermissionErrorCode(error)
  if (code === null) return DEFAULT_ERROR_MESSAGE
  if (code === 'grant_already_exists') return '이미 부여된 권한입니다'
  if (code === 'grantee_not_found') return '대상을 찾을 수 없습니다'
  if (code === 'unknown_permission') return '알 수 없는 권한입니다'
  return DEFAULT_ERROR_MESSAGE
}

// ─────────────────────────────────────────────────────────────────────────────
// FormBody — 내부 폼 (key prop 재마운트로 열릴 때마다 상태 초기화)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly onClose: () => void
}

function FormBody({ onClose }: FormBodyProps): JSX.Element {
  const firstPermissionCode = GLOBAL_PERMISSION_CODES[0] ?? ''
  const [permission, setPermission] = useState<string>(firstPermissionCode)
  const [granteeType, setGranteeType] = useState<GranteeType>('USER')
  const [granteeId, setGranteeId] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const grantMutation = useGrantGlobalPermission()

  function handleGranteeTypeChange(next: GranteeType): void {
    setGranteeType(next)
    // B-3: 대상 종류를 바꾸면 이전에 고른 granteeId를 반드시 리셋한다.
    // 리셋하지 않으면 예를 들어 그룹 id를 골라둔 채로 USER로 전환해도 granteeId가
    // 남아 있어 "사용자에게 그룹 id를 부여"하는 잘못된 요청이 만들어질 수 있다.
    setGranteeId(null)
    setErrorMessage(null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    if (granteeId === null) return

    setErrorMessage(null)
    grantMutation.mutate(
      { permission, granteeType, granteeId },
      {
        onSuccess: () => { onClose() },
        onError: (error) => { setErrorMessage(resolveErrorMessage(error)) },
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {/* 권한 */}
      <div className="mb-4">
        <label htmlFor="gp-permission" className="block text-sm font-medium mb-1">
          권한
        </label>
        <select
          id="gp-permission"
          aria-label="권한"
          value={permission}
          onChange={(e) => { setPermission(e.target.value) }}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
        >
          {GLOBAL_PERMISSION_CODES.map((code) => (
            <option key={code} value={code}>
              {GLOBAL_PERMISSION_LABELS[code] ?? code}
            </option>
          ))}
        </select>
      </div>

      {/* 대상 종류 */}
      <div className="mb-4">
        <span className="block text-sm font-medium mb-1">대상 종류</span>
        <GranteeTypeToggle value={granteeType} onChange={handleGranteeTypeChange} />
      </div>

      {/* 대상 선택기 */}
      <div className="mb-4">
        {granteeType === 'USER' ? (
          <UserGranteeSelector
            selectedUserId={granteeId}
            onSelect={(user) => { setGranteeId(user.id) }}
          />
        ) : (
          <GroupGranteeSelector
            selectedGroupId={granteeId}
            onSelect={(group) => { setGranteeId(group.id) }}
          />
        )}
      </div>

      {/* 서버 오류 */}
      {errorMessage !== null && (
        <p className="text-sm text-destructive mb-4" role="alert">
          {errorMessage}
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex justify-end gap-2 mt-6">
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          취소
        </Button>
        <Button type="submit" size="sm" disabled={granteeId === null || grantMutation.isPending}>
          부여
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GlobalPermissionFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

interface GlobalPermissionFormDialogProps {
  /** 다이얼로그 열림 여부 */
  readonly isOpen: boolean
  /** 취소/성공 시 호출되는 닫기 콜백 — 목록 갱신은 mutation invalidate가 담당 */
  readonly onClose: () => void
}

/**
 * 전역 권한 부여 다이얼로그.
 *
 * - 권한 select: {@link GLOBAL_PERMISSION_CODES} 화이트리스트, 기본값 첫 코드(CREATE_PROJECT).
 * - 대상 종류(USER/GROUP) 토글 — 전환 시 granteeId를 반드시 리셋한다(B-3).
 * - USER: {@link useUserSearch} typeahead(2자 이상). GROUP: {@link useGroups} 드롭다운.
 * - 제출은 {@link useGrantGlobalPermission}으로 직접 수행하며, 성공 시 onClose()를 호출한다.
 *   실패 시 {@link resolveErrorMessage}로 한글 메시지를 표시하고 다이얼로그는 유지된다.
 *
 * @param props {@link GlobalPermissionFormDialogProps}
 */
export const GlobalPermissionFormDialog = ({
  isOpen,
  onClose,
}: GlobalPermissionFormDialogProps): JSX.Element => {
  return (
    <DialogPrimitive.Root
      open={isOpen}
      onOpenChange={(next) => { if (!next) onClose() }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 overflow-y-auto max-h-[90vh]"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            전역 권한 부여
          </DialogPrimitive.Title>

          <FormBody key={isOpen ? 'open' : 'closed'} onClose={onClose} />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
