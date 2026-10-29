// 로그인 카드가 쓰는 브라우저 기억 — 로그아웃 표시 · 마지막 계정 · 바로 넘긴 시각 (S21 · S22)

/** 바로 넘긴 시각(sessionStorage) — 넘긴 지 얼마 안 돼 `/login` 에 또 오면 다시 넘기지 않는다 */
export const AUTO_SSO_AT_KEY = 'bts.login.auto-sso-at'
/**
 * 로그아웃 표시(localStorage) — 새 탭 · 북마크로 열어도 Auth 로 바로 넘기지 않고 카드를 보인다.
 * BTS 로그아웃은 Auth SSO 세션을 끝내지 않아서 넘기면 곧장 다시 로그인된다.
 */
const LOGGED_OUT_KEY = 'bts.login.logged-out'
/** 마지막 계정(localStorage) — 카드의 「이 계정으로 로그인」. 토큰이 아니라 이름뿐이다(DEVELOPMENT §1.4-18) */
const LAST_ACCOUNT_KEY = 'bts.login.last-account'

export interface LastAccount {
  username: string
  name: string
}

/** 저장소가 막힌 브라우저(사생활 보호 모드 등) — 기억만 잃고 로그인은 산다 */
function quietly<T>(fn: () => T, fallback: T): T {
  try {
    return fn()
  } catch {
    return fallback
  }
}

export const markLoggedOut = () => quietly(() => localStorage.setItem(LOGGED_OUT_KEY, '1'), undefined)
export const clearLoggedOut = () => quietly(() => localStorage.removeItem(LOGGED_OUT_KEY), undefined)
export const isLoggedOut = () => quietly(() => localStorage.getItem(LOGGED_OUT_KEY) !== null, false)

export const forgetLastAccount = () => quietly(() => localStorage.removeItem(LAST_ACCOUNT_KEY), undefined)

export function readLastAccount(): LastAccount | null {
  return quietly(() => {
    const raw: unknown = JSON.parse(localStorage.getItem(LAST_ACCOUNT_KEY) ?? 'null')
    if (typeof raw !== 'object' || raw === null) return null
    const { username, name } = raw as Record<string, unknown>
    return typeof username === 'string' && username !== '' && typeof name === 'string' ? { username, name } : null
  }, null)
}

/**
 * 로그인 성공 — 계정을 기억하고, 로그아웃 표시와 바로 넘긴 시각을 지운다.
 * 시각을 남기면 30초 안에 `/login` 에 다시 왔을 때(만료 등) 바로 넘기지 않고 고리 오류 카드가 뜬다.
 */
export function rememberLogin(account: LastAccount) {
  quietly(() => {
    localStorage.setItem(LAST_ACCOUNT_KEY, JSON.stringify(account))
    localStorage.removeItem(LOGGED_OUT_KEY)
    sessionStorage.removeItem(AUTO_SSO_AT_KEY)
  }, undefined)
}
