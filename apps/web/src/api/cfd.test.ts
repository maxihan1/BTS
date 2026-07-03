// 누적 흐름도(CFD) 차트 API 클라이언트 단위 테스트 — FR-RP-03 D6/D7 Task-2
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  cfdPointResponseSchema,
  cfdResponseSchema,
  fetchProjectCfd,
  isCfdEmpty,
  type CfdResponse,
  type CfdPointResponse,
} from './cfd'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const projectKey = 'BTS'

const todoOnlyPointFixture: CfdPointResponse = {
  date: '2026-06-01',
  todoCount: 3,
  inProgressCount: 0,
  doneCount: 0,
}

const mixedPointFixture: CfdPointResponse = {
  date: '2026-06-02',
  todoCount: 1,
  inProgressCount: 1,
  doneCount: 1,
}

const zeroPointFixture: CfdPointResponse = {
  date: '2026-06-03',
  todoCount: 0,
  inProgressCount: 0,
  doneCount: 0,
}

const cfdFixture: CfdResponse = {
  projectKey,
  from: '2026-06-01',
  to: '2026-06-02',
  points: [todoOnlyPointFixture, mixedPointFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('cfdPointResponseSchema', () => {
  it('유효한 포인트를 파싱한다', () => {
    const result = cfdPointResponseSchema.parse(mixedPointFixture)
    expect(result.date).toBe('2026-06-02')
    expect(result.todoCount).toBe(1)
    expect(result.inProgressCount).toBe(1)
    expect(result.doneCount).toBe(1)
  })

  it('todoCount 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { todoCount: _todoCount, ...withoutTodoCount } = mixedPointFixture
    expect(() => cfdPointResponseSchema.parse(withoutTodoCount)).toThrow()
  })

  it('inProgressCount 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { inProgressCount: _inProgressCount, ...withoutInProgressCount } = mixedPointFixture
    expect(() => cfdPointResponseSchema.parse(withoutInProgressCount)).toThrow()
  })

  it('doneCount 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { doneCount: _doneCount, ...withoutDoneCount } = mixedPointFixture
    expect(() => cfdPointResponseSchema.parse(withoutDoneCount)).toThrow()
  })
})

describe('cfdResponseSchema', () => {
  it('유효한 응답을 파싱한다', () => {
    const result = cfdResponseSchema.parse(cfdFixture)
    expect(result.projectKey).toBe(projectKey)
    expect(result.from).toBe('2026-06-01')
    expect(result.to).toBe('2026-06-02')
    expect(result.points).toHaveLength(2)
  })

  it('projectKey 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { projectKey: _projectKey, ...withoutProjectKey } = cfdFixture
    expect(() => cfdResponseSchema.parse(withoutProjectKey)).toThrow()
  })

  it('from 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { from: _from, ...withoutFrom } = cfdFixture
    expect(() => cfdResponseSchema.parse(withoutFrom)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isCfdEmpty
// ─────────────────────────────────────────────────────────────────────────────

describe('isCfdEmpty', () => {
  it('모든 포인트의 3종 카운트 합이 0이면 true를 반환한다', () => {
    const emptyResponse: CfdResponse = { ...cfdFixture, points: [zeroPointFixture] }
    expect(isCfdEmpty(emptyResponse)).toBe(true)
  })

  it('하나라도 카운트가 0보다 크면 false를 반환한다', () => {
    expect(isCfdEmpty(cfdFixture)).toBe(false)
  })

  it('points가 빈 배열이면 true를 반환한다', () => {
    const noPointsResponse: CfdResponse = { ...cfdFixture, points: [] }
    expect(isCfdEmpty(noPointsResponse)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchProjectCfd
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchProjectCfd', () => {
  it('올바른 URL을 GET 호출하고 200 응답의 data를 반환한다', async () => {
    let capturedUrl = ''
    let capturedMethod = ''
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cfd`, ({ request }) => {
        capturedUrl = request.url
        capturedMethod = request.method
        return HttpResponse.json({ data: cfdFixture })
      }),
    )
    const result = await fetchProjectCfd(projectKey)
    expect(capturedUrl).toContain(`/api/v1/projects/${projectKey}/cfd`)
    expect(capturedMethod).toBe('GET')
    expect(result.projectKey).toBe(projectKey)
    expect(result.points).toHaveLength(2)
  })

  it('401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cfd`, () =>
        HttpResponse.json({ errorCode: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(fetchProjectCfd(projectKey)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchProjectCfd(projectKey)).rejects.toMatchObject({ status: 401 })
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cfd`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchProjectCfd(projectKey)).rejects.toMatchObject({ status: 403 })
  })
})
