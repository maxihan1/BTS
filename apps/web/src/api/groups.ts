// 전역 사용자 그룹 조회 API 클라이언트 — GET /api/v1/groups (FR-PM-07)
import { z } from 'zod'
import { apiGet } from './client'
import { groupResponseSchema } from './field-permissions.types'

export type { GroupResponse } from './field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 그룹 목록 응답 Zod 스키마.
 * backend UserGroupController.listGroups 는 배열을 직접 반환 (data 래퍼 없음).
 */
const groupListSchema = z.array(groupResponseSchema)

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전체 그룹 목록(멤버 수 포함)을 조회한다.
 *
 * GET /api/v1/groups → GroupResponse[] 배열을 직접 반환 (data 래퍼 없음).
 * SYSTEM_ADMIN 전용 엔드포인트 — 권한 없는 사용자는 403을 받는다.
 *
 * @returns GroupResponse 배열 — 그룹이 없으면 빈 배열
 * @throws ApiError(403) 전역 관리자 권한 없음 시
 * @throws ApiError(401) 미인증 시
 */
export async function fetchGroups(): Promise<z.infer<typeof groupListSchema>> {
  return apiGet('/api/v1/groups', groupListSchema)
}
