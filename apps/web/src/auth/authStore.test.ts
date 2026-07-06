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
    useAuthStore.setState({ avatarVersion: 0 })
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

  it('setUser 호출 시 accessToken은 보존하고 user만 교체 (FR-PR-01 D6 Task 5)', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    const fresh: WhoamiResponse = {
      ...MOCK_USER,
      displayName: '새표시이름',
      avatarUrl: '/api/v1/users/user-001/avatar',
    }

    useAuthStore.getState().setUser(fresh)

    const { accessToken, user } = useAuthStore.getState()
    expect(accessToken).toBe(MOCK_TOKEN)
    expect(user).toEqual(fresh)
  })

  it('setUser 후 sessionStorage에도 갱신된 user가 반영됨', () => {
    useAuthStore.getState().setSession({ accessToken: MOCK_TOKEN, user: MOCK_USER })
    const fresh: WhoamiResponse = { ...MOCK_USER, displayName: '새표시이름' }

    useAuthStore.getState().setUser(fresh)

    const raw = sessionStorage.getItem('bts.auth')
    expect(raw).not.toBeNull()
    const parsed = JSON.parse(raw as string) as { state: { accessToken: string; user: WhoamiResponse } }
    expect(parsed.state.accessToken).toBe(MOCK_TOKEN)
    expect(parsed.state.user).toEqual(fresh)
  })

  it('초기 avatarVersion은 0이다', () => {
    expect(useAuthStore.getState().avatarVersion).toBe(0)
  })

  it('bumpAvatarVersion 호출 시 avatarVersion이 1씩 증가한다 (아바타 교체 캐시버스트)', () => {
    useAuthStore.getState().bumpAvatarVersion()
    expect(useAuthStore.getState().avatarVersion).toBe(1)
    useAuthStore.getState().bumpAvatarVersion()
    expect(useAuthStore.getState().avatarVersion).toBe(2)
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
