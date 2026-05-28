// 이슈 타입 backend API 클라이언트 + Zod 스키마 (5 표준 read-only)
import { z } from 'zod'
import { apiGet } from './client'
import { dataOf } from './workflow-schemes.types'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueTypeDto 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 타입 단건 응답 Zod 스키마.
 * iconUrl은 backend T4 cross-BC lookup으로 채워지며, 미설정 시 null.
 */
export const issueTypeResponseSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  description: z.string(),
  iconUrl: z.string().nullable(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 타입 응답 타입 */
export type IssueTypeResponse = z.infer<typeof issueTypeResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 타입 목록을 조회한다.
 * GET /api/v1/issue-types → { data: IssueTypeResponse[] }
 *
 * 5 표준 이슈 타입(bug, task, story, epic, subtask)은 시스템 고정값으로 쓰기 불가.
 * 이 API는 read-only 조회 전용이다.
 */
export async function fetchIssueTypes(): Promise<IssueTypeResponse[]> {
  const wrapped = await apiGet(
    '/api/v1/issue-types',
    dataOf(z.array(issueTypeResponseSchema)),
  )
  return wrapped.data
}
