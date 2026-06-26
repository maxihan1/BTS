// MSW 핸들러 통합 인덱스 — BC별 handlers를 spread해서 내보낸다
import { backlogHandlers } from './backlog-handlers'
import { favoriteHandlers } from './favorite-handlers'
import { inboxHandlers } from './inbox-handlers'
import { boardHandlers } from './board-handlers'
import { dashboardHandlers } from './dashboard-handlers'
import { accountLinkHandlers } from './account-link-handlers'
import { attachmentHandlers } from './attachment-handlers'
import { issueGraphHandlers } from './issue-graph-handlers'
import { issueLinkHandlers } from './issue-link-handlers'
import { adminUserHandlers } from './admin-user-handlers'
import { auditLogHandlers } from './audit-log-handlers'
import { authHandlers } from './auth-handlers'
import { bulkOperationHandlers } from './bulk-operation-handlers'
import { changelogHandlers } from './changelog-handlers'
import { componentHandlers } from './component-handlers'
import { customFieldHandlers } from './custom-field-handlers'
import { fieldPermissionHandlers } from './field-permission-handlers'
import { groupHandlers } from './group-handlers'
import { issueHandlers } from './issue-handlers'
import { issueMoveHandlers } from './issue-move-handlers'
import { issueWatcherHandlers } from './issue-watcher-handlers'
import { issuePermissionHandlers } from './issue-permission-handlers'
import { issueTemplateHandlers } from './issue-template-handlers'
import { issueTypeHandlers } from './issue-type-handlers'
import { labelHandlers } from './label-handlers'
import { mfaHandlers } from './mfa-handlers'
import { notificationPolicyHandlers } from './notification-policy-handlers'
import { userNotificationSubscriptionHandlers } from './user-notification-subscription-handlers'
import { postActionHandlers } from './post-action-handlers'
import { passwordHandlers } from './password-handlers'
import { projectLeadHandlers } from './project-lead-handlers'
import { projectMemberHandlers } from './project-member-handlers'
import { projectPermissionHandlers } from './project-permission-handlers'
import { resolutionHandlers } from './resolution-handlers'
import { schemeHandlers } from './scheme-handlers'
import { sessionHandlers } from './session-handlers'
import { trustedDevicesHandlers } from './trusted-devices-handlers'
import { userHandlers } from './user-handlers'
import { versionHandlers } from './version-handlers'
import { webauthnHandlers } from './webauthn-handlers'
import { workflowHandlers } from './workflow-handlers'
import { worklogHandlers } from './worklog-handlers'
import { worklogAggregateHandlers } from './worklog-aggregate-handlers'
import { samlHandlers } from './saml-handlers'
import { oidcHandlers } from './oidc-handlers'
import { routeHandlers } from './route-handlers'
import { securityLevelHandlers } from './security-level-handlers'
import { searchHandlers } from './search-handlers'
import { timelineHandlers } from './timeline-handlers'

/**
 * 애플리케이션 전체 MSW 핸들러 집합.
 *
 * 모든 BC(issue-tracking, project-workflow, auth, identity-access, project-management, notification)의
 * mock endpoint를 하나의 배열로 통합한다. 알파벳순 BC 그룹 정렬.
 */
export const handlers = [
  ...backlogHandlers,
  ...favoriteHandlers,
  ...inboxHandlers,
  ...boardHandlers,
  ...dashboardHandlers,
  ...accountLinkHandlers,
  ...adminUserHandlers,
  ...attachmentHandlers,
  ...auditLogHandlers,
  ...authHandlers,
  ...bulkOperationHandlers,
  ...changelogHandlers,
  ...componentHandlers,
  ...customFieldHandlers,
  ...fieldPermissionHandlers,
  ...groupHandlers,
  ...issueGraphHandlers,
  // issueMoveHandlers를 issueHandlers보다 앞에 두어야 moved 이슈 GET을 먼저 가로챈다
  ...issueMoveHandlers,
  ...issueHandlers,
  ...issueLinkHandlers,
  ...issueWatcherHandlers,
  ...issuePermissionHandlers,
  ...issueTemplateHandlers,
  ...issueTypeHandlers,
  ...labelHandlers,
  ...mfaHandlers,
  ...notificationPolicyHandlers,
  ...userNotificationSubscriptionHandlers,
  ...postActionHandlers,
  ...passwordHandlers,
  ...projectLeadHandlers,
  ...projectMemberHandlers,
  ...projectPermissionHandlers,
  ...resolutionHandlers,
  ...oidcHandlers,
  ...routeHandlers,
  ...samlHandlers,
  ...securityLevelHandlers,
  ...schemeHandlers,
  ...sessionHandlers,
  ...trustedDevicesHandlers,
  ...userHandlers,
  ...versionHandlers,
  ...webauthnHandlers,
  ...worklogHandlers,
  ...worklogAggregateHandlers,
  ...searchHandlers,
  ...timelineHandlers,
  ...workflowHandlers,
]
