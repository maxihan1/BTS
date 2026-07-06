// 사용자 프로필 TanStack Query 훅 — mutation 성공 시 profile invalidate + whoami 재조회로 authStore 갱신
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient, UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { getProfile, patchProfile, uploadAvatar, deleteAvatar } from '@/api/profile'
import type { ProfileResponse, ProfilePatchBody, AvatarUploadResponse } from '@/api/profile'
import { apiGet, ApiError } from '@/api/client'
import { WhoamiResponseSchema } from '@/api/schemas'
import { useAuthStore } from '@/auth/authStore'

/**
 * 본인 프로필 조회 쿼리 키 — `["profile", "me"]`.
 * mutation onSuccess의 invalidateQueries가 이 키의 상위 prefix(`["profile"]`)를 사용해
 * 이 쿼리를 함께 무효화한다.
 */
export const PROFILE_QUERY_KEY = ['profile', 'me'] as const

/**
 * profile 쿼리 invalidate 시 사용하는 상위 prefix 키.
 * {@link PROFILE_QUERY_KEY}(`["profile", "me"]`)가 이 prefix로 시작하므로
 * `invalidateQueries({ queryKey: PROFILE_QUERY_PREFIX })` 한 번으로 함께 무효화된다.
 */
const PROFILE_QUERY_PREFIX = ['profile'] as const

/**
 * whoami를 재조회해 authStore.user를 최신 값으로 교체한다.
 *
 * ★ 설계 — whoami는 TanStack Query가 아니라 zustand `authStore.user`에만 존재한다
 * (Header가 `useAuthStore().user`를 구독). 따라서 프로필 PATCH/아바타 업로드·삭제 성공 후
 * `invalidateQueries(PROFILE_QUERY_PREFIX)`만으로는 Header의 아바타/표시이름이 갱신되지 않는다.
 * mutation onSuccess에서 이 헬퍼를 함께 호출해 store.user를 최신 whoami 응답으로 교체한다.
 *
 * accessToken이 없으면(로그아웃 상태) whoami 호출을 스킵한다 — 401만 발생시킬 뿐 의미가 없다.
 */
export async function refreshWhoami(): Promise<void> {
  const { accessToken, setUser } = useAuthStore.getState()
  if (accessToken === null) return

  const fresh = await apiGet('/api/v1/users/me/whoami', WhoamiResponseSchema)
  setUser(fresh)
}

/**
 * 프로필 mutation(PATCH/아바타 업로드/아바타 삭제) 공통 onSuccess 처리.
 *
 * (a) {@link PROFILE_QUERY_PREFIX} 하위 쿼리 전체를 invalidate하고,
 * (b) {@link refreshWhoami}로 authStore.user를 최신화한다.
 * 세 mutation 훅이 동일한 로직을 중복 작성하지 않도록 이 헬퍼로 추출했다.
 *
 * @param queryClient 호출측 훅의 `useQueryClient()` 인스턴스
 */
async function invalidateProfileAndRefreshWhoami(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_PREFIX })
  await refreshWhoami()
}

/**
 * 본인 프로필 조회 훅.
 *
 * `GET /api/v1/users/me/profile` → {@link ProfileResponse}.
 *
 * @returns TanStack Query `useQuery` 결과
 */
export function useProfile(): UseQueryResult<ProfileResponse, ApiError> {
  return useQuery<ProfileResponse, ApiError>({
    queryKey: PROFILE_QUERY_KEY,
    queryFn: getProfile,
  })
}

/**
 * 프로필 3-state 부분 수정 mutation 훅.
 *
 * 성공 시 {@link invalidateProfileAndRefreshWhoami}로 profile 쿼리 invalidate + authStore.user 갱신.
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(body)`로 실행
 */
export function usePatchProfile(): UseMutationResult<ProfileResponse, ApiError, ProfilePatchBody> {
  const queryClient = useQueryClient()

  return useMutation<ProfileResponse, ApiError, ProfilePatchBody>({
    mutationFn: patchProfile,
    onSuccess: () => invalidateProfileAndRefreshWhoami(queryClient),
  })
}

/**
 * 아바타 업로드 mutation 훅.
 *
 * 성공 시 {@link invalidateProfileAndRefreshWhoami}로 profile 쿼리 invalidate + authStore.user(avatarUrl) 갱신.
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(file)`로 실행
 */
export function useUploadAvatar(): UseMutationResult<AvatarUploadResponse, ApiError, File> {
  const queryClient = useQueryClient()

  return useMutation<AvatarUploadResponse, ApiError, File>({
    mutationFn: uploadAvatar,
    onSuccess: () => invalidateProfileAndRefreshWhoami(queryClient),
  })
}

/**
 * 아바타 삭제 mutation 훅.
 *
 * 성공 시 {@link invalidateProfileAndRefreshWhoami}로 profile 쿼리 invalidate + authStore.user(avatarUrl=null) 갱신.
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate()`로 실행
 */
export function useDeleteAvatar(): UseMutationResult<void, ApiError, void> {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, void>({
    mutationFn: deleteAvatar,
    onSuccess: () => invalidateProfileAndRefreshWhoami(queryClient),
  })
}
