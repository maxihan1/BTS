// Slack 채널 매핑 이벤트 필터 카탈로그 — 백엔드 com.bts.slack.domain.SlackChannelEventType 미러 (FR-SL-06 D6 Task 2)

/**
 * 이벤트 필터가 속하는 그룹 — 체크박스 UI에서 fieldset 단위로 묶는 기준.
 */
export type SlackEventFilterGroup = '이슈' | '스프린트' | '자동화'

/** Slack 채널 매핑 이벤트 필터 단일 옵션. */
export interface SlackEventTypeOption {
  /** 백엔드 `SlackChannelEventType` 상수와 1:1 대응하는 wire 문자열(예: `"issue.created"`). */
  readonly wireValue: string
  /** 체크박스 옆에 표시할 한국어 라벨. */
  readonly label: string
  /** 소속 그룹 — {@link SLACK_EVENT_FILTER_GROUPS} 순서로 렌더된다. */
  readonly group: SlackEventFilterGroup
}

/**
 * Slack 채널 매핑 `event_filter` 허용값 10종 카탈로그.
 *
 * 백엔드 `com.bts.slack.domain.SlackChannelEventType`(slack-integration BC,
 * notification `NotificationEventType.wireValue` 미러)과 값이 1:1 일치해야 한다.
 * **변경 시 동기화** — 백엔드 카탈로그가 바뀌면 이 배열도 함께 갱신할 것.
 */
export const SLACK_EVENT_TYPE_CATALOG = [
  { wireValue: 'issue.created', label: '이슈 생성', group: '이슈' },
  { wireValue: 'issue.assigned', label: '담당자 지정', group: '이슈' },
  { wireValue: 'issue.transitioned', label: '상태 전환', group: '이슈' },
  { wireValue: 'issue.commented', label: '댓글 작성', group: '이슈' },
  { wireValue: 'issue.due_soon', label: '마감 임박', group: '이슈' },
  { wireValue: 'issue.overdue', label: '마감 초과', group: '이슈' },
  { wireValue: 'issue.mentioned', label: '멘션', group: '이슈' },
  { wireValue: 'sprint.started', label: '스프린트 시작', group: '스프린트' },
  { wireValue: 'sprint.ended', label: '스프린트 종료', group: '스프린트' },
  { wireValue: 'automation.failed', label: '자동화 실패', group: '자동화' },
] as const satisfies readonly SlackEventTypeOption[]

/**
 * 카탈로그에 등장하는 순서 그대로 도출한 distinct 그룹 목록 — 렌더 순서의 단일 출처.
 * 그룹을 하드코딩하지 않고 카탈로그에서 파생시켜, 카탈로그 갱신 시 그룹 목록이 자동으로 따라온다.
 */
export const SLACK_EVENT_FILTER_GROUPS: readonly SlackEventFilterGroup[] = Array.from(
  new Set(SLACK_EVENT_TYPE_CATALOG.map((option) => option.group)),
)
