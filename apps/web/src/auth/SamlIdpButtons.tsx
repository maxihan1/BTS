// SAML IdP 로그인 버튼 목록 컴포넌트 — LoginForm에서 분리된 표현 전담 컴포넌트
import { Button } from '@/components/ui/button'
import { loginStrings } from '@/i18n/ko'
import type { SamlIdp } from '@/api/saml'

interface SamlIdpButtonsProps {
  /** 표시할 SAML IdP 목록 — 빈 배열이면 아무것도 렌더하지 않음 */
  idps: SamlIdp[]
}

/**
 * SAML IdP 로그인 버튼 목록을 렌더한다.
 *
 * - `idps`가 비어 있으면 null을 반환해 divider 포함 영역 전체를 숨긴다.
 * - 각 버튼 클릭 시 `window.location.assign`으로 SP-initiated SAML 인증 경로로 풀 네비게이션.
 *   SPA 라우터(TanStack Router)를 거치지 않고 백엔드 SAML 필터가 직접 IdP로 리다이렉트해야 한다.
 *
 * @param idps 활성 SAML IdP 배열
 */
export const SamlIdpButtons = ({ idps }: SamlIdpButtonsProps) => {
  if (idps.length === 0) return null

  return (
    <div className="space-y-2">
      <div className="relative flex items-center py-1">
        <div className="flex-grow border-t border-border" />
        <span className="mx-3 flex-shrink text-xs text-muted-foreground">
          {loginStrings.samlDividerText}
        </span>
        <div className="flex-grow border-t border-border" />
      </div>
      {idps.map((idp) => (
        <Button
          key={idp.registrationId}
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => {
            window.location.assign(`/sso/saml2/authenticate/${idp.registrationId}`)
          }}
        >
          {loginStrings.samlLoginButtonLabel(idp.displayName)}
        </Button>
      ))}
    </div>
  )
}
