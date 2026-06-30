// AQL 검색 + 비동기 Export 잡 MSW 픽스처 — FR-SR-02 D6 Task-3 / FR-EX-02 D7 Task-3
import type { AqlSearchHit, AqlSearchPage, ExportJobStatus } from '@/api/search'

// ─────────────────────────────────────────────────────────────────────────────
// AqlSearchHit 샘플 데이터
// 백엔드 AqlSearchHit DTO 필드와 1:1 대응 (labels 없음)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 AQL 검색 결과 샘플 — ATLAS 프로젝트 버그 이슈 */
export const SEARCH_HIT_BUG: AqlSearchHit = {
  key: 'ATLAS-1',
  summary: '로그인 버튼이 동작하지 않음',
  typeKey: 'bug',
  currentStateKey: 'open',
  assigneeId: '00000000-0000-4000-a000-000000000001',
  priority: 2,
  priorityName: 'High',
  projectKey: 'ATLAS',
  updatedAt: '2026-06-25T10:00:00Z',
}

/** AQL 검색 결과 샘플 — 담당자 미할당 */
export const SEARCH_HIT_UNASSIGNED: AqlSearchHit = {
  key: 'ATLAS-2',
  summary: '대시보드 로딩이 느림',
  typeKey: 'task',
  currentStateKey: 'in_progress',
  assigneeId: null,
  priority: 3,
  priorityName: 'Medium',
  projectKey: 'ATLAS',
  updatedAt: '2026-06-24T08:30:00Z',
}

/** AQL 검색 결과 샘플 — 완료된 이슈 */
export const SEARCH_HIT_DONE: AqlSearchHit = {
  key: 'ATLAS-3',
  summary: '회원가입 이메일 인증 구현',
  typeKey: 'story',
  currentStateKey: 'done',
  assigneeId: '00000000-0000-4000-a000-000000000002',
  priority: 2,
  priorityName: 'High',
  projectKey: 'ATLAS',
  updatedAt: '2026-06-23T15:45:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 응답 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AqlSearchPage 픽스처 생성 헬퍼.
 * Spring Page 직렬화 형태와 1:1 대응.
 */
export function makeSearchPage(
  hits: AqlSearchHit[],
  overrides: Partial<Omit<AqlSearchPage, 'content'>> = {},
): AqlSearchPage {
  const totalElements = overrides.totalElements ?? hits.length
  const size = overrides.size ?? 20
  const number = overrides.number ?? 0
  const totalPages = overrides.totalPages ?? (totalElements === 0 ? 0 : Math.ceil(totalElements / size))
  return {
    content: hits,
    totalElements,
    totalPages,
    size,
    number,
    first: overrides.first ?? number === 0,
    last: overrides.last ?? number >= totalPages - 1,
    empty: overrides.empty ?? hits.length === 0,
  }
}

/** 기본 검색 결과 페이지 — 3건 */
export const DEFAULT_SEARCH_PAGE: AqlSearchPage = makeSearchPage([
  SEARCH_HIT_BUG,
  SEARCH_HIT_UNASSIGNED,
  SEARCH_HIT_DONE,
])

/** 빈 검색 결과 페이지 */
export const EMPTY_SEARCH_PAGE: AqlSearchPage = makeSearchPage([])

// ─────────────────────────────────────────────────────────────────────────────
// Export 잡 응답 픽스처 팩토리 — MSW stateful 핸들러 + 단위 테스트용
//
// ★ @JsonInclude(NON_NULL) 재현 (BLOCKER-2 / CONCERN-E):
//   PENDING·RUNNING 응답에는 rowCount·errorCode 키가 실제 백엔드 JSON에 존재하지 않는다.
//   JavaScript 객체에 해당 키를 포함하지 않으면 JSON.stringify가 키 자체를 생략한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PENDING 상태 Export 잡 응답 픽스처.
 * rowCount·errorCode 키 생략 — @JsonInclude(NON_NULL) 재현.
 *
 * @param jobId Export 잡 UUID
 */
export function makeExportJobPendingResponse(
  jobId: string,
): Omit<ExportJobStatus, 'rowCount' | 'errorCode'> {
  return { jobId, status: 'PENDING', progress: 0, format: 'CSV', downloadReady: false }
}

/**
 * RUNNING 상태 Export 잡 응답 픽스처.
 * rowCount·errorCode 키 생략 — @JsonInclude(NON_NULL) 재현.
 *
 * @param jobId Export 잡 UUID
 */
export function makeExportJobRunningResponse(
  jobId: string,
): Omit<ExportJobStatus, 'rowCount' | 'errorCode'> {
  return { jobId, status: 'RUNNING', progress: 50, format: 'CSV', downloadReady: false }
}

/**
 * COMPLETED 상태 Export 잡 응답 픽스처 (다운로드 준비 완료).
 * rowCount 포함, errorCode 키 생략 — @JsonInclude(NON_NULL) 재현.
 *
 * @param jobId Export 잡 UUID
 * @param rowCount 완료된 행 수 (기본 42)
 */
export function makeExportJobCompletedResponse(
  jobId: string,
  rowCount = 42,
): Omit<ExportJobStatus, 'errorCode'> {
  return { jobId, status: 'COMPLETED', progress: 100, format: 'CSV', downloadReady: true, rowCount }
}
