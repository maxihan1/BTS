// 일괄 작업 MSW 핸들러 — POST 접수(202) + GET 폴링 stateful 비동기 진행 재현
import { http, HttpResponse } from 'msw'
import type { BulkUpdateInput } from '@/api/bulk-operations'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

interface BulkOpState {
  operationType: 'BULK_EDIT' | 'BULK_TRANSITION'
  payload: Record<string, unknown>
  issueKeys: string[]
  totalCount: number
  pollCount: number
  failKeys: Set<string>
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — 각 테스트 격리를 위해 resetBulkOperationState()로 초기화
// ─────────────────────────────────────────────────────────────────────────────

let bulkOpsStore: Map<string, BulkOpState> = new Map()

/** 테스트 격리용 상태 초기화 */
export function resetBulkOperationState(): void {
  bulkOpsStore = new Map()
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * 3번째 그룹 첫 글자 '4', 4번째 그룹 첫 글자 '8'|'9'|'a'|'b' 보증.
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  // 폴백: Math.random 기반 v4 UUID
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function problemDetail(
  status: number,
  errorCode: string,
  detail: string,
): HttpResponse {
  return HttpResponse.json({ errorCode, detail }, { status })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/bulk-update
// ─────────────────────────────────────────────────────────────────────────────

const handleBulkUpdate = http.post('/api/v1/issues/bulk-update', async ({ request }) => {
  // 1. localStorage E2E 플래그 분기
  const rejectFlag = globalThis.localStorage?.getItem('__bts_e2e_bulk_reject')
  if (rejectFlag === 'validation') {
    return problemDetail(400, 'ISSUE_BULK_VALIDATION_FAILED', '선택한 이슈가 없거나 너무 많습니다.')
  }
  if (rejectFlag === 'forbidden') {
    return problemDetail(403, 'ISSUE_BULK_FORBIDDEN', '일괄 작업 권한이 없습니다.')
  }

  const body = await request.json() as BulkUpdateInput

  // 2. issueKeys 길이 유효성 검증
  const { operationType, issueKeys, editPayload, transitionPayload } = body
  if (!issueKeys || issueKeys.length < 1 || issueKeys.length > 1000) {
    return problemDetail(400, 'ISSUE_BULK_VALIDATION_FAILED', '선택한 이슈가 없거나 너무 많습니다.')
  }

  // 3. payload 구성
  const payload: Record<string, unknown> =
    operationType === 'BULK_EDIT'
      ? { priority: editPayload?.priority ?? null, impact: editPayload?.impact ?? null }
      : { toStateKey: transitionPayload?.toStateKey ?? '' }

  // 4. partial-fail 플래그 처리
  const failKeys = new Set<string>()
  const partialFail = globalThis.localStorage?.getItem('__bts_e2e_bulk_partial_fail')
  if (partialFail === 'true' && issueKeys.length > 0) {
    failKeys.add(issueKeys[issueKeys.length - 1]!)
  }

  // 5. op 등록 + 202 반환
  const id = generateUuidV4()
  bulkOpsStore.set(id, {
    operationType,
    payload,
    issueKeys,
    totalCount: issueKeys.length,
    pollCount: 0,
    failKeys,
  })

  return HttpResponse.json(
    {
      data: {
        bulkOperationId: id,
        status: 'PENDING',
        totalCount: issueKeys.length,
      },
    },
    { status: 202 },
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/bulk-operations/:id
// ─────────────────────────────────────────────────────────────────────────────

const handleGetBulkOperation = http.get('/api/v1/bulk-operations/:id', ({ params }) => {
  const { id } = params as { id: string }
  const op = bulkOpsStore.get(id)
  if (!op) {
    return problemDetail(404, 'ISSUE_BULK_NOT_FOUND', '일괄 작업을 찾을 수 없습니다.')
  }

  // pollCount 증가
  op.pollCount += 1

  // 처리 진행: 첫 폴 절반, 둘째 폴 전량 → 최대 2폴로 COMPLETED 도달
  const processed = Math.min(
    Math.ceil((op.totalCount * op.pollCount) / 2),
    op.totalCount,
  )

  // items 구성 — 처리된 앞 processed개, 미처리분 PENDING
  const items = op.issueKeys.map((issueKey, idx) => {
    if (idx >= processed) {
      return { issueKey, status: 'PENDING', failureReasonCode: null }
    }
    if (op.failKeys.has(issueKey)) {
      return { issueKey, status: 'FAILED', failureReasonCode: 'VERSION_CONFLICT' }
    }
    return { issueKey, status: 'SUCCEEDED', failureReasonCode: null }
  })

  const succeededCount = items.filter(i => i.status === 'SUCCEEDED').length
  const failedCount = items.filter(i => i.status === 'FAILED').length
  const status = processed >= op.totalCount ? 'COMPLETED' : 'RUNNING'

  return HttpResponse.json({
    data: {
      id,
      operationType: op.operationType,
      status,
      payload: op.payload,
      totalCount: op.totalCount,
      processedCount: processed,
      succeededCount,
      failedCount,
      items,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 배열 export
// ─────────────────────────────────────────────────────────────────────────────

/** 일괄 작업 BC MSW 핸들러 집합 */
export const bulkOperationHandlers = [handleBulkUpdate, handleGetBulkOperation]
