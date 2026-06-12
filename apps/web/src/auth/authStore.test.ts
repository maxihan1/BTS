// Zustand authStore 단위 테스트 — sessionStorage persist + 절대 규칙(localStorage 금지) 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from './authStore'
import type { WhoamiResponse } from '@/api/schemas'

const MOCK_USER: WhoamiResponse = {
  username: 'alice',
  email: 'alice@example.com',
  authMethod: 'local',
  userId: 'user-001',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

const MOCK_TOKEN = 'eyJhbGciOiJIUzI1NiJ9.test.token'

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clearSession()
    sessionStorage.clear()
    localStorage.clear()
  })

  it('초기 상태는 accessToken/user 모두 null', () => {
    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBeNull()
    expect(user).toBeNull()
  })

  it('setSession 후 accessToken/user 반영', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBe(MOCK_TOKEN)
    expect(user).toEqual(MOCK_USER)
  })

  it('clearSession 후 accessToken/user 다시 null', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    useAuthStore.getState().clearSession()
    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBeNull()
    expect(user).toBeNull()
  })

  it('setSession 후 sessionStorage에 저장됨 (key "bts.auth")', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    const raw = sessionStorage.getItem('bts.auth')
    expect(raw).not.toBeNull()
    const parsed = JSON.parse(raw as string) as { state: { accessToken: string; user: WhoamiResponse } }
    expect(parsed.state.accessToken).toBe(MOCK_TOKEN)
    expect(parsed.state.user).toEqual(MOCK_USER)
  })

  it('clearSession 후 sessionStorage에서 제거됨', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    useAuthStore.getState().clearSession()
    expect(sessionStorage.getItem('bts.auth')).toBeNull()
  })

  it('setAccessToken만 호출 시 access만 갱신, user 유지', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    const NEW_TOKEN = 'new.refreshed.token'
    useAuthStore.getState().setAccessToken(NEW_TOKEN)
    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBe(NEW_TOKEN)
    expect(user).toEqual(MOCK_USER)
  })

  it('localStorage에는 절대 저장되지 않음 (절대 규칙)', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    expect(localStorage.getItem('bts.auth')).toBeNull()
    // localStorage 전체에 토큰 문자열이 포함되어 있지 않아야 함
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key !== null) {
        const value = localStorage.getItem(key)
        expect(value).not.toContain(MOCK_TOKEN)
      }
    }
  })

  it('sessionStorage에 데이터 있을 때 store 재생성 시 hydrate', () => {
    // sessionStorage에 미리 persist 형식으로 데이터 삽입 (새로고침 시뮬레이션)
    const persistedData = {
      state: { accessToken: 'hydrated.token', user: MOCK_USER },
      version: 0,
    }
    sessionStorage.setItem('bts.auth', JSON.stringify(persistedData))

    // Zustand persist는 모듈 로드 시 자동 hydrate하지만,
    // 테스트 환경에서는 수동으로 rehydrate를 호출해야 한다
    useAuthStore.persist.rehydrate()

    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBe('hydrated.token')
    expect(user).toEqual(MOCK_USER)
  })
})
