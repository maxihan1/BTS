// 이슈 변경 이력 changelog REST API 클라이언트 + Zod 스키마 (FR-HS-02)
import { z } from 'zod'
import { apiGet, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// pageSchema 재사용 — issues.ts의 정의와 동일 형태
// issues.ts가 pageSchema를 내보내지 않으므로 여기에 동일하게 정의한다.
// (변경 시 두 파일을 함께 수정할 것)
// ─────────────────────────────────────────────────────────────────────────────

/** Spring Page 응답 Zod 스키마 — changelog 전용 제네릭 헬퍼 */
const changelogPageSchema = <T>(itemSchema: z.ZodSchema<T>) =>
  z.object({
    content: z.array(itemSchema),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    size: z.number().int().positive(),
    number: z.number().int().nonnegative(),
    first: z.boolean(),
    last: z.boolean(),
    empty: z.boolean(),
  })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueChangelogController DTO 직렬화 형태와 1:1 대응.
// @JsonInclude(NON_NULL) 적용으로 null 필드가 없을 수 있어 .nullish() 사용.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 변경 항목 Zod 스키마.
 * backend ChangeItemResponse DTO 직렬화 형태와 1:1 대응.
 * - fromLabel/toLabel: #120 박제(assignee/securityLevel만 non-null), 나머지는 null
 * - fromValue/toValue: lifecycle(created/deleted) 행위자명 등 raw 값
 */
export const changeItemSchema = z.object({
  /** 변경된 필드 키 (예: "summary", "assignee", "priority") */
  field: z.string(),
  /** 변경 전 raw 값. 없으면 null(신규 생성 등) */
  fromValue: z.string().nullish(),
  /** 변경 후 raw 값. 없으면 null(삭제 등) */
  toValue: z.string().nullish(),
  /** 변경 전 박제 표시명. assignee/securityLevel만 non-null, 나머지 null */
  fromLabel: z.string().nullish(),
  /** 변경 후 박제 표시명. assignee/securityLevel만 non-null, 나머지 null */
  toLabel: z.string().nullish(),
})

/**
 * 이슈 변경 그룹 Zod 스키마.
 * backend ChangeGroupResponse DTO 직렬화 형태와 1:1 대응.
 * - actorId/actorName: 시스템 행위자이면 null (graceful degrade)
 */
export const changeGroupSchema = z.object({
  /** 행위자 UUID. 시스템 이벤트(생성 등)이면 null */
  actorId: z.string().nullish(),
  /** 행위자 표시명. actorId=null이면 null, UserLookupPort 해석 실패 시도 null */
  actorName: z.string().nullish(),
  /** 변경 시각 ISO-8601 문자열 */
  createdAt: z.string(),
  /** 해당 변경 그룹에 속한 변경 항목 목록 */
  items: z.array(changeItemSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 변경 항목 타입 */
export type ChangeItem = z.infer<typeof changeItemSchema>

/** 이슈 변경 그룹 타입 */
export type ChangeGroup = z.infer<typeof changeGroupSchema>

/** Spring Page<ChangeGroup> 타입 */
export type ChangelogPage = z.infer<ReturnType<typeof changelogPageSchema<ChangeGroup>>>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 변경 이력을 페이징 조회한다.
 *
 * GET /api/v1/issues/{key}/changelog?page={page}&size={size}
 * 응답은 Spring Page 형태 — 래퍼 없음 (fetchIssues 선례와 동일).
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns Spring Page 구조 — content 배열 + 페이징 메타 (래퍼 없음)
 * @throws ApiError(404) 해당 key의 이슈가 없거나 권한 없을 때
 */
export async function fetchIssueChangelog(
  key: string,
  page: number,
  size: number,
): Promise<ChangelogPage> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  return apiGet(
    `/api/v1/issues/${key}/changelog?${query.toString()}`,
    changelogPageSchema(changeGroupSchema),
  ).catch((err: unknown) => {
    if (err instanceof ApiError && err.status === 404) {
      throw new ApiError(404, { message: `이슈를 찾을 수 없습니다. ${key}` })
    }
    throw err
  })
}
