// 일괄 작업 상태 및 실패사유 한국어 라벨 맵 — E2E 셀렉터 정본
import { type z } from 'zod'
import { type BulkOperationResponse, failureReasonCodeSchema } from '@/api/bulk-operations'

/** 일괄 작업 개별 이슈 실패사유 코드 타입 — failureReasonCodeSchema에서 추론 */
type FailureReasonCode = z.infer<typeof failureReasonCodeSchema>

/**
 * 일괄 작업 개별 이슈 실패사유 코드 한국어 라벨 맵.
 * backend FailureReasonCode enum 7종과 1:1 대응한다.
 *
 * - 라벨 변경 시 이 파일만 수정하면 컴포넌트와 E2E 셀렉터가 자동 반영된다.
 */
export const failureReasonLabels: Record<FailureReasonCode, string> = {
  NOT_FOUND: '이슈를 찾을 수 없음',
  FORBIDDEN: '권한 없음',
  TRANSITION_NOT_ALLOWED: '허용되지 않는 전이',
  VERSION_CONFLICT: '다른 요청이 먼저 수정함',
  WORKFLOW_NOT_CONFIGURED: '워크플로우 미구성',
  TYPE_NOT_FOUND: '이슈 유형 없음',
  UNKNOWN: '알 수 없는 오류',
} as const

/**
 * 일괄 작업 전체 상태 한국어 라벨 맵.
 * backend BulkOperationStatus enum(PENDING/RUNNING/COMPLETED/FAILED)과 1:1 대응한다.
 */
export const statusLabels: Record<BulkOperationResponse['status'], string> = {
  PENDING: '대기 중',
  RUNNING: '처리 중',
  COMPLETED: '완료',
  FAILED: '실패',
} as const

/**
 * 일괄 작업 다이얼로그가 노출하는 그 밖의 한국어 문구.
 *
 * 위 두 맵은 backend enum 과 1:1 대응하는 **번역표**라 화면 문구를 섞지 않는다.
 * 결의안 Select 의 placeholder 는 여기 두지 않는다 — 세 화면 공용이라
 * `resolution-labels.ts` 가 소유한다.
 */
export const bulkOperationLabels = {
  /** 전이 상태 Select 미선택 placeholder (`issues/BulkTransitionDialog.tsx`) */
  statusSelectPlaceholder: '상태를 선택하세요',
} as const

/**
 * 실패사유 코드를 한국어 라벨로 변환한다.
 * 알 수 없는 코드(미래 enum 확장 등)에 대해 fallback 문자열을 반환한다.
 *
 * @param code backend FailureReasonCode 또는 미지정 코드
 * @returns 한국어 라벨. 알 수 없는 코드면 "알 수 없는 오류"를 반환한다.
 */
export function getFailureReasonLabel(code: string): string {
  const known: string | undefined = failureReasonLabels[code as FailureReasonCode]
  return known ?? failureReasonLabels['UNKNOWN']
}

/** 라벨 const 추론 타입 */
export type BulkOperationLabels = {
  failureReasonLabels: typeof failureReasonLabels
  statusLabels: typeof statusLabels
}
