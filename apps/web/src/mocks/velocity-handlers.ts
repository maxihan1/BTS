// 스프린트 벨로시티 차트 BC MSW 핸들러 — GET /api/v1/projects/:projectKey/velocity, 시드 가능 store (FR-RP-02 D6/D7 Task-6)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수(dev/E2E 빈 화면 방지, 단위 테스트는 MODE='test'에서 건너뜀)
//   - frontend-zod-backend-dto-contract-gap: 응답 스키마는 backend DTO(SprintVelocityController/VelocityResponse.kt)에서
//     도출 — 필드명·타입을 grep으로 대조했다 (projectKey/averageCommitmentSeconds/averageCompletedSeconds/sprints).
//
import { http, HttpResponse } from 'msw'
import type { VelocityResponse } from '@/api/velocity'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 트리거 projectKey 상수 — 특정 값 요청 시 고정 응답 (E2E/단위테스트 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** 이 projectKey로 요청하면 403(AGILE_ACCESS_DENIED)을 반환한다 */
export const FORBIDDEN_PROJECT_KEY = 'PROJECT-FORBIDDEN'

/** 이 projectKey로 요청하면 200이되 완료 스프린트가 없는 빈 벨로시티(sprints:[], 평균 0)를 반환한다 */
export const EMPTY_PROJECT_KEY = 'PROJECT-EMPTY'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — projectKey → VelocityResponse
// ─────────────────────────────────────────────────────────────────────────────

/** 벨로시티 store — projectKey 기준. GET 핸들러가 읽어 응답을 구성한다 (읽기 전용 API라 mutation 없음) */
export let velocityStore: Map<string, VelocityResponse> = new Map()

/** store를 초기 상태로 리셋한다. 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다. */
export function resetVelocityStore(): void {
  velocityStore = new Map()
}

/**
 * VelocityResponse를 store에 시드한다. 동일 projectKey가 이미 있으면 덮어쓴다.
 *
 * @param response 시드할 벨로시티 응답
 */
export function seedVelocity(response: VelocityResponse): void {
  velocityStore.set(response.projectKey, structuredClone(response))
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — backlog-fixtures.ts DEFAULT_BACKLOG / burndown-handlers.ts DEFAULT_BURNDOWN과
// 동일한 projectKey('ATLAS')를 사용한다. 백로그→벨로시티 화면 이동 E2E에서 데이터가 이어지도록 정합.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 기본 벨로시티 픽스처 — ATLAS 프로젝트, 완료 스프린트 3개.
 * averageCommitmentSeconds/averageCompletedSeconds는 sprints의 산술평균이다
 * (VelocityResponse.averageCommitmentSeconds KDoc — "스프린트가 없으면 0" 계약과 일치).
 */
export const DEFAULT_VELOCITY: VelocityResponse = {
  projectKey: 'ATLAS',
  averageCommitmentSeconds: 30000,
  averageCompletedSeconds: 24000,
  sprints: [
    {
      sprintId: 'a0000000-0000-4000-8000-000000000001',
      name: 'Sprint 1',
      startDate: '2026-05-01',
      endDate: '2026-05-14',
      commitmentSeconds: 36000,
      completedSeconds: 27000,
    },
    {
      sprintId: 'a0000000-0000-4000-8000-000000000002',
      name: 'Sprint 2',
      startDate: '2026-05-15',
      endDate: '2026-05-28',
      commitmentSeconds: 28800,
      completedSeconds: 21600,
    },
    {
      sprintId: 'a0000000-0000-4000-8000-000000000003',
      name: 'Sprint 3',
      startDate: '2026-05-29',
      endDate: '2026-06-11',
      commitmentSeconds: 25200,
      completedSeconds: 23400,
    },
  ],
}

// 모듈 로드 시 기본 픽스처를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 화면이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedVelocity(DEFAULT_VELOCITY)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/velocity
// ─────────────────────────────────────────────────────────────────────────────

/** projectKey에 대응하는 completed 스프린트가 없을 때 반환하는 빈 벨로시티 응답을 구성한다 */
function emptyVelocity(projectKey: string): VelocityResponse {
  return {
    projectKey,
    averageCommitmentSeconds: 0,
    averageCompletedSeconds: 0,
    sprints: [],
  }
}

/**
 * GET /api/v1/projects/{projectKey}/velocity — 프로젝트 최근 완료 스프린트 벨로시티 시계열 조회.
 *
 * 분기.
 * - projectKey=FORBIDDEN_PROJECT_KEY → 403 AGILE_ACCESS_DENIED
 * - projectKey=EMPTY_PROJECT_KEY → 200 { data } 이되 sprints:[], 평균 0
 * - store에 있으면 → 200 { data: 저장된 VelocityResponse }
 * - store에 없으면 → 200 빈 벨로시티 (백엔드는 유효 프로젝트+완료 스프린트 0개면 200 빈 응답을 반환한다.
 *   SprintVelocityController/SprintVelocityService 대조 — 404 아님)
 *
 * 에러 body 형식은 burndown-handlers.ts(같은 agile-planning BC) 선례를 따른다 — { errorCode, message }.
 */
const getProjectVelocityHandler = http.get('/api/v1/projects/:projectKey/velocity', ({ params }) => {
  const projectKey = params['projectKey'] as string

  if (projectKey === FORBIDDEN_PROJECT_KEY) {
    return HttpResponse.json(
      { errorCode: 'AGILE_ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }

  if (projectKey === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({ data: emptyVelocity(projectKey) })
  }

  const stored = velocityStore.get(projectKey)
  if (stored === undefined) {
    return HttpResponse.json({ data: emptyVelocity(projectKey) })
  }

  return HttpResponse.json({ data: stored })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 스프린트 벨로시티 BC MSW 핸들러 배열 */
export const velocityHandlers = [getProjectVelocityHandler]
