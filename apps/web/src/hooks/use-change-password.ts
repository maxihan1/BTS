// 비밀번호 변경 mutation 훅 — TanStack Query useMutation 얇은 래퍼
import { useMutation } from '@tanstack/react-query'
import type { UseMutationResult } from '@tanstack/react-query'
import { changePassword } from '@/api/password'
import type { ChangePasswordParams, ChangePasswordSuccess } from '@/api/password'
import { ApiError } from '@/api/client'

/**
 * 비밀번호 변경 mutation 훅.
 *
 * `changePassword` API 함수를 TanStack Query `useMutation`으로 감싸
 * 컴포넌트가 `isPending / isSuccess / isError / error / mutate / reset`을
 * 일관된 인터페이스로 소비할 수 있게 한다.
 *
 * 에러 처리.
 * - onError 시 `error`가 `ApiError` 인스턴스로 노출된다.
 * - 컴포넌트는 `error.body.code`를 `PasswordChangeErrorCode` 상수와 비교해 한글 메시지를 표시한다.
 *
 * 제네릭 명시 이유.
 * - `useMutation<TData, TError, TVariables>` 를 명시하지 않으면 `data`가 `unknown` 타입이 돼
 *   교차파일 타입 에러가 발생한다 (learnings 2026-05-27 — useMutation 제네릭 미명시 위험).
 *
 * @returns UseMutationResult — mutate(params) 호출로 비밀번호 변경 실행
 */
export function useChangePassword(): UseMutationResult<ChangePasswordSuccess, ApiError, ChangePasswordParams> {
  return useMutation<ChangePasswordSuccess, ApiError, ChangePasswordParams>({
    mutationFn: changePassword,
  })
}
