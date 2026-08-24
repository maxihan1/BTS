// 워크플로우 관리 errorCode → 한국어 매핑 판별식 — 백엔드 코드 전수와 대조
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { toast } from 'sonner'
import { WorkflowAdminApiError } from '@/api/workflows-admin'
import {
  WORKFLOW_ADMIN_ERROR_MESSAGES,
  DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE,
  mapWorkflowAdminError,
  notifyWorkflowAdminError,
} from '../workflow-admin-error'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

/**
 * 백엔드가 워크플로우 **관리 경로**에서 던지는 errorCode 전수.
 *
 * 손으로 적은 목록이라 백엔드가 코드를 늘리면 여기가 먼저 썩는다. 그래서 이 배열이
 * 매핑 테이블과 **차집합 0** 인지를 아래에서 양방향으로 본다 — 한쪽만 보면 「매핑에는
 * 있는데 백엔드엔 없는」 죽은 항목이 조용히 남는다.
 */
const BACKEND_ERROR_CODES = [
  'WORKFLOW_NOT_FOUND',
  'WORKFLOW_KEY_CONFLICT',
  'WORKFLOW_IN_USE',
  'WORKFLOW_LOCKED',
  'WORKFLOW_INVALID_REQUEST',
  'WORKFLOW_PERMISSION_DENIED',
  'WORKFLOW_STATUS_COMPOSITION_INVALID',
  'WORKFLOW_STATUS_IN_USE',
  'WORKFLOW_STATUS_REFERENCED_BY_TRANSITION',
  'WORKFLOW_TRANSITION_CONFLICT',
  'STATUS_KEY_CONFLICT',
  'STATUS_NAME_CONFLICT',
  'STATUS_NOT_FOUND',
  'STATUS_IN_USE',
  'STATUS_PROTECTED',
] as const

describe('WORKFLOW_ADMIN_ERROR_MESSAGES — 백엔드 코드와 차집합 0', () => {
  it('비-공허: 검사 대상 코드가 0건이 아니다', () => {
    expect(BACKEND_ERROR_CODES.length).toBeGreaterThan(10)
  })

  it('백엔드 코드 전부에 한국어 메시지가 있다', () => {
    const missing = BACKEND_ERROR_CODES.filter((c) => WORKFLOW_ADMIN_ERROR_MESSAGES[c] === undefined)
    expect(missing).toEqual([])
  })

  it('매핑에만 있고 백엔드엔 없는 죽은 항목이 없다', () => {
    const stale = Object.keys(WORKFLOW_ADMIN_ERROR_MESSAGES).filter(
      (c) => !(BACKEND_ERROR_CODES as readonly string[]).includes(c),
    )
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
