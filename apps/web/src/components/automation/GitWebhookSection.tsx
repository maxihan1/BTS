// Git 웹훅 섹션 — 등록 Dialog·URL 1회 노출 모달·목록·삭제 확인을 조립하는 컨테이너 (FR-AT-07 PR-D Task 6)
import { useState } from 'react'
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { useGitWebhooks, useCreateGitWebhook, useDeleteGitWebhook, GIT_WEBHOOKS_QUERY_KEY } from '@/api/useGitWebhooks'
import { extractAutomationRuleErrorCode } from '@/api/automation-rules'
import { useDateFormat } from '@/hooks/use-date-format'
import { GitWebhookRegisterDialog } from './GitWebhookRegisterDialog'
import { GitWebhookUrlModal } from './GitWebhookUrlModal'
import type { CreateGitWebhookInput, GitProvider, GitWebhookSummary } from '@/api/automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// 한국어 라벨 — BC 내 고정 (AutomationRuleList.tsx/GitWebhookUrlModal.tsx 관례, 별도 i18n 파일 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  heading: 'Git 웹훅',
  addButton: '웹훅 등록',
  loadingStatus: 'Git 웹훅 목록 로딩 중',
  accessDenied: '권한이 없습니다.',
  genericError: 'Git 웹훅을 불러오지 못했습니다.',
  emptyMessage: '등록된 Git 웹훅이 없습니다.',
  deleteButton: '삭제',
  deleteFailed: '삭제에 실패했습니다.',
  registerFailed: '등록에 실패했습니다.',
  deleteConfirmTitle: 'Git 웹훅을 삭제하시겠습니까?',
  deleteConfirmMessage:
    '연동이 끊기고 복구할 수 없습니다. 삭제 후에는 provider 설정의 URL 도 함께 교체해야 다시 정상 동작합니다.',
  deleteConfirmButton: '삭제',
  deleteCancelButton: '취소',
  reissueHelp:
    '보안을 위해 발급된 URL은 재발급할 수 없습니다. URL을 분실했다면 이 웹훅을 삭제한 뒤 다시 등록하고, ' +
    'GitHub/GitLab 웹훅 설정의 URL도 새 값으로 교체해 주세요.',
} as const

/** provider → 한국어(고유명사) 표시 라벨 (GitWebhookRegisterDialog.tsx PROVIDER_OPTIONS 동형) */
const providerLabels: Record<GitProvider, string> = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirmDialog — file-local 복제(AutomationRuleList.tsx DeleteConfirmDialog 구조 동형,
// radix-ui 직접 사용 — components/ui에 Dialog 래퍼 부재)
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmDialogProps {
  /** 삭제 확인 대상 웹훅. null이면 모달을 렌더하지 않는다 */
  readonly webhook: GitWebhookSummary | null
  /** 삭제 mutation 진행 중 여부 — 확인/취소 버튼을 disabled 처리한다 */
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/** 삭제 확인 모달 — webhook이 null이면 렌더하지 않는다 */
function DeleteConfirmDialog({ webhook, isPending, onConfirm, onCancel }: DeleteConfirmDialogProps): JSX.Element | null {
  if (webhook === null) return null

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
            {labels.deleteConfirmMessage}
          </DialogPrimitive.Description>

          <div className="mt-6 flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={isPending}
              data-testid="git-webhook-delete-cancel"
              onClick={onCancel}
            >
              {labels.deleteCancelButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={isPending}
              data-testid={`git-webhook-delete-confirm-${webhook.id}`}
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
// GitWebhookRow — 행 서브컴포넌트 (같은 파일 내부 분리 — AutomationRuleRow 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface GitWebhookRowProps {
  readonly webhook: GitWebhookSummary
  readonly onDeleteClick: (webhook: GitWebhookSummary) => void
}

/** 웹훅 단일 행 — provider·createdAt으로 식별한다. token/secret/createdBy(UUID)는 표시하지 않는다. */
function GitWebhookRow({ webhook, onDeleteClick }: GitWebhookRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const providerLabel = providerLabels[webhook.provider]

  return (
    <li
      data-testid={`git-webhook-row-${webhook.id}`}
      className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="min-w-0 flex-1 space-y-1">
        <span className="text-sm font-medium">{providerLabel}</span>
        <p className="text-xs text-muted-foreground">{formatDateTime(webhook.createdAt)}</p>
      </div>
      <Button
        variant="destructive"
        size="sm"
        aria-label={`${providerLabel} ${labels.deleteButton}`}
        data-testid={`git-webhook-delete-${webhook.id}`}
        onClick={() => {
          onDeleteClick(webhook)
        }}
      >
        {labels.deleteButton}
      </Button>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** GitWebhookSection props */
export interface GitWebhookSectionProps {
  /** Git 웹훅을 관리할 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// GitWebhookSection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 Git 웹훅 섹션 — `AutomationRuleList`의 형제 섹션으로 프로젝트 설정 화면에 마운트된다.
 *
 * - useGitWebhooks로 목록 조회. 4분기: 로딩 → 상태 표시, 에러 → 메시지(403은 권한 없음으로 별도
 *   분기, 그 외는 일반 메시지), 빈 → 빈 상태 문구, 목록 → GitWebhookRow 렌더(AutomationRuleList
 *   4분기 동형).
 * - "웹훅 등록" 헤더 버튼은 목록이 비어있어도 항상 노출되어 빈 상태의 CTA를 겸한다(dead path 없음).
 * - 등록 Dialog(`GitWebhookRegisterDialog`)의 open 상태는 이 컴포넌트가 소유한다(FR2 — route
 *   상태 불변). 등록 성공(201) 시 `onSuccess`에서 즉시 Dialog를 닫는다.
 * - **URL 1회 노출 모달(`GitWebhookUrlModal`)의 `webhookUrl`은 별도 state가 아니라
 *   `registerMutation.data`에서 파생시킨다.** 등록 Dialog가 닫히는 커밋과 같은 커밋에서
 *   `registerMutation.data`가 채워지므로 "등록 Dialog 부재 + URL 모달 존재"가 항상 동시에
 *   성립한다(FR2, 겹침 없음). 모달의 유일한 닫기 기전은 `registerMutation.reset()`이다(FR7) —
 *   `reset()`은 `data`(URL·token)뿐 아니라 `variables`(secret 원문)까지 함께 비워, secret이
 *   mutation 캐시에 gcTime 동안 잔존하는 것을 막는다(NFR1). 이 파생 배선이 지워지면(별도
 *   `urlPayload` state로 되돌리면) 모달이 다시는 닫히지 않는다 — REFACTOR 단계에서 mutation
 *   테스트로 확인.
 * - 삭제는 확인 모달(file-local `DeleteConfirmDialog`) → 확인 시 useDeleteGitWebhook.
 *   404(이미 삭제됨 — 동시 삭제 등)는 에러로 취급하지 않고 토스트 없이 목록만 invalidate한다.
 *   그 외(403 등)는 토스트만 표시하고 목록을 그대로 둔다(낙관적 제거를 하지 않으므로 별도
 *   롤백이 필요 없다).
 * - `existingProviders`/`listUnavailable`을 목록 쿼리 결과로 채워 등록 Dialog에 내린다(FR13,
 *   신규 엔드포인트 0).
 * - 재발급 엔드포인트가 없으므로(백엔드 0건) 재발급 부재 도움말을 목록 상태와 무관하게 항상
 *   렌더한다(FR11) — 대안(삭제 후 재등록 + provider 설정 갱신)을 함께 안내한다.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function GitWebhookSection({ projectKey }: GitWebhookSectionProps): JSX.Element {
  const { data: webhooks, isLoading, isError, error } = useGitWebhooks(projectKey)
  const registerMutation = useCreateGitWebhook(projectKey)
  const deleteMutation = useDeleteGitWebhook(projectKey)
  const queryClient = useQueryClient()

  const [formOpen, setFormOpen] = useState(false)
  const [deletingWebhook, setDeletingWebhook] = useState<GitWebhookSummary | null>(null)

  const webhookList = webhooks ?? []

  function handleRegisterSubmit(input: CreateGitWebhookInput): void {
    // input은 GitWebhookRegisterDialog가 클라 검증을 통과시킨 원문 그대로다 — secret을 여기서
    // trim/normalize하면 provider HMAC 서명이 영구 불일치한다(BLOCKER-0). 그대로 전달만 한다.
    registerMutation.mutate(input, {
      onSuccess: () => {
        setFormOpen(false)
      },
    })
  }

  function handleDeleteClick(webhook: GitWebhookSummary): void {
    setDeletingWebhook(webhook)
  }

  function handleDeleteConfirm(): void {
    if (deletingWebhook === null) return
    deleteMutation.mutate(deletingWebhook.id, {
      onSuccess: () => {
        setDeletingWebhook(null)
      },
      onError: (deleteError) => {
        setDeletingWebhook(null)
        if (deleteError.status === 404) {
          // 이미 삭제된 웹훅(동시 삭제 등) — 에러로 취급하지 않고 최신 상태로 조용히 refetch한다.
          void queryClient.invalidateQueries({ queryKey: GIT_WEBHOOKS_QUERY_KEY(projectKey) })
          return
        }
        toast.error(labels.deleteFailed)
      },
    })
  }

  function handleDeleteCancel(): void {
    setDeletingWebhook(null)
  }

  const registerErrorMessage = registerMutation.isError
    ? extractAutomationRuleErrorCode(registerMutation.error) === 'AUTOMATION_ACCESS_DENIED'
      ? labels.accessDenied
      : labels.registerFailed
    : undefined

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-base font-semibold">{labels.heading}</h2>
        <Button
          size="sm"
          data-testid="git-webhook-add-button"
          onClick={() => {
            setFormOpen(true)
          }}
        >
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

      {!isLoading && !isError && webhookList.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">{labels.emptyMessage}</p>
      )}

      {!isLoading && !isError && webhookList.length > 0 && (
        <ul className="space-y-2">
          {webhookList.map((webhook) => (
            <GitWebhookRow key={webhook.id} webhook={webhook} onDeleteClick={handleDeleteClick} />
          ))}
        </ul>
      )}

      {/* FR11 — 재발급 부재 도움말은 위 4분기와 무관하게 항상 렌더된다 */}
      <p className="text-xs text-muted-foreground">{labels.reissueHelp}</p>

      <GitWebhookRegisterDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        onSubmit={handleRegisterSubmit}
        isPending={registerMutation.isPending}
        submitError={registerErrorMessage}
        existingProviders={webhookList.map((webhook) => webhook.provider)}
        listUnavailable={isLoading || isError}
      />

      <GitWebhookUrlModal
        webhookUrl={registerMutation.data?.webhookUrl ?? null}
        // ★ FR7 load-bearing — reset()이 유일한 닫기 기전이다. 이 한 줄을 지우면
        // "URL 모달에서 닫기→확인 하면 모달이 사라진다" / "모달을 닫은 뒤 다시 등록하면 새 URL이
        // 뜬다" 테스트 2건이 즉시 red로 전환됨을 mutation 검증으로 확인했다(task-6 REFACTOR).
        // 별도 `urlPayload` state로 되돌리지 말 것 — 파일 상단 GitWebhookSection JSDoc 참고.
        onClose={() => {
          registerMutation.reset()
        }}
      />

      <DeleteConfirmDialog
        webhook={deletingWebhook}
        isPending={deleteMutation.isPending}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
      />
    </div>
  )
}
