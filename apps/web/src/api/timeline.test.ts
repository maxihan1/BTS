// 타임라인 API 클라이언트 단위 테스트 (FR-TL-01 Task-1)
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { fetchTimeline } from './timeline'

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
})
