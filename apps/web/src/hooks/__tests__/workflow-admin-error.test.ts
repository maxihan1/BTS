// 워크플로우 관리 errorCode 매핑 판별식 — 백엔드 핸들러 .kt 를 직접 읽어 대조
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { toast } from 'sonner'
import { WorkflowAdminApiError } from '@/api/workflows-admin'
import {
  WORKFLOW_ADMIN_ERROR_MESSAGES,
  DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE,
  mapWorkflowAdminError,
  notifyWorkflowAdminError,
} from '../workflow-admin-error'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드에서 코드를 뽑는다 — 손으로 적지 않는다
//
// ★ 종전 이 파일은 프론트에 손으로 적은 `BACKEND_ERROR_CODES` 배열과 매핑을 비교했다.
//   양쪽 다 프론트라 백엔드를 한 번도 안 봤고, 실제로 나오는
//   `WORKFLOW_DEFINITION_ACCESS_DENIED` 가 빠진 채로 「차집합 0」이 초록이었다
//   (MEMORY `two-lists-never-check-each-other`). 이제 핸들러 원본을 읽는다.
// ─────────────────────────────────────────────────────────────────────────────

/** vitest 는 `apps/web` 에서 돈다 — 거기서 두 단계 위가 저장소 루트다 */
const REPO_ROOT = resolve(process.cwd(), '../..')
const PW = `${REPO_ROOT}/backend/modules/project-workflow/src/main/kotlin/com/bts/workflow`

/** 이 화면의 쓰기·조회가 실제로 지나는 핸들러 전량 */
const HANDLER_FILES = [
  `${PW}/web/WorkflowExceptionHandler.kt`,
  `${PW}/web/WorkflowStatusCompositionExceptionHandler.kt`,
  `${PW}/web/TransitionConflictExceptionHandler.kt`,
  `${PW}/status/web/StatusExceptionHandler.kt`,
]

/** `code = ex.errorCode` 로 새는 코드의 정의처 */
const SHARED_EXCEPTION_FILE = `${REPO_ROOT}/backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowDefinitionAccessDeniedException.kt`

/**
 * 핸들러가 내는 코드를 뽑는다.
 *
 * **세 형태를 다 본다.** 하나라도 빠뜨리면 그 핸들러의 코드가 통째로 안 보이고, 차집합은
 * 못 본 쪽을 「없는 것」으로 처리해 조용히 통과한다.
 *
 * 1. `code = "리터럴"` — `WorkflowExceptionHandler` 등 명시 인자 형태
 * 2. 위치 인자 헬퍼 `error(HttpStatus.CONFLICT, "STATUS_KEY_CONFLICT", "...")` —
 *    `StatusExceptionHandler` 형태. 실측으로 발견했다(처음엔 1번만 봐서 STATUS_* 5건이
 *    「매핑에만 있는 죽은 항목」으로 잘못 잡혔다)
 * 3. `code = ex.errorCode` — 예외가 상수로 들고 있어 정의 파일에서 따로 읽는다
 *
 * `log.info("STATUS_409_KEY …")` 같은 **로그 태그**는 형태가 코드와 구별되지 않으므로
 * `log.` 로 시작하는 줄을 통째로 뺀다.
 */
function extractBackendCodes(): Set<string> {
  const codes = new Set<string>()
  for (const file of HANDLER_FILES) {
    const body = readFileSync(file, 'utf8')
    const scannable = body
      .split('\n')
      .filter((line) => !/^\s*log\./.test(line))
      .join('\n')
    for (const m of scannable.matchAll(/"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+){1,})"/g)) {
      codes.add(m[1]!)
    }
    if (/code\s*=\s*ex\.errorCode/.test(body)) {
      const shared = readFileSync(SHARED_EXCEPTION_FILE, 'utf8')
      for (const m of shared.matchAll(/const val [A-Z_]+: String = "([A-Z_]+)"/g)) {
        codes.add(m[1]!)
      }
    }
  }
  return codes
}

describe('백엔드 핸들러 원본 대조 — 차집합 0', () => {
  it('비-공허: 읽으려는 핸들러 파일이 전부 실재한다', () => {
    // 경로가 틀리면 아래 대조가 빈 집합끼리 비교해 조용히 통과한다.
    const missing = [...HANDLER_FILES, SHARED_EXCEPTION_FILE].filter((f) => !existsSync(f))
    expect(missing).toEqual([])
  })

  it('비-공허: 뽑은 코드가 10건 이상이다', () => {
    expect(extractBackendCodes().size).toBeGreaterThanOrEqual(10)
  })

  it('비-공허: `ex.errorCode` 경로가 실제로 코드를 하나 이상 실어 온다', () => {
    // 리터럴만 훑던 종전 방식이 놓친 자리다. 이 단언이 그 회귀를 막는다.
    expect(extractBackendCodes()).toContain('WORKFLOW_DEFINITION_ACCESS_DENIED')
  })

  it('백엔드가 내는 코드 전부에 한국어 메시지가 있다', () => {
    const missing = [...extractBackendCodes()].filter((c) => WORKFLOW_ADMIN_ERROR_MESSAGES[c] === undefined)
    expect(missing).toEqual([])
  })

  it('매핑에만 있고 백엔드엔 없는 죽은 항목이 없다', () => {
    const backend = extractBackendCodes()
    const stale = Object.keys(WORKFLOW_ADMIN_ERROR_MESSAGES).filter((c) => !backend.has(c))
    expect(stale).toEqual([])
  })

  it('모든 메시지가 서로 다르다 — 복사 실수로 같은 문구가 두 코드를 덮지 않는다', () => {
    const values = Object.values(WORKFLOW_ADMIN_ERROR_MESSAGES)
    expect(new Set(values).size).toBe(values.length)
  })
})

describe('mapWorkflowAdminError', () => {
  it('알 수 없는 코드는 기본 메시지로 떨어진다', () => {
    expect(mapWorkflowAdminError('NOPE')).toBe(DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE)
  })

  it('상태 제거가 전환에 막힌 경우를 전환 때문이라고 말한다', () => {
    expect(mapWorkflowAdminError('WORKFLOW_STATUS_REFERENCED_BY_TRANSITION')).toContain('전환')
  })

  it('이슈가 쓰는 상태 제거는 이슈 때문이라고 말한다 — 위 전환 사유와 구별된다', () => {
    expect(mapWorkflowAdminError('WORKFLOW_STATUS_IN_USE')).toContain('이슈')
    expect(mapWorkflowAdminError('WORKFLOW_STATUS_IN_USE')).not.toBe(
      mapWorkflowAdminError('WORKFLOW_STATUS_REFERENCED_BY_TRANSITION'),
    )
  })

  it('권한 거부는 편집 권한이 없다고 말한다 (resolver 경로 — 이 화면의 모든 쓰기가 지난다)', () => {
    expect(mapWorkflowAdminError('WORKFLOW_DEFINITION_ACCESS_DENIED')).toContain('권한')
  })

  it('막은 대상 이름이 실린 서버 문구를 버리지 않는다', () => {
    // 백엔드가 어느 전환이 막았는지를 message 에 일부러 싣는다. 고정 문구만 띄우면 두 번 버린다.
    const shown = mapWorkflowAdminError('WORKFLOW_STATUS_REFERENCED_BY_TRANSITION', '전환 「검토 요청」이 사용 중')
    expect(shown).toContain('검토 요청')
  })

  it('편성 실패는 사유 넷이 한 코드를 공유하므로 단정하지 않는다', () => {
    // 「마지막 상태는 뺄 수 없습니다」로 단정하면 나머지 셋에서 거짓 안내가 된다.
    expect(mapWorkflowAdminError('WORKFLOW_STATUS_COMPOSITION_INVALID')).not.toContain('마지막')
  })

  it('덧붙임 대상이 아닌 코드에는 서버 문구를 안 붙인다', () => {
    expect(mapWorkflowAdminError('WORKFLOW_NOT_FOUND', '내부 상세')).not.toContain('내부 상세')
  })
})

describe('notifyWorkflowAdminError', () => {
  beforeEach(() => vi.clearAllMocks())

  it('WorkflowAdminApiError 면 매핑된 메시지로 toast 한다', () => {
    notifyWorkflowAdminError(new WorkflowAdminApiError(409, 'WORKFLOW_IN_USE', ''))
    expect(toast.error).toHaveBeenCalledWith(WORKFLOW_ADMIN_ERROR_MESSAGES['WORKFLOW_IN_USE'])
  })

  it('그 타입이 아니면 기본 메시지로 toast 한다', () => {
    notifyWorkflowAdminError(new Error('boom'))
    expect(toast.error).toHaveBeenCalledWith(DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE)
  })
})
