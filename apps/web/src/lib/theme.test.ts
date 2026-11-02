// lib/theme.ts 순수 로직 단위 테스트 — resolveTheme/applyTheme/read·writeStoredTheme (FR-PF-01 Task-6 RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { resolveTheme, applyTheme, readStoredTheme, writeStoredTheme, isTheme } from './theme'

const STORAGE_KEY = 'bts.theme'

/** matchMedia(prefers-color-scheme: dark)를 고정 matches 값으로 mock한다. */
function mockMatchMedia(matches: boolean): void {
  const mql = {
    matches,
    media: '(prefers-color-scheme: dark)',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue(mql))
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.classList.remove('dark')
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.classList.remove('dark')
})

describe('resolveTheme', () => {
  it("'dark'는 그대로 'dark'를 반환한다", () => {
    expect(resolveTheme('dark')).toBe('dark')
  })

  it("'light'는 그대로 'light'를 반환한다", () => {
    expect(resolveTheme('light')).toBe('light')
  })

  it("'system'이고 matchMedia가 다크를 선호하면 'dark'를 반환한다", () => {
    mockMatchMedia(true)
    expect(resolveTheme('system')).toBe('dark')
  })

  it("'system'이고 matchMedia가 라이트를 선호하면 'light'를 반환한다", () => {
    mockMatchMedia(false)
    expect(resolveTheme('system')).toBe('light')
  })
})

describe('applyTheme', () => {
  it("'dark'면 documentElement에 .dark 클래스를 부착한다", () => {
    applyTheme('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it("'light'면 documentElement에서 .dark 클래스를 제거한다", () => {
    document.documentElement.classList.add('dark')
    applyTheme('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it("'system'이고 matchMedia가 다크를 선호하면 .dark를 부착한다", () => {
    mockMatchMedia(true)
    applyTheme('system')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it("'system'이고 matchMedia가 라이트를 선호하면 .dark를 제거한다", () => {
    document.documentElement.classList.add('dark')
    mockMatchMedia(false)
    applyTheme('system')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  // Q3 — 밝게 · 어둡게 전환은 화면 전체가 0.2초 겹쳐 바뀐다(공통 정책 §4). 바뀔 때만 감싼다
  it('테마가 실제로 바뀌면 View Transitions 로 감싸 바꾼다', () => {
    document.documentElement.classList.remove('dark')
    const start = vi.fn((cb: () => void) => cb())
    Object.assign(document, { startViewTransition: start })
    applyTheme('dark')
    expect(start).toHaveBeenCalledTimes(1)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    Reflect.deleteProperty(document, 'startViewTransition')
  })

  it('이미 같은 테마면 전환 움직임을 걸지 않는다 — 첫 마운트 · 같은 값 재적용', () => {
    document.documentElement.classList.add('dark')
    const start = vi.fn((cb: () => void) => cb())
    Object.assign(document, { startViewTransition: start })
    applyTheme('dark')
    expect(start).not.toHaveBeenCalled()
    Reflect.deleteProperty(document, 'startViewTransition')
  })
})

describe('readStoredTheme / writeStoredTheme', () => {
  it('writeStoredTheme가 bts.theme 키로 저장하고 readStoredTheme가 그대로 읽는다', () => {
    writeStoredTheme('dark')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(readStoredTheme()).toBe('dark')
  })

  it('저장된 값이 없으면 null을 반환한다', () => {
    expect(readStoredTheme()).toBeNull()
  })

  it('저장된 값이 유효한 Theme가 아니면 null을 반환한다', () => {
    localStorage.setItem(STORAGE_KEY, 'not-a-theme')
    expect(readStoredTheme()).toBeNull()
  })
})

describe('isTheme', () => {
  it("'light'/'dark'/'system'는 유효한 Theme다", () => {
    expect(isTheme('light')).toBe(true)
    expect(isTheme('dark')).toBe(true)
    expect(isTheme('system')).toBe(true)
  })

  it('유효하지 않은 문자열은 false를 반환한다', () => {
    expect(isTheme('blue')).toBe(false)
  })
})
