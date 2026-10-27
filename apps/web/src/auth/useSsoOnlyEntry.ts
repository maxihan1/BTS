// 비밀번호 방식 없이 SSO 하나뿐인 설치인지 판정해 그 SSO 진입 주소를 주는 훅 — /login 바로 넘기기 (S21)
import { useQuery } from '@tanstack/react-query'
import { fetchProviders } from '@/api/providers'
import { fetchSamlIdps } from '@/api/saml'
import { fetchOidcProviders } from '@/api/oidc'
import { loginStrings } from '@/i18n/ko'
import { ssoEntryUrl } from './ssoEntryUrl'

/**
 * 로그인 수단 목록 조회 3종. `LoginForm` 도 이것을 쓴다 — 키가 같아야 `/login` 의 판정과
 * 폼이 같은 캐시를 읽는다(한 번만 부른다).
 */
export const loginProvidersQuery = { queryKey: ['auth', 'providers'], queryFn: fetchProviders, staleTime: 60_000 }
export const samlIdpsQuery = { queryKey: ['saml', 'idps'], queryFn: fetchSamlIdps, staleTime: 60_000 }
export const oidcProvidersQuery = { queryKey: ['oidc', 'providers'], queryFn: fetchOidcProviders, staleTime: 60_000 }

/** SSO 하나뿐인 설치의 그 SSO — 버튼 주소와 문구 */
export interface SsoOnlyEntry {
  href: string
  label: string
}

/**
 * 비밀번호 방식(LOCAL · LDAP)이 0 이고 SAML · OIDC 를 합쳐 하나뿐이면 그 진입점을 돌려준다.
 *
 * - `undefined` — 아직 조회 중(또는 `enabled=false`). 판정 전이라 아무것도 하지 마라.
 * - `null` — 해당 없음. 지금처럼 로그인 dialog 를 쓴다.
 * - `{ href, label }` — `/login` 은 dialog 대신 이 주소로 바로 넘긴다(로그아웃 · 실패 표시가 있으면 카드).
 *
 * providers 조회 실패는 `LoginForm` 이 LOCAL 로 폴백하므로(EC-06-06) 여기서도 「해당 없음」이다.
 * SAML · OIDC 조회 실패는 `LoginForm` 처럼 빈 목록으로 본다.
 *
 * @param enabled false 면 조회하지 않는다 — 인증된 화면마다 세 API 를 부르지 않게
 */
export function useSsoOnlyEntry(enabled = true): SsoOnlyEntry | null | undefined {
  const providers = useQuery({ ...loginProvidersQuery, enabled })
  const saml = useQuery({ ...samlIdpsQuery, enabled })
  const oidc = useQuery({ ...oidcProvidersQuery, enabled })

  if (providers.isPending || saml.isPending || oidc.isPending) return undefined
  if (providers.isError || providers.data.length > 0) return null

  const entries: SsoOnlyEntry[] = [
    ...(saml.data ?? []).map((idp) => ({
      href: ssoEntryUrl('SAML', idp.registrationId),
      label: loginStrings.samlLoginButtonLabel(idp.displayName),
    })),
    ...(oidc.data ?? []).map((provider) => ({
      href: ssoEntryUrl('OIDC', provider.registrationId),
      label: loginStrings.oidcLoginButtonLabel(provider.displayName),
    })),
  ]
  return entries.length === 1 ? entries[0] : null
}
