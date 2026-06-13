// 이슈 링크 BC MSW 핸들러 — stateful CRUD + 에러 시나리오 토글 (FR-LK-01)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입
// ─────────────────────────────────────────────────────────────────────────────

interface StoredLink {
  id: number
  issueKey: string
  linkType: string
  direction: 'OUTWARD' | 'INWARD'
  label: string
  otherIssue: {
    key: string
    summary: string
    statusKey: string
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈-스코프 stateful 저장소
// ─────────────────────────────────────────────────────────────────────────────

/** 링크 저장소 — issueKey별 링크 목록 (E2E/단위테스트 양쪽에서 브라우저 시드 가능) */
let linkStore: Map<string, StoredLink[]> = new Map()

/** 다음 링크 ID 카운터 (단조 증가, 고유성 보장) */
let nextLinkId = 1

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 관리 exports
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 링크 저장소를 초기화한다 — 각 테스트 afterEach에서 호출.
 * nextLinkId도 1로 리셋한다.
 */
export function resetIssueLinkStore(): void {
  linkStore = new Map()
  nextLinkId = 1
}

/**
 * 특정 이슈의 링크 목록을 시드한다.
 * E2E에서 addInitScript + localStorage 플래그 대신 직접 호출 가능.
 *
 * @param issueKey 이슈 키 (예: "ATLAS-1")
 * @param links 초기 링크 목록
 */
export function seedIssueLinks(issueKey: string, links: Omit<StoredLink, 'issueKey'>[]): void {
  const stored: StoredLink[] = links.map((l) => ({ ...l, issueKey }))
  linkStore.set(issueKey, stored)
  // nextLinkId를 시드 데이터의 최대 id + 1로 갱신
  const maxId = stored.reduce((max, l) => Math.max(max, l.id), 0)
  if (maxId >= nextLinkId) {
    nextLinkId = maxId + 1
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 링크 타입 → 레이블 매핑 (백엔드 IssueLinkType.label 미러)
// ─────────────────────────────────────────────────────────────────────────────

/** linkType(소문자) → 표시 레이블 매핑 */
const LINK_TYPE_LABEL: Record<string, string> = {
  blocks: 'blocks',
  relates: 'is related to',
  duplicates: 'duplicates',
  clones: 'clones',
}

/** linkType(소문자) → 역방향 표시 레이블 매핑 (INWARD 방향 레이블) */
const LINK_TYPE_INWARD_LABEL: Record<string, string> = {
  blocks: 'is blocked by',
  relates: 'is related to',
  duplicates: 'is duplicated by',
  clones: 'is cloned by',
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/issues/:key/links — 링크 목록 조회.
 * 저장소에서 outward(이 이슈가 링크함) / inward(이 이슈가 링크됨)를 분리해 반환.
 * 성공 → 200 { data: { outward, inward } }
 */
const getLinksHandler = http.get('/api/v1/issues/:key/links', ({ params }) => {
  const key = params['key'] as string
  const allLinks = linkStore.get(key) ?? []

  const outward = allLinks
    .filter((l) => l.direction === 'OUTWARD')
    .map(({ id, linkType, direction, label, otherIssue }) => ({
      id,
      linkType: linkType.toUpperCase(),
      direction,
      label,
      otherIssue,
    }))

  const inward = allLinks
    .filter((l) => l.direction === 'INWARD')
    .map(({ id, linkType, direction, label, otherIssue }) => ({
      id,
      linkType: linkType.toUpperCase(),
      direction,
      label,
      otherIssue,
    }))

  return HttpResponse.json({ data: { outward, inward } })
})

/**
 * POST /api/v1/issues/:key/links — 링크 생성.
 * body { targetKey: string, linkType: string(소문자) }
 * 생성된 링크를 저장소에 추가하고 201 + { data: IssueLinkResponse } 반환.
 *
 * 에러 시나리오.
 * - targetKey === key → 422 LINK_SELF_REFERENCE
 * - linkType이 유효하지 않은 값 → 400 INVALID_LINK_TYPE
 */
const createLinkHandler = http.post('/api/v1/issues/:key/links', async ({ params, request }) => {
  const key = params['key'] as string
  const body = (await request.json()) as { targetKey?: string; linkType?: string }
  const targetKey = body.targetKey ?? ''
  const linkTypeLower = (body.linkType ?? '').toLowerCase()

  // 자기 참조 방지
  if (targetKey === key) {
    return HttpResponse.json(
      { errorCode: 'LINK_SELF_REFERENCE', message: '자기 자신에게 링크할 수 없습니다' },
      { status: 422 },
    )
  }

  // 유효한 링크 타입 검증
  if (!(linkTypeLower in LINK_TYPE_LABEL)) {
    return HttpResponse.json(
      { errorCode: 'INVALID_LINK_TYPE', message: `유효하지 않은 링크 타입: ${body.linkType ?? ''}` },
      { status: 400 },
    )
  }

  // 중복 링크 체크 (같은 방향, 같은 대상, 같은 타입)
  const existingLinks = linkStore.get(key) ?? []
  const isDuplicate = existingLinks.some(
    (l) => l.direction === 'OUTWARD' && l.otherIssue.key === targetKey && l.linkType === linkTypeLower,
  )
  if (isDuplicate) {
    return HttpResponse.json(
      { errorCode: 'DUPLICATE_LINK', message: '이미 동일한 링크가 존재합니다' },
      { status: 409 },
    )
  }

  const newId = nextLinkId++
  const label = LINK_TYPE_LABEL[linkTypeLower] ?? linkTypeLower
  const inwardLabel = LINK_TYPE_INWARD_LABEL[linkTypeLower] ?? linkTypeLower

  // OUTWARD 링크를 source 이슈에 추가
  const outwardLink: StoredLink = {
    id: newId,
    issueKey: key,
    linkType: linkTypeLower,
    direction: 'OUTWARD',
    label,
    otherIssue: {
      key: targetKey,
      summary: `${targetKey} 이슈`,
      statusKey: 'open',
    },
  }
  const sourceLinks = linkStore.get(key) ?? []
  linkStore.set(key, [...sourceLinks, outwardLink])

  // INWARD 링크를 대상 이슈에 추가 (대칭 반영)
  const inwardLink: StoredLink = {
    id: newId,
    issueKey: targetKey,
    linkType: linkTypeLower,
    direction: 'INWARD',
    label: inwardLabel,
    otherIssue: {
      key,
      summary: `${key} 이슈`,
      statusKey: 'open',
    },
  }
  const targetLinks = linkStore.get(targetKey) ?? []
  linkStore.set(targetKey, [...targetLinks, inwardLink])

  return HttpResponse.json(
    {
      data: {
        id: outwardLink.id,
        linkType: outwardLink.linkType.toUpperCase(),
        direction: outwardLink.direction,
        label: outwardLink.label,
        otherIssue: outwardLink.otherIssue,
      },
    },
    { status: 201 },
  )
})

/**
 * DELETE /api/v1/issues/:key/links/:linkId — 링크 삭제.
 * 저장소에서 해당 id를 제거하고 204 반환.
 * 미존재 시 404 LINK_NOT_FOUND.
 */
const deleteLinkHandler = http.delete(
  '/api/v1/issues/:key/links/:linkId',
  ({ params }) => {
    const key = params['key'] as string
    const linkId = parseInt(params['linkId'] as string, 10)

    const links = linkStore.get(key) ?? []
    const idx = links.findIndex((l) => l.id === linkId)
    if (idx === -1) {
      return HttpResponse.json(
        { errorCode: 'LINK_NOT_FOUND', message: `링크를 찾을 수 없습니다: ${linkId}` },
        { status: 404 },
      )
    }

    // OUTWARD/INWARD 양쪽에서 동일 id를 제거 (대칭 삭제)
    const removedLink = links[idx]
    const updatedSourceLinks = links.filter((l) => l.id !== linkId)
    linkStore.set(key, updatedSourceLinks)

    if (removedLink !== undefined) {
      const targetKey = removedLink.otherIssue.key
      const targetLinks = linkStore.get(targetKey) ?? []
      linkStore.set(targetKey, targetLinks.filter((l) => l.id !== linkId))
    }

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 링크 BC MSW 핸들러 배열 */
export const issueLinkHandlers = [
  getLinksHandler,
  createLinkHandler,
  deleteLinkHandler,
]
