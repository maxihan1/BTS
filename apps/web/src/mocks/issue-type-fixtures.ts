// 이슈 타입 MSW fixture 데이터 — 5 표준 이슈 타입 (PR #25 shared-kernel 통일)

/** 이슈 타입 응답 형태 */
export interface IssueTypeFixture {
  key: string
  name: string
  description: string
  iconUrl: string | null
}

/** Bug — 예상치 못한 동작 또는 결함 */
export const bugIssueTypeFixture: IssueTypeFixture = {
  key: 'bug',
  name: '버그',
  description: '예상치 못한 동작 또는 결함',
  iconUrl: null,
}

/** Story — 사용자 스토리 */
export const storyIssueTypeFixture: IssueTypeFixture = {
  key: 'story',
  name: '스토리',
  description: '사용자 스토리',
  iconUrl: null,
}

/** Task — 일반 작업 */
export const taskIssueTypeFixture: IssueTypeFixture = {
  key: 'task',
  name: '작업',
  description: '일반 작업',
  iconUrl: null,
}

/** Epic — 대형 작업 묶음 */
export const epicIssueTypeFixture: IssueTypeFixture = {
  key: 'epic',
  name: '에픽',
  description: '대형 작업 묶음',
  iconUrl: null,
}

/** Sub-task — 다른 이슈의 하위 작업 */
export const subtaskIssueTypeFixture: IssueTypeFixture = {
  key: 'subtask',
  name: '하위 작업',
  description: '다른 이슈의 하위 작업',
  iconUrl: null,
}

/** 5 표준 이슈 타입 전체 목록 */
export const allIssueTypeFixtures: IssueTypeFixture[] = [
  bugIssueTypeFixture,
  storyIssueTypeFixture,
  taskIssueTypeFixture,
  epicIssueTypeFixture,
  subtaskIssueTypeFixture,
]
