// 최근 본 이슈 MRU localStorage 영속 스토어 단위 테스트 — FR-UX-08 PR-B Task 1 (RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRecentIssues, RECENT_ISSUES_STORAGE_KEY, MAX_RECENT_ISSUES } from '../use-recent-issues'

describe('useRecentIssues — MRU 상한 5 localStorage 영속 스토어 (FR2)', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 유지된다 — 매 테스트 전 리셋.
    // 리셋이 없으면 "나머지는 비어 있다" 류 단언이 거짓통과·거짓실패 둘 다 가능하다
    // (FR-UX-08 PR-A plan 리뷰 BLOCKER B2 의 재발 지점).
    useRecentIssues.setState({ recentIssueKeys: [] })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-RI-1: 초기 상태는 빈 목록이다', () => {
    const { result } = renderHook(() => useRecentIssues())
    expect(result.current.recentIssueKeys).toEqual([])
  })

  it('T-RI-2: push 하면 목록 맨 앞에 들어가고 localStorage 에 함께 저장된다', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
    })

    expect(result.current.recentIssueKeys).toEqual(['ATLAS-12'])
    expect(localStorage.getItem(RECENT_ISSUES_STORAGE_KEY)).toBe(JSON.stringify(['ATLAS-12']))
  })

  it('T-RI-3: 나중에 push 한 것이 앞에 온다 (MRU 정렬, S8)', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
      result.current.pushRecentIssue('INFRA-3')
    })

    expect(result.current.recentIssueKeys).toEqual(['INFRA-3', 'ATLAS-12'])
  })

  it('T-RI-4: 이미 목록에 있는 키를 push 하면 맨 앞으로 이동하고 중복되지 않는다', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
      result.current.pushRecentIssue('INFRA-3')
      result.current.pushRecentIssue('ZETA-7')
      result.current.pushRecentIssue('ATLAS-12')
    })

    expect(result.current.recentIssueKeys).toEqual(['ATLAS-12', 'ZETA-7', 'INFRA-3'])
  })

  it('T-RI-5 (E5): 이미 맨 앞인 키를 다시 push 하면 localStorage write 를 생략한다', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
    })

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(result.current.recentIssueKeys).toEqual(['ATLAS-12'])
  })

  it('T-RI-6 (E6): 상한을 넘으면 가장 오래된 항목을 축출하고 길이가 정확히 상한이다', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      // 상한 + 1 개를 순서대로 넣는다 — ATLAS-0 이 가장 오래된 항목이 된다
      for (let i = 0; i <= MAX_RECENT_ISSUES; i += 1) {
        result.current.pushRecentIssue(`ATLAS-${String(i)}`)
      }
    })

    expect(result.current.recentIssueKeys).toHaveLength(MAX_RECENT_ISSUES)
    expect(result.current.recentIssueKeys).not.toContain('ATLAS-0')
    expect(result.current.recentIssueKeys[0]).toBe(`ATLAS-${String(MAX_RECENT_ISSUES)}`)
  })

  it('T-RI-7: 모듈 초기화 시 저장값을 복원한다 (실제 초기화 경로)', async () => {
    localStorage.setItem(RECENT_ISSUES_STORAGE_KEY, JSON.stringify(['INFRA-3', 'ATLAS-12']))

    // 싱글턴이라 모듈을 재로드해야 초기화 코드가 다시 탄다
    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual(['INFRA-3', 'ATLAS-12'])
  })

  it('T-RI-8 (E4): 저장값이 배열이 아니면 빈 목록으로 폴백한다', async () => {
    localStorage.setItem(RECENT_ISSUES_STORAGE_KEY, JSON.stringify('ATLAS-12'))

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual([])
  })

  it('T-RI-9 (E4): 저장값 원소가 문자열이 아니면 빈 목록으로 폴백한다', async () => {
    localStorage.setItem(RECENT_ISSUES_STORAGE_KEY, JSON.stringify(['ATLAS-12', { key: 'X-1' }]))

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual([])
  })

  it('T-RI-10 (E4): 잘못된 JSON → 빈 목록 (fail-safe)', async () => {
    localStorage.setItem(RECENT_ISSUES_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual([])
  })

  it('T-RI-11 (E4): 저장값이 상한을 넘겨 변조돼도 상한까지만 복원한다', async () => {
    localStorage.setItem(
      RECENT_ISSUES_STORAGE_KEY,
      JSON.stringify(['A-1', 'A-2', 'A-3', 'A-4', 'A-5', 'A-6', 'A-7']),
    )

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toHaveLength(MAX_RECENT_ISSUES)
  })

  it('T-RI-12 (PR-A CR2 동형): 저장값에 중복이 있어도 복원 시 제거한다', async () => {
    // push 경로에만 중복 제거가 있고 복원 경로에 없으면 사이드바가 같은 이슈를 두 번 렌더하고
    // React `key` 중복 경고가 난다. PR-A 코드리뷰 CR2 가 정확히 이 갭이었다 — 선차단한다.
    localStorage.setItem(
      RECENT_ISSUES_STORAGE_KEY,
      JSON.stringify(['ATLAS-12', 'INFRA-3', 'ATLAS-12']),
    )

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-12', 'INFRA-3'])
  })

  it('T-RI-13 (NFR2): localStorage 읽기가 throw 해도 빈 목록으로 폴백한다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    vi.resetModules()
    const fresh = await import('../use-recent-issues')

    expect(fresh.useRecentIssues.getState().recentIssueKeys).toEqual([])
  })

  it('T-RI-14 (NFR2): localStorage 쓰기가 throw 해도 메모리 상태는 정상 갱신된다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
    })

    expect(result.current.recentIssueKeys).toEqual(['ATLAS-12'])
  })

  it('T-RI-15: 빈 문자열 키는 기록하지 않는다', () => {
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('')
    })

    expect(result.current.recentIssueKeys).toEqual([])
  })

  it('T-RI-16: 서로 다른 훅 인스턴스가 상태를 공유한다 (라우트 기록 ↔ 사이드바 소비)', () => {
    const { result: instanceA } = renderHook(() => useRecentIssues())
    const { result: instanceB } = renderHook(() => useRecentIssues())

    act(() => {
      instanceA.current.pushRecentIssue('ATLAS-12')
    })

    expect(instanceB.current.recentIssueKeys).toEqual(['ATLAS-12'])
  })

  it('T-RI-17 (NFR1): 저장값에는 이슈 키 형태만 담긴다 — 제목·본문을 저장하지 않는다', () => {
    // ADR §D4 — 로그아웃이 localStorage 를 지우지 않으므로(authStore.clearSession 은
    // sessionStorage 만 정리) 제목을 저장하면 같은 브라우저의 다음 사용자가 읽는다.
    const { result } = renderHook(() => useRecentIssues())

    act(() => {
      result.current.pushRecentIssue('ATLAS-12')
      result.current.pushRecentIssue('INFRA-3')
    })

    const raw = localStorage.getItem(RECENT_ISSUES_STORAGE_KEY)
    expect(raw).not.toBeNull()
    const parsed: unknown = JSON.parse(raw ?? '[]')
    expect(Array.isArray(parsed)).toBe(true)
    for (const entry of parsed as unknown[]) {
      expect(typeof entry).toBe('string')
      // 이슈 키 형태만 허용 — 객체·제목 문자열이 섞이면 여기서 걸린다
      expect(entry as string).toMatch(/^[A-Z][A-Z0-9]*-\d+$/)
    }
  })
})
