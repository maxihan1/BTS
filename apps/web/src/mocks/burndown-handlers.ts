// 스프린트 번다운/번업 BC MSW 핸들러 — GET /api/v1/sprints/:id/burndown, 시드 가능 store (FR-RP-01 D6/D7 Task-4)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수(dev/E2E 빈 화면 방지, 단위 테스트는 MODE='test'에서 건너뜀)
//
import { http, HttpResponse } from 'msw'
import type { BurndownResponse } from '@/api/burndown'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 트리거 sprintId 상수 — 특정 값 요청 시 고정 에러 응답 (E2E/단위테스트 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** 이 sprintId로 요청하면 403(AGILE_ACCESS_DENIED)을 반환한다 */
export const FORBIDDEN_SPRINT_ID = 'sprint-forbidden'

/** 이 sprintId로 요청하면 422(AGILE_SPRINT_DATES_REQUIRED)를 반환한다 */
export const DATES_REQUIRED_SPRINT_ID = 'sprint-dates-required'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — sprintId → BurndownResponse
// ─────────────────────────────────────────────────────────────────────────────

/** 번다운 store — sprintId 기준. GET 핸들러가 읽어 응답을 구성한다 (읽기 전용 API라 mutation 없음) */
export let burndownStore: Map<string, BurndownResponse> = new Map()

/** store를 초기 상태로 리셋한다. 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다. */
export function resetBurndownStore(): void {
  burndownStore = new Map()
}

/**
 * BurndownResponse를 store에 시드한다. 동일 sprintId가 이미 있으면 덮어쓴다.
 *
 * @param response 시드할 번다운 응답
 */
export function seedBurndown(response: BurndownResponse): void {
  burndownStore.set(response.sprintId, structuredClone(response))
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — backlog-fixtures.ts DEFAULT_BACKLOG의 스프린트 1과 동일 sprintId
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 시드 스프린트 ID — backlog-fixtures.ts DEFAULT_BACKLOG.sprints[0]과 동일 값 */
export const DEFAULT_SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/**
 * 기본 번다운 픽스처 — ATLAS 프로젝트, 5일 시계열, 마지막 2일은 미래(remaining/completed=null).
 * BurndownResponse 계약과 정확히 일치 (프론트-백엔드 drift 방지, frontend-zod-backend-dto-contract-gap 교훈).
 */
export const DEFAULT_BURNDOWN: BurndownResponse = {
  sprintId: DEFAULT_SPRINT_ID,
  projectKey: 'ATLAS',
  status: 'ACTIVE',
  startDate: '2026-06-01',
  endDate: '2026-06-05',
  totalScopeSeconds: 36000,
  points: [
    { date: '2026-06-01', remainingSeconds: 36000, idealSeconds: 36000, completedSeconds: 0, scopeSeconds: 36000 },
    { date: '2026-06-02', remainingSeconds: 27000, idealSeconds: 27000, completedSeconds: 9000, scopeSeconds: 36000 },
    { date: '2026-06-03', remainingSeconds: 18000, idealSeconds: 18000, completedSeconds: 18000, scopeSeconds: 36000 },
    { date: '2026-06-04', remainingSeconds: null, idealSeconds: 9000, completedSeconds: null, scopeSeconds: 36000 },
    { date: '2026-06-05', remainingSeconds: null, idealSeconds: 0, completedSeconds: null, scopeSeconds: 36000 },
  ],
}

// 모듈 로드 시 기본 픽스처를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 화면이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedBurndown(DEFAULT_BURNDOWN)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/sprints/:id/burndown
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/sprints/{id}/burndown — 스프린트 번다운/번업 시계열 조회.
 *
 * 분기.
 * - sprintId=FORBIDDEN_SPRINT_ID → 403 AGILE_ACCESS_DENIED
 * - sprintId=DATES_REQUIRED_SPRINT_ID → 422 AGILE_SPRINT_DATES_REQUIRED
 * - store에 없는 sprintId → 404 AGILE_SPRINT_NOT_FOUND
 * - store에 있으면 → 200 { data: BurndownResponse }
 *
 * 에러 body 형식은 backlog-handlers.ts(같은 agile-planning BC) 선례를 따른다 — { errorCode, message }.
 */
const getSprintBurndownHandler = http.get('/api/v1/sprints/:id/burndown', ({ params }) => {
  const sprintId = params['id'] as string

  if (sprintId === FORBIDDEN_SPRINT_ID) {
    return HttpResponse.json(
      { errorCode: 'AGILE_ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }

  if (sprintId === DATES_REQUIRED_SPRINT_ID) {
    return HttpResponse.json(
      { errorCode: 'AGILE_SPRINT_DATES_REQUIRED', message: '스프린트 기간이 설정되지 않았습니다.' },
      { status: 422 },
    )
  }

  const stored = burndownStore.get(sprintId)
  if (stored === undefined) {
    return HttpResponse.json(
      { errorCode: 'AGILE_SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
      { status: 404 },
    )
  }

  return HttpResponse.json({ data: stored })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 스프린트 번다운/번업 BC MSW 핸들러 배열 */
export const burndownHandlers = [getSprintBurndownHandler]
