// 스프린트 생성 실패를 사용자 문구로 옮기는 판정 (FR-BD-04 PR ⑤)
import { ApiError } from '@/api/client'
import { backlogLabels } from '@/i18n/backlog-labels'

/**
 * 스프린트 생성 실패를 사용자 문구로 옮긴다.
 *
 * 🛑 `backlogLabels.moveFailedError` 를 쓰지 마라 — 생성 실패에 「이슈 이동에 실패했습니다」가
 * 떠서 사용자가 **하지도 않은 동작**의 이름을 받는다. PR ⑤ 전에는 이 경로가 항상 201 이라
 * 문구가 드러나지 않았다.
 *
 * 404 를 가르는 이유. PR ⑤ 가 `resolveTargetBoard` 에 `boardType == SCRUM` 을 넣으면서 이 경로가
 * 실제로 실패할 수 있게 됐다. 그런데 읽기 경로는 막지 않아 칸반 보드의 백로그 URL 은 200 으로
 * 열리므로, 사용자는 자기가 어느 보드에 있는지 모른 채 이 실패를 만난다(보드 스위처는 스크럼만
 * 노출한다). 그 실패는 **재시도로 절대 성공하지 않으므로** 「다시 시도」를 권하지 않는다.
 *
 * 컴포넌트 밖 별도 모듈인 이유는 두 가지다 — 줄수 래칫(R4 · 200줄)과, 렌더 없이 직접 부를 수
 * 있는 판정 지점. 컴포넌트 파일에서 export 하면 `react-refresh/only-export-components` 가 는다.
 */
export function sprintCreateErrorMessage(error: unknown): string {
  return error instanceof ApiError && error.status === 404
    ? backlogLabels.sprintCreateBoardRejectedError
    : backlogLabels.sprintCreateFailedError
}
