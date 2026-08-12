// CFD(누적 흐름도) MSW 핸들러 단위 테스트 — 계약 drift 가드 (FR-RP-03 D6/D7 Task 6)

import { server } from '@/test/server'
import { beforeEach, describe, expect, it } from 'vitest'
import { cfdResponseSchema } from '@/api/cfd'
import {
  cfdHandlers,
  DEFAULT_CFD,
  EMPTY_PROJECT_KEY,
  FORBIDDEN_PROJECT_KEY,
  resetCfdStore,
  seedCfd,
} from './cfd-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...cfdHandlers)
})

beforeEach(() => {
  resetCfdStore()
  seedCfd(DEFAULT_CFD)
})


// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 기본 픽스처가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_CFD 픽스처 계약 검증', () => {
  it('T1-1: cfdResponseSchema로 파싱 가능하다 (MSW↔계약 drift 가드)', () => {
    const parsed = cfdResponseSchema.parse(DEFAULT_CFD)
    expect(parsed.projectKey).toBe(DEFAULT_CFD.projectKey)
  })

  it('T1-2: points가 비어있지 않고, 모든 카운트가 음수 없는 정수다', () => {
    expect(DEFAULT_CFD.points.length).toBeGreaterThan(0)
    for (const point of DEFAULT_CFD.points) {
      expect(Number.isInteger(point.todoCount)).toBe(true)
      expect(Number.isInteger(point.inProgressCount)).toBe(true)
      expect(Number.isInteger(point.doneCount)).toBe(true)
      expect(point.todoCount).toBeGreaterThanOrEqual(0)
      expect(point.inProgressCount).toBeGreaterThanOrEqual(0)
      expect(point.doneCount).toBeGreaterThanOrEqual(0)
    }
  })

  it('T1-3: from/to가 points 배열의 첫/마지막 날짜와 일치한다', () => {
    const first = DEFAULT_CFD.points[0]
    const last = DEFAULT_CFD.points[DEFAULT_CFD.points.length - 1]
    expect(first).toBeDefined()
    expect(last).toBeDefined()
    expect(DEFAULT_CFD.from).toBe(first?.date)
    expect(DEFAULT_CFD.to).toBe(last?.date)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/cfd
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/cfd', () => {
  it('T2-1: 정상 projectKey 요청 시 200과 { data } 외피로 응답하고 points가 비어있지 않다', async () => {
    const res = await fetch(`/api/v1/projects/${DEFAULT_CFD.projectKey}/cfd`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cfdResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe(DEFAULT_CFD.projectKey)
    expect(parsed.points.length).toBeGreaterThan(0)
  })

  it('T2-2: EMPTY_PROJECT_KEY 요청 시 200이되 모든 point의 카운트가 0이다', async () => {
    const res = await fetch(`/api/v1/projects/${EMPTY_PROJECT_KEY}/cfd`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cfdResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe(EMPTY_PROJECT_KEY)
    expect(parsed.points.length).toBeGreaterThan(0)
    for (const point of parsed.points) {
      expect(point.todoCount).toBe(0)
      expect(point.inProgressCount).toBe(0)
      expect(point.doneCount).toBe(0)
    }
  })

  it('T2-3: FORBIDDEN_PROJECT_KEY 요청 시 403과 ISSUE_ACCESS_DENIED errorCode를 반환한다', async () => {
    const res = await fetch(`/api/v1/projects/${FORBIDDEN_PROJECT_KEY}/cfd`)

    expect(res.status).toBe(403)

    const body = (await res.json()) as { errorCode: string; message: string }
    expect(body.errorCode).toBe('ISSUE_ACCESS_DENIED')
    expect(typeof body.message).toBe('string')
  })

  it('T2-4: store에 없는 projectKey 요청 시 200과 모든 카운트 0인 응답을 반환한다 (404 아님)', async () => {
    const res = await fetch('/api/v1/projects/UNSEEDED/cfd')

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cfdResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe('UNSEEDED')
    for (const point of parsed.points) {
      expect(point.todoCount).toBe(0)
      expect(point.inProgressCount).toBe(0)
      expect(point.doneCount).toBe(0)
    }
  })

  it('T2-5: store에 시드된 커스텀 응답이 있으면 그 저장값을 그대로 반환한다', async () => {
    const custom = {
      projectKey: 'CUSTOM-PROJECT',
      from: '2026-01-01',
      to: '2026-01-02',
      points: [
        { date: '2026-01-01', todoCount: 5, inProgressCount: 1, doneCount: 0 },
        { date: '2026-01-02', todoCount: 4, inProgressCount: 1, doneCount: 1 },
      ],
    }
    seedCfd(custom)

    const res = await fetch(`/api/v1/projects/${custom.projectKey}/cfd`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cfdResponseSchema.parse(body.data)

    expect(parsed).toEqual(custom)
  })
})
