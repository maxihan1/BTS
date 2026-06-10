// 계정 연결 mutation 훅 묶음 — reauth/link/unlink/ssoLinkStart/ssoReauthStart (FR-AU-08/08b)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  reauth,
  linkAccount,
  unlinkAccount,
  ssoLinkStart,
  ssoReauthStart,
} from '@/api/account-links'
import type {
  ReauthResponse,
  AccountLinkResponse,
  SsoLinkStartResponse,
} from '@/api/account-links'
import { ACCOUNT_LINKS_QUERY_KEY } from './useAccountLinksQuery'

/**
 * 계정 연결 관련 mutation 훅 묶음.
 *
 * **invalidate-only** — 성공 시 `queryClient.invalidateQueries`만 호출한다.
 * `setQueryData`로 캐시를 직접 교체하면 단건 GET에서만 채워지는 파생 필드가 null로 덮혀 화면이 플리커하므로 금지.
 *
 * 에러는 호출자(컴포넌트)에 전파한다. 토스트 표시는 컴포넌트 책임.
 *
 * @returns `{ reauth, link, unlink, ssoLinkStart, ssoReauthStart }` mutation 객체 묶음
 */
export function useAccountLinkMutations() {
  const queryClient = useQueryClient()

  const invalidateLinks = () =>
    void queryClient.invalidateQueries({ queryKey: ACCOUNT_LINKS_QUERY_KEY })

  /**
   * step-up 재인증을 수행한다.
   * 성공 시 `stepUpExpiresAt`(ISO 8601 문자열)을 반환 — 컴포넌트가 step-up 윈도우 만료를 추적.
   */
  const reauthMutation = useMutation<
    ReauthResponse,
    unknown,
    Parameters<typeof reauth>[0]
  >({
    mutationFn: reauth,
    onSuccess: invalidateLinks,
  })

  /**
   * LDAP 계정을 현재 사용자에게 연결한다.
   * step-up이 유효해야 한다. 실패 에러(401/409/503)는 컴포넌트로 전파.
   */
  const linkMutation = useMutation<
    AccountLinkResponse,
    unknown,
    Parameters<typeof linkAccount>[0]
  >({
    mutationFn: linkAccount,
    onSuccess: invalidateLinks,
  })

  /**
   * 지정한 계정 연결을 해제한다.
   * step-up이 유효해야 한다. 실패 에러(401/404/409)는 컴포넌트로 전파.
   */
  const unlinkMutation = useMutation<void, unknown, string>({
    mutationFn: unlinkAccount,
    onSuccess: invalidateLinks,
  })

  /**
   * SSO 공급자 계정 연결 흐름을 시작한다.
   * 성공 시 `authorizeUrl`(IdP 인증 URL)을 반환. 컴포넌트가 해당 URL로 리디렉트.
   */
  const ssoLinkStartMutation = useMutation<
    SsoLinkStartResponse,
    unknown,
    Parameters<typeof ssoLinkStart>[0]
  >({
    mutationFn: ssoLinkStart,
    onSuccess: invalidateLinks,
  })

  /**
   * SSO step-up 재인증 흐름을 시작한다.
   * 성공 시 `authorizeUrl`을 반환. 컴포넌트가 해당 URL로 리디렉트.
   */
  const ssoReauthStartMutation = useMutation<
    SsoLinkStartResponse,
    unknown,
    Parameters<typeof ssoReauthStart>[0]
  >({
    mutationFn: ssoReauthStart,
    onSuccess: invalidateLinks,
  })

  return {
    reauth: reauthMutation,
    link: linkMutation,
    unlink: unlinkMutation,
    ssoLinkStart: ssoLinkStartMutation,
    ssoReauthStart: ssoReauthStartMutation,
  }
}
