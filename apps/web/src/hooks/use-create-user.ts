// 사용자 생성 mutation 훅 — TanStack Query useMutation 얇은 래퍼
import { useMutation } from '@tanstack/react-query'
import type { UseMutationResult } from '@tanstack/react-query'
import { createUser } from '@/api/users'
import type { CreateUserParams, CreateUserResponse } from '@/api/users'
import { ApiError } from '@/api/client'

/**
 * 사용자 생성 mutation 훅.
 *
 * `createUser` API 함수를 TanStack Query `useMutation`으로 감싸
 * 컴포넌트가 `isPending / isSuccess / isError / error / data / mutate / reset`을
 * 일관된 인터페이스로 소비할 수 있게 한다.
 *
 * 에러 처리.
 * - 실패 시 `error`가 `ApiError` 인스턴스로 노출된다.
 * - 컴포넌트는 `(error.body as { code?: string })?.code`를
 *   `CreateUserErrorCode` 상수와 비교해 한글 메시지를 표시한다.
 *
 * 보안 주의.
 * - 성공 응답의 `data.temporaryPassword`는 화면 표시 전용이다.
 * - localStorage · sessionStorage · console.log 저장 절대 금지 (§1.1, DEVELOPMENT.md).
 *
 * @returns `UseMutationResult<CreateUserResponse, ApiError, CreateUserParams>`
 *
 * @example
 * ```tsx
 * const { mutate, isPending, isSuccess, data, isError, error, reset } = useCreateUser()
 * mutate({ username: 'newuser', displayName: '새 사용자' })
 * ```
 */
export function useCreateUser(): UseMutationResult<CreateUserResponse, ApiError, CreateUserParams> {
  return useMutation<CreateUserResponse, ApiError, CreateUserParams>({
    mutationFn: createUser,
  })
}
