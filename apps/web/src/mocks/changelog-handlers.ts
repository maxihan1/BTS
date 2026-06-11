// 이슈 변경 이력 MSW 핸들러 — GET /api/v1/issues/:key/changelog 페이징 + 404 (FR-HS-02)
import { http, HttpResponse } from 'msw'
import {
  DENIED_CHANGELOG_KEYS,
  atlasOneChangelogFixture,
  type ChangeGroupFixture,
} from './changelog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 fixture 저장소 — key별 그룹 목록
// ─────────────────────────────────────────────────────────────────────────────

/**
 * key → ChangeGroupFixture[] 맵.
 * 기본값으로 ATLAS-1 fixture가 사전 등록된다.
 * seedChangelogFixture()로 외부에서 시드를 주입할 수 있다 (E2E 지원).
 */
const fixtureStore: Map<string, ChangeGroupFixture[]> = new Map([
  ['ATLAS-1', atlasOneChangelogFixture],
])

/**
 * 특정 이슈 key에 changelog fixture를 등록한다.
 * E2E MSW 시나리오 시드 주입 지원 (learning msw-derived-behavior-shared-store-e2e).
 */
export function seedChangelogFixture(key: string, groups: ChangeGroupFixture[]): void {
  fixtureStore.set(key, groups)
}

// ─────────────────────────────────────────────────────────────────────────────
// RFC 7807 ProblemDetail 에러 헬퍼
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

// ─────────────────────────────────────────────────────────────────────────────
// 페이징 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Spring Page 응답 형태로 배열을 슬라이싱한다.
 * content/totalElements/totalPages/size/number/first/last/empty — issues.ts pageSchema와 1:1 정합.
 */
function buildPage<T>(
  all: T[],
  page: number,
  size: number,
): {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
  empty: boolean
} {
  const totalElements = all.length
  const totalPages = size > 0 ? Math.ceil(totalElements / size) : 0
  const offset = page * size
  const content = all.slice(offset, offset + size)
  const first = page === 0
  const last = page >= totalPages - 1 || totalPages === 0
  const empty = content.length === 0

  return {
    content,
    totalElements,
    totalPages,
    size,
    number: page,
    first,
    last,
    empty,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/changelog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 변경 이력 페이징 조회.
 *
 * - 권한 없는 key(DENIED_CHANGELOG_KEYS) 또는 fixture 미등록 key → 404 ISSUE_NOT_FOUND
 * - page(0-base)/size 쿼리 파라미터로 페이징
 * - 응답: Spring Page<ChangeGroupResponse> (래퍼 없음)
 */
const getChangelogHandler = http.get('/api/v1/issues/:key/changelog', ({ request, params }) => {
  const key = params['key'] as string

  // 권한 없는 key 또는 미존재 key → 404 (단건 조회와 동일 — 존재 probe 방지)
  if (DENIED_CHANGELOG_KEYS.has(key) || !fixtureStore.has(key)) {
    return problemDetail(
      404,
      'issue-not-found',
      'Issue Not Found',
      'ISSUE_NOT_FOUND',
      `이슈를 찾을 수 없습니다: ${key}`,
    )
  }

  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') ?? '0', 10)
  const size = parseInt(url.searchParams.get('size') ?? '20', 10)

  const groups = fixtureStore.get(key) ?? []
  const result = buildPage(groups, page, size)

  return HttpResponse.json(result)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 변경 이력 MSW 핸들러 배열 */
export const changelogHandlers = [getChangelogHandler]
