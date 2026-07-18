// 전역 권한 부여 BC TanStack Query 훅 — 목록/부여/회수 + grantee UUID 이름 해소 헬퍼 (FR-PM-10)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchGlobalPermissions,
  createGlobalPermission,
  deleteGlobalPermission,
  GLOBAL_PERMISSION_LABELS,
} from '@/api/global-permissions'
import type { GrantResponse, CreateGlobalPermissionInput } from '@/api/global-permissions.types'
import type { UserSummary } from '@/api/users'
import type { GroupResponse } from '@/api/groups'
import { formatDate } from '@/lib/date-format'
import { useUsersByIds } from './use-users'
import { useGroups } from './use-groups'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여 BC queryKey 팩토리 */
export const GLOBAL_PERMISSION_KEYS = {
  /** 전역 권한 부여 전체 목록 queryKey */
  all: ['global-permissions'] as const,
} satisfies Record<string, readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 표시 폴백 상수 — EC-1/EC-2 orphan grantee 대응
// ─────────────────────────────────────────────────────────────────────────────

/** orphan USER grantee/부여자 표시 폴백 (EC-1) */
const DELETED_USER_LABEL = '삭제된 사용자'

/** orphan GROUP grantee 표시 폴백 (EC-2) */
const DELETED_GROUP_LABEL = '삭제된 그룹'

/** 대상 종류 → 한글 라벨 */
const GRANTEE_TYPE_LABELS: Record<GrantResponse['granteeType'], string> = {
  USER: '사용자',
  GROUP: '그룹',
}

// ─────────────────────────────────────────────────────────────────────────────
// useGlobalPermissions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 목록을 조회한다.
 *
 * GET /api/v1/admin/global-permissions → GrantResponse[] (bare 배열).
 * staleTime 30초.
 *
 * @returns GrantResponse 배열을 담은 UseQueryResult
 */
export function useGlobalPermissions() {
  return useQuery({
    queryKey: GLOBAL_PERMISSION_KEYS.all,
    queryFn: () => fetchGlobalPermissions(),
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useGrantGlobalPermission — 부여
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한을 부여한다.
 *
 * POST /api/v1/admin/global-permissions → 201 GrantResponse.
 * 성공 시 목록 쿼리를 invalidate한다 (setQueryData 부분갱신 금지,
 * [[mutation-setquerydata-partial-response-flicker]]).
 *
 * @returns GrantResponse를 반환하는 UseMutationResult
 */
export function useGrantGlobalPermission() {
  const queryClient = useQueryClient()

  return useMutation<GrantResponse, unknown, CreateGlobalPermissionInput>({
    mutationFn: (input) => createGlobalPermission(input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: GLOBAL_PERMISSION_KEYS.all })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useRevokeGlobalPermission — 회수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여를 회수한다.
 *
 * DELETE /api/v1/admin/global-permissions/{grantId} → 204 No Content.
 * 성공 시 목록 쿼리를 invalidate한다 (setQueryData 부분갱신 금지).
 *
 * @returns grantId를 인자로 받는 UseMutationResult
 */
export function useRevokeGlobalPermission() {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (grantId) => deleteGlobalPermission(grantId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: GLOBAL_PERMISSION_KEYS.all })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// collectUserIds / collectGroupIds — 순수 헬퍼 (grantee UUID 수집)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 목록에 등장하는 사용자 UUID를 중복 없이 수집한다.
 * USER 종류 grantee의 granteeId + 모든 grant의 grantedBy를 합집합한다.
 *
 * @param grants 전역 권한 부여 목록
 * @returns 중복 제거된 사용자 UUID 배열 (순서 무보장)
 */
export function collectUserIds(grants: GrantResponse[]): string[] {
  const ids = new Set<string>()
  for (const grant of grants) {
    if (grant.granteeType === 'USER') {
      ids.add(grant.granteeId)
    }
    ids.add(grant.grantedBy)
  }
  return Array.from(ids)
}

/**
 * 전역 권한 부여 목록에 등장하는 그룹 UUID를 중복 없이 수집한다.
 * GROUP 종류 grantee의 granteeId만 대상이다.
 *
 * @param grants 전역 권한 부여 목록
 * @returns 중복 제거된 그룹 UUID 배열 (순서 무보장)
 */
export function collectGroupIds(grants: GrantResponse[]): string[] {
  const ids = new Set<string>()
  for (const grant of grants) {
    if (grant.granteeType === 'GROUP') {
      ids.add(grant.granteeId)
    }
  }
  return Array.from(ids)
}

// ─────────────────────────────────────────────────────────────────────────────
// toDisplayRows — 순수 헬퍼 (grantee/부여자 이름 해소 + 정렬)
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여 표시행 — grantee/부여자 UUID를 이름으로 해소한 뷰모델 */
export interface GlobalPermissionDisplayRow {
  /** grant UUID */
  id: string
  /** 권한 한글 라벨 (매핑 없으면 원본 코드) */
  permissionLabel: string
  /** 대상 종류 한글 라벨 ('사용자' | '그룹') */
  granteeTypeLabel: string
  /** 대상 표시 이름 (orphan이면 "삭제된 사용자/그룹") */
  granteeName: string
  /** 부여자 표시 이름 (orphan이면 "삭제된 사용자") */
  grantedByName: string
  /** 가독 포맷된 부여 시각 */
  createdAtLabel: string
  /** 원본 ISO 부여 시각 — 정렬용 */
  createdAt: string
}

function resolveUserName(userId: string, users: UserSummary[]): string {
  const user = users.find((candidate) => candidate.id === userId)
  if (user === undefined) {
    return DELETED_USER_LABEL
  }
  return user.displayName ?? user.username
}

function resolveGroupName(groupId: string, groups: GroupResponse[]): string {
  const group = groups.find((candidate) => candidate.id === groupId)
  return group === undefined ? DELETED_GROUP_LABEL : group.name
}

function resolveGranteeName(
  grant: GrantResponse,
  users: UserSummary[],
  groups: GroupResponse[],
): string {
  return grant.granteeType === 'USER'
    ? resolveUserName(grant.granteeId, users)
    : resolveGroupName(grant.granteeId, groups)
}

/**
 * 전역 권한 부여 목록을 표시행으로 변환한다.
 *
 * grantee/부여자 UUID를 배치 조회된 users/groups로 해소하고(미존재 시 "삭제된 사용자/그룹" 폴백,
 * EC-1/EC-2), createdAt 내림차순으로 정렬한다(B-4, 백엔드 목록 순서 무보장 가정).
 *
 * @param grants 전역 권한 부여 목록
 * @param users 배치 조회된 사용자 목록 ({@link collectUserIds} 결과로 조회)
 * @param groups 전체 그룹 목록
 * @returns 표시행 배열 — createdAt desc 정렬
 */
export function toDisplayRows(
  grants: GrantResponse[],
  users: UserSummary[],
  groups: GroupResponse[],
): GlobalPermissionDisplayRow[] {
  const rows = grants.map((grant) => ({
    id: grant.id,
    permissionLabel: GLOBAL_PERMISSION_LABELS[grant.permission] ?? grant.permission,
    granteeTypeLabel: GRANTEE_TYPE_LABELS[grant.granteeType],
    granteeName: resolveGranteeName(grant, users, groups),
    grantedByName: resolveUserName(grant.grantedBy, users),
    createdAtLabel: formatDate(grant.createdAt),
    createdAt: grant.createdAt,
  }))
  return rows.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
}

// ─────────────────────────────────────────────────────────────────────────────
// useGlobalPermissionRows — 조합 훅 (목록 + 이름 해소)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link useGlobalPermissionRows} 반환 타입 */
export interface UseGlobalPermissionRowsResult {
  /** 이름까지 해소된 표시행 배열 */
  rows: GlobalPermissionDisplayRow[]
  /** 세 쿼리(목록·사용자·그룹) 중 하나라도 최초 로딩 중이면 true */
  isLoading: boolean
  /** 세 쿼리 중 하나라도 에러면 true */
  isError: boolean
}

/**
 * 전역 권한 부여 목록 조회 + grantee/부여자 이름 해소를 조합한다.
 *
 * {@link useGlobalPermissions}로 grant 목록을 받아 {@link collectUserIds}/
 * {@link collectGroupIds}로 필요한 UUID만 추출한 뒤 {@link useUsersByIds}/
 * {@link useGroups}로 배치 조회(NFR-6, N+1 회피)하고 {@link toDisplayRows}로 합성한다.
 *
 * @returns 표시행 + 로딩/에러 상태
 */
export function useGlobalPermissionRows(): UseGlobalPermissionRowsResult {
  const grantsQuery = useGlobalPermissions()
  const grants = grantsQuery.data ?? []

  const usersQuery = useUsersByIds(collectUserIds(grants))
  const groupsQuery = useGroups()

  const rows = toDisplayRows(grants, usersQuery.data ?? [], groupsQuery.data ?? [])

  return {
    rows,
    isLoading: grantsQuery.isLoading || usersQuery.isLoading || groupsQuery.isLoading,
    isError: grantsQuery.isError || usersQuery.isError || groupsQuery.isError,
  }
}
