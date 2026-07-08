// PreferencesProvider 컴포넌트 단위 테스트 — theme 적용/html lang 반영/OS 변경 추종 (FR-PF-01 Task-6 RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, waitFor, cleanup } from '@testing-library/react'
import { useAuthStore } from '@/auth/authStore'
import type { WhoamiResponse } from '@/api/schemas'
import { PreferencesProvider } from './PreferencesProvider'

const STORAGE_KEY = 'bts.theme'

/** 테스트 공통 whoami 필수 필드 — theme/locale은 각 테스트가 override */
const BASE_USER: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'local',
  userId: 'u1',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

/** matchMedia(prefers-color-scheme: dark) mock — change 이벤트 트리거를 지원한다. */
function mockMatchMedia(initialMatches: boolean) {
  let matches = initialMatches
  const listeners = new Set<() => void>()
  const mql = {
    get matches() {
      return matches
    },
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_type: string, listener: () => void) => {
      listeners.add(listener)
    },
    removeEventListener: (_type: string, listener: () => void) => {
      listeners.delete(listener)
    },
  }
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue(mql))
  return {
    fireChange(next: boolean) {
      matches = next
      listeners.forEach((listener) => listener())
    },
  }
}

/** authStore에 로그인 사용자를 세팅한다 (Header.test.tsx 선례와 동일한 setState 패턴). */
function setAuthUser(overrides: Partial<WhoamiResponse>): void {
  useAuthStore.setState({ accessToken: 'test-token', user: { ...BASE_USER, ...overrides } })
}

function renderProvider() {
  return render(
    <PreferencesProvider>
      <div>child</div>
    </PreferencesProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.classList.remove('dark')
  document.documentElement.lang = ''
})

afterEach(() => {
  // RTL 자동 cleanup(afterEach)보다 먼저 명시적으로 unmount한다 — 그렇지 않으면 아직 마운트된
  // PreferencesProvider가 남아있는 상태에서 다음 줄의 setState(user: null)가 실시간 리렌더를
  // 유발해 theme이 'system'으로 폴백하며 matchMedia를 호출한다(테스트 크래시 원인, 실제 앱 동작과
  // 무관한 테스트 정리 순서 문제). cleanup()은 멱등이라 RTL의 자체 afterEach가 다시 호출해도 안전.
  cleanup()
  useAuthStore.setState({ accessToken: null, user: null })
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.classList.remove('dark')
  document.documentElement.lang = ''
})

describe('PreferencesProvider', () => {
  it('theme=dark면 <html>에 .dark를 부착한다', () => {
    setAuthUser({ theme: 'dark' })
    renderProvider()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('theme=light면 <html>에서 .dark를 제거한다', () => {
    document.documentElement.classList.add('dark')
    setAuthUser({ theme: 'light' })
    renderProvider()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('theme=system이면 matchMedia 선호도를 따른다', () => {
    mockMatchMedia(true)
    setAuthUser({ theme: 'system' })
    renderProvider()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('theme=system이면 OS 변경 이벤트에 반응해 .dark를 토글한다', async () => {
    const media = mockMatchMedia(false)
    setAuthUser({ theme: 'system' })
    renderProvider()
    expect(document.documentElement.classList.contains('dark')).toBe(false)

    media.fireChange(true)

    await waitFor(() => {
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
  })

  it('theme이 undefined이면 system으로 폴백한다', () => {
    mockMatchMedia(true)
    setAuthUser({ theme: undefined })
    renderProvider()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('locale=en이면 <html lang="en">이 된다', () => {
    setAuthUser({ theme: 'light', locale: 'en' })
    renderProvider()
    expect(document.documentElement.lang).toBe('en')
  })

  it('locale이 undefined이면 ko로 폴백한다', () => {
    setAuthUser({ theme: 'light', locale: undefined })
    renderProvider()
    expect(document.documentElement.lang).toBe('ko')
  })

  it('적용한 theme을 bts.theme localStorage에 미러링한다 (FOUC 인라인 스크립트 재사용 대상)', () => {
    setAuthUser({ theme: 'dark' })
    renderProvider()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
  })

  it('children을 그대로 렌더한다', () => {
    setAuthUser({ theme: 'light' })
    const { getByText } = renderProvider()
    expect(getByText('child')).toBeInTheDocument()
  })
})
