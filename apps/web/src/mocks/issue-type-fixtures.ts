// 이슈 타입 MSW fixture 데이터 — 5 표준 이슈 타입 (PR #25 shared-kernel 통일)

/** 이슈 타입 응답 형태 */
export interface IssueTypeFixture {
  id: number
  key: string
  name: string
  description: string
  iconName: string | null
}

/** Bug — 예상치 못한 동작 또는 결함 */
export const bugIssueTypeFixture: IssueTypeFixture = {
  id: 1,
  key: 'bug',
  name: '버그',
  description: '예상치 못한 동작 또는 결함',
  iconName: null,
}

/** Story — 사용자 스토리 */
export const storyIssueTypeFixture: IssueTypeFixture = {
  id: 2,
  key: 'story',
  name: '스토리',
  description: '사용자 스토리',
  iconName: null,
}

/** Task — 일반 작업 */
export const taskIssueTypeFixture: IssueTypeFixture = {
  id: 3,
  key: 'task',
  name: '작업',
  description: '일반 작업',
  iconName: null,
}

/** Epic — 대형 작업 묶음 */
export const epicIssueTypeFixture: IssueTypeFixture = {
  id: 4,
  key: 'epic',
  name: '에픽',
  description: '대형 작업 묶음',
  iconName: null,
}

/** Sub-task — 다른 이슈의 하위 작업 */
export const subtaskIssueTypeFixture: IssueTypeFixture = {
  id: 5,
  key: 'subtask',
  name: '하위 작업',
  description: '다른 이슈의 하위 작업',
  iconName: null,
}

/** 5 표준 이슈 타입 전체 목록 */
export const allIssueTypeFixtures: IssueTypeFixture[] = [
  bugIssueTypeFixture,
  storyIssueTypeFixture,
  taskIssueTypeFixture,
  epicIssueTypeFixture,
  subtaskIssueTypeFixture,
]
