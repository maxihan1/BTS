// 경로 파라미터 → 활성 프로젝트 기록기 테스트 — FR-UX-07 Task 5 + 코드리뷰 CR3 재설계
//
// 이 훅이 없으면 S4 가 성립하지 않는다 — /projects/INFRA/board 를 보다가 사이드바 "이슈"를
// 눌러도 INFRA 가 아니라 이전 저장값이 열린다. plan 독립 리뷰 BLOCKER B1.
//
// 두 축을 분리한다.
// - 요청이 나가는지(F2) 는 캐시를 **미주입**한 렌더에서 스파이 카운터로 본다(T-TR-1, T-TR-3b).
//   캐시가 신선(staleTime 30초)하면 `enabled` 값과 무관하게 재조회가 안 돌아 이 축을 가린다
//   — 그래서 이 축을 검증하는 테스트는 반드시 미주입이어야 한다.
// - 가드가 하중을 지는지(CR3) 는 캐시를 **선주입**한 렌더에서 동기 단언으로 본다
//   (T-TR-2·3a·5·6·7). 선주입하면 `projects` 가 마운트 시점부터 정의돼 `isKnownProject` 가
//   우회로가 되지 못한다.
// 두 방식을 테스트마다 명시적으로 선택하고 섞지 않는다 — 섞으면(예: 선주입 렌더에서 요청
// 카운터를 단언) 신선한 캐시가 네트워크 축을 가려 뮤테이션을 되주입해도 통과하는 공허한
// 단언이 된다(T-TR-3 최초안에서 실측 확인 — `{ enabled }` 인자 제거가 RED를 만들지 못했다).
import React from 'react'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useActiveProject } from '../use-active-project'
import { useRecentProjects } from '../use-recent-projects'
import { useTrackActiveProject } from '../use-track-active-project'

/**
 * 접근 가능 프로젝트 목록 — CR3 가드가 이 목록과 대조한다.
 * `id` 는 유효 UUID 여야 한다(`projectSchema.id` 가 `z.string().uuid()`).
 */
const PROJECTS = [
  { id: '11111111-1111-4111-8111-111111111111', key: 'INFRA', name: 'Infra' },
  { id: '22222222-2222-4222-8222-222222222222', key: 'ATLAS', name: 'Atlas' },
]

/**
 * @param seed 마운트 시점부터 목록을 캐시에 심는다 — 가드가 유일한 판별자가 되도록.
 *   `useProjects` 의 staleTime 이 30초라 선주입하면 재조회가 돌지 않는다(동기 단언 성립).
 */
function renderTracker(
  enabled: boolean,
  { seed = true, projects = PROJECTS }: { seed?: boolean; projects?: { id: string; key: string; name: string }[] } = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  if (seed) client.setQueryData(['projects', false], projects)
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { ...renderHook(() => useTrackActiveProject(enabled), { wrapper }), client }
}

/** useParams 반환값 — 테스트마다 갈아끼운다 */
let mockParams: Record<string, string | undefined> = {}

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockParams,
}))

describe('useTrackActiveProject — 경로 파라미터 기록기 (B1 + CR3)', () => {
  /** `/api/v1/projects` 요청 횟수 — T-TR-1(요청 나감) / T-TR-3b(요청 안 나감) 양성·음성 짝 */
  let projectsCalls = 0

  beforeEach(() => {
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
    // FR-UX-08 T3 — 최근 목록도 모듈 전역 zustand 싱글턴이라 테스트 간 누출을 차단한다
    useRecentProjects.setState({ recentProjectKeys: [] })
    mockParams = {}
    projectsCalls = 0
    server.use(
      http.get('/api/v1/projects', () => {
        projectsCalls += 1
        return HttpResponse.json({ data: PROJECTS })
      }),
    )
  })

  it('T-TR-1 (S4 + 요청 양성 대조): 목록 도착 후 경로 파라미터 키를 저장값에 기록한다', async () => {
    mockParams = { projectKey: 'INFRA' }

    renderTracker(true, { seed: false })

    // 목록 도착 전 — 아직 기록되지 않았다
    expect(useActiveProject.getState().activeProjectKey).toBeNull()

    await waitFor(() => expect(useActiveProject.getState().activeProjectKey).toBe('INFRA'))
    // 양성 대조군 — 인증 경로에서는 실제로 요청이 나가야 한다. 없으면 "0"이 판별식 고장인지
    // 진짜 0인지 구분되지 않는다.
    expect(projectsCalls).toBeGreaterThan(0)
  })

  it('T-TR-2: 경로 파라미터가 없으면 저장값을 건드리지 않는다 (/issues 등)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = {}

    renderTracker(true)

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-TR-3a (CR3): 미인증이면 목록이 이미 있어도 기록하지 않는다 (effect 가드)', () => {
    mockParams = { projectKey: 'INFRA' }

    // ★ 선주입(seed:true, 기본값) — `if (!enabled) return` 삭제만 단독으로 되주입해도 RED가
    // 되도록, 목록이 이미 도착해 `isKnownProject`가 우회로가 되지 못하는 상태에서 검증한다.
    renderTracker(false)

    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-TR-3b (CR3): 미인증이면 /api/v1/projects 요청 자체가 나가지 않는다 (enabled 관통 가드)', async () => {
    mockParams = { projectKey: 'INFRA' }

    // ★ 미주입(seed:false) — `{ enabled }` 를 `useProjects`까지 관통시키지 못하는 뮤테이션은
    // 캐시가 이미 신선(staleTime 30초)하면 재조회가 안 돌아 가려진다. 캐시를 비워야
    // `enabled` 값에 따라 최초 fetch 자체가 나가는지 여부가 그대로 드러난다.
    renderTracker(false, { seed: false })

    // 마운트 effect 이후 마이크로태스크까지 흘려보낸다 — 동기 단언은 결함을 되주입해도 통과한다
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })

    expect(projectsCalls).toBe(0)
    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-TR-4 (E7): 같은 프로젝트 재방문 시 localStorage write 를 생략한다', async () => {
    mockParams = { projectKey: 'INFRA' }
    const { rerender } = renderTracker(true)
    await waitFor(() => expect(useActiveProject.getState().activeProjectKey).toBe('INFRA'))

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
    rerender()

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
    setItemSpy.mockRestore()
  })

  it('T-TR-5: 경로 파라미터가 문자열이 아니면 무시한다 (방어)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = { projectKey: undefined }

    renderTracker(true)

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-TR-6: 빈 문자열 경로 파라미터는 무시한다 (빈 스코프 방지)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = { projectKey: '' }

    // 목록에 빈 키 프로젝트를 섞어 넣어 isKnownProject 가 대신 막아주는 우회로를 닫는다
    renderTracker(true, {
      projects: [...PROJECTS, { id: '33333333-3333-4333-8333-333333333333', key: '', name: '빈키' }],
    })

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-TR-7 (CR3): 목록 확정 후에도 접근 불가 키는 기록하지 않는다', () => {
    mockParams = { projectKey: 'INFRA' }
    const { rerender } = renderTracker(true) // 캐시 선주입 → effect 즉시 실행

    // 양성 대조군 — null → 'INFRA' 전환. 이펙트가 실제로 돌아야만 통과한다(t=0 에 참이 아니다)
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')

    // 목록이 확정된 상태에서 접근 불가 키로 이동한다 (/projects/TYPO/board, 404)
    mockParams = { projectKey: 'TYPO' }
    rerender() // rerender 는 act 로 감싸져 effect 가 동기 flush

    // 덮어썼다면 이 시점에 이미 'TYPO' 다 — 동기 단언으로 충분
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-08 PR-A Task 3 — 최근 프로젝트 기록 (FR3)
  //
  // ★ 기록 지점을 늘리지 않는다. 새 훅을 만들면 저장값 생산 지점이 둘이 되어
  //   CR3 가 걸었던 결함("가드가 한쪽에만 있음")이 그대로 재발한다. 그래서
  //   아래 세 테스트의 핵심은 "활성값과 최근 목록이 **같은 가드 아래** 있는가" 다.
  // ───────────────────────────────────────────────────────────────────────────

  it('T-TR-8 (FR3): 경로 파라미터 키를 활성값과 최근 목록에 함께 기록한다', () => {
    mockParams = { projectKey: 'INFRA' }

    renderTracker(true) // 캐시 선주입 → effect 즉시 실행

    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
    expect(useRecentProjects.getState().recentProjectKeys).toEqual(['INFRA'])
  })

  it('T-TR-9 (FR3 + CR3 핵심): 접근 불가 키는 최근 목록에도 들어가지 않는다', () => {
    mockParams = { projectKey: 'INFRA' }
    const { rerender } = renderTracker(true)

    // 양성 대조군 — 정상 키는 두 곳 모두에 기록된다
    expect(useRecentProjects.getState().recentProjectKeys).toEqual(['INFRA'])

    // 목록이 확정된 상태에서 접근 불가 키로 이동한다 (/projects/TYPO/board, 404)
    mockParams = { projectKey: 'TYPO' }
    rerender()

    // ★ 가드가 두 기록 지점을 모두 덮어야 한다. push 를 가드 밖에 두면 여기서 TYPO 가 샌다.
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
    expect(useRecentProjects.getState().recentProjectKeys).toEqual(['INFRA'])
  })

  it('T-TR-10 (FR3): 미인증이면 최근 목록도 건드리지 않는다', () => {
    mockParams = { projectKey: 'INFRA' }

    renderTracker(false) // 선주입 — isKnownProject 가 우회로가 되지 못하는 상태

    expect(useActiveProject.getState().activeProjectKey).toBeNull()
    expect(useRecentProjects.getState().recentProjectKeys).toEqual([])
  })

  it('T-TR-11 (FR3, S3): 여러 프로젝트를 오가면 최근 목록이 MRU 순으로 쌓인다', () => {
    mockParams = { projectKey: 'INFRA' }
    const { rerender } = renderTracker(true)
    expect(useRecentProjects.getState().recentProjectKeys).toEqual(['INFRA'])

    mockParams = { projectKey: 'ATLAS' }
    rerender()

    expect(useRecentProjects.getState().recentProjectKeys).toEqual(['ATLAS', 'INFRA'])
  })
})
