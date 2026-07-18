// 전역 권한 부여/회수 BC MSW 핸들러 — stateful CRUD + X-MSW-Seed 시드 헤더 (FR-PM-10 D6)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 — GlobalPermissionGrantController 응답/요청 계약 (백엔드 #277)
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여 대상 종류 */
export type GranteeType = 'USER' | 'GROUP'

/** `GET/POST /api/v1/admin/global-permissions` 응답 단건(bare, 래퍼 없음) */
export interface GlobalPermissionGrant {
  id: string
  permission: string
  granteeType: GranteeType
  granteeId: string
  grantedBy: string
  createdAt: string
}

/** `POST /api/v1/admin/global-permissions` 요청 바디 */
interface CreateGlobalPermissionGrantInput {
  permission: string
  granteeType: GranteeType
  granteeId: string
}

/** MSW 시드 헤더로 주입되는 grant — id/grantedBy/createdAt 은 선택(누락 시 기본값 채움) */
interface GlobalPermissionGrantSeed {
  id: string
  permission: string
  granteeType: GranteeType
  granteeId: string
  grantedBy?: string
  createdAt?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const BASE_PATH = '/api/v1/admin/global-permissions'

/**
 * 시드/그랜트 부여 시 grantedBy 기본값 — MSW 환경에서는 세션을 실제로 파싱하지 않으므로
 * git-webhook/DEFAULT_GIT_WEBHOOK_CREATOR_ID 등 다른 admin write 핸들러와 동일하게
 * 기본 관리자 alice의 UUID(auth-fixtures.ts 시드값)를 고정 사용한다.
 */
const DEFAULT_GRANTED_BY = '00000000-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 — field-permission-handlers.ts와 동일한 자기완결 패턴
// ─────────────────────────────────────────────────────────────────────────────

function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — resetGlobalPermissionStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let globalPermissionStore: GlobalPermissionGrant[] = []

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetGlobalPermissionStore(): void {
  globalPermissionStore = []
}

/** 동일 (permission, granteeType, granteeId) 조합의 기존 grant를 찾는다 */
function findDuplicate(
  input: Pick<GlobalPermissionGrant, 'permission' | 'granteeType' | 'granteeId'>,
): GlobalPermissionGrant | undefined {
  return globalPermissionStore.find(
    (g) =>
      g.permission === input.permission &&
      g.granteeType === input.granteeType &&
      g.granteeId === input.granteeId,
  )
}

/** 시드 헤더 JSON 항목을 저장소 레코드로 정규화한다 (id/grantedBy/createdAt 누락 시 기본값 채움) */
function toStoredGrant(seed: GlobalPermissionGrantSeed): GlobalPermissionGrant {
  return {
    id: seed.id,
    permission: seed.permission,
    granteeType: seed.granteeType,
    granteeId: seed.granteeId,
    grantedBy: seed.grantedBy ?? DEFAULT_GRANTED_BY,
    createdAt: seed.createdAt ?? new Date().toISOString(),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/admin/global-permissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 목록 조회.
 *
 * backend GlobalPermissionGrantController.list 는 배열을 직접 반환 (data 래퍼 없음).
 *
 * E2E 시나리오 토글 — X-MSW-Seed-GlobalPermissions 헤더(encodeURIComponent JSON 배열)가 있으면
 * globalPermissionStore를 시드 데이터로 초기화한다.
 *
 * 성공 → 200 GlobalPermissionGrant[] (data 래퍼 없음)
 */
const listGlobalPermissionsHandler = http.get(BASE_PATH, ({ request }) => {
  const seedHeader = request.headers.get('X-MSW-Seed-GlobalPermissions')
  if (seedHeader !== null) {
    try {
      const seeds = JSON.parse(decodeURIComponent(seedHeader)) as GlobalPermissionGrantSeed[]
      globalPermissionStore = seeds.map(toStoredGrant)
    } catch {
      // seed 파싱 실패 시 무시하고 기존 데이터 반환
      console.error('[MSW] X-MSW-Seed-GlobalPermissions 파싱 실패 — 기존 데이터 유지')
    }
  }

  return HttpResponse.json(globalPermissionStore)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/admin/global-permissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여.
 *
 * 에러 분기 (백엔드와 동일, 이 mock 범위 내).
 * 1. 동일 (permission, granteeType, granteeId) 조합 중복 → 409 { error: 'grant_already_exists' }
 * 성공 → 201 GlobalPermissionGrant (data 래퍼 없음), id/grantedBy/createdAt 은 서버가 채운다.
 *
 * 401/403(미인가) 분기는 이 mock 단위 테스트 범위 밖 — E2E는 admin 세션으로 진입한다.
 */
const createGlobalPermissionHandler = http.post(BASE_PATH, async ({ request }) => {
  const body = (await request.json()) as CreateGlobalPermissionGrantInput

  if (findDuplicate(body) !== undefined) {
    return HttpResponse.json({ error: 'grant_already_exists' }, { status: 409 })
  }

  const newGrant: GlobalPermissionGrant = {
    id: generateUuidV4(),
    permission: body.permission,
    granteeType: body.granteeType,
    granteeId: body.granteeId,
    grantedBy: DEFAULT_GRANTED_BY,
    createdAt: new Date().toISOString(),
  }
  globalPermissionStore = [...globalPermissionStore, newGrant]

  return HttpResponse.json(newGrant, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/admin/global-permissions/:grantId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 회수.
 *
 * 에러 분기 (백엔드와 동일, 이 mock 범위 내).
 * 1. 미존재 grantId → 404 { error: 'grant_not_found' }
 * 성공 → 204 No Content
 */
const deleteGlobalPermissionHandler = http.delete(`${BASE_PATH}/:grantId`, ({ params }) => {
  const grantId = params['grantId'] as string
  const target = globalPermissionStore.find((g) => g.id === grantId)
  if (target === undefined) {
    return HttpResponse.json({ error: 'grant_not_found' }, { status: 404 })
  }

  globalPermissionStore = globalPermissionStore.filter((g) => g.id !== grantId)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여/회수 BC MSW 핸들러 배열 */
export const globalPermissionHandlers = [
  listGlobalPermissionsHandler,
  createGlobalPermissionHandler,
  deleteGlobalPermissionHandler,
]
