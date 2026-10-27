// 로그인 라우트 — main 랜드마크를 소유한다. 폼은 전역 LoginDialog 가 그리고, SSO 하나뿐인 설치는 여기서 바로 넘기거나 남는 카드를 그린다
import { useEffect, useState } from 'react'
import { CircleAlert, Key } from 'lucide-react'
import { AuthBackdrop } from '@/auth/AuthBackdrop'
import { useSsoOnlyEntry, type SsoOnlyEntry } from '@/auth/useSsoOnlyEntry'
import { ssoLoginScreenStrings } from '@/i18n/ko'

/** 바로 넘긴 시각 — 넘긴 지 얼마 안 돼 `/login` 에 또 오면 다시 넘기지 않는다 */
export const AUTO_SSO_AT_KEY = 'bts.login.auto-sso-at'
/** 이 안에 돌아오면 고리로 본다. Auth 왕복은 몇 초면 끝난다 */
const AUTO_SSO_LOOP_MS = 30_000

type Notice = 'loggedOut' | 'error'

/**
 * SSO 로 바로 넘기지 않고 화면을 보일 이유. 없으면 null — 바로 넘긴다 (S21).
 *
 * - `?error` — SSO 실패. Spring `oauth2Login` · `saml2Login` 의 기본 실패 주소가 `/login?error` 다.
 *   다시 넘기면 같은 실패를 되풀이한다.
 * - `?logged_out` — 로그아웃 직후(`useLogoutMutation`). BTS 로그아웃은 Auth 세션을 끝내지 않아서
 *   넘기면 로그아웃하자마자 다시 로그인된다.
 * - 방금 넘겼는데 또 왔다 — Auth 는 통과했는데 BTS 세션을 못 이어받은 것이다(쿠키 설정 등).
 *   넘기면 두 서버 사이를 끝없이 오간다.
 */
function readNotice(): Notice | null {
  const search = new URLSearchParams(window.location.search)
  if (search.has('error')) return 'error'
  if (search.has('logged_out')) return 'loggedOut'
  if (Date.now() - Number(sessionStorage.getItem(AUTO_SSO_AT_KEY)) < AUTO_SSO_LOOP_MS) return 'error'
  return null
}

export const LoginPage = () => {
  const ssoOnly = useSsoOnlyEntry()
  // 첫 렌더에 한 번만 읽는다 — 시간이 지나 판정이 바뀌어 보던 카드가 넘어가 버리지 않게
  const [notice] = useState(readNotice)
  const autoHref = ssoOnly != null && notice === null ? ssoOnly.href : null

  useEffect(() => {
    if (autoHref === null) return
    sessionStorage.setItem(AUTO_SSO_AT_KEY, String(Date.now()))
    window.location.assign(autoHref)
  }, [autoHref])

  // 로그인 라우트는 `_shell`(ShellLayout) 밖 rootRoute 직속이라 ShellLayout의 <main>을
  // 물려받지 못한다 — main 랜드마크를 이 컴포넌트가 직접 소유해야 한다(C3, D-D).
  // e2e landmark.spec.ts L2 가 "미인증 /login → main 1개" 를 계약으로 잡고 있다.
  if (ssoOnly == null) {
    return (
      <main>
        <AuthBackdrop />
      </main>
    )
  }
  // 바로 넘기는 동안은 카드 없이 바탕만 — 버튼이 잠깐 보였다 사라지면 화면을 두 번 거치는 셈이다
  return (
    <main className="flex min-h-screen items-center justify-center bg-[#0C0B0A] px-4 font-['IBM_Plex_Sans_KR',system-ui,sans-serif] break-keep">
      {notice !== null && <SsoLoginCard entry={ssoOnly} notice={notice} />}
    </main>
  )
}

/**
 * 남는 화면 카드 — 세 앱 공통 B안(어두운 바탕 + 주황) ④. BTS 디자인 token 이 아니라 시안 값을 그대로 쓴다
 * (Maxi 2026-10-27 — 세 앱이 같은 로그인 화면). 세션 만료 dialog 는 BTS 모양 그대로다.
 *
 * 주 버튼은 `<a href>` — 누르면 풀 네비게이션으로 SSO 를 시작한다(`OidcIdpButtons` 의 `location.assign` 과 같은 효과).
 */
const SsoLoginCard = ({ entry, notice }: { entry: SsoOnlyEntry; notice: Notice }) => (
  <section
    aria-labelledby="sso-login-heading"
    className="flex w-[360px] max-w-full flex-col gap-[22px] rounded-[10px] border border-[#2C2A27] bg-[#1B1A18] p-7 shadow-[0_16px_40px_rgba(0,0,0,0.5)]"
  >
    <div className="flex flex-col gap-3.5">
      <div className="flex size-9 items-center justify-center rounded-[8px] bg-[#FF7A1A]">
        <Key aria-hidden="true" className="size-5 text-[#1A0E05]" strokeWidth={2} />
      </div>
      <div className="flex flex-col gap-1.5">
        <p className="text-[13px] font-semibold text-[#A8A196]">{ssoLoginScreenStrings.appName}</p>
        <h1 id="sso-login-heading" className="text-[20px] leading-[1.35] font-semibold text-[#F1EEE8]">
          {notice === 'error' ? ssoLoginScreenStrings.errorHeading : ssoLoginScreenStrings.loggedOutHeading}
        </h1>
        {notice === 'loggedOut' && (
          <p className="text-[14px] leading-[1.5] text-[#A8A196]">{ssoLoginScreenStrings.loggedOutDescription}</p>
        )}
      </div>
    </div>
    {notice === 'error' && (
      <div
        role="alert"
        className="flex items-start gap-2 rounded-[6px] border border-[#5A2620] bg-[#2A1512] px-3 py-2.5 text-[14px] leading-[1.45] text-[#FF9C8F]"
      >
        <CircleAlert aria-hidden="true" className="mt-px size-[18px] shrink-0" strokeWidth={2} />
        <span>{ssoLoginScreenStrings.errorMessage}</span>
      </div>
    )}
    <a
      href={entry.href}
      className="flex h-11 items-center justify-center rounded-[6px] border-b-[3px] border-[#C85400] bg-[#FF7A1A] text-[15px] font-semibold text-[#1A0E05] no-underline outline-none focus-visible:shadow-[0_0_0_3px_rgba(255,122,26,0.28)]"
    >
      {entry.label}
    </a>
  </section>
)
