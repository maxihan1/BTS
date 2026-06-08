// 사용자 그룹 BC MSW 핸들러 — GET 목록 + E2E 시드 헤더 지원 (FR-PM-07)
import { http, HttpResponse } from 'msw'
import type { GroupResponse } from '../api/field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 기본 그룹 시드 데이터 — 필드 권한 설정 UI E2E에서 사용
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_GROUPS: GroupResponse[] = [
  {
    id: '11111111-0000-4000-8000-000000000001',
    name: '개발팀',
    description: '소프트웨어 개발자 그룹',
    memberCount: 12,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: '11111111-0000-4000-8000-000000000002',
    name: '기획팀',
    description: '제품 기획 및 PM 그룹',
    memberCount: 5,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: '11111111-0000-4000-8000-000000000003',
    name: '디자인팀',
    description: 'UX/UI 디자이너 그룹',
    memberCount: 4,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: '11111111-0000-4000-8000-000000000004',
    name: 'QA팀',
    description: '품질 보증 엔지니어 그룹',
    memberCount: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — resetGroupStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let groupStore: GroupResponse[] = [...DEFAULT_GROUPS]

/** 저장소를 기본 시드 상태로 초기화한다 (테스트 격리용) */
export function resetGroupStore(): void {
  groupStore = [...DEFAULT_GROUPS]
}

/**
 * 그룹 저장소에서 id로 단건을 조회한다.
 * field-permission-handlers.ts가 groupName 조회 시 사용하는 공유 store 읽기 헬퍼.
 *
 * @param id 그룹 UUID
 * @returns GroupResponse 또는 undefined
 */
export function getGroupById(id: string): GroupResponse | undefined {
  return groupStore.find((g) => g.id === id)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/groups
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전체 그룹 목록 조회.
 *
 * backend UserGroupController.listGroups 는 배열을 직접 반환 (data 래퍼 없음).
 * fetchGroups API 함수가 z.array(groupResponseSchema)로 직접 parse하므로
 * 응답 body는 GroupResponse[] 배열을 직접 반환한다.
 *
 * E2E 시나리오 토글 — X-MSW-Seed-Groups 헤더(encodeURIComponent JSON 배열)가 있으면
 * groupStore를 시드 데이터로 초기화한다.
 *
 * 성공 → 200 GroupResponse[] (data 래퍼 없음)
 */
const listGroupsHandler = http.get('/api/v1/groups', ({ request }) => {
  // E2E seed 헤더 처리 — X-MSW-Seed-Groups: encodeURIComponent(JSON 배열)
  const seedHeader = request.headers.get('X-MSW-Seed-Groups')
  if (seedHeader !== null) {
    try {
      const seeds = JSON.parse(decodeURIComponent(seedHeader)) as Array<{
        id: string
        name: string
        description?: string | null
        memberCount?: number
        createdAt?: string
        updatedAt?: string
      }>
      groupStore = seeds.map((seed) => ({
        id: seed.id,
        name: seed.name,
        description: seed.description ?? null,
        memberCount: seed.memberCount ?? 0,
        createdAt: seed.createdAt ?? new Date().toISOString(),
        updatedAt: seed.updatedAt ?? new Date().toISOString(),
      }))
    } catch {
      // seed 파싱 실패 시 무시하고 기존 데이터 반환
      console.error('[MSW] X-MSW-Seed-Groups 파싱 실패 — 기존 데이터 유지')
    }
  }

  return HttpResponse.json(groupStore)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 그룹 BC MSW 핸들러 배열 */
export const groupHandlers = [listGroupsHandler]
