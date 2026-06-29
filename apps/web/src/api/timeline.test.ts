// 타임라인 API 클라이언트 단위 테스트 (FR-TL-01 Task-1)
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { fetchTimeline, timelineDepsResponseSchema, fetchTimelineDeps } from './timeline'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const VALID_UUID = '123e4567-e89b-12d3-a456-426614174000'

/** 모든 nullable 필드가 null인 최소 아이템 픽스처 */
const minimalItem = {
  key: 'ATLAS-1',
  summary: '기본 태스크',
  issueType: 'task',
  currentStateKey: 'open',
  assigneeId: null,
  startDate: null,
  dueDate: null,
  targetDate: null,
  epicKey: null,
}

/** 날짜와 선택 필드가 모두 채워진 아이템 픽스처 */
const fullItem = {
  key: 'ATLAS-2',
  summary: '에픽 스토리',
  issueType: 'story',
  currentStateKey: 'in_progress',
  assigneeId: VALID_UUID,
  startDate: '2026-06-01',
  dueDate: '2026-07-20',
  targetDate: '2026-07-31',
  epicKey: 'ATLAS-0',
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchTimeline 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchTimeline', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/timeline', ({ request }) => {
        const url = new URL(request.url)
        const project = url.searchParams.get('project')
        if (project === 'ATLAS') {
          return HttpResponse.json({
            data: { items: [minimalItem], truncated: false },
          })
        }
        return HttpResponse.json({ errorCode: 'PROJECT_NOT_FOUND' }, { status: 404 })
      }),
    )
  })

  it('GET /api/v1/timeline?project={key} 호출 후 DataResponse 봉투를 언랩해 반환한다', async () => {
    const result = await fetchTimeline('ATLAS')
    expect(result.items).toHaveLength(1)
    expect(result.truncated).toBe(false)
  })

  it('items[0] 필드가 백엔드 DTO와 1:1로 파싱된다', async () => {
    const result = await fetchTimeline('ATLAS')
    const item = result.items[0]
    expect(item?.key).toBe('ATLAS-1')
    expect(item?.summary).toBe('기본 태스크')
    expect(item?.issueType).toBe('task')
    expect(item?.currentStateKey).toBe('open')
  })

  it('nullable 필드(assigneeId / startDate / dueDate / targetDate / epicKey)가 null일 때 파싱 통과', async () => {
    const result = await fetchTimeline('ATLAS')
    const item = result.items[0]
    expect(item?.assigneeId).toBeNull()
    expect(item?.startDate).toBeNull()
    expect(item?.dueDate).toBeNull()
    expect(item?.targetDate).toBeNull()
    expect(item?.epicKey).toBeNull()
  })

  it('날짜가 ISO date 문자열("2026-07-20")일 때 string 그대로 반환한다', async () => {
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [fullItem], truncated: false },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    const item = result.items[0]
    expect(item?.startDate).toBe('2026-06-01')
    expect(item?.dueDate).toBe('2026-07-20')
    expect(item?.targetDate).toBe('2026-07-31')
  })

  it('assigneeId가 UUID 문자열일 때 string으로 파싱된다', async () => {
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [fullItem], truncated: false },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    expect(result.items[0]?.assigneeId).toBe(VALID_UUID)
  })

  it('epicKey가 문자열일 때 string으로 파싱된다', async () => {
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [fullItem], truncated: false },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    expect(result.items[0]?.epicKey).toBe('ATLAS-0')
  })

  it('issueType은 소문자 string으로 파싱된다 (epic / story / task / bug)', async () => {
    const epicItem = { ...minimalItem, issueType: 'epic' }
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [epicItem], truncated: false },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    expect(result.items[0]?.issueType).toBe('epic')
  })

  it('truncated: true일 때 boolean 그대로 반환한다', async () => {
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [], truncated: true },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    expect(result.truncated).toBe(true)
    expect(result.items).toHaveLength(0)
  })

  it('403 응답 시 ApiError(403)를 throw하고 body에 errorCode가 포함된다', async () => {
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchTimeline('ATLAS')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchTimeline('ATLAS')).rejects.toMatchObject({
      status: 403,
      body: { errorCode: 'FORBIDDEN' },
    })
  })

  it('nullable 필드 키 자체가 응답에서 누락(omit)되면 null로 파싱된다 (C4 — @JsonInclude(NON_NULL) 방어)', async () => {
    // 백엔드가 @JsonInclude(NON_NULL)을 적용하면 null 필드 키 자체가 JSON에서 사라진다.
    // .default(null) 없이는 Zod ZodError 발생 → parse 실패.
    const itemWithOmittedNullableFields = {
      key: 'ATLAS-1',
      summary: '기본 태스크',
      issueType: 'task',
      currentStateKey: 'open',
      // assigneeId, startDate, dueDate, targetDate, epicKey 모두 키 자체 누락
    }
    server.use(
      http.get('/api/v1/timeline', () =>
        HttpResponse.json({
          data: { items: [itemWithOmittedNullableFields], truncated: false },
        }),
      ),
    )
    const result = await fetchTimeline('ATLAS')
    const item = result.items[0]
    expect(item?.assigneeId).toBeNull()
    expect(item?.startDate).toBeNull()
    expect(item?.dueDate).toBeNull()
    expect(item?.targetDate).toBeNull()
    expect(item?.epicKey).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// timelineDepsResponseSchema 테스트 (FR-TL-02 D6/D7)
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineDepsResponseSchema', () => {
  it('유효한 deps 응답 { deps:[{blockerKey,blockedKey}], truncated:false } 을 파싱한다', () => {
    const result = timelineDepsResponseSchema.parse({
      deps: [{ blockerKey: 'BTS-2', blockedKey: 'BTS-3' }],
      truncated: false,
    })
    expect(result.deps).toHaveLength(1)
    expect(result.deps[0]).toEqual({ blockerKey: 'BTS-2', blockedKey: 'BTS-3' })
    expect(result.truncated).toBe(false)
  })

  it('deps 빈 배열 + truncated:true 도 파싱한다', () => {
    const result = timelineDepsResponseSchema.parse({ deps: [], truncated: true })
    expect(result.deps).toHaveLength(0)
    expect(result.truncated).toBe(true)
  })

  it('여러 엣지를 포함한 deps 배열을 파싱한다', () => {
    const result = timelineDepsResponseSchema.parse({
      deps: [
        { blockerKey: 'BTS-1', blockedKey: 'BTS-2' },
        { blockerKey: 'BTS-2', blockedKey: 'BTS-3' },
      ],
      truncated: false,
    })
    expect(result.deps).toHaveLength(2)
  })

  it('blockerKey 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      timelineDepsResponseSchema.parse({
        deps: [{ blockedKey: 'BTS-3' }],
        truncated: false,
      }),
    ).toThrow()
  })

  it('blockedKey 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      timelineDepsResponseSchema.parse({
        deps: [{ blockerKey: 'BTS-2' }],
        truncated: false,
      }),
    ).toThrow()
  })

  it('truncated 필드 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      timelineDepsResponseSchema.parse({
        deps: [{ blockerKey: 'BTS-2', blockedKey: 'BTS-3' }],
      }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchTimelineDeps 테스트 (FR-TL-02 D6/D7)
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchTimelineDeps', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/timeline/deps', ({ request }) => {
        const url = new URL(request.url)
        const project = url.searchParams.get('project')
        if (project === 'BTS') {
          return HttpResponse.json({
            data: {
              deps: [{ blockerKey: 'BTS-2', blockedKey: 'BTS-3' }],
              truncated: false,
            },
          })
        }
        if (project === 'TRUNCATED') {
          return HttpResponse.json({
            data: {
              deps: [{ blockerKey: 'BTS-1', blockedKey: 'BTS-4' }],
              truncated: true,
            },
          })
        }
        return HttpResponse.json({ data: { deps: [], truncated: false } })
      }),
    )
  })

  it('GET /api/v1/timeline/deps?project=BTS 호출 후 DataResponse 봉투를 언랩해 반환한다', async () => {
    const result = await fetchTimelineDeps('BTS')
    expect(result.deps).toHaveLength(1)
    expect(result.deps[0]).toEqual({ blockerKey: 'BTS-2', blockedKey: 'BTS-3' })
    expect(result.truncated).toBe(false)
  })

  it('truncated:true 응답도 정상 파싱한다', async () => {
    const result = await fetchTimelineDeps('TRUNCATED')
    expect(result.deps).toHaveLength(1)
    expect(result.truncated).toBe(true)
  })

  it('알 수 없는 프로젝트 → deps:[] 반환한다', async () => {
    const result = await fetchTimelineDeps('UNKNOWN')
    expect(result.deps).toHaveLength(0)
    expect(result.truncated).toBe(false)
  })
})
