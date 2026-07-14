// 프로젝트 자동화 룰 목록 — 트리거/enabled/액션 배지·활성 토글·삭제 확인 모달·빈 상태 CTA (FR-AT-01 D6 Task 5, 액션 배지 FR-AT-02 D6 Task 7)
import { useState } from 'react'
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import {
  useAutomationRules,
  useUpdateAutomationRule,
  useDeleteAutomationRule,
  AUTOMATION_RULES_QUERY_KEY,
} from '@/api/useAutomationRules'
import { extractAutomationRuleErrorCode } from '@/api/automation-rules'
import { useDateFormat } from '@/hooks/use-date-format'
import type { ActionType, AutomationRule, TriggerType } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// 한국어 라벨 — BC 내 고정 (PatList.tsx/WebhookTokenModal.tsx 관례, 별도 i18n 파일 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  heading: '자동화 룰',
  addButton: '룰 추가',
  editButton: '수정',
  deleteButton: '삭제',
  enableButton: '활성화',
  disableButton: '비활성화',
  historyButton: '이력',
  historyAriaSuffix: '실행 이력',
  enabledBadge: '활성',
  disabledBadge: '비활성',
  nextFireAtLabel: '다음 실행',
  emptyMessage: '아직 자동화 룰이 없습니다.',
  loadingStatus: '자동화 룰 목록 로딩 중',
  accessDenied: '권한이 없습니다.',
  genericError: '자동화 룰을 불러오지 못했습니다.',
  toggleFailed: '변경에 실패했습니다.',
  deleteFailed: '삭제에 실패했습니다.',
  deleteConfirmTitle: '자동화 룰을 삭제하시겠습니까?',
  deleteConfirmMessage: '삭제하면 되돌릴 수 없습니다.',
  deleteConfirmButton: '삭제',
  deleteCancelButton: '취소',
  noActionsBadge: '액션 없음',
} as const

/** 트리거 타입 → 한국어 배지 라벨 (backend TriggerType 5종 1:1 대응) */
const triggerTypeLabels: Record<TriggerType, string> = {
  ISSUE_CREATED: '생성',
  ISSUE_UPDATED: '수정',
  ISSUE_COMMENTED: '댓글',
  SCHEDULED: '스케줄',
  WEBHOOK: '웹훅',
}

/** 액션 타입 → 한국어 배지 라벨 (backend ActionType 4종 1:1 대응, FR-AT-02 FR11) */
const actionTypeLabels: Record<ActionType, string> = {
  SET_FIELD: '필드 변경',
  ASSIGN: '담당자',
  ADD_COMMENT: '댓글',
  CALL_WEBHOOK: '웹훅',
}

/**
 * 액션 배지 그룹의 접근성(aria) 라벨을 만든다.
 *
 * 배지들은 시각적으로는 개별 pill로 나뉘어 렌더되지만, 스크린리더 사용자에게는 "액션: 필드 변경,
 * 담당자"처럼 하나의 요약 문장으로 전달하는 편이 낫다(NFR2). `rule.actions`는 실행 순서(position)
 * 그대로이므로 라벨도 같은 순서를 유지한다 — 같은 타입 액션이 여러 개면 라벨도 그만큼 반복된다.
 *
 * @param actions 룰의 액션 목록(실행 순서).
 * @returns 액션이 없으면 "액션 없음", 있으면 "액션: 라벨1, 라벨2" 형식의 문자열.
 */
function actionsGroupLabel(actions: AutomationRule['actions']): string {
  if (actions.length === 0) return labels.noActionsBadge
  return `액션: ${actions.map((action) => actionTypeLabels[action.type]).join(', ')}`
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** AutomationRuleList props */
export interface AutomationRuleListProps {
  /** 룰을 표시할 프로젝트 식별 키 */
  readonly projectKey: string
  /** "룰 추가" 클릭 시 상위에서 생성 폼을 열기 위한 콜백 (이 컴포넌트는 폼을 렌더하지 않는다) */
  readonly onAddRule: () => void
  /** 행 "수정" 클릭 시 상위에서 편집 폼을 열기 위한 콜백 — 클릭된 룰을 전달 */
  readonly onEditRule: (rule: AutomationRule) => void
  /** 행 "이력" 클릭 시 상위에서 실행 이력 뷰를 열기 위한 콜백 — 클릭된 룰을 전달 (FR-AT-05 D6) */
  readonly onViewHistory: (rule: AutomationRule) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirmDialog — radix-ui 직접 사용(components/ui에 Dialog 래퍼 부재, WebhookTokenModal.tsx 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmDialogProps {
  /** 삭제 확인 대상 룰. null이면 모달을 렌더하지 않는다 */
  readonly rule: AutomationRule | null
  /** 삭제 mutation 진행 중 여부 — 확인/취소 버튼을 disabled 처리한다 */
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/** 삭제 확인 모달 — rule이 null이면 렌더하지 않는다 */
function DeleteConfirmDialog({ rule, isPending, onConfirm, onCancel }: DeleteConfirmDialogProps): JSX.Element | null {
  if (rule === null) return null

  return (
    <DialogPrimitive.Root
      open
      onOpenChange={(open) => {
        if (!open) onCancel()
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95">
          <DialogPrimitive.Title className="text-lg font-semibold">
            {labels.deleteConfirmTitle}
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="mt-2 text-sm text-muted-foreground">
            {rule.name} — {labels.deleteConfirmMessage}
          </DialogPrimitive.Description>

          <div className="mt-6 flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={isPending}
              data-testid="automation-rule-delete-cancel"
              onClick={onCancel}
            >
              {labels.deleteCancelButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={isPending}
              data-testid={`automation-rule-delete-confirm-${rule.id}`}
              onClick={onConfirm}
            >
              {labels.deleteConfirmButton}
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AutomationRuleRow — 행 서브컴포넌트 (같은 파일 내부 분리 — 이 Task는 별도 파일 생성이 금지됨)
// ─────────────────────────────────────────────────────────────────────────────

interface AutomationRuleRowProps {
  readonly rule: AutomationRule
  readonly isToggling: boolean
  readonly onToggle: (rule: AutomationRule) => void
  readonly onEdit: (rule: AutomationRule) => void
  readonly onViewHistory: (rule: AutomationRule) => void
  readonly onDeleteClick: (rule: AutomationRule) => void
}

/** 배지 공통 클래스 — tone에 따라 muted(중립)/primary(강조) 톤만 다르다 */
const BADGE_BASE_CLASS = 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium'
const BADGE_TONE_CLASS = {
  muted: 'bg-muted text-muted-foreground',
  primary: 'bg-primary/10 text-primary',
} as const

/** 트리거 타입/enabled 상태를 나타내는 작은 배지 — tone으로 muted/primary 톤 전환 */
function RuleBadge({ tone, children }: { readonly tone: keyof typeof BADGE_TONE_CLASS; readonly children: string }): JSX.Element {
  return <span className={`${BADGE_BASE_CLASS} ${BADGE_TONE_CLASS[tone]}`}>{children}</span>
}

/**
 * 룰 단일 행 — 이름·트리거 배지·enabled 배지·(SCHEDULED만) nextFireAt·액션 타입 배지 그룹(FR-AT-02
 * FR11, `rule.actions`가 비어있으면 "액션 없음") + 토글/수정/삭제 액션.
 */
function AutomationRuleRow({
  rule,
  isToggling,
  onToggle,
  onEdit,
  onViewHistory,
  onDeleteClick,
}: AutomationRuleRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()

  return (
    <li className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium">{rule.name}</span>
          <RuleBadge tone="muted">{triggerTypeLabels[rule.triggerType]}</RuleBadge>
          <RuleBadge tone={rule.enabled ? 'primary' : 'muted'}>
            {rule.enabled ? labels.enabledBadge : labels.disabledBadge}
          </RuleBadge>
        </div>
        {rule.triggerType === 'SCHEDULED' && rule.nextFireAt !== null && (
          <p className="text-xs text-muted-foreground">
            {labels.nextFireAtLabel}: {formatDateTime(rule.nextFireAt)}
          </p>
        )}
        {/* 액션 타입 배지 그룹 (FR-AT-02 FR11) — position 순서 그대로, 0개면 배지 대신 안내 문구.
            role="group" + aria-label로 개별 배지를 순회하지 않고도 스크린리더가 요약을 읽게 한다(NFR2). */}
        <div
          role="group"
          aria-label={actionsGroupLabel(rule.actions)}
          className="flex flex-wrap items-center gap-1"
        >
          {rule.actions.length === 0 ? (
            <span className="text-xs text-muted-foreground">{labels.noActionsBadge}</span>
          ) : (
            rule.actions.map((action, index) => (
              // key: 같은 타입 액션이 여러 개일 수 있어 id가 없다 — position(index)으로 안정적 구분.
              <RuleBadge key={`${action.type}-${index}`} tone="muted">
                {actionTypeLabels[action.type]}
              </RuleBadge>
            ))
          )}
        </div>
      </div>

      <div className="flex items-center gap-2 shrink-0">
        <Button
          variant="outline"
          size="sm"
          disabled={isToggling}
          aria-label={`${rule.name} ${rule.enabled ? labels.disableButton : labels.enableButton}`}
          data-testid={`automation-rule-toggle-${rule.id}`}
          onClick={() => {
            onToggle(rule)
          }}
        >
          {rule.enabled ? labels.disableButton : labels.enableButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          aria-label={`${rule.name} ${labels.editButton}`}
          data-testid={`automation-rule-edit-${rule.id}`}
          onClick={() => {
            onEdit(rule)
          }}
        >
          {labels.editButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          aria-label={`${rule.name} ${labels.historyAriaSuffix}`}
          data-testid={`automation-rule-history-${rule.id}`}
          onClick={() => {
            onViewHistory(rule)
          }}
        >
          {labels.historyButton}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          aria-label={`${rule.name} ${labels.deleteButton}`}
          data-testid={`automation-rule-delete-${rule.id}`}
          onClick={() => {
            onDeleteClick(rule)
          }}
        >
          {labels.deleteButton}
        </Button>
      </div>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AutomationRuleList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 자동화 룰 목록 컴포넌트.
 *
 * - useAutomationRules로 목록 조회. 4분기: 로딩 → 상태 표시, 에러 → 메시지(403은 권한 없음으로
 *   별도 분기, 그 외는 일반 메시지), 빈 → 빈 상태 문구, 목록 → AutomationRuleRow 렌더.
 * - "룰 추가" 헤더 버튼은 목록이 비어있어도 항상 노출되어 빈 상태의 CTA를 겸한다 — onAddRule을
 *   그대로 위임한다(dead path 없음). 룰 생성/편집 폼 자체는 이 컴포넌트가 렌더하지 않는다
 *   (Task 6 AutomationRuleFormDialog, 조립은 Task 8이 담당).
 * - 행 "수정" 클릭은 onEditRule(rule)을 위임한다.
 * - 행 "이력" 클릭은 onViewHistory(rule)을 위임한다(FR-AT-05 D6) — 실행 이력 뷰 자체는 이
 *   컴포넌트가 렌더하지 않는다.
 * - 활성 토글은 useUpdateAutomationRule로 `{ version, enabled: !enabled }`를 PATCH한다(OCC).
 *   실패 시 toast로 알린다. 버전 충돌(409 AUTOMATION_RULE_VERSION_CONFLICT)이면 목록 쿼리를
 *   invalidate해 refetch를 유도한다 — 그렇지 않으면 stale version으로 재시도가 반복되어
 *   무한 409에 빠진다 (스펙 §4 FR-7 · §6 E5 · §2 S4).
 * - 삭제는 확인 모달(DeleteConfirmDialog) → 확인 시 useDeleteAutomationRule.
 * - 행마다 `rule.actions` 타입 배지 그룹을 표시한다(FR-AT-02 FR11) — SET_FIELD/ASSIGN/ADD_COMMENT/
 *   CALL_WEBHOOK을 한국어 라벨로, 액션이 0개면 배지 대신 "액션 없음" 문구.
 *
 * @param projectKey 프로젝트 식별 키
 * @param onAddRule "룰 추가" 클릭 콜백
 * @param onEditRule 행 "수정" 클릭 콜백 — 클릭된 룰을 인자로 전달
 * @param onViewHistory 행 "이력" 클릭 콜백 — 클릭된 룰을 인자로 전달
 */
export function AutomationRuleList({
  projectKey,
  onAddRule,
  onEditRule,
  onViewHistory,
}: AutomationRuleListProps): JSX.Element {
  const { data: rules, isLoading, isError, error } = useAutomationRules(projectKey)
  const updateRule = useUpdateAutomationRule(projectKey)
  const deleteRule = useDeleteAutomationRule(projectKey)
  const queryClient = useQueryClient()

  const [deletingRule, setDeletingRule] = useState<AutomationRule | null>(null)

  function handleToggle(rule: AutomationRule): void {
    updateRule.mutate(
      { id: rule.id, body: { version: rule.version, enabled: !rule.enabled } },
      {
        onError: (toggleError) => {
          toast.error(labels.toggleFailed)
          if (extractAutomationRuleErrorCode(toggleError) === 'AUTOMATION_RULE_VERSION_CONFLICT') {
            void queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })
          }
        },
      },
    )
  }

  function handleDeleteClick(rule: AutomationRule): void {
    setDeletingRule(rule)
  }

  function handleDeleteConfirm(): void {
    if (deletingRule === null) return
    deleteRule.mutate(deletingRule.id, {
      onSuccess: () => {
        setDeletingRule(null)
      },
      onError: () => {
        setDeletingRule(null)
        toast.error(labels.deleteFailed)
      },
    })
  }

  function handleDeleteCancel(): void {
    setDeletingRule(null)
  }

  const ruleList = rules ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">{labels.heading}</h2>
        <Button size="sm" data-testid="automation-rule-add-button" onClick={onAddRule}>
          {labels.addButton}
        </Button>
      </div>

      {isLoading && (
        <div role="status" aria-label={labels.loadingStatus} className="py-8 text-center text-sm text-muted-foreground">
          {labels.loadingStatus}
        </div>
      )}

      {!isLoading && isError && (
        <p className="text-sm text-destructive">
          {extractAutomationRuleErrorCode(error) === 'AUTOMATION_ACCESS_DENIED'
            ? labels.accessDenied
            : labels.genericError}
        </p>
      )}

      {!isLoading && !isError && ruleList.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">{labels.emptyMessage}</p>
      )}

      {!isLoading && !isError && ruleList.length > 0 && (
        <ul className="space-y-2">
          {ruleList.map((rule) => (
            <AutomationRuleRow
              key={rule.id}
              rule={rule}
              isToggling={updateRule.isPending}
              onToggle={handleToggle}
              onEdit={onEditRule}
              onViewHistory={onViewHistory}
              onDeleteClick={handleDeleteClick}
            />
          ))}
        </ul>
      )}

      <DeleteConfirmDialog
        rule={deletingRule}
        isPending={deleteRule.isPending}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
      />
    </div>
  )
}
