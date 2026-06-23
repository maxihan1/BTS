// 에픽 진행률 API fetchEpicProgress + EpicProgressSchema 단위 테스트 — FR-EP-02 Task-4
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { fetchEpicProgress, EpicProgressSchema } from './epic-children'

// ─────────────────────────────────────────────────────────────────────────────
// T-EP-1. EpicProgressSchema — Zod 파싱 계약 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressSchema — Zod 파싱 계약', () => {
  it('T-EP-1a: 정상 응답이 파싱 성공하고 모든 필드를 포함한다', () => {
    const raw = {
      total: 10,
      done: 4,
      donePercentage: 40,
      byCategory: { todo: 3, inProgress: 3, done: 4 },
    }
    const result = EpicProgressSchema.safeParse(raw)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.total).toBe(10)
      expect(result.data.done).toBe(4)
      expect(result.data.donePercentage).toBe(40)
      expect(result.data.byCategory.todo).toBe(3)
      expect(result.data.byCategory.inProgress).toBe(3)
      expect(result.data.byCategory.done).toBe(4)
    }
  })

  it('T-EP-1b: total 필드 누락 시 파싱 실패(throw)한다', () => {
    const raw = {
      done: 4,
      donePercentage: 40,
      byCategory: { todo: 3, inProgress: 3, done: 4 },
    }
    const result = EpicProgressSchema.safeParse(raw)
    expect(result.success).toBe(false)
  })

  it('T-EP-1c: byCategory.inProgress 필드 누락 시 파싱 실패한다', () => {
    const raw = {
      total: 10,
      done: 4,
      donePercentage: 40,
      byCategory: { todo: 3, done: 4 },
    }
    const result = EpicProgressSchema.safeParse(raw)
    expect(result.success).toBe(false)
  })

  it('T-EP-1d: 빈 에픽 (total=0, donePercentage=0) 파싱 성공한다', () => {
    const raw = {
      total: 0,
      done: 0,
      donePercentage: 0,
      byCategory: { todo: 0, inProgress: 0, done: 0 },
    }
    const result = EpicProgressSchema.safeParse(raw)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.total).toBe(0)
      expect(result.data.donePercentage).toBe(0)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EP-2. fetchEpicProgress — GET /api/v1/epics/{key}/progress 정상 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchEpicProgress — GET /api/v1/epics/{key}/progress', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/epics/:key/progress', () =>
        HttpResponse.json({
          data: {
            total: 10,
            done: 4,
            donePercentage: 40,
            byCategory: { todo: 3, inProgress: 3, done: 4 },
          },
        }),
      ),
    )
  })

  it('T-EP-2a: 계약대로 파싱하여 EpicProgress 객체를 반환한다', async () => {
    const result = await fetchEpicProgress('ATLAS-EPIC-1')
    expect(result.total).toBe(10)
    expect(result.done).toBe(4)
    expect(result.donePercentage).toBe(40)
    expect(result.byCategory.todo).toBe(3)
    expect(result.byCategory.inProgress).toBe(3)
    expect(result.byCategory.done).toBe(4)
  })

  it('T-EP-2b: 빈 에픽 (total=0, donePercentage=0) 파싱 성공한다', async () => {
    server.use(
      http.get('/api/v1/epics/:key/progress', () =>
        HttpResponse.json({
          data: {
            total: 0,
            done: 0,
            donePercentage: 0,
            byCategory: { todo: 0, inProgress: 0, done: 0 },
          },
        }),
      ),
    )
    const result = await fetchEpicProgress('ATLAS-EPIC-EMPTY')
    expect(result.total).toBe(0)
    expect(result.donePercentage).toBe(0)
  })

  it('T-EP-2c: 404 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.get('/api/v1/epics/:key/progress', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: '에픽을 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchEpicProgress('ATLAS-INVALID')).rejects.toBeInstanceOf(ApiError)
  })

  it('T-EP-2d: 응답에 필수 필드 누락 시 Zod 파싱 에러가 throw된다', async () => {
    server.use(
      http.get('/api/v1/epics/:key/progress', () =>
        HttpResponse.json({
          data: {
            total: 10,
            // done 필드 누락
            donePercentage: 40,
            byCategory: { todo: 3, inProgress: 3, done: 4 },
          },
        }),
      ),
    )
    await expect(fetchEpicProgress('ATLAS-EPIC-1')).rejects.toThrow()
  })
})
