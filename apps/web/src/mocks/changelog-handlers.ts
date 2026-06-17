// 이슈 변경 이력 MSW 핸들러 — GET /api/v1/issues/:key/changelog 페이징 + 404 (FR-HS-02)
// FR-MV-02: 이동 후 newKey changelog에 key 변경 항목 동적 삽입 (stateful — movedIssueStore 참조)
import { http, HttpResponse } from 'msw'
import {
  DENIED_CHANGELOG_KEYS,
  atlasOneChangelogFixture,
  paginationChangelogFixture,
  buildChangeGroup,
  buildChangeItem,
  type ChangeGroupFixture,
} from './changelog-fixtures'
import { movedIssueStore } from './issue-move-handlers'

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
  // ATLAS-2 — "더 보기" 페이징 E2E 검증용 21그룹(런타임 시드 없이 사전 등록)
  ['ATLAS-2', paginationChangelogFixture],
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
 * 이동된 이슈(movedIssueStore)의 newKey로 changelog를 조회할 때
 * `key` 변경 항목 그룹을 맨 앞에 동적으로 삽입한다.
 *
 * 이동 전에는 key 항목이 없고, 이동 후(newKey로 조회 시)에만 등장한다.
 * → stateful: movedIssueStore에 기록된 시점 이후 요청에만 적용 (FR-MV-02 명세).
 *
 * @param key 조회 대상 이슈 키
 * @param baseGroups fixtureStore에서 읽은 기본 그룹 목록
 * @returns key 변경 그룹이 앞에 추가된 그룹 목록
 */
function injectMoveChangeGroup(key: string, baseGroups: ChangeGroupFixture[]): ChangeGroupFixture[] {
  // movedIssueStore에서 이 key가 newKey인 이동 기록을 찾는다
  const moveEntry = Array.from(movedIssueStore.values()).find((e) => e.newKey === key)
  if (moveEntry === undefined) {
    return baseGroups
  }

  // key 변경 그룹 (이동 이벤트 — actorName=null(시스템), fromValue=옛키, toValue=새키)
  const moveGroup: ChangeGroupFixture = buildChangeGroup({
    actorId: null,
    actorName: null,
    createdAt: new Date().toISOString(),
    items: [
      buildChangeItem('key', moveEntry.oldKey, moveEntry.newKey),
    ],
  })

  return [moveGroup, ...baseGroups]
}

/**
 * 이슈 변경 이력 페이징 조회.
 *
 * - 권한 없는 key(DENIED_CHANGELOG_KEYS) → 404 ISSUE_NOT_FOUND
 * - fixture 미등록 key이지만 movedIssueStore의 newKey인 경우 → 빈 fixture에 이동 그룹 삽입
 * - page(0-base)/size 쿼리 파라미터로 페이징
 * - 응답: Spring Page<ChangeGroupResponse> (래퍼 없음)
 */
const getChangelogHandler = http.get('/api/v1/issues/:key/changelog', ({ request, params }) => {
  const key = params['key'] as string

  // 권한 없는 key → 404 (존재 probe 방지)
  if (DENIED_CHANGELOG_KEYS.has(key)) {
    return problemDetail(
      404,
      'issue-not-found',
      'Issue Not Found',
      'ISSUE_NOT_FOUND',
      `이슈를 찾을 수 없습니다: ${key}`,
    )
  }

  // fixture 미등록 key이지만 movedIssueStore의 newKey인 경우 → 빈 기반 + 이동 그룹
  // fixture 미등록이고 이동 기록도 없는 경우 → 404
  const isMovedNewKey = Array.from(movedIssueStore.values()).some((e) => e.newKey === key)
  if (!fixtureStore.has(key) && !isMovedNewKey) {
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

  const baseGroups = fixtureStore.get(key) ?? []
  // FR-MV-02: 이동된 이슈의 newKey 조회 시 key 변경 그룹을 맨 앞에 삽입
  const groups = injectMoveChangeGroup(key, baseGroups)
  const result = buildPage(groups, page, size)

  return HttpResponse.json(result)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 변경 이력 MSW 핸들러 배열 */
export const changelogHandlers = [getChangelogHandler]
