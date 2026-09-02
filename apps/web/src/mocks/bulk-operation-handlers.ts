// 일괄 작업 MSW 핸들러 — POST 접수(202) + GET 폴링 stateful 비동기 진행 재현
import { http, HttpResponse } from 'msw'
import type { BulkUpdateInput } from '@/api/bulk-operations'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 에러 시나리오 강제 플래그. 'validation' | 'forbidden' | null */
const LS_KEY_BULK_REJECT = '__bts_e2e_bulk_reject'

/** partial-fail 시나리오 강제 플래그. 'true' | null */
const LS_KEY_BULK_PARTIAL_FAIL = '__bts_e2e_bulk_partial_fail'

/**
 * 부분 실패 시나리오가 켜져 있나.
 *
 * 일괄 편집(이 파일)과 상태 이관(`workflow-draft-handlers.ts`)이 **같은 플래그 하나**를 읽는다 —
 * 플래그가 둘로 갈리면 「일괄 작업이 일부 실패한다」는 시나리오도 둘로 갈린다.
 */
export function isPartialFailEnabled(): boolean {
  return globalThis.localStorage?.getItem(LS_KEY_BULK_PARTIAL_FAIL) === 'true'
}

/** issueKeys 최소 개수 */
const ISSUE_KEYS_MIN = 1

/** issueKeys 최대 개수 */
const ISSUE_KEYS_MAX = 1000

/**
 * 최대 폴링 횟수 — 이 횟수에 도달하면 반드시 COMPLETED.
 * (Math.ceil(total * pollCount / 2) 공식으로 2회 폴에 전량 처리)
 */
const MAX_POLLS_TO_COMPLETE = 2

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상태 타입
// ─────────────────────────────────────────────────────────────────────────────

interface BulkOpState {
  operationType: 'BULK_EDIT' | 'BULK_TRANSITION' | 'STATUS_MIGRATION'
  payload: Record<string, unknown>
  issueKeys: string[]
  totalCount: number
  pollCount: number
  failKeys: Set<string>
  /**
   * COMPLETED 도달 시(폴링될 때마다, 멱등) 불리는 콜백. **실패한 이슈 키를 함께 넘긴다.**
   *
   * 이 파일은 어느 도메인의 부수효과(예: 워크플로우 이관 완료 후 잔여 건수를 다시 세는 것)도
   * 알지 못한다 — 그 지식은 등록하는 쪽(`registerStatusMigration` 호출부)이 클로저로 들고 온다.
   * 다만 **무엇이 실패했는지**는 이 파일만 아는 사실이라 인자로 준다. 이것을 안 주면 호출부는
   * 「COMPLETED = 전량 성공」으로 볼 수밖에 없고, 그 순간 목이 서버보다 관대해진다.
   */
  onCompleted?: (failedIssueKeys: string[]) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — 각 테스트 격리를 위해 resetBulkOperationState()로 초기화
// ─────────────────────────────────────────────────────────────────────────────

let bulkOpsStore: Map<string, BulkOpState> = new Map()

/** 테스트 격리용 상태 초기화 */
export function resetBulkOperationState(): void {
  bulkOpsStore = new Map()
}

/**
 * 상태 이관 작업을 이 스토어에 등록한다.
 *
 * `workflow-draft-handlers.ts` 의 `POST /publish/migrate` 가 202 를 돌려주기 **전에** 불러야
 * 한다 — 그러지 않으면 접수 직후 폴링(`GET /api/v1/bulk-operations/:id`)이 이 스토어에서 못
 * 찾아 항상 404 를 준다(이 파일이 봉합하는 갭의 본체).
 *
 * `POST /issues/bulk-update` 와 달리 이관은 실제 이슈 키 목록을 받지 않는다 — 워크플로우
 * 발행 화면은 상태별 잔여 건수만 안다. **자리표시 키는 호출부가 만들어 준다** — 어느 이슈가
 * 어느 출발 상태에서 왔는지는 이 파일이 모르고, 완료 후 상태별 잔여를 다시 세려면 그 귀속이
 * 필요하기 때문이다. `handleGetBulkOperation` 의 진행률 계산(첫 폴 절반 · 둘째 폴 전량)은
 * 그대로 재사용한다.
 *
 * @param id 접수 응답에 실을 작업 id
 * @param params.issueKeys 이관 대상 자리표시 이슈 키. 길이가 곧 `totalCount` 다
 * @param params.mappings 출발 상태 키 → 도착 상태 키
 * @param params.projectKeys 이관 대상 프로젝트 범위. 이관 마법사가 아직 이 값을 모아 보내지
 *   않아 빈 배열을 준다 — `statusMigrationPayloadSchema` 는 빈 배열을 허용한다
 * @param params.failKeys 실패시킬 이슈 키. **비워 두면 이관은 절대 실패하지 않는다** — 그러면
 *   부분 실패(E1) 경로가 어디서도 실행되지 않아, 목이 서버보다 관대해진 것을 아무도 못 본다
 * @param onCompleted COMPLETED 도달 시 불릴 콜백. 실패한 이슈 키를 받는다. 도메인 부수효과는
 *   호출부가 쥔다
 */
export function registerStatusMigration(
  id: string,
  params: {
    issueKeys: string[]
    mappings: Record<string, string>
    projectKeys: string[]
    failKeys: Set<string>
  },
  onCompleted: (failedIssueKeys: string[]) => void,
): void {
  bulkOpsStore.set(id, {
    operationType: 'STATUS_MIGRATION',
    payload: { mappings: params.mappings, projectKeys: params.projectKeys },
    issueKeys: params.issueKeys,
    totalCount: params.issueKeys.length,
    pollCount: 0,
    failKeys: params.failKeys,
    onCompleted,
  })
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

function problemDetail(status: number, errorCode: string, detail: string) {
  return HttpResponse.json({ errorCode, detail }, { status })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/bulk-update
// ─────────────────────────────────────────────────────────────────────────────

const handleBulkUpdate = http.post('/api/v1/issues/bulk-update', async ({ request }) => {
  // 1. localStorage E2E 플래그 분기
  const rejectFlag = globalThis.localStorage?.getItem(LS_KEY_BULK_REJECT)
  if (rejectFlag === 'validation') {
    return problemDetail(400, 'ISSUE_BULK_VALIDATION_FAILED', '선택한 이슈가 없거나 너무 많습니다.')
  }
  if (rejectFlag === 'forbidden') {
    return problemDetail(403, 'ISSUE_BULK_FORBIDDEN', '일괄 작업 권한이 없습니다.')
  }

  const body = await request.json() as BulkUpdateInput

  // 2. issueKeys 길이 유효성 검증
  const { operationType, issueKeys, editPayload, transitionPayload } = body
  if (!issueKeys || issueKeys.length < ISSUE_KEYS_MIN || issueKeys.length > ISSUE_KEYS_MAX) {
    return problemDetail(400, 'ISSUE_BULK_VALIDATION_FAILED', '선택한 이슈가 없거나 너무 많습니다.')
  }

  // 3. payload 구성
  const payload: Record<string, unknown> =
    operationType === 'BULK_EDIT'
      ? { priority: editPayload?.priority ?? null, impact: editPayload?.impact ?? null }
      : {
          toStateKey: transitionPayload?.toStateKey ?? '',
          ...(transitionPayload?.resolutionId !== undefined
            ? { resolutionId: transitionPayload.resolutionId }
            : {}),
        }

  // 4. partial-fail 플래그 처리
  const failKeys = new Set<string>()
  if (isPartialFailEnabled() && issueKeys.length > 0) {
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

  // 처리 진행: 첫 폴 절반, 둘째 폴 전량 → MAX_POLLS_TO_COMPLETE 이내에 COMPLETED 도달
  const processed = Math.min(
    Math.ceil((op.totalCount * op.pollCount) / MAX_POLLS_TO_COMPLETE),
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

  // 멱등 — COMPLETED 도달 뒤에도 다시 폴링될 수 있으니(재발행 재시도 등) 콜백이 여러 번
  // 불려도 안전해야 한다. 등록하는 쪽(registerStatusMigration 호출부)이 그 책임을 진다.
  if (status === 'COMPLETED') {
    op.onCompleted?.(items.filter((item) => item.status === 'FAILED').map((item) => item.issueKey))
  }

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
