// 이슈를 바꾸면 그 이슈를 보여 주는 **모든 화면**이 갱신된다 — 무효화 정본 계약
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { invalidateIssueViews, ISSUE_VIEW_KEY_PREFIXES } from './issue-view-invalidation'

/** `invalidateQueries` 가 실제로 받은 queryKey 전량 */
function captureKeys(client: QueryClient): unknown[][] {
  const calls: unknown[][] = []
  vi.spyOn(client, 'invalidateQueries').mockImplementation((filters?: { queryKey?: unknown }) => {
    calls.push((filters?.queryKey ?? []) as unknown[])
    return Promise.resolve()
  })
  return calls
}

let client: QueryClient

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})

describe('invalidateIssueViews — 이슈를 보여 주는 화면 전량', () => {
  it('이슈 목록·보드·백로그 접두를 모두 무효화한다', () => {
    // 🛑 이것이 결함의 본체다. 종전에는 이슈 상세 수정이 `['issue', key]` 하나만 무효화해
    //    목록·보드가 새로고침 전까지 옛 값을 보여 줬다(Maxi 보고 2026-09-07).
    const calls = captureKeys(client)

    invalidateIssueViews(client, 'ATLAS-1')

    expect(calls).toContainEqual(['issues'])
    expect(calls).toContainEqual(['board'])
    expect(calls).toContainEqual(['backlog'])
  })

  it('이슈 키를 주면 단건·전환 캐시도 함께 무효화한다', () => {
    const calls = captureKeys(client)

    invalidateIssueViews(client, 'ATLAS-1')

    expect(calls).toContainEqual(['issue', 'ATLAS-1'])
    expect(calls.some((k) => k[0] === 'issue-transitions' && k[1] === 'ATLAS-1')).toBe(true)
  })

  it('이슈 키가 없으면 단건 캐시는 건드리지 않는다 (일괄 작업 경로)', () => {
    // 일괄 작업은 수십 건을 한 번에 바꾸고 **어느 키인지 열거하지 않는다**. 접두 무효화만으로
    // 목록·보드·백로그가 회복되고, 열려 있지 않은 상세는 다음 조회 때 새로 받는다.
    const calls = captureKeys(client)

    invalidateIssueViews(client)

    expect(calls).toContainEqual(['issues'])
    expect(calls.some((k) => k[0] === 'issue')).toBe(false)
  })

  it('접두 목록이 비어 있지 않다 (공허한 통과 차단)', () => {
    expect(ISSUE_VIEW_KEY_PREFIXES.length).toBeGreaterThanOrEqual(3)
  })
})

describe('실 캐시 대조 — 접두가 실제 화면 키에 걸린다', () => {
  /**
   * 🛑 위 단언들은 「무엇을 무효화했다고 말했는가」만 본다. 접두가 **실제 화면이 쓰는 키**에
   *    안 걸리면 그래도 통과한다. 그래서 진짜 캐시를 심어 두고 `getQueryState` 로 stale 판정을
   *    확인한다 — 이 저장소가 요구하는 「비-공허 짝」이다.
   */
  it.each([
    ['이슈 목록', ['issues', 'ATLAS', 0, {}, null]],
    ['보드 상세', ['board', 'b-1', {}]],
    ['백로그', ['backlog', 'ATLAS', 'b-1']],
    ['이슈 단건', ['issue', 'ATLAS-1']],
  ])('%s 캐시가 stale 로 바뀐다', async (_label, key) => {
    client.setQueryData(key as unknown[], { seeded: true })
    expect(client.getQueryState(key as unknown[])?.isInvalidated).toBe(false)

    await invalidateIssueViews(client, 'ATLAS-1')

    expect(client.getQueryState(key as unknown[])?.isInvalidated).toBe(true)
  })

  it('무관한 캐시는 건드리지 않는다 (대조군)', async () => {
    // 전량 무효화(`invalidateQueries()` 인자 없음)로 때우면 이 단언이 red 가 된다.
    // 사용자 목록·프로젝트 설정까지 매번 다시 부르는 것은 이 결함의 처방이 아니다.
    client.setQueryData(['users'], [{ id: 'u1' }])

    await invalidateIssueViews(client, 'ATLAS-1')

    expect(client.getQueryState(['users'])?.isInvalidated).toBe(false)
  })
})
