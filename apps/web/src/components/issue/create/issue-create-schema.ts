// 이슈 생성 폼의 검증 스키마·상수·요청 본문 조립 (FR-UX-09 F2)
import { z } from 'zod'
import { issueCreateStrings } from '@/i18n/ko'
import type { CreateIssueInput, CustomFieldValues } from '@/api/issues'

/**
 * 이슈 제목(summary) 최대 길이 — zod 검증 · URL summary 프리필 clamp(FR-UX-04 FR7) ·
 * 상세 화면 제목 편집 `maxLength` 가 공유한다.
 *
 * ## 200 → 255 (2026-09-04, Jira 패리티)
 *
 * FR-UX-09 F2 는 프론트(500)를 **백엔드(200)에 맞춰** 봉합했다. 그때 200 자체가 DB
 * `VARCHAR(255)`·도메인 `require(<=255)` 보다 좁다는 것은 되재지 않았고, 그래서 「클론으로는
 * 255자 제목을 만들 수 있는데 그 이슈를 열어 저장하면 400」이라는 비대칭이 남아 있었다.
 *
 * 이번에는 반대 방향으로 **백엔드를 Jira 값에 맞췄다** — Jira Cloud 는 255 이고 변경 불가다
 * ("The 255 character limit is for single-line text fields, like Summary. You can not change it.",
 * 2026-09-04 조회). 백엔드 단일 출처는 `IssueTextConstraints.SUMMARY_MAX`.
 */
export const SUMMARY_MAX_LENGTH = 255

/** 기본 우선순위 — 백엔드 도메인 기본값(3, Medium)과 같은 값이다. */
export const DEFAULT_PRIORITY = 3

/** 이슈 생성 폼 입력 Zod 스키마 */
export const issueCreateSchema = z.object({
  projectKey: z.string().min(1, issueCreateStrings.projectKeyRequired),
  summary: z
    .string()
    .min(1, issueCreateStrings.summaryRequired)
    .max(SUMMARY_MAX_LENGTH, issueCreateStrings.summaryTooLong),
  /** FR-UX-09 F2 — 본문. 비우면 서버가 프로젝트 템플릿으로 채운다(FR-TM-01)라 필수가 아니다. */
  description: z.string(),
})

/** Zod 스키마에서 추론한 폼 값 타입 */
export type IssueCreateFormValues = z.infer<typeof issueCreateSchema>

/**
 * 명령 팔레트 `/issue <제목>`(FR-UX-04)가 넘긴 URL summary를 폼 기본값으로 쓸 수 있게 정제한다.
 *
 * - undefined → 빈 문자열(기존 동작 유지, 회귀 방지)
 * - 앞뒤 공백 trim
 * - zod max(SUMMARY_MAX_LENGTH)를 넘으면 제출 시 검증 에러가 바로 뜨는 것을 막기 위해 clamp
 *
 * @param rawSummary URL search param에서 읽은 summary(선택)
 * @returns 폼 defaultValues.summary에 바로 쓸 수 있는 문자열
 */
export function sanitizeInitialSummary(rawSummary: string | undefined): string {
  if (rawSummary === undefined) return ''
  return rawSummary.trim().slice(0, SUMMARY_MAX_LENGTH)
}

/** `buildCreateIssuePayload` 가 폼 값 밖에서 받아야 하는 선택 상태 묶음 */
export interface IssueCreateSelections {
  /** 이슈 유형 id — null 이면 키를 빼 서버 fallback(task)에 맡긴다 */
  typeId: number | null
  /** 담당자 3-state — `undefined` 는 키 자체를 넣지 않는다는 뜻이다 */
  assigneeIntent: string | null | undefined
  priority: number
  labels: string[]
  componentIds: string[]
  securityLevelId: string | null
  customFieldValues: CustomFieldValues
}

/**
 * 폼 값 + 선택 상태를 `POST /issues` 요청 본문으로 조립한다.
 *
 * ★**키를 넣느냐 빼느냐가 서버 동작을 가른다.** 값이 없다고 `null` 을 넣으면 안 되는 필드가
 * 섞여 있어 스프레드로 키 존재 자체를 제어한다.
 * - `typeId` 미전달 → 서버가 task 로 fallback
 * - `description` 공백 → 서버가 프로젝트 템플릿으로 채움 (FR-TM-01)
 * - `assigneeId` **키 부재** → 서버 자동 배정 유지 / **명시 null** → 자동 배정을 끄고 미할당 확정
 * - `securityLevelId` 는 반대로 null 을 **실어야** 서버가 무등급으로 처리한다
 */
export function buildCreateIssuePayload(
  values: IssueCreateFormValues,
  selections: IssueCreateSelections,
): CreateIssueInput {
  const { description, ...rest } = values
  return {
    ...rest,
    ...(selections.typeId !== null ? { typeId: selections.typeId } : {}),
    ...(description.trim() !== '' ? { description } : {}),
    ...(selections.assigneeIntent !== undefined
      ? { assigneeId: selections.assigneeIntent }
      : {}),
    priority: selections.priority,
    labels: selections.labels,
    componentIds: selections.componentIds,
    securityLevelId: selections.securityLevelId,
    ...(Object.keys(selections.customFieldValues).length > 0
      ? { customFields: selections.customFieldValues }
      : {}),
  }
}
