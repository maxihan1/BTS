// 버전 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 + 릴리즈 노트 lazy 조회 (FR-VR-01, FR-VR-02, FR-VR-04)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchVersions,
  createVersion,
  updateVersion,
  changeVersionDates,
  deleteVersion,
  changeVersionStatus,
  getReleaseNotes,
  extractVersionErrorCode,
} from '@/api/versions'
import { versionErrorMessage } from '@/i18n/version-labels'
import type {
  Version,
  ReleaseNotes,
  CreateVersionInput,
  UpdateVersionInput,
  ChangeDatesInput,
  VersionStatus,
} from '@/api/versions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 BC queryKey 팩토리 */
export const VERSION_KEYS = {
  /** 프로젝트별 버전 목록 queryKey */
  list: (projectKey: string) => ['versions', projectKey] as const,
  /** 버전 릴리즈 노트 단건 queryKey */
  releaseNotes: (projectKey: string, versionId: string) =>
    ['versions', projectKey, versionId, 'release-notes'] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사, 컴포넌트 중복 금지
// ─────────────────────────────────────────────────────────────────────────────

function notifyVersionError(error: unknown): void {
  const code = extractVersionErrorCode(error)
  toast.error(versionErrorMessage(code))
}

// ─────────────────────────────────────────────────────────────────────────────
// useReleaseNotes — 릴리즈 노트 lazy 조회 (FR-VR-04)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 릴리즈 노트를 lazy하게 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/versions/{versionId}/release-notes → ReleaseNotes
 * Dialog open 시 enabled=true로 전환해 fetch를 트리거한다.
 * staleTime 0 — 릴리즈 노트는 요청마다 최신 생성.
 *
 * @param projectKey 프로젝트 식별 키
 * @param versionId 버전 UUID
 * @param enabled 쿼리 활성 여부 — false이면 fetch 안 함 (lazy pattern)
 */
export function useReleaseNotes(projectKey: string, versionId: string, enabled: boolean) {
  return useQuery<ReleaseNotes, unknown>({
    queryKey: VERSION_KEYS.releaseNotes(projectKey, versionId),
    queryFn: () => getReleaseNotes(projectKey, versionId),
    enabled,
    staleTime: 0,
    retry: false,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useVersions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 버전 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/versions → Version[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * ★**키가 비면 요청을 내지 않는다.** 이 가드가 없어서 `issues.$key.tsx` 가 이슈 로드 전에
 * `GET /api/v1/projects//versions` 를 실제로 쐈다(프로덕션 콘솔 실측 2026-09-07).
 *
 * 빈 세그먼트(`//`)는 그냥 404 가 아니다 — Spring Security 의 경로 매처가 그 URL 을 원래
 * 규칙에 매칭시키지 못해 `SecurityConfig.kt` 의 포괄 규칙 `/api/**` → authenticated 로
 * 떨어진다. 프로덕션 실측으로 증명했다. **공개** 엔드포인트조차
 * `/api/v1/auth/oidc/providers` 는 200, `/api/v1/auth//oidc/providers` 는 401 이다.
 * 그래서 「로그인했는데 401」로 보이지만 인증 문제가 아니고, 토큰이 붙어도 성공하지 않는다.
 *
 * ★가드를 **호출부가 아니라 훅에** 둔다. 소비처가 5곳이고, 호출부마다 달면 새 소비처가
 * 생길 때 빠뜨리는 자리가 다섯 개에서 여섯 개로 늘 뿐이다. 정본은 하나여야 한다.
 *
 * @param projectKey 프로젝트 식별 키. 아직 모르면 `undefined`(또는 빈 문자열) — 조회를 미룬다
 */
export function useVersions(projectKey: string | undefined) {
  const hasKey = projectKey !== undefined && projectKey !== ''

  return useQuery({
    // ★queryKey 는 `enabled` 와 무관하게 평가되므로 폴백이 필요하다. 키가 없는 동안의
    //   캐시 엔트리는 `enabled:false` 라 영영 비어 있고, 키가 생기면 **다른 키**로 옮겨가
    //   그때 조회가 일어난다.
    queryKey: VERSION_KEYS.list(projectKey ?? ''),
    // `hasKey` 가 false 면 실행되지 않는다 — 위 좁힘이 여기까지 이어지지 않아 non-null 단언을 쓴다
    queryFn: () => fetchVersions(projectKey!),
    enabled: hasKey,
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateVersion — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전을 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/versions → 201 Version
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → extractVersionErrorCode → versionErrorMessage → toast.error
 *
 * @param projectKey 버전을 추가할 프로젝트 키
 */
export function useCreateVersion(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = VERSION_KEYS.list(projectKey)

  return useMutation<Version, unknown, CreateVersionInput>({
    mutationFn: (input) => createVersion(projectKey, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyVersionError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateVersion — 이름/설명 수정
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 수정 mutation 입력 타입 */
export interface UpdateVersionMutationInput {
  id: string
  input: UpdateVersionInput
}

/**
 * 버전 이름 / 설명을 수정한다.
 *
 * PATCH /api/v1/projects/{projectKey}/versions/{id} → 200 Version
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useUpdateVersion(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = VERSION_KEYS.list(projectKey)

  return useMutation<Version, unknown, UpdateVersionMutationInput>({
    mutationFn: ({ id, input }) => updateVersion(projectKey, id, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyVersionError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeVersionDates — 날짜 변경
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 날짜 변경 mutation 입력 타입 */
export interface ChangeVersionDatesMutationInput {
  id: string
  startDate: string | null
  releaseDate: string | null
}

/**
 * 버전의 시작일과 릴리스 예정일을 지정하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectKey}/versions/{id}/dates → 200 Version
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useChangeVersionDates(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = VERSION_KEYS.list(projectKey)

  return useMutation<Version, unknown, ChangeVersionDatesMutationInput>({
    mutationFn: ({ id, startDate, releaseDate }) => {
      const input: ChangeDatesInput = { startDate, releaseDate }
      return changeVersionDates(projectKey, id, input)
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyVersionError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeVersionStatus — 상태 전환 (FR-VR-02)
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 상태 전환 mutation 입력 타입 */
export interface ChangeVersionStatusMutationInput {
  id: string
  status: VersionStatus
}

/**
 * 버전 상태를 전환한다.
 *
 * PATCH /api/v1/projects/{projectKey}/versions/{id}/status → 200 Version
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useChangeVersionStatus(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = VERSION_KEYS.list(projectKey)

  return useMutation<Version, unknown, ChangeVersionStatusMutationInput>({
    mutationFn: ({ id, status }) => changeVersionStatus(projectKey, id, status),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyVersionError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteVersion — 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전을 소프트 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/versions/{id} → 204 No Content
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useDeleteVersion(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = VERSION_KEYS.list(projectKey)

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deleteVersion(projectKey, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyVersionError(error)
    },
  })
}
