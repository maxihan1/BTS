// 계정 연결 MSW fixture — 시드 데이터·store 헬퍼·시나리오 플래그 상수 (FR-AU-08/08b)
import type { AccountLinkResponse, LinkableProvider } from '../api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 플래그 localStorage 키 상수
// E2E에서 addInitScript로 localStorage에 플래그를 세팅해 분기를 유발한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 시나리오 플래그 키 타입 — 값은 localStorage 키 문자열 */
type ScenarioKeyMap = Record<string, string>

export const SCENARIO_KEY = {
  /** step-up 재인증 유효 플래그 — 미세팅이면 mutation 403 반환 */
  STEP_UP_VALID: 'msw:account-link:step-up-valid',
  /** reauth 실패 시나리오 — 세팅 시 POST /reauth 401 반환 */
  REAUTH_FAIL: 'msw:account-link:reauth-fail',
  /** 중복 연결 시나리오 — 세팅 시 POST /links 409 반환 */
  CONFLICT: 'msw:account-link:conflict',
  /** 공급자 불가 시나리오 — 세팅 시 POST /links 503 반환 */
  PROVIDER_UNAVAILABLE: 'msw:account-link:provider-unavailable',
  /** 마지막 인증수단 시나리오 — 세팅 시 DELETE /links/:id 409 반환 */
  LAST_METHOD: 'msw:account-link:last-method',
} as const satisfies ScenarioKeyMap

// ─────────────────────────────────────────────────────────────────────────────
// UUID는 RFC4122 v4 형식 준수 — 3번째 그룹 첫 글자 '4', 4번째 그룹 첫 글자 '8'~'b'
// Zod v4 UUID 검증 통과 필수 (zod-v4-uuid-fixture-strictness)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 LDAP 연결 fixture — id/providerId UUID v4 고정값 */
export const DEFAULT_LDAP_LINK: AccountLinkResponse = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  providerId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  providerName: 'BTS LDAP',
  providerType: 'LDAP',
  providerEnabled: true,
  externalSubjectMasked: 'ali***',
  linkedAt: '2026-05-01T00:00:00Z',
  lastLoginAt: '2026-06-01T10:00:00Z',
}

/** 기본 SAML SSO 연결 fixture */
export const DEFAULT_SSO_LINK: AccountLinkResponse = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  providerId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  providerName: 'Corp SAML',
  providerType: 'SAML',
  providerEnabled: true,
  externalSubjectMasked: 'ali***@corp.example.com',
  linkedAt: '2026-05-10T00:00:00Z',
  lastLoginAt: null,
}

/** 연결 가능한 공급자 기본 목록 fixture */
export const DEFAULT_LINKABLE_PROVIDERS: LinkableProvider[] = [
  {
    kind: 'LDAP',
    providerId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    displayName: 'BTS LDAP',
  },
  {
    kind: 'SAML',
    registrationId: 'saml-corp',
    displayName: 'Corp SAML',
  },
  {
    kind: 'OIDC',
    registrationId: 'oidc-google',
    displayName: 'Google OIDC',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 내부 인메모리 store — account-link-handlers.ts와 공유
// handlers.ts가 직접 import해 읽고, 이 모듈이 단일 진실 출처
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 인메모리 링크 store (handlers에서 직접 참조) */
export const linkStore: Map<string, AccountLinkResponse> = new Map()

/**
 * store를 초기 상태(빈 Map)로 리셋한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 이전 테스트 잔여 데이터를 제거한다.
 */
export function resetStore(): void {
  linkStore.clear()
}

/**
 * 지정한 링크 배열로 store를 시드한다.
 * 기존 항목은 id 기준으로 덮어쓴다 (중복 id 허용 — 의도적 override).
 * E2E addInitScript에서 호출하거나 테스트 beforeEach에서 초기 상태를 구성할 때 사용한다.
 */
export function seedLinks(links: AccountLinkResponse[]): void {
  for (const link of links) {
    linkStore.set(link.id, link)
  }
}
