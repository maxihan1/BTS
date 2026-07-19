// MSW 핸들러 통합 인덱스 — BC별 handlers를 spread해서 내보낸다
import { backlogHandlers } from './backlog-handlers'
import { burndownHandlers } from './burndown-handlers'
import { velocityHandlers } from './velocity-handlers'
import { cfdHandlers } from './cfd-handlers'
import { cycleTimeHandlers } from './cycle-time-handlers'
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
import { importHandlers } from './import-handlers'
import { issueHandlers } from './issue-handlers'
import { issueMoveHandlers } from './issue-move-handlers'
import { issueWatcherHandlers } from './issue-watcher-handlers'
import { issuePermissionHandlers } from './issue-permission-handlers'
import { issueTemplateHandlers } from './issue-template-handlers'
import { issueTypeHandlers } from './issue-type-handlers'
import { keymapHandlers } from './keymap-handlers'
import { labelHandlers } from './label-handlers'
import { mfaHandlers } from './mfa-handlers'
import { notificationPolicyHandlers } from './notification-policy-handlers'
import { userNotificationSubscriptionHandlers } from './user-notification-subscription-handlers'
import { postActionHandlers } from './post-action-handlers'
import { passwordHandlers } from './password-handlers'
import { patHandlers } from './pat-handlers'
import { preferencesHandlers } from './preferences-handlers'
import { profileHandlers } from './profile-handlers'
import { projectLeadHandlers } from './project-lead-handlers'
import { projectListHandlers } from './project-list-handlers'
import { projectMemberHandlers } from './project-member-handlers'
import { projectPermissionHandlers } from './project-permission-handlers'
import { resolutionHandlers } from './resolution-handlers'
import { schemeHandlers } from './scheme-handlers'
import { sessionHandlers } from './session-handlers'
import { slackHandlers } from './slack-handlers'
import { slackChannelMappingHandlers } from './slack-channel-mapping-handlers'
import { slackUserConnectionHandlers } from './slack-user-connection-handlers'
import { statusHandlers } from './status-handlers'
import { oooHandlers } from './ooo-handlers'
import { trustedDevicesHandlers } from './trusted-devices-handlers'
import { userHandlers } from './user-handlers'
import { versionHandlers } from './version-handlers'
import { webauthnHandlers } from './webauthn-handlers'
import { webhookHandlers } from './webhook-handlers'
import { workflowHandlers } from './workflow-handlers'
import { worklogHandlers } from './worklog-handlers'
import { worklogAggregateHandlers } from './worklog-aggregate-handlers'
import { samlHandlers } from './saml-handlers'
import { oidcHandlers } from './oidc-handlers'
import { routeHandlers } from './route-handlers'
import { securityLevelHandlers } from './security-level-handlers'
import { savedFilterHandlers } from './saved-filter-handlers'
import { searchHandlers } from './search-handlers'
import { timelineHandlers } from './timeline-handlers'
import { calendarHandlers } from './calendar-handlers'
import { calendarFeedHandlers } from './calendar-feed-handlers'
import { automationRuleHandlers } from './automation-rule-handlers'
import { automationExecutionHandlers } from './automation-execution-handlers'
import { gitWebhookHandlers } from './git-webhook-handlers'
import { globalPermissionHandlers } from './global-permission-handlers'

/**
 * 애플리케이션 전체 MSW 핸들러 집합.
 *
 * 모든 BC(issue-tracking, project-workflow, auth, identity-access, project-management, notification)의
 * mock endpoint를 하나의 배열로 통합한다. 알파벳순 BC 그룹 정렬.
 */
export const handlers = [
  ...backlogHandlers,
  ...burndownHandlers,
  ...velocityHandlers,
  ...cfdHandlers,
  ...cycleTimeHandlers,
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
  ...importHandlers,
  ...issueGraphHandlers,
  // issueMoveHandlers를 issueHandlers보다 앞에 두어야 moved 이슈 GET을 먼저 가로챈다
  ...issueMoveHandlers,
  ...issueHandlers,
  ...issueLinkHandlers,
  ...issueWatcherHandlers,
  ...issuePermissionHandlers,
  ...issueTemplateHandlers,
  ...issueTypeHandlers,
  ...keymapHandlers,
  ...labelHandlers,
  ...mfaHandlers,
  ...notificationPolicyHandlers,
  ...userNotificationSubscriptionHandlers,
  ...postActionHandlers,
  ...passwordHandlers,
  ...patHandlers,
  ...preferencesHandlers,
  ...profileHandlers,
  ...projectLeadHandlers,
  ...projectListHandlers,
  ...projectMemberHandlers,
  ...projectPermissionHandlers,
  ...resolutionHandlers,
  ...oidcHandlers,
  ...routeHandlers,
  ...samlHandlers,
  ...securityLevelHandlers,
  ...schemeHandlers,
  ...sessionHandlers,
  ...slackHandlers,
  ...slackChannelMappingHandlers,
  ...slackUserConnectionHandlers,
  ...statusHandlers,
  ...oooHandlers,
  ...trustedDevicesHandlers,
  ...userHandlers,
  ...versionHandlers,
  ...webauthnHandlers,
  ...webhookHandlers,
  ...worklogHandlers,
  ...worklogAggregateHandlers,
  ...savedFilterHandlers,
  ...searchHandlers,
  ...timelineHandlers,
  ...workflowHandlers,
  ...calendarHandlers,
  ...calendarFeedHandlers,
  ...automationRuleHandlers,
  ...automationExecutionHandlers,
  ...gitWebhookHandlers,
  ...globalPermissionHandlers,
]
