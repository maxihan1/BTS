// 스프린트 벨로시티 MSW 핸들러 단위 테스트 — 계약 drift 가드 (FR-RP-02 D6/D7 Task 6)

import { server } from '@/test/server'
import { beforeEach, describe, expect, it } from 'vitest'
import { velocityResponseSchema } from '@/api/velocity'
import {
  DEFAULT_VELOCITY,
  EMPTY_PROJECT_KEY,
  FORBIDDEN_PROJECT_KEY,
  resetVelocityStore,
  seedVelocity,
  velocityHandlers,
} from './velocity-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...velocityHandlers)
})

beforeEach(() => {
  resetVelocityStore()
  seedVelocity(DEFAULT_VELOCITY)
})


// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 기본 픽스처가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_VELOCITY 픽스처 계약 검증', () => {
  it('T1-1: velocityResponseSchema로 파싱 가능하다 (MSW↔계약 drift 가드)', () => {
    const parsed = velocityResponseSchema.parse(DEFAULT_VELOCITY)
    expect(parsed.projectKey).toBe(DEFAULT_VELOCITY.projectKey)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/velocity
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/velocity', () => {
  it('T2-1: 정상 projectKey 요청 시 200과 { data } 외피로 응답하고 sprints가 비어있지 않다', async () => {
    const res = await fetch(`/api/v1/projects/${DEFAULT_VELOCITY.projectKey}/velocity`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = velocityResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe(DEFAULT_VELOCITY.projectKey)
    expect(parsed.sprints.length).toBeGreaterThan(0)
  })

  it('T2-2: EMPTY_PROJECT_KEY 요청 시 200이되 sprints가 빈 배열이고 평균이 0이다', async () => {
    const res = await fetch(`/api/v1/projects/${EMPTY_PROJECT_KEY}/velocity`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = velocityResponseSchema.parse(body.data)

    expect(parsed.sprints).toEqual([])
    expect(parsed.averageCommitmentSeconds).toBe(0)
    expect(parsed.averageCompletedSeconds).toBe(0)
  })

  it('T2-3: FORBIDDEN_PROJECT_KEY 요청 시 403과 AGILE_ACCESS_DENIED errorCode를 반환한다', async () => {
    const res = await fetch(`/api/v1/projects/${FORBIDDEN_PROJECT_KEY}/velocity`)

    expect(res.status).toBe(403)

    const body = (await res.json()) as { errorCode: string; message: string }
    expect(body.errorCode).toBe('AGILE_ACCESS_DENIED')
    expect(typeof body.message).toBe('string')
  })

  it('T2-4: store에 없는 projectKey 요청 시 200과 빈 벨로시티를 반환한다 (404 아님)', async () => {
    const res = await fetch('/api/v1/projects/UNSEEDED/velocity')

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = velocityResponseSchema.parse(body.data)

    expect(parsed.sprints).toEqual([])
  })
})
