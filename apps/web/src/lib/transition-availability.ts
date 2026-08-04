// 전이 가용성 판정 순수 헬퍼 — 422(워크플로우 미설정)와 200+빈 배열(종료 상태)을 가른다 (스펙 E5)
import { ApiError } from '@/api/client'
// 타입 정본은 IssueMetaPanel.tsx 에 그대로 둔다. 이번 PR 은 **함수만** 옮기는 이동이고,
// 타입 정의를 옮기면 IssueMetaPanel.tsx·IssueStateTransition.tsx 를 함께 고쳐야 해
// task 범위를 벗어난다. lib → components 로의 type-only import 는 선례가 있다
// (lib/swimlane-group.ts → components/board/BoardCard) — 컴파일 시 소거되므로
// 런타임 결합이 생기지 않는다. 타입을 lib 로 옮기는 것은 후속 과제.
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'

/**
 * GET /transitions 응답 상태를 바탕으로 전이 불가 사유를 결정한다.
 * - 에러 + 422 → 'no-workflow' (워크플로우 미설정)
 * - 정상 + 빈 배열 → 'terminal' (종료상태)
 * - 정상 + 전이 있음 → null (전이 가능)
 * - 에러 + 비422 → null (에러는 별도 처리, 전이 불가 사유 없음으로 처리)
 *
 * ★상세 화면(`routes/issues.$key.tsx`)과 목록 셀(`components/issues/cells/StatusCell.tsx`)의
 * **공용 판정**이다. 복제하면 두 화면이 같은 응답을 서로 다르게 설명하게 된다.
 *
 * @param input 전이 조회의 에러 여부·에러 객체·조회된 전이 개수
 * @returns 전이 컨트롤을 노출할 수 없는 사유. 노출 가능하면 null
 */
export function resolveTransitionUnavailableReason({
  isError,
  error,
  transitionCount,
}: {
  isError: boolean
  error: unknown
  transitionCount: number
}): TransitionUnavailableReason {
  if (isError) {
    return error instanceof ApiError && error.status === 422 ? 'no-workflow' : null
  }
  return transitionCount === 0 ? 'terminal' : null
}
