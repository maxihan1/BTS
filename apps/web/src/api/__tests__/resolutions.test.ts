// resolutions API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { resolutionSchema, fetchResolutions } from '../resolutions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 5종 표준 결의안 (백엔드 seed UUID, Zod v4 형식)
// ─────────────────────────────────────────────────────────────────────────────

/** 표준 결의안 5종 fixture — 백엔드 ResolutionResponse DTO와 1:1 대응 */
const resolutionFixtures = [
  {
    id: '00000000-0000-4000-8000-000000000001',
    key: 'fixed',
    name: 'Fixed',
    description: null,
    displayOrder: 1,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000002',
    key: 'wontfix',
    name: "Won't Fix",
    description: null,
    displayOrder: 2,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000003',
    key: 'duplicate',
    name: 'Duplicate',
    description: null,
    displayOrder: 3,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000004',
    key: 'cannotreproduce',
    name: 'Cannot Reproduce',
    description: null,
    displayOrder: 4,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000005',
    key: 'done',
    name: 'Done',
    description: '작업 완료로 인한 결의안',
    displayOrder: 5,
    isStandard: true,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('resolutionSchema', () => {
  it('필수 필드를 올바르게 파싱한다', () => {
    const fixture = resolutionFixtures[0]
    const result = resolutionSchema.parse(fixture)

    expect(result.id).toBe('00000000-0000-4000-8000-000000000001')
    expect(result.key).toBe('fixed')
    expect(result.name).toBe('Fixed')
    expect(result.description).toBeNull()
    expect(result.displayOrder).toBe(1)
    expect(result.isStandard).toBe(true)
  })

  it('description이 있는 경우도 파싱한다', () => {
    const fixture = resolutionFixtures[4]
    const result = resolutionSchema.parse(fixture)

    expect(result.description).toBe('작업 완료로 인한 결의안')
  })

  it('UUID가 아닌 id는 파싱 실패한다', () => {
    expect(() =>
      resolutionSchema.parse({ ...resolutionFixtures[0], id: 'not-a-uuid' }),
    ).toThrow()
  })

  it('displayOrder가 없으면 파싱 실패한다', () => {
    const { displayOrder: _omit, ...withoutOrder } = resolutionFixtures[0] as typeof resolutionFixtures[0] & { displayOrder?: number }
    expect(() => resolutionSchema.parse(withoutOrder)).toThrow()
  })

  it('isStandard가 없으면 파싱 실패한다', () => {
    const { isStandard: _omit, ...withoutStandard } = resolutionFixtures[0] as typeof resolutionFixtures[0] & { isStandard?: boolean }
    expect(() => resolutionSchema.parse(withoutStandard)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchResolutions API 통합 테스트 (MSW)
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchResolutions', () => {
  it('GET /api/v1/resolutions → Resolution[] 반환', async () => {
    server.use(
      http.get('/api/v1/resolutions', () =>
        HttpResponse.json({ data: resolutionFixtures }),
      ),
    )

    const result = await fetchResolutions()

    expect(result).toHaveLength(5)
    expect(result[0]?.id).toBe('00000000-0000-4000-8000-000000000001')
    expect(result[0]?.key).toBe('fixed')
    expect(result[4]?.key).toBe('done')
  })

  it('응답이 { data: [...] } 래퍼 구조가 아니면 ZodError를 던진다', async () => {
    server.use(
      http.get('/api/v1/resolutions', () =>
        HttpResponse.json(resolutionFixtures),
      ),
    )

    await expect(fetchResolutions()).rejects.toThrow()
  })

  it('비-2xx 응답 시 ApiError를 던진다', async () => {
    server.use(
      http.get('/api/v1/resolutions', () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }),
      ),
    )

    await expect(fetchResolutions()).rejects.toMatchObject({ status: 401 })
  })
})
