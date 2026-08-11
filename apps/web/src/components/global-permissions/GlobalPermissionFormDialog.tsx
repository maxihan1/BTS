// 전역 권한 부여 다이얼로그 — 권한 select + 대상 종류 토글 + USER 검색/GROUP 드롭다운 (FR-PM-10 D6)
import type { FormEvent, JSX } from 'react'
import { useState } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
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
import { globalPermissionLabels } from '@/i18n/global-permission-labels'

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
        placeholder={globalPermissionLabels.subjectSearchPlaceholder}
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
                  {/* PR22 OUT — P5 옵션 행: 콤보박스 후보라 w-full text-left 가 필요하고, Button의 inline-flex/justify-center와 충돌한다 */}
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

/** 알려지지 않은 에러 코드/네트워크 에러에 표시할 기본 메시지 */
const DEFAULT_ERROR_MESSAGE = '권한 부여에 실패했습니다'

/**
 * 백엔드 `{ error: code }`(snake_case) 코드 → 한글 메시지 매핑.
 * 목록에 없는 코드는 {@link resolveErrorMessage}가 {@link DEFAULT_ERROR_MESSAGE}로 폴백한다.
 */
const ERROR_MESSAGE_BY_CODE: Record<string, string> = {
  grant_already_exists: '이미 부여된 권한입니다',
  grantee_not_found: '대상을 찾을 수 없습니다',
  unknown_permission: '알 수 없는 권한입니다',
}

/**
 * mutation 에러를 폼에 표시할 한글 메시지로 변환한다.
 *
 * {@link extractGlobalPermissionErrorCode}로 `error` 코드를 추출한 뒤
 * {@link ERROR_MESSAGE_BY_CODE}에서 찾는다. 코드가 없거나(네트워크 에러 등) 매핑에
 * 없는 코드면 {@link DEFAULT_ERROR_MESSAGE}로 폴백한다.
 *
 * @param error useGrantGlobalPermission mutation에서 던져진 에러 (unknown)
 * @returns 표시할 한글 에러 메시지
 */
function resolveErrorMessage(error: unknown): string {
  const code = extractGlobalPermissionErrorCode(error)
  if (code === null) return DEFAULT_ERROR_MESSAGE
  return ERROR_MESSAGE_BY_CODE[code] ?? DEFAULT_ERROR_MESSAGE
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

  /**
   * 대상 종류(USER/GROUP) 토글 변경 핸들러.
   *
   * **B-3 (correctness, 반드시 유지)** — granteeId는 USER 선택기와 GROUP 선택기가
   * 공유하는 단일 state다. 종류를 바꿀 때 granteeId를 리셋하지 않으면, 예를 들어
   * GROUP에서 그룹 id를 고른 채로 USER로 전환해도 그 id가 그대로 남아
   * `POST { granteeType: 'USER', granteeId: <그룹 id> }` 같은 잘못된 요청이
   * (사용자가 다시 고르지 않아도) 그대로 제출 가능해진다.
   * 그래서 종류가 바뀌면 항상 대상을 다시 고르도록 granteeId를 null로 리셋하고,
   * 제출 버튼은 `granteeId === null`일 때 disabled로 막는다.
   *
   * 참고 — UserGranteeSelector/GroupGranteeSelector는 `granteeType`에 따라
   * 조건부로 마운트/언마운트되므로 검색어(query) 같은 내부 state는 자동으로
   * 사라진다. 여기서 명시적으로 리셋해야 하는 건 두 선택기가 함께 쓰는 상위 state,
   * 즉 granteeId뿐이다.
   *
   * @param next 새로 선택된 대상 종류
   */
  function handleGranteeTypeChange(next: GranteeType): void {
    setGranteeType(next)
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
      <DialogFooter>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          취소
        </Button>
        <Button type="submit" size="sm" disabled={granteeId === null || grantMutation.isPending}>
          부여
        </Button>
      </DialogFooter>
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
    <Dialog
      open={isOpen}
      onOpenChange={(next) => { if (!next) onClose() }}
    >
      <DialogContent className="max-w-lg overflow-y-auto max-h-[90vh]" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>전역 권한 부여</DialogTitle>
        </DialogHeader>

        <FormBody key={isOpen ? 'open' : 'closed'} onClose={onClose} />
      </DialogContent>
    </Dialog>
  )
}
