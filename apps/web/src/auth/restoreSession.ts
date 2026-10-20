// 앱 기동 때 access token 이 없으면 refresh cookie 로 세션을 한 번 되살린다 — SSO 콜백은 cookie 만 굽고 /dashboard 로 보낸다
import { refreshSession } from '@/api/client'
import { useAuthStore } from './authStore'

/**
 * SSO(OIDC · SAML) 성공 핸들러는 access token 을 SPA 에 넘기지 않고 refresh_token cookie 만 굽는다.
 * sessionStorage 가 빈 채로 `/dashboard` 에 착지하면 `requireAuth` 가 곧장 `/login` 으로 보내므로,
 * 라우터를 띄우기 전에 refresh 를 한 번 시도한다. 새 탭에서 열 때도 같은 효과다.
 *
 * 실패(cookie 없음 · 만료)는 「로그인 안 됨」으로 앱을 띄운다 — `doRefresh` 는 세션이 없던 경우 만료 모달을 띄우지 않는다.
 * refresh 는 됐는데 whoami 가 실패하면 token 만 남아 가드가 user 없이 통과하므로 그 반쪽 세션을 지운다.
 */
export async function restoreSession(): Promise<void> {
  if (useAuthStore.getState().accessToken) return
  try {
    await refreshSession()
  } catch (error) {
    if (useAuthStore.getState().user === null) useAuthStore.getState().clearSession()
    console.warn('세션 복구 안 됨 — 로그인 화면으로 시작한다', error)
  }
}
