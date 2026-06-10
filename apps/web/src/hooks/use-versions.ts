// 버전 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 (FR-VR-01, FR-VR-02)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchVersions,
  createVersion,
  updateVersion,
  changeVersionDates,
  deleteVersion,
  changeVersionStatus,
  extractVersionErrorCode,
} from '@/api/versions'
import { versionErrorMessage } from '@/i18n/version-labels'
import type {
  Version,
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
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사, 컴포넌트 중복 금지
// ─────────────────────────────────────────────────────────────────────────────

function notifyVersionError(error: unknown): void {
  const code = extractVersionErrorCode(error)
  toast.error(versionErrorMessage(code))
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
 * @param projectKey 프로젝트 식별 키
 */
export function useVersions(projectKey: string) {
  return useQuery({
    queryKey: VERSION_KEYS.list(projectKey),
    queryFn: () => fetchVersions(projectKey),
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
// useChangeVersionStatus — 상태 전이 (FR-VR-02)
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 상태 전이 mutation 입력 타입 */
export interface ChangeVersionStatusMutationInput {
  id: string
  status: VersionStatus
}

/**
 * 버전 상태를 전이한다.
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
