// MSW 핸들러 통합 인덱스 — BC별 handlers를 spread해서 내보낸다
import { authHandlers } from './auth-handlers'
import { issueHandlers } from './issue-handlers'
import { issueTypeHandlers } from './issue-type-handlers'
import { passwordHandlers } from './password-handlers'
import { projectMemberHandlers } from './project-member-handlers'
import { schemeHandlers } from './scheme-handlers'
import { sessionHandlers } from './session-handlers'
import { usersHandlers } from './users-handlers'
import { workflowHandlers } from './workflow-handlers'

/**
 * 애플리케이션 전체 MSW 핸들러 집합.
 *
 * 모든 BC(issue-tracking, project-workflow, auth, project-management)의 mock endpoint를
 * 하나의 배열로 통합한다. 알파벳순 BC 그룹 정렬.
 */
export const handlers = [
  ...authHandlers,
  ...issueHandlers,
  ...issueTypeHandlers,
  ...passwordHandlers,
  ...projectMemberHandlers,
  ...schemeHandlers,
  ...sessionHandlers,
  ...usersHandlers,
  ...workflowHandlers,
]
