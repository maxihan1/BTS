// 워크로그 BC MSW 핸들러 — stateful in-memory store (FR-TT-01 D6/D7)
// 이슈별 워크로그 목록 + 원 추정/잔여 추정 Map으로 상태를 관리한다.
// PATCH/DELETE 시 remaining은 불변(자동차감 없음) — 백엔드 시맨틱 정확 재현.
import { http, HttpResponse } from 'msw'
import type { WorklogResponse } from '@/api/worklogs'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (attachment-handlers 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/** RFC4122 v4 UUID를 생성한다. */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 토큰 → 사용자 도출 헬퍼 (issue-watcher-handlers 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/** Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다. */
function resolveUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  // username → userId 매핑 (auth-fixtures AUTH_USERS 정본과 일치, RFC4122 v4 형식)
  const USER_ID_MAP: Readonly<Record<string, string>> = {
    alice: '00000000-0000-4000-8000-000000000001',
    bob: '00000000-0000-4000-8000-000000000002',
  }
  return USER_ID_MAP[username] ?? null
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — 이슈별 워크로그 목록 + 추정 Map
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈별 추정값 인터페이스 */
interface IssueEstimate {
  originalEstimateSeconds: number | null
  remainingEstimateSeconds: number | null
}

/** 이슈 키 → 워크로그 목록 Map */
let worklogStore: Map<string, WorklogResponse[]> = new Map()

/** 이슈 키 → 추정값 Map (issue-handlers PATCH /issues/:key 와 동기화) */
let estimateStore: Map<string, IssueEstimate> = new Map()

/**
 * worklog store와 estimate store를 초기화한다 (테스트 격리용).
 * 각 테스트의 beforeEach에서 호출해 이전 테스트 상태가 남지 않도록 한다.
 */
export function resetWorklogStore(): void {
  worklogStore = new Map()
  estimateStore = new Map()
}

/**
 * 이슈 추정값을 store에 설정한다.
 * issue-handlers의 PATCH /issues/:key 핸들러에서 추정 필드 변경 시 호출한다.
 * 3-state 의미론: undefined = 유지, null = 클리어, number = 설정.
 *
 * @param issueKey 이슈 키 (예: "ATLAS-1")
 * @param originalEstimateSeconds 원 추정 초 (undefined이면 기존값 유지)
 * @param remainingEstimateSeconds 잔여 추정 초 (undefined이면 기존값 유지)
 */
export function setIssueEstimate(
  issueKey: string,
  originalEstimateSeconds: number | null | undefined,
  remainingEstimateSeconds: number | null | undefined,
): void {
  const current = estimateStore.get(issueKey) ?? {
    originalEstimateSeconds: null,
    remainingEstimateSeconds: null,
  }
  estimateStore.set(issueKey, {
    originalEstimateSeconds:
      originalEstimateSeconds !== undefined
        ? originalEstimateSeconds
        : current.originalEstimateSeconds,
    remainingEstimateSeconds:
      remainingEstimateSeconds !== undefined
        ? remainingEstimateSeconds
        : current.remainingEstimateSeconds,
  })
}

/**
 * 이슈 추정값을 조회한다 (issue-handlers GET /issues/:key 응답 보강용).
 *
 * @param issueKey 이슈 키
 * @returns 추정값 (미설정이면 null 반환)
 */
export function getIssueEstimate(issueKey: string): IssueEstimate {
  return estimateStore.get(issueKey) ?? {
    originalEstimateSeconds: null,
    remainingEstimateSeconds: null,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈의 활성 워크로그 목록에서 timeSpentSeconds 합계를 계산한다.
 * 소프트삭제된 항목은 목록에서 제거하므로 현재 목록 SUM이 timeSpent 집계값이다.
 */
function sumTimeSpent(issueKey: string): number {
  const list = worklogStore.get(issueKey) ?? []
  return list.reduce((acc, w) => acc + w.timeSpentSeconds, 0)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/worklogs — 워크로그 목록 + 집계 요약 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 목록 조회 핸들러.
 * 성공 → 200 { data: { worklogs: [...startedAt desc], summary: { ... } } }
 * summary.timeSpentSeconds = 활성 워크로그 SUM (소프트삭제 제외).
 * originalEstimateSeconds / remainingEstimateSeconds는 estimateStore에서 읽는다.
 */
const listWorklogsHandler = http.get('/api/v1/issues/:key/worklogs', ({ params }) => {
  const issueKey = params['key'] as string

  const list = worklogStore.get(issueKey) ?? []
  // startedAt 내림차순 정렬 (백엔드 default 정렬 재현)
  const sorted = [...list].sort(
    (a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime(),
  )

  const estimate = getIssueEstimate(issueKey)
  const timeSpentSeconds = sumTimeSpent(issueKey)

  return HttpResponse.json({
    data: {
      worklogs: sorted,
      summary: {
        originalEstimateSeconds: estimate.originalEstimateSeconds,
        timeSpentSeconds,
        remainingEstimateSeconds: estimate.remainingEstimateSeconds,
      },
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/:key/worklogs — 워크로그 추가
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 추가 핸들러.
 * 성공 → 201 { data: WorklogResponse }
 *
 * 자동차감 로직 (백엔드 시맨틱 정확 재현).
 * - newRemainingEstimateSeconds 미전송 → remaining = max(0, remaining − timeSpentSeconds(이번 추가분))
 *   remaining이 null이면 null 유지 (자동차감 안 함).
 * - newRemainingEstimateSeconds 전송 → 그 값으로 override.
 *
 * timeSpent(이슈 집계값)는 추가 후 SUM 재집계.
 * authorId는 Authorization 토큰에서 도출한 현재 사용자 userId.
 */
const addWorklogHandler = http.post('/api/v1/issues/:key/worklogs', async ({ params, request }) => {
  const issueKey = params['key'] as string

  const body = await request.clone().json() as {
    timeSpentSeconds: number
    startedAt: string
    comment?: string | null
    newRemainingEstimateSeconds?: number | null
  }

  // 현재 사용자 도출 (인증 토큰 기반)
  const authorId = resolveUserIdFromRequest(request) ?? '00000000-0000-4000-8000-000000000001'

  const now = new Date().toISOString()
  const newWorklog: WorklogResponse = {
    id: generateUuidV4(),
    issueKey,
    authorId,
    timeSpentSeconds: body.timeSpentSeconds,
    startedAt: body.startedAt,
    comment: body.comment ?? null,
    createdAt: now,
    updatedAt: now,
  }

  const existing = worklogStore.get(issueKey) ?? []
  worklogStore.set(issueKey, [...existing, newWorklog])

  // 자동차감: newRemainingEstimateSeconds 미전송 시 remaining - 이번 추가분
  const estimate = getIssueEstimate(issueKey)
  let newRemaining: number | null = estimate.remainingEstimateSeconds
  if (body.newRemainingEstimateSeconds !== undefined) {
    // 명시 지정
    newRemaining = body.newRemainingEstimateSeconds
  } else if (newRemaining !== null) {
    // 자동차감
    newRemaining = Math.max(0, newRemaining - body.timeSpentSeconds)
  }
  // remaining이 null이었으면 null 유지 (else 없음 — newRemaining이 이미 null)
  estimateStore.set(issueKey, {
    originalEstimateSeconds: estimate.originalEstimateSeconds,
    remainingEstimateSeconds: newRemaining,
  })

  return HttpResponse.json({ data: newWorklog }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/issues/:key/worklogs/:worklogId — 워크로그 수정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 수정 핸들러.
 * 성공 → 200 { data: WorklogResponse }
 *
 * ★ remaining 불변 (자동차감 없음) — 백엔드 PATCH 시맨틱 정확 재현.
 * timeSpent는 수정 후 SUM 재집계.
 * 본인(authorId === 현재 userId)만 허용, 타인이면 403.
 */
const updateWorklogHandler = http.patch(
  '/api/v1/issues/:key/worklogs/:worklogId',
  async ({ params, request }) => {
    const issueKey = params['key'] as string
    const worklogId = params['worklogId'] as string

    const currentUserId = resolveUserIdFromRequest(request)
    const list = worklogStore.get(issueKey) ?? []
    const idx = list.findIndex((w) => w.id === worklogId)

    if (idx === -1) {
      return HttpResponse.json(
        { errorCode: 'WORKLOG_NOT_FOUND', detail: '워크로그를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    const target = list[idx]!
    // 본인 여부 검증 — 타인이면 403
    if (currentUserId !== null && target.authorId !== currentUserId) {
      return HttpResponse.json(
        { errorCode: 'WORKLOG_FORBIDDEN', detail: '본인이 작성한 워크로그만 수정할 수 있습니다.' },
        { status: 403 },
      )
    }

    const body = await request.clone().json() as {
      timeSpentSeconds?: number
      startedAt?: string
      comment?: string | null
    }

    const updated: WorklogResponse = {
      ...target,
      timeSpentSeconds: body.timeSpentSeconds ?? target.timeSpentSeconds,
      startedAt: body.startedAt ?? target.startedAt,
      // comment: null = 무변경(백엔드 계약), undefined = 무변경
      comment: body.comment === undefined ? target.comment : (body.comment ?? target.comment),
      updatedAt: new Date().toISOString(),
    }

    const newList = [...list.slice(0, idx), updated, ...list.slice(idx + 1)]
    worklogStore.set(issueKey, newList)

    // ★ remaining 불변 — PATCH는 remaining 재계산 없음 (백엔드 시맨틱)
    // timeSpent는 SUM 재집계 (응답 summary에 반영됨)

    return HttpResponse.json({ data: updated })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/issues/:key/worklogs/:worklogId — 워크로그 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 삭제 핸들러 (소프트삭제 시뮬 — 목록에서 제거).
 * 성공 → 204 No Content
 *
 * ★ remaining 불변 (미복원) — 백엔드 DELETE 시맨틱 정확 재현.
 * timeSpent는 삭제 후 SUM 재집계.
 * 본인(authorId === 현재 userId)만 허용, 타인이면 403.
 */
const deleteWorklogHandler = http.delete(
  '/api/v1/issues/:key/worklogs/:worklogId',
  ({ params, request }) => {
    const issueKey = params['key'] as string
    const worklogId = params['worklogId'] as string

    const currentUserId = resolveUserIdFromRequest(request)
    const list = worklogStore.get(issueKey) ?? []
    const idx = list.findIndex((w) => w.id === worklogId)

    if (idx === -1) {
      return HttpResponse.json(
        { errorCode: 'WORKLOG_NOT_FOUND', detail: '워크로그를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    const target = list[idx]!
    // 본인 여부 검증 — 타인이면 403
    if (currentUserId !== null && target.authorId !== currentUserId) {
      return HttpResponse.json(
        { errorCode: 'WORKLOG_FORBIDDEN', detail: '본인이 작성한 워크로그만 삭제할 수 있습니다.' },
        { status: 403 },
      )
    }

    const newList = [...list.slice(0, idx), ...list.slice(idx + 1)]
    worklogStore.set(issueKey, newList)

    // ★ remaining 불변 (미복원) — DELETE는 remaining 재계산 없음 (백엔드 시맨틱)
    // timeSpent는 SUM 재집계 (다음 GET 시 반영)

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 BC MSW 핸들러 배열 */
export const worklogHandlers = [
  listWorklogsHandler,
  addWorklogHandler,
  updateWorklogHandler,
  deleteWorklogHandler,
]
