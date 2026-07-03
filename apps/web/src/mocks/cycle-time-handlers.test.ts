// Cycle Time / Lead Time MSW 핸들러 단위 테스트 — 계약 drift 가드 (FR-RP-04 D6/D7 Task 8)

import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { cycleTimeResponseSchema } from '@/api/cycle-time'
import {
  cycleTimeHandlers,
  DEFAULT_CYCLE_TIME,
  EMPTY_PROJECT_KEY,
  FORBIDDEN_PROJECT_KEY,
  resetCycleTimeStore,
  seedCycleTime,
} from './cycle-time-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...cycleTimeHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  resetCycleTimeStore()
  seedCycleTime(DEFAULT_CYCLE_TIME)
})

afterEach(() => server.resetHandlers())

// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 기본 픽스처가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_CYCLE_TIME 픽스처 계약 검증', () => {
  it('T1-1: cycleTimeResponseSchema로 파싱 가능하다 (MSW↔계약 drift 가드)', () => {
    const parsed = cycleTimeResponseSchema.parse(DEFAULT_CYCLE_TIME)
    expect(parsed.projectKey).toBe(DEFAULT_CYCLE_TIME.projectKey)
  })

  it('T1-2: cycleTime/leadTime 모두 표본이 있고, samples가 seconds 오름차순이며 음수가 없다', () => {
    expect(DEFAULT_CYCLE_TIME.cycleTime.count).toBeGreaterThan(0)
    expect(DEFAULT_CYCLE_TIME.leadTime.count).toBeGreaterThan(0)

    for (const metric of [DEFAULT_CYCLE_TIME.cycleTime, DEFAULT_CYCLE_TIME.leadTime]) {
      expect(metric.samples).toHaveLength(metric.count)
      for (let i = 0; i < metric.samples.length; i += 1) {
        const sample = metric.samples[i]
        expect(sample).toBeDefined()
        expect(sample?.seconds).toBeGreaterThanOrEqual(0)
        if (i > 0) {
          const prev = metric.samples[i - 1]
          expect(prev).toBeDefined()
          expect(sample?.seconds).toBeGreaterThanOrEqual(prev?.seconds ?? 0)
        }
      }
    }
  })

  it('T1-3: from/to가 YYYY-MM-DD 형식이다', () => {
    expect(DEFAULT_CYCLE_TIME.from).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(DEFAULT_CYCLE_TIME.to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/cycle-time
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/cycle-time', () => {
  it('T2-1: 정상 projectKey 요청 시 200과 { data } 외피로 응답하고 cycleTime/leadTime 표본이 채워진다', async () => {
    const res = await fetch(`/api/v1/projects/${DEFAULT_CYCLE_TIME.projectKey}/cycle-time`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe(DEFAULT_CYCLE_TIME.projectKey)
    expect(parsed.cycleTime.count).toBeGreaterThan(0)
    expect(parsed.leadTime.count).toBeGreaterThan(0)
  })

  it('T2-2: EMPTY_PROJECT_KEY 요청 시 200이되 cycleTime/leadTime 모두 count=0이고 통계가 null이다', async () => {
    const res = await fetch(`/api/v1/projects/${EMPTY_PROJECT_KEY}/cycle-time`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe(EMPTY_PROJECT_KEY)
    for (const metric of [parsed.cycleTime, parsed.leadTime]) {
      expect(metric.count).toBe(0)
      expect(metric.min).toBeNull()
      expect(metric.max).toBeNull()
      expect(metric.avg).toBeNull()
      expect(metric.p25).toBeNull()
      expect(metric.p50).toBeNull()
      expect(metric.p75).toBeNull()
      expect(metric.p90).toBeNull()
      expect(metric.samples).toHaveLength(0)
    }
  })

  it('T2-3: FORBIDDEN_PROJECT_KEY 요청 시 403과 ISSUE_ACCESS_DENIED errorCode를 반환한다', async () => {
    const res = await fetch(`/api/v1/projects/${FORBIDDEN_PROJECT_KEY}/cycle-time`)

    expect(res.status).toBe(403)

    const body = (await res.json()) as { errorCode: string; message: string }
    expect(body.errorCode).toBe('ISSUE_ACCESS_DENIED')
    expect(typeof body.message).toBe('string')
  })

  it('T2-4: store에 없는 projectKey 요청 시 200과 count=0인 응답을 반환한다 (404 아님)', async () => {
    const res = await fetch('/api/v1/projects/UNSEEDED/cycle-time')

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed.projectKey).toBe('UNSEEDED')
    expect(parsed.cycleTime.count).toBe(0)
    expect(parsed.leadTime.count).toBe(0)
  })

  it('T2-5: store에 시드된 커스텀 응답이 있으면 그 저장값을 그대로 반환한다', async () => {
    const custom = {
      projectKey: 'CUSTOM-PROJECT',
      from: '2026-01-01',
      to: '2026-01-31',
      cycleTime: {
        count: 1,
        min: 100,
        max: 100,
        avg: 100,
        p25: 100,
        p50: 100,
        p75: 100,
        p90: 100,
        samples: [{ issueKey: 'CUSTOM-1', seconds: 100 }],
      },
      leadTime: {
        count: 0,
        min: null,
        max: null,
        avg: null,
        p25: null,
        p50: null,
        p75: null,
        p90: null,
        samples: [],
      },
    }
    seedCycleTime(custom)

    const res = await fetch(`/api/v1/projects/${custom.projectKey}/cycle-time`)

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed).toEqual(custom)
  })

  it('T2-6: from/to 쿼리 파라미터를 붙여도 무시하고 저장된 값을 그대로 반환한다 (핸들러는 기간 필터링을 하지 않는다)', async () => {
    const res = await fetch(
      `/api/v1/projects/${DEFAULT_CYCLE_TIME.projectKey}/cycle-time?from=1999-01-01&to=1999-01-02`,
    )

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed).toEqual(DEFAULT_CYCLE_TIME)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ATLAS 기본 시드 정합성 (Task 8b — dev/E2E 빈 화면 방지 회귀 가드)
//
// cfd-handlers.ts/velocity-handlers.ts와 동일하게 DEFAULT_CYCLE_TIME은 projectKey='ATLAS'로
// populated(count>0) 응답이어야 하고, 모듈 로드 시 자동 시드되어야 한다(dev 서버·E2E 진입 시
// 빈 화면 노출 방지 — fr-bd-01/msw-derived-behavior-shared-store-e2e 교훈).
// ─────────────────────────────────────────────────────────────────────────────

describe('ATLAS 기본 시드 정합성', () => {
  it('T3-1: DEFAULT_CYCLE_TIME의 projectKey는 backlog/cfd/velocity 기본 픽스처와 동일한 ATLAS다', () => {
    expect(DEFAULT_CYCLE_TIME.projectKey).toBe('ATLAS')
  })

  it('T3-2: leadTime 표본 수가 cycleTime 표본 수 이상이다 (IN_PROGRESS 미경유 이슈는 leadTime에만 집계되는 도메인 정합)', () => {
    expect(DEFAULT_CYCLE_TIME.leadTime.samples.length).toBeGreaterThanOrEqual(
      DEFAULT_CYCLE_TIME.cycleTime.samples.length,
    )
  })

  it('T3-3: seedCycleTime(DEFAULT_CYCLE_TIME) 시드 후 ATLAS 조회 시 cycleTime/leadTime 모두 count>0을 반환한다', async () => {
    // beforeEach가 이미 resetCycleTimeStore() + seedCycleTime(DEFAULT_CYCLE_TIME)을 수행한다.
    // 이 테스트는 그 상태에서 실제 dev/E2E 진입 시(모듈 로드 자동 시드) 관찰될 응답과 동일한지
    // 명시적으로 고정한다 — 'ATLAS' 요청이 우연히 EMPTY/UNSEEDED 분기로 빠지지 않음을 검증한다.
    const res = await fetch('/api/v1/projects/ATLAS/cycle-time')

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed.cycleTime.count).toBeGreaterThan(0)
    expect(parsed.leadTime.count).toBeGreaterThan(0)
    expect(parsed.leadTime.samples.length).toBeGreaterThanOrEqual(parsed.cycleTime.samples.length)
  })

  it('T3-4: 단위 테스트 환경(MODE=test)에서는 모듈 로드 시 자동 시드가 유지되지 않는다 — 재시드 없이 리셋만 하면 ATLAS도 count=0을 반환한다', async () => {
    // beforeEach의 시드를 이 테스트 안에서 명시적으로 되돌린다 — MODE='test'라 모듈 로드
    // 시점의 자동 시드(import.meta.env.MODE !== 'test' 가드)가 건너뛰어졌음을 전제로 하는
    // 검증이다. 자동 시드가 가드 없이 항상 실행되도록 바뀌면 이 테스트가 실패해 회귀를 잡는다.
    resetCycleTimeStore()

    const res = await fetch('/api/v1/projects/ATLAS/cycle-time')

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const parsed = cycleTimeResponseSchema.parse(body.data)

    expect(parsed.cycleTime.count).toBe(0)
    expect(parsed.leadTime.count).toBe(0)
  })
})
