// OIDC provider 로그인 버튼 목록 컴포넌트 — LoginForm에서 분리된 표현 전담 컴포넌트
import { Button } from '@/components/ui/button'
import { loginStrings } from '@/i18n/ko'
import { ssoEntryUrl } from './ssoEntryUrl'
import type { OidcProvider } from '@/api/oidc'

interface OidcIdpButtonsProps {
  /** 표시할 OIDC provider 목록 — 빈 배열이면 아무것도 렌더하지 않음 */
  providers: OidcProvider[]
}

/**
 * OIDC provider 로그인 버튼 목록을 렌더한다.
 *
 * - `providers`가 비어 있으면 null을 반환해 divider 포함 영역 전체를 숨긴다.
 * - 각 버튼 클릭 시 `window.location.assign`으로 OIDC 인증 경로로 풀 네비게이션.
 *   SPA 라우터(TanStack Router)를 거치지 않고 백엔드 Spring Security OAuth2 필터가 직접 IdP로 리다이렉트해야 한다.
 *   경로는 Spring Security oauth2Login 표준 엔드포인트 `/oauth2/authorization/{registrationId}` 를 따른다.
 *
 * @param providers 활성 OIDC provider 배열
 */
export const OidcIdpButtons = ({ providers }: OidcIdpButtonsProps) => {
  if (providers.length === 0) return null

  return (
    <div className="space-y-2">
      <div className="relative flex items-center py-1">
        <div className="flex-grow border-t border-border" />
        <span className="mx-3 flex-shrink text-xs text-muted-foreground">
          {loginStrings.oidcDividerText}
        </span>
        <div className="flex-grow border-t border-border" />
      </div>
      {providers.map((provider) => (
        <Button
          key={provider.registrationId}
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => {
            window.location.assign(ssoEntryUrl('OIDC', provider.registrationId))
          }}
        >
          {loginStrings.oidcLoginButtonLabel(provider.displayName)}
        </Button>
      ))}
    </div>
  )
}
