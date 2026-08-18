// 일괄 작업 상태/실패사유 한국어 라벨 맵 단위 테스트
import { describe, it, expect } from 'vitest'
import { failureReasonCodeSchema, bulkOperationStatusSchema } from '@/api/bulk-operations'
import { failureReasonLabels, statusLabels, getFailureReasonLabel } from './bulk-operation-labels'

// ─────────────────────────────────────────────────────────────────────────────
// failureReasonLabels — 전 enum 키 커버리지
// ─────────────────────────────────────────────────────────────────────────────

describe('failureReasonLabels', () => {
  const allCodes = failureReasonCodeSchema.options

  it('BULK-LABEL-1: 모든 FailureReasonCode 키가 누락 없이 매핑된다', () => {
    for (const code of allCodes) {
      expect(
        Object.prototype.hasOwnProperty.call(failureReasonLabels, code),
        `${code} 키가 failureReasonLabels에 없다`,
      ).toBe(true)
    }
  })

  it('BULK-LABEL-2: 모든 라벨이 비어있지 않은 한국어 문자열이다', () => {
    for (const code of allCodes) {
      const label = failureReasonLabels[code]
      expect(typeof label, `${code} 라벨이 string이 아니다`).toBe('string')
      expect((label as string).length, `${code} 라벨이 비어있다`).toBeGreaterThan(0)
    }
  })

  it('BULK-LABEL-3: NOT_FOUND는 "이슈를 찾을 수 없음" 이다', () => {
    expect(failureReasonLabels['NOT_FOUND']).toBe('이슈를 찾을 수 없음')
  })

  it('BULK-LABEL-4: FORBIDDEN는 "권한 없음" 이다', () => {
    expect(failureReasonLabels['FORBIDDEN']).toBe('권한 없음')
  })

  it('BULK-LABEL-5: TRANSITION_NOT_ALLOWED는 "허용되지 않는 전환" 이다', () => {
    expect(failureReasonLabels['TRANSITION_NOT_ALLOWED']).toBe('허용되지 않는 전환')
  })

  it('BULK-LABEL-6: VERSION_CONFLICT는 "다른 요청이 먼저 수정함" 이다', () => {
    expect(failureReasonLabels['VERSION_CONFLICT']).toBe('다른 요청이 먼저 수정함')
  })

  it('BULK-LABEL-7: WORKFLOW_NOT_CONFIGURED는 "워크플로우 미구성" 이다', () => {
    expect(failureReasonLabels['WORKFLOW_NOT_CONFIGURED']).toBe('워크플로우 미구성')
  })

  it('BULK-LABEL-8: TYPE_NOT_FOUND는 "이슈 유형 없음" 이다', () => {
    expect(failureReasonLabels['TYPE_NOT_FOUND']).toBe('이슈 유형 없음')
  })

  it('BULK-LABEL-9: UNKNOWN는 "알 수 없는 오류" 이다', () => {
    expect(failureReasonLabels['UNKNOWN']).toBe('알 수 없는 오류')
  })

  it('BULK-LABEL-10: getFailureReasonLabel은 미지정 코드에 대해 fallback 문자열을 반환한다', () => {
    const result = getFailureReasonLabel('NON_EXISTENT_CODE')
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// statusLabels — 전 enum 키 커버리지
// ─────────────────────────────────────────────────────────────────────────────

describe('statusLabels', () => {
  const allStatuses = bulkOperationStatusSchema.options

  it('BULK-LABEL-11: 모든 status 키가 누락 없이 매핑된다', () => {
    for (const status of allStatuses) {
      expect(
        Object.prototype.hasOwnProperty.call(statusLabels, status),
        `${status} 키가 statusLabels에 없다`,
      ).toBe(true)
    }
  })

  it('BULK-LABEL-12: PENDING은 "대기 중" 이다', () => {
    expect(statusLabels['PENDING']).toBe('대기 중')
  })

  it('BULK-LABEL-13: RUNNING은 "처리 중" 이다', () => {
    expect(statusLabels['RUNNING']).toBe('처리 중')
  })

  it('BULK-LABEL-14: COMPLETED는 "완료" 이다', () => {
    expect(statusLabels['COMPLETED']).toBe('완료')
  })

  it('BULK-LABEL-15: FAILED는 "실패" 이다', () => {
    expect(statusLabels['FAILED']).toBe('실패')
  })
})
