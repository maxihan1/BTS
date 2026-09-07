// 로그인 방식(provider) 기본값 3단 해소 + 마지막 성공 방식 저장 — FR-AU-01 · FR-AU-06
import type { ProviderEntry } from '@/api/providers'

/**
 * 마지막으로 **로그인에 성공한** provider id 를 담는 localStorage 키.
 *
 * 🛑 `bts.` 접두를 붙인다 — 같은 오리진의 다른 기능이 같은 키를 쓰면 서로를 덮어쓴다.
 *    값 계약은 판별식(`login-provider-preference.test.ts` §저장 키)이 얼려 둔다.
 */
export const LOGIN_PROVIDER_STORAGE_KEY = 'bts.login.provider'

/** 해소된 provider 가 어디서 왔는지. 호출자가 **다시 저장할지** 판단하는 데 쓴다 */
export type LoginProviderSource = 'stored' | 'local' | 'first' | 'none'

/** {@link resolveLoginProvider} 반환값 */
export interface LoginProviderResolution {
  /** 폼 기본값으로 쓸 provider id. 목록이 비면 `''` */
  readonly id: string
  /** 출처 — `'stored'` 면 이미 저장돼 있으므로 write 를 생략한다 */
  readonly source: LoginProviderSource
}

/** {@link resolveLoginProvider} 입력 — 조회는 호출자가 끝낸 뒤 주입한다 */
export interface LoginProviderInput {
  /** localStorage 에 저장된 id. 없으면 null */
  readonly storedId: string | null | undefined
  /** 서버가 준 사용 가능 provider 목록 */
  readonly providers: readonly ProviderEntry[]
}

/** LOCAL provider 의 id. 서버 계약상 고정값이다(`LOCAL_FALLBACK` 과 같은 값) */
const LOCAL_PROVIDER_ID = 'local'

/**
 * 로그인 방식 기본값을 3단으로 해소한다 (Maxi 확정 2026-09-07).
 *
 * ```
 * ① 저장값이 목록에 실재      → 'stored'
 * ② 목록에 `local` 이 있다     → 'local'   ← 기본값
 * ③ 목록의 첫 원소            → 'first'
 * ④ 목록이 비었다             → '' · 'none'
 * ```
 *
 * **왜 `local` 이 `providers[0]` 을 이기는가.** 서버는 `priority` 순으로 주고 조직에 따라
 * LDAP 이 0 이다. 그러면 Local 계정 사용자가 **매번** 드롭다운을 바꿔야 했다. 종전 동작이
 * 정확히 그것이었다.
 *
 * 🛑 **저장값은 반드시 목록과 대조한다.** localStorage 는 같은 오리진의 스크립트가 쓸 수 있고
 *    (XSS 시 공격자 통제) provider 는 조직 설정에서 사라질 수도 있다. 대조 없이 채우면
 *    드롭다운이 빈 값으로 뜨고 폼이 **서버가 모르는 값**을 제출한다. 두 위험을 같은 한 줄이
 *    막는다 — 계획 §보안 렌즈 S2·S3. `lib/active-project.ts` 가 같은 이유로 같은 대조를 한다.
 */
export function resolveLoginProvider({
  storedId,
  providers,
}: LoginProviderInput): LoginProviderResolution {
  const has = (id: string): boolean => providers.some((provider) => provider.id === id)

  if (typeof storedId === 'string' && storedId.length > 0 && has(storedId)) {
    return { id: storedId, source: 'stored' }
  }
  if (has(LOCAL_PROVIDER_ID)) {
    return { id: LOCAL_PROVIDER_ID, source: 'local' }
  }
  const first = providers[0]
  if (first !== undefined) {
    return { id: first.id, source: 'first' }
  }
  return { id: '', source: 'none' }
}

/**
 * 저장된 provider id 를 읽는다. 없거나 읽기가 실패하면 `null`.
 *
 * 🛑 접근 자체가 던지는 환경이 있다(사파리 프라이빗 · 사이트 데이터 차단). 그것이 로그인
 *    폼을 통째로 못 뜨게 만드는 것은 이 편의 기능이 감당할 대가가 아니다.
 */
export function readStoredLoginProvider(): string | null {
  try {
    return window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)
  } catch {
    return null
  }
}

/**
 * 로그인에 **성공한** provider id 를 저장한다.
 *
 * 🛑 빈 문자열은 쓰지 않는다 — 다음 방문에 빈 기본값을 만든다.
 * 🛑 실패를 삼킨다. 여기서 던지면 성공한 로그인의 `onSuccess` 체인이 끊겨 사용자가
 *    로그인 화면에 갇힌다. 저장 실패는 편의 기능의 실패일 뿐이다.
 */
export function writeStoredLoginProvider(providerId: string): void {
  if (providerId.length === 0) return
  try {
    window.localStorage.setItem(LOGIN_PROVIDER_STORAGE_KEY, providerId)
  } catch {
    // 저장 실패는 무시한다 — 위 KDoc 참조
  }
}
