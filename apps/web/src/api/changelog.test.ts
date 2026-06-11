// 이슈 변경 이력 changelog API 클라이언트 단위 테스트 — RED phase (FR-HS-02 Task-F1)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ApiError } from './client'
import { fetchIssueChangelog } from './changelog'

// ─────────────────────────────────────────────────────────────────────────────
// apiGet 모킹 — MSW 비의존 (client mock 방식)
// ─────────────────────────────────────────────────────────────────────────────
vi.mock('./client', async (importOriginal) => {
  const original = await importOriginal<typeof import('./client')>()
  return {
    ...original,
    apiGet: vi.fn(),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — Spring Page<ChangeGroupResponse>
// ─────────────────────────────────────────────────────────────────────────────
const changeGroupFixture = {
  actorId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  actorName: 'Alice',
  createdAt: '2026-06-10T10:00:00Z',
  items: [
    {
      field: 'summary',
      fromValue: '이전 제목',
      toValue: '새 제목',
      fromLabel: null,
      toLabel: null,
    },
  ],
}

const pageFixture = {
  content: [changeGroupFixture],
  totalElements: 1,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-CL-S. Zod 스키마 파싱 — 직접 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('changeGroupSchema / changeItemSchema', () => {
  it('T-CL-S1: 정상 page 응답을 파싱한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce(pageFixture)

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)
    expect(result.content).toHaveLength(1)
    expect(result.content[0]?.actorName).toBe('Alice')
    expect(result.content[0]?.items[0]?.field).toBe('summary')
  })

  it('T-CL-S2: actorId=null(시스템 행위자)인 그룹을 파싱한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce({
      ...pageFixture,
      content: [{ ...changeGroupFixture, actorId: null, actorName: null }],
    })

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)
    expect(result.content[0]?.actorId).toBeNull()
    expect(result.content[0]?.actorName).toBeNull()
  })

  it('T-CL-S3: fromLabel/toLabel이 없는 항목(null)을 파싱한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce(pageFixture)

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)
    expect(result.content[0]?.items[0]?.fromLabel).toBeNull()
    expect(result.content[0]?.items[0]?.toLabel).toBeNull()
  })

  it('T-CL-S4: fromValue/toValue가 없는 항목(null)을 파싱한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce({
      ...pageFixture,
      content: [
        {
          ...changeGroupFixture,
          items: [{ field: 'assignee', fromValue: null, toValue: null, fromLabel: null, toLabel: null }],
        },
      ],
    })

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)
    expect(result.content[0]?.items[0]?.fromValue).toBeNull()
    expect(result.content[0]?.items[0]?.toValue).toBeNull()
  })

  it('T-CL-S5: 빈 content 페이지(empty=true)를 파싱한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce({
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 20,
      number: 0,
      first: true,
      last: true,
      empty: true,
    })

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)
    expect(result.content).toHaveLength(0)
    expect(result.empty).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CL-1. fetchIssueChangelog — 성공
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssueChangelog — 성공', () => {
  beforeEach(async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockClear()
  })

  it('T-CL-1a: apiGet을 올바른 경로로 호출한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce(pageFixture)

    await fetchIssueChangelog('ATLAS-1', 0, 20)

    expect(apiGet).toHaveBeenCalledOnce()
    const callArg = vi.mocked(apiGet).mock.calls[0]?.[0] as string
    expect(callArg).toContain('/api/v1/issues/ATLAS-1/changelog')
  })

  it('T-CL-1b: page=0, size=20 쿼리 파라미터가 포함된다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce(pageFixture)

    await fetchIssueChangelog('ATLAS-1', 0, 20)

    const callArg = vi.mocked(apiGet).mock.calls[0]?.[0] as string
    expect(callArg).toContain('page=0')
    expect(callArg).toContain('size=20')
  })

  it('T-CL-1c: page=1, size=10 쿼리 파라미터가 포함된다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce({ ...pageFixture, number: 1 })

    await fetchIssueChangelog('ATLAS-2', 1, 10)

    const callArg = vi.mocked(apiGet).mock.calls[0]?.[0] as string
    expect(callArg).toContain('page=1')
    expect(callArg).toContain('size=10')
  })

  it('T-CL-1d: Spring Page 페이징 메타를 그대로 반환한다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockResolvedValueOnce(pageFixture)

    const result = await fetchIssueChangelog('ATLAS-1', 0, 20)

    expect(result.totalElements).toBe(1)
    expect(result.totalPages).toBe(1)
    expect(result.first).toBe(true)
    expect(result.last).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CL-2. fetchIssueChangelog — 404 에러
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssueChangelog — 404 에러', () => {
  it('T-CL-2a: 404 ApiError가 throw된다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(404, { message: 'Not Found' }))

    let thrown: unknown
    try {
      await fetchIssueChangelog('ATLAS-99', 0, 20)
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })

  it('T-CL-2b: 404 외 에러는 그대로 전파된다', async () => {
    const { apiGet } = await import('./client')
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, { message: 'Server Error' }))

    let thrown: unknown
    try {
      await fetchIssueChangelog('ATLAS-1', 0, 20)
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(500)
  })
})
