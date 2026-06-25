// AQL 검색 MSW 픽스처 — FR-SR-02 D6 Task-3
import type { AqlSearchHit, AqlSearchPage } from '@/api/search'

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
