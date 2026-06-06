// 프로젝트 리드 MSW 핸들러 — stateful GET/PATCH + RFC 7807 ProblemDetail 에러 (FR-CM-04)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입 — 프로젝트별 리드 Map
// ─────────────────────────────────────────────────────────────────────────────

interface StoredProjectLead {
  /** 프로젝트 식별자 (UUID 또는 키) */
  projectId: string
  /** 리드 사용자 UUID — null이면 리드 미지정 */
  leadUserId: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/** 422 강제 플래그 키 — 'true' 세팅 시 PATCH /lead가 422를 반환한다 */
const LS_KEY_PROJECT_LEAD_422 = 'msw-project-lead-422'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — resetProjectLeadStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let projectLeadStore: Map<string, StoredProjectLead> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetProjectLeadStore(): void {
  projectLeadStore = new Map()
}

/**
 * 특정 프로젝트의 리드 정보를 store에 직접 삽입한다 (테스트 / E2E 시드용).
 *
 * @param projectId 프로젝트 UUID 또는 키
 * @param leadUserId 리드 사용자 UUID — null 허용
 */
export function seedProjectLead(projectId: string, leadUserId: string | null): void {
  projectLeadStore.set(projectId, { projectId, leadUserId })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

function problemDetail(
  status: number,
  type: string,
  title: string,
  errorCode: string,
  detail: string,
): HttpResponse<ProblemDetail> {
  return HttpResponse.json<ProblemDetail>(
    {
      type: `https://bts.example.com/problems/${type}`,
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

function projectNotFound(projectIdOrKey: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'project-not-found',
    'Project Not Found',
    'PROJECT_NOT_FOUND',
    `프로젝트를 찾을 수 없습니다: ${projectIdOrKey}`,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/lead
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드 사용자 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재(store에 없는 키) → 404 PROJECT_NOT_FOUND
 * 성공 → 200 { data: { projectId, leadUserId } }
 *
 * E2E 시나리오 토글 — X-MSW-Seed-ProjectLead 헤더(encodeURIComponent(JSON)) 가 있으면
 * store를 해당 데이터로 초기화한다.
 * 이 헤더는 테스트 전용이며 프로덕션 API에는 존재하지 않는다.
 */
const getProjectLeadHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/lead',
  ({ params, request }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string

    // E2E seed 헤더 처리 — X-MSW-Seed-ProjectLead: encodeURIComponent(JSON)
    const seedHeader = request.headers.get('X-MSW-Seed-ProjectLead')
    if (seedHeader !== null) {
      try {
        const seed = JSON.parse(decodeURIComponent(seedHeader)) as {
          projectId: string
          leadUserId?: string | null
        }
        projectLeadStore.set(projectIdOrKey, {
          projectId: seed.projectId,
          leadUserId: seed.leadUserId ?? null,
        })
      } catch {
        // seed 파싱 실패 시 무시하고 기존 데이터 반환
      }
    }

    const stored = projectLeadStore.get(projectIdOrKey)
    if (stored === undefined) {
      return projectNotFound(projectIdOrKey)
    }

    return HttpResponse.json({ data: { projectId: stored.projectId, leadUserId: stored.leadUserId } })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/lead
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드 사용자 변경.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 프로젝트 미존재(store에 없는 키) → 404 PROJECT_NOT_FOUND
 * 2. localStorage 플래그(msw-project-lead-422) → 422 PROJECT_LEAD_NOT_FOUND (E2E 토글)
 * 성공 → 200 { data: { projectId, leadUserId } }
 */
const patchProjectLeadHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/lead',
  async ({ request, params }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string
    const stored = projectLeadStore.get(projectIdOrKey)
    if (stored === undefined) {
      return projectNotFound(projectIdOrKey)
    }

    // E2E 시나리오 토글 — localStorage 플래그로 422 강제
    const leadFlag = globalThis.localStorage?.getItem(LS_KEY_PROJECT_LEAD_422)
    if (leadFlag === 'true') {
      return problemDetail(
        422,
        'project-lead-not-found',
        'Project Lead Not Found',
        'PROJECT_LEAD_NOT_FOUND',
        '리드로 지정한 사용자를 찾을 수 없습니다.',
      )
    }

    const body = (await request.json()) as { leadUserId: string | null }
    const updated: StoredProjectLead = {
      ...stored,
      leadUserId: body.leadUserId,
    }
    projectLeadStore.set(projectIdOrKey, updated)

    return HttpResponse.json({ data: { projectId: updated.projectId, leadUserId: updated.leadUserId } })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 리드 BC MSW 핸들러 배열 */
export const projectLeadHandlers = [getProjectLeadHandler, patchProjectLeadHandler]
