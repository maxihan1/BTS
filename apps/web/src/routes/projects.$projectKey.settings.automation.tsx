// 프로젝트 자동화 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능) (FR-AT-01 D6 Task 8)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { AutomationRuleList } from '@/components/automation/AutomationRuleList'
import { AutomationRuleFormDialog } from '@/components/automation/AutomationRuleFormDialog'
import { WebhookTokenModal } from '@/components/automation/WebhookTokenModal'
import { RuleConflictWarningModal } from '@/components/automation/RuleConflictWarningModal'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'
import type { AutomationRule, RuleConflict } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectAutomationSettingsPage에 전달한다.
 */
export function ProjectAutomationSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectAutomationSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectAutomationSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 자동화 설정 페이지.
 *
 * - 헤더 + AutomationRuleList + AutomationRuleFormDialog + WebhookTokenModal 조립.
 * - projectKey가 없거나 빈 문자열이면 ProjectNotFoundScreen을 렌더한다.
 * - 상태 4종을 이 컴포넌트가 보유한다.
 *   - `dialogOpen`/`editingRule` — AutomationRuleList의 onAddRule(신규)·onEditRule(수정)
 *     콜백이 갱신하고, AutomationRuleFormDialog에 그대로 전달한다.
 *   - `webhookToken` — FormDialog의 onWebhookToken 콜백으로 1회 전달받아 WebhookTokenModal에
 *     노출한다. 모달 onClose 시 즉시 null로 되돌려 raw token이 이 컴포넌트의 state에도
 *     남지 않게 한다(§1.18 — storage 저장 절대 금지, WebhookTokenModal.tsx KDoc 동일 계약).
 *   - `conflicts` — FormDialog의 onConflicts 콜백으로 1회 전달받아 RuleConflictWarningModal에
 *     노출한다(FR-AT-04 D6/D7). 웹훅 토큰과 충돌이 동시에 세팅되면 토큰 모달을 먼저 노출하고
 *     닫은 뒤에야 충돌 모달이 순차로 노출된다(`webhookToken===null` 가드, 토큰 우선).
 * - 403 등 목록 조회 에러 상태는 AutomationRuleList가 자체적으로 처리한다.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectAutomationSettingsPage({
  projectKey,
}: ProjectAutomationSettingsPageProps): JSX.Element {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<AutomationRule | null>(null)
  const [webhookToken, setWebhookToken] = useState<string | null>(null)
  const [conflicts, setConflicts] = useState<RuleConflict[] | null>(null)

  if (!projectKey) {
    return <ProjectNotFoundScreen />
  }

  function handleAddRule(): void {
    setEditingRule(null)
    setDialogOpen(true)
  }

  function handleEditRule(rule: AutomationRule): void {
    setEditingRule(rule)
    setDialogOpen(true)
  }

  function handleWebhookTokenClose(): void {
    setWebhookToken(null)
  }

  function handleConflictsClose(): void {
    setConflicts(null)
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">자동화</h1>
        <p className="text-muted-foreground text-sm">
          이슈 이벤트나 예약 일정에 따라 자동으로 실행될 트리거 규칙을 관리합니다.
        </p>
      </header>

      <AutomationRuleList
        projectKey={projectKey}
        onAddRule={handleAddRule}
        onEditRule={handleEditRule}
      />

      <AutomationRuleFormDialog
        projectKey={projectKey}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        editingRule={editingRule}
        onWebhookToken={setWebhookToken}
        onConflicts={setConflicts}
      />

      <WebhookTokenModal token={webhookToken} onClose={handleWebhookTokenClose} />
      <RuleConflictWarningModal
        conflicts={webhookToken === null ? conflicts : null}
        onClose={handleConflictsClose}
      />
    </div>
  )
}
