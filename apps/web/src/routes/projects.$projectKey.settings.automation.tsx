// 프로젝트 자동화 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능) (FR-AT-01 D6 Task 8)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { AutomationRuleList } from '@/components/automation/AutomationRuleList'
import { GitWebhookSection } from '@/components/automation/GitWebhookSection'
import { AutomationRuleFormDialog } from '@/components/automation/AutomationRuleFormDialog'
import { WebhookTokenModal } from '@/components/automation/WebhookTokenModal'
import { RuleConflictWarningModal } from '@/components/automation/RuleConflictWarningModal'
import { RuleExecutionHistoryDialog } from '@/components/automation/RuleExecutionHistoryDialog'
import { AutomationYamlImportDialog } from '@/components/automation/AutomationYamlImportDialog'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'
import { exportAutomationRulesYaml, extractAutomationRuleErrorCode } from '@/api/automation-rules'
import { triggerBlobDownload } from '@/lib/download'
import type { AutomationRule, RuleConflict } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (admin.webhooks.tsx 관례 — 토스트 문구를 페이지 labels로 상수화)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  export: {
    success: 'YAML을 내보냈습니다.',
    accessDenied: '권한이 없습니다.',
    failure: 'YAML 내보내기에 실패했습니다.',
  },
} as const

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
 * - 헤더 + AutomationRuleList + AutomationRuleFormDialog + WebhookTokenModal + RuleConflictWarningModal
 *   + RuleExecutionHistoryDialog + AutomationYamlImportDialog + GitWebhookSection 조립. GitWebhookSection은
 *   AutomationRuleList의 형제 섹션으로, 등록 Dialog·URL 노출 모달·삭제 확인 등 자신의 상태는 스스로
 *   소유한다(FR-AT-07 PR-D — 이 컴포넌트는 상태를 추가로 늘리지 않는다).
 * - projectKey가 없거나 빈 문자열이면 ProjectNotFoundScreen을 렌더한다.
 * - 상태 6종을 이 컴포넌트가 보유한다.
 *   - `dialogOpen`/`editingRule` — AutomationRuleList의 onAddRule(신규)·onEditRule(수정)
 *     콜백이 갱신하고, AutomationRuleFormDialog에 그대로 전달한다.
 *   - `webhookToken` — FormDialog의 onWebhookToken 콜백으로 1회 전달받아 WebhookTokenModal에
 *     노출한다. 모달 onClose 시 즉시 null로 되돌려 raw token이 이 컴포넌트의 state에도
 *     남지 않게 한다(§1.18 — storage 저장 절대 금지, WebhookTokenModal.tsx KDoc 동일 계약).
 *   - `conflicts` — FormDialog의 onConflicts 콜백으로 1회 전달받아 RuleConflictWarningModal에
 *     노출한다(FR-AT-04 D6/D7). 웹훅 토큰과 충돌이 동시에 세팅되면 토큰 모달을 먼저 노출하고
 *     닫은 뒤에야 충돌 모달이 순차로 노출된다(`webhookToken===null` 가드, 토큰 우선).
 *   - `historyRule` — AutomationRuleList의 onViewHistory 콜백이 클릭된 룰로 세팅하고,
 *     RuleExecutionHistoryDialog에 그대로 전달한다(FR-AT-05 D6/D7). Dialog가 닫히면
 *     (onOpenChange(false)) 즉시 null로 되돌려 다음 open 시 잔존 필터/자동펼침 상태 없이
 *     새로 조회되게 한다(Dialog 내부는 open/ruleId 조합 key 재마운트로 이미 자체 격리하지만,
 *     이 컴포넌트도 ruleId를 null로 되돌려 "선택된 룰 없음"을 명확히 한다).
 *   - `yamlImportOpen` — AutomationRuleList의 onImportYaml 콜백이 true로 세팅하고,
 *     AutomationYamlImportDialog에 controlled open으로 그대로 전달한다(FR-AT-06 D6). Dialog
 *     내부 상태(파일·결과·에러) 리셋은 Dialog 자신의 책임 — 이 컴포넌트는 open 여부만 소유한다.
 * - "YAML 내보내기" 클릭은 이 컴포넌트가 보유한 `exportMutation`(useMutation)이 처리한다 —
 *   성공 시 `triggerBlobDownload`로 즉시 다운로드 + 성공 토스트, 실패 시 errorCode가
 *   AUTOMATION_ACCESS_DENIED면 권한 없음 토스트, 그 외는 일반 실패 토스트(FR-AT-06 D6 S1/S3).
 *   가져오기(import)는 errorCode 분기 없이 Dialog가 서버 detail을 그대로 신뢰한다(spec §에러
 *   비대칭 — AutomationYamlImportDialog.tsx KDoc 참조).
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
  const [historyRule, setHistoryRule] = useState<AutomationRule | null>(null)
  const [yamlImportOpen, setYamlImportOpen] = useState(false)

  const exportMutation = useMutation({
    mutationFn: () => exportAutomationRulesYaml(projectKey),
    onSuccess: ({ blob, filename }) => {
      triggerBlobDownload(blob, filename)
      toast.success(labels.export.success)
    },
    onError: (error) => {
      const code = extractAutomationRuleErrorCode(error)
      toast.error(code === 'AUTOMATION_ACCESS_DENIED' ? labels.export.accessDenied : labels.export.failure)
    },
  })

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

  function handleHistoryDialogOpenChange(open: boolean): void {
    if (!open) setHistoryRule(null)
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">자동화</h1>
        <p className="text-muted-foreground text-sm">
          이슈 이벤트·예약 일정·PR 머지에 따라 자동으로 실행될 규칙과, 규칙을 발화시키는 웹훅 연동을
          관리합니다.
        </p>
      </header>

      <AutomationRuleList
        projectKey={projectKey}
        onAddRule={handleAddRule}
        onEditRule={handleEditRule}
        onViewHistory={setHistoryRule}
        onExportYaml={() => exportMutation.mutate()}
        isExportingYaml={exportMutation.isPending}
        onImportYaml={() => setYamlImportOpen(true)}
      />

      <GitWebhookSection projectKey={projectKey} />

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
      <RuleExecutionHistoryDialog
        open={historyRule !== null}
        onOpenChange={handleHistoryDialogOpenChange}
        projectKey={projectKey}
        ruleId={historyRule?.id ?? null}
        ruleName={historyRule?.name ?? null}
      />
      <AutomationYamlImportDialog
        open={yamlImportOpen}
        onOpenChange={setYamlImportOpen}
        projectKey={projectKey}
      />
    </div>
  )
}
