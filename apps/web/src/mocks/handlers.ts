// MSW 핸들러 통합 인덱스 — BC별 handlers를 spread해서 내보낸다
import { authHandlers } from './auth-handlers'
import { bulkOperationHandlers } from './bulk-operation-handlers'
import { componentHandlers } from './component-handlers'
import { issueHandlers } from './issue-handlers'
import { issuePermissionHandlers } from './issue-permission-handlers'
import { issueTypeHandlers } from './issue-type-handlers'
import { passwordHandlers } from './password-handlers'
import { projectMemberHandlers } from './project-member-handlers'
import { projectPermissionHandlers } from './project-permission-handlers'
import { schemeHandlers } from './scheme-handlers'
import { sessionHandlers } from './session-handlers'
import { userHandlers } from './user-handlers'
import { versionHandlers } from './version-handlers'
import { workflowHandlers } from './workflow-handlers'

/**
 * 애플리케이션 전체 MSW 핸들러 집합.
 *
 * 모든 BC(issue-tracking, project-workflow, auth, identity-access, project-management)의 mock endpoint를
 * 하나의 배열로 통합한다. 알파벳순 BC 그룹 정렬.
 */
export const handlers = [
  ...authHandlers,
  ...bulkOperationHandlers,
  ...componentHandlers,
  ...issueHandlers,
  ...issuePermissionHandlers,
  ...issueTypeHandlers,
  ...passwordHandlers,
  ...projectMemberHandlers,
  ...projectPermissionHandlers,
  ...schemeHandlers,
  ...sessionHandlers,
  ...userHandlers,
  ...versionHandlers,
  ...workflowHandlers,
]
