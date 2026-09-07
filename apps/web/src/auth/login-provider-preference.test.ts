// 로그인 방식 3단 해소 + 저장 계약 (FR-AU-01 · FR-AU-06)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  LOGIN_PROVIDER_STORAGE_KEY,
  readStoredLoginProvider,
  resolveLoginProvider,
  writeStoredLoginProvider,
} from './login-provider-preference'
import type { ProviderEntry } from '@/api/providers'

/** 서버가 주는 목록 모양 — priority 순으로 LDAP 이 앞에 오는 조직을 가정한다 */
const LDAP: ProviderEntry = { id: 'ldap', type: 'LDAP', displayName: 'Corp LDAP', priority: 0, available: true }
const LOCAL: ProviderEntry = { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 1, available: true }
const SAML: ProviderEntry = { id: 'saml-a', type: 'SAML', displayName: 'SAML A', priority: 2, available: true }

beforeEach(() => {
  window.localStorage.clear()
})

describe('resolveLoginProvider — 3단 해소', () => {
  it('저장값이 목록에 실재하면 그것을 쓴다', () => {
    expect(resolveLoginProvider({ storedId: 'saml-a', providers: [LDAP, LOCAL, SAML] })).toEqual({
      id: 'saml-a',
      source: 'stored',
    })
  })

  it('저장값이 없으면 목록 첫 원소가 아니라 `local` 이 기본이다 (Maxi 지시 2026-09-07)', () => {
    // 🛑 이것이 이 변경의 본체다. 종전 동작은 `providers[0]` 이라 LDAP 이 우선순위 0 인
    //    조직에서 Local 사용자가 매번 드롭다운을 바꿔야 했다.
    expect(resolveLoginProvider({ storedId: null, providers: [LDAP, LOCAL, SAML] })).toEqual({
      id: 'local',
      source: 'local',
    })
  })

  it('저장값이 목록에 없으면 버리고 `local` 로 내려간다 (자가 치유)', () => {
    // provider 가 조직 설정에서 사라졌거나, localStorage 가 조작됐거나 — 둘 다 같은 처리다.
    // 대조 없이 채우면 드롭다운이 빈 값으로 뜨고, 폼이 서버가 모르는 값을 제출한다(보안 렌즈 S2·S3).
    expect(resolveLoginProvider({ storedId: 'gone', providers: [LDAP, LOCAL] })).toEqual({
      id: 'local',
      source: 'local',
    })
  })

  it('`local` 이 목록에 없으면 첫 원소로 폴백한다', () => {
    expect(resolveLoginProvider({ storedId: null, providers: [LDAP, SAML] })).toEqual({
      id: 'ldap',
      source: 'first',
    })
  })

  it('목록이 비면 빈 문자열이다 — 폼이 아직 채우지 않는다', () => {
    expect(resolveLoginProvider({ storedId: null, providers: [] })).toEqual({ id: '', source: 'none' })
  })

  it('빈 문자열 저장값은 미지정으로 접는다', () => {
    expect(resolveLoginProvider({ storedId: '', providers: [LDAP, LOCAL] }).source).toBe('local')
  })
})

describe('readStoredLoginProvider — 읽기', () => {
  it('저장된 값을 그대로 돌려준다', () => {
    window.localStorage.setItem(LOGIN_PROVIDER_STORAGE_KEY, 'ldap')

    expect(readStoredLoginProvider()).toBe('ldap')
  })

  it('없으면 null 이다', () => {
    expect(readStoredLoginProvider()).toBeNull()
  })

  it('localStorage 접근이 던져도 null 로 삼킨다 — 로그인 화면이 죽지 않는다', () => {
    // 🛑 사파리 프라이빗·저장소 차단 설정에서 getItem 자체가 던진다. 그것이 로그인 폼을
    //    통째로 못 뜨게 만드는 것은 이 기능이 감당할 대가가 아니다.
    const spy = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })

    expect(readStoredLoginProvider()).toBeNull()

    spy.mockRestore()
  })
})

describe('writeStoredLoginProvider — 쓰기', () => {
  it('값을 저장한다', () => {
    writeStoredLoginProvider('ldap')

    expect(window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)).toBe('ldap')
  })

  it('빈 문자열은 저장하지 않는다 — 다음 방문에 빈 기본값이 되면 안 된다', () => {
    writeStoredLoginProvider('')

    expect(window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)).toBeNull()
  })

  it('localStorage 가 던져도 삼킨다 — 로그인 성공이 취소되면 안 된다', () => {
    // 🛑 저장 실패는 편의 기능의 실패일 뿐이다. 여기서 던지면 성공한 로그인의
    //    onSuccess 체인이 끊겨 사용자가 로그인 화면에 갇힌다.
    const spy = vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })

    expect(() => writeStoredLoginProvider('local')).not.toThrow()

    spy.mockRestore()
  })
})

describe('저장 키 — 계약', () => {
  it('키가 다른 저장 항목과 겹치지 않는다', () => {
    // 같은 오리진의 다른 기능이 같은 키를 쓰면 서로를 덮어쓴다.
    expect(LOGIN_PROVIDER_STORAGE_KEY).toBe('bts.login.provider')
  })
})
