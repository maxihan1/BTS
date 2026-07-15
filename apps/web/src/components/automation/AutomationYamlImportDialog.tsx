// 자동화 룰 GitOps YAML 업로드 Dialog — 파일 선택 → 2단계 확인 → 결과/에러/토큰 1회 노출 (FR-AT-06 D6)
import { useEffect, useState } from 'react'
import type { ChangeEvent, JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/api/client'
import { importAutomationRulesYaml, extractAutomationImportFailedIndex } from '@/api/automation-rules'
import { AUTOMATION_RULES_QUERY_KEY } from '@/api/useAutomationRules'
import type { AutomationImportResponse, ImportedWebhookToken, RuleConflict } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 백엔드 MAX_IMPORT_BYTES 미러(§API 계약). 매직 넘버 회피 + 클라이언트 선제 차단용(S7-a).
// ─────────────────────────────────────────────────────────────────────────────

const MAX_IMPORT_BYTES = 1_048_576

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어(i18n 파일 미도입 관례). tokenWarning/copyButton/copiedLabel/copyFailed는
// WebhookTokenModal.tsx:12-17 문구를 그대로 재사용한다(컴포넌트 자체는 재사용하지 않는다 — 단건
// 모달 vs 다건 목록 차이, gap 분석 CONCERN-4). copyHint는 에러 조건과 무관하게 항상 노출한다(S8).
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: 'YAML 가져오기',
  fileInputLabel: 'YAML 파일',
  copyHint:
    '다른 프로젝트의 룰을 복사하려면 YAML에서 id: 줄을 제거하세요. 같은 프로젝트에 다시 적용하는 경우에는 그대로 두면 됩니다.',
  applyButton: '적용',
  applyingButton: '적용 중...',
  confirmWarning: '적용하면 기존 룰이 덮어쓰일 수 있습니다.',
  confirmButton: '확정',
  cancelButton: '취소',
  closeButton: '닫기',
  rollbackNote: '적용된 변경이 없습니다(전량 취소).',
  genericFailure: '가져오기에 실패했습니다.',
  failedRuleSuffix: '번째 룰에서 실패했습니다. ',
  tooLarge: '파일이 너무 큽니다(최대 1MiB).',
  tokenWarning: '이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.',
  copyButton: '복사',
  copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  closeConfirm: '토큰은 다시 볼 수 없습니다. 닫을까요?',
  createdLabel: '생성',
  updatedLabel: '갱신',
  totalLabel: '총',
  conflictsHeading: '다음 충돌이 감지되었습니다.',
} as const

/** 룰 충돌 타입 4종 → 한국어 배지 라벨 — RuleConflictWarningModal.tsx 선례 동형(별도 export가 없어 로컬 재정의). */
const CONFLICT_TYPE_LABELS: Record<string, string> = {
  CYCLE: '순환 참조',
  FIELD_CONFLICT: '필드 충돌',
  PRIORITY_AMBIGUITY: '우선순위 모호',
  PERMISSION_MISSING: '권한 부족',
}

/**
 * 경고 박스(amber) 공통 클래스 — 토큰 노출 영역과 conflicts 경고 영역이 동일한 시각 언어를
 * 공유한다(AutomationRuleList.tsx `BADGE_BASE_CLASS` DRY 관례 동형).
 */
const AMBER_WARNING_BOX_CLASS = 'space-y-2 rounded-md border border-amber-200 bg-amber-50 p-3 dark:border-amber-900 dark:bg-amber-950'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 메시지 조립 — 단일 규칙(errorCode 분기 없음, spec §에러 · BLOCKER-1 해소)
// ─────────────────────────────────────────────────────────────────────────────

/** ApiError body에서 서버 `detail` 문자열을 안전하게 추출한다(RFC 7807 ProblemDetail 계약). */
function extractImportErrorDetail(error: ApiError | null): string | undefined {
  if (error === null) return undefined
  const body = error.body as Record<string, unknown> | undefined
  const detail = body?.['detail']
  return typeof detail === 'string' ? detail : undefined
}

/**
 * 가져오기 실패 메시지를 단일 규칙으로 조립한다 — errorCode로 사유를 분기하지 않고 서버 `detail`을
 * 그대로 신뢰한다(깨진 YAML/projectKey 불일치/커맨드 검증 실패/OCC 충돌이 와이어에서 구별 불가하기
 * 때문 — BLOCKER-1). `failedIndex`가 있으면 "N번째 룰에서 실패했습니다."를 접두하고(+1, 0-based→
 * 1-based), 모든 실패에 rollbackNote(전량 취소 안내)를 병기한다(atomic fail-closed 명시, FR10).
 */
function buildImportErrorMessage(error: ApiError | null): string {
  const failedIndex = extractAutomationImportFailedIndex(error)
  const prefix = failedIndex !== null ? `${failedIndex + 1}${labels.failedRuleSuffix}` : ''
  const detail = extractImportErrorDetail(error) ?? labels.genericFailure
  return `${prefix}${detail} ${labels.rollbackNote}`
}

// ─────────────────────────────────────────────────────────────────────────────
// ImportedTokenRow — 토큰 1건 행(복사 상태를 행 단위로 소유, WebhookTokenModal.tsx 단건 로직의 목록 확장)
// ─────────────────────────────────────────────────────────────────────────────

interface ImportedTokenRowProps {
  readonly token: ImportedWebhookToken
}

function ImportedTokenRow({ token }: ImportedTokenRowProps): JSX.Element {
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  async function handleCopy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(token.token)
      setCopied(true)
      setCopyError(null)
    } catch {
      setCopyError(labels.copyFailed)
      setCopied(false)
    }
  }

  return (
    <li className="space-y-1">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{token.name}</span>
        <Button
          variant="outline"
          size="sm"
          data-testid={`automation-yaml-import-token-copy-${token.ruleId}`}
          onClick={() => { void handleCopy() }}
        >
          {copied ? labels.copiedLabel : labels.copyButton}
        </Button>
      </div>
      <code className="block break-all rounded bg-muted px-2 py-1 text-xs font-mono select-all">{token.token}</code>
      {copyError !== null && <p role="alert" className="text-xs text-destructive">{copyError}</p>}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ImportedTokensSection — 토큰 경고 + 목록 + 닫기 2단계 확인(EC7, NFR3)
// ─────────────────────────────────────────────────────────────────────────────

interface ImportedTokensSectionProps {
  readonly tokens: readonly ImportedWebhookToken[]
  readonly closeConfirming: boolean
  readonly onConfirmClose: () => void
  readonly onCancelClose: () => void
}

function ImportedTokensSection({
  tokens,
  closeConfirming,
  onConfirmClose,
  onCancelClose,
}: ImportedTokensSectionProps): JSX.Element {
  return (
    <div data-testid="automation-yaml-import-tokens-section" className={AMBER_WARNING_BOX_CLASS}>
      <p role="alert" className="text-sm text-amber-900 dark:text-amber-100">{labels.tokenWarning}</p>
      <ul className="space-y-2">
        {tokens.map((token) => (
          <ImportedTokenRow key={token.ruleId} token={token} />
        ))}
      </ul>
      {closeConfirming && (
        <div className="space-y-2 rounded-md border border-destructive/20 bg-destructive/5 p-3">
          <p className="text-sm">{labels.closeConfirm}</p>
          <div className="flex gap-2">
            <Button
              variant="destructive"
              size="sm"
              data-testid="automation-yaml-import-close-confirm"
              onClick={onConfirmClose}
            >
              {labels.confirmButton}
            </Button>
            <Button
              variant="outline"
              size="sm"
              data-testid="automation-yaml-import-close-cancel"
              onClick={onCancelClose}
            >
              {labels.cancelButton}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ImportResultSummary / ImportConflictsWarning — 결과 카운트 + 룰 충돌 인라인 경고(FR6/FR7)
// ─────────────────────────────────────────────────────────────────────────────

function ImportResultSummary({ result }: { readonly result: AutomationImportResponse }): JSX.Element {
  return (
    <p data-testid="automation-yaml-import-summary" className="text-sm">
      {labels.createdLabel} {result.created} · {labels.updatedLabel} {result.updated} · {labels.totalLabel}{' '}
      {result.total}
    </p>
  )
}

function ImportConflictsWarning({ conflicts }: { readonly conflicts: readonly RuleConflict[] }): JSX.Element | null {
  if (conflicts.length === 0) return null
  return (
    <div role="alert" data-testid="automation-yaml-import-conflicts" className={AMBER_WARNING_BOX_CLASS}>
      <p className="text-sm font-medium text-amber-900 dark:text-amber-100">{labels.conflictsHeading}</p>
      <ul className="space-y-1">
        {conflicts.map((conflict, index) => (
          <li key={`${conflict.type}-${index}`} className="text-sm text-amber-900 dark:text-amber-100">
            <span className="mr-1 font-semibold">{CONFLICT_TYPE_LABELS[conflict.type] ?? conflict.type}</span>
            {conflict.detail}
          </li>
        ))}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ApplySection — 적용 버튼 + 인라인 2단계 확인(RuleExecutionTraceRow.tsx:192-199 구성 그대로)
// ─────────────────────────────────────────────────────────────────────────────

interface ApplySectionProps {
  readonly confirming: boolean
  readonly isPending: boolean
  readonly canApply: boolean
  readonly onApplyClick: () => void
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

function ApplySection({ confirming, isPending, canApply, onApplyClick, onConfirm, onCancel }: ApplySectionProps): JSX.Element {
  if (!confirming) {
    return (
      <div className="mt-4 flex justify-end">
        <Button size="sm" disabled={!canApply} data-testid="automation-yaml-import-apply-button" onClick={onApplyClick}>
          {labels.applyButton}
        </Button>
      </div>
    )
  }

  return (
    <div className="mt-4 space-y-2 rounded-md border border-destructive/20 bg-destructive/5 p-3">
      <p className="text-sm">{labels.confirmWarning}</p>
      <div className="flex gap-2">
        <Button
          variant="destructive"
          size="sm"
          disabled={isPending}
          data-testid="automation-yaml-import-confirm-button"
          onClick={onConfirm}
        >
          {isPending ? labels.applyingButton : labels.confirmButton}
        </Button>
        <Button variant="outline" size="sm" disabled={isPending} data-testid="automation-yaml-import-cancel-button" onClick={onCancel}>
          {labels.cancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** AutomationYamlImportDialog props */
export interface AutomationYamlImportDialogProps {
  /** Dialog 열림 여부(controlled) */
  readonly open: boolean
  /** 열림 상태 변경 콜백 — X/ESC/오버레이에서 호출된다(토큰 노출 중이면 가로채 2단계 확인, EC7) */
  readonly onOpenChange: (open: boolean) => void
  /** 대상 프로젝트 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// AutomationYamlImportDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 GitOps YAML 가져오기 Dialog.
 *
 * - 파일 선택(`.yaml`/`.yml` 힌트, 확장자 강제 검증 없음 — 손 작성 파일 배제 위험이라 서버 400에
 *   위임) → 1MiB 초과는 클라이언트가 요청 없이 즉시 차단(S7-a) → "적용" 클릭 시 인라인 2단계 확인
 *   (`RuleExecutionTraceRow.tsx` `RuleExecutionReplaySection` 구성 동형) → 확정 시 `File.text()`
 *   원문을 `importAutomationRulesYaml`로 전송한다.
 * - 성공 시 `AUTOMATION_RULES_QUERY_KEY(projectKey)`를 invalidate하고(FR9) 결과를 토큰(있으면) →
 *   카운트 → conflicts(있으면) 순으로 렌더한다 — 토큰이 가장 되돌릴 수 없는 정보라 최상단(정보 계층).
 * - 실패는 errorCode 분기 없는 단일 규칙으로 표시한다({@link buildImportErrorMessage}, BLOCKER-1).
 * - 타 프로젝트 복사 안내(`copyHint`)는 에러 발생 여부와 무관하게 항상 노출한다(S8 상시 도움말).
 * - 새 WEBHOOK 룰 토큰이 있으면 Dialog를 닫으려는 4경로(X·ESC·오버레이·onOpenChange)를 전부
 *   가로채 2단계 확인을 요구한다(EC7). 토큰은 이 컴포넌트의 React state로만 보유하고 storage/URL/
 *   로그에 남기지 않는다(NFR3, §1.18).
 * - `open`이 false→true로 재전이하면 파일·결과·에러 상태를 초기화한다(EC8) — 같은 인스턴스가 열림/
 *   닫힘을 반복하며 재사용되므로 `key` prop 재마운트 대신 `useEffect`로 직접 리셋한다
 *   (react-usestate-stale-key-prop 교훈이되, 이 컴포넌트는 리셋 시점이 "재오픈"이라 재마운트가
 *   아닌 effect가 더 적합하다).
 *
 * @param open Dialog 열림 여부
 * @param onOpenChange 열림 상태 변경 콜백
 * @param projectKey 대상 프로젝트 키
 */
export function AutomationYamlImportDialog({ open, onOpenChange, projectKey }: AutomationYamlImportDialogProps): JSX.Element {
  const [file, setFile] = useState<File | null>(null)
  const [sizeError, setSizeError] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [closeConfirming, setCloseConfirming] = useState(false)
  const [result, setResult] = useState<AutomationImportResponse | null>(null)
  const [importError, setImportError] = useState<ApiError | null>(null)

  const queryClient = useQueryClient()
  const importMutation = useMutation<AutomationImportResponse, ApiError, string>({
    mutationFn: (yamlText: string) => importAutomationRulesYaml(projectKey, yamlText),
    onSuccess: (response) => {
      setResult(response)
      setImportError(null)
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })
    },
    onError: (error) => {
      setImportError(error)
      setConfirming(false)
    },
  })

  useEffect(() => {
    if (open) {
      setFile(null)
      setSizeError(false)
      setConfirming(false)
      setCloseConfirming(false)
      setResult(null)
      setImportError(null)
    }
    // open 재전이에서만 리셋한다 — useState setter만 참조해 안정 참조이므로 의존성 배열은 [open]만으로 충분하다.
  }, [open])

  const hasUnackedTokens = result?.webhookTokens !== undefined && result.webhookTokens.length > 0

  function handleFileChange(event: ChangeEvent<HTMLInputElement>): void {
    const selected = event.target.files?.[0] ?? null
    setFile(selected)
    setSizeError(selected !== null && selected.size > MAX_IMPORT_BYTES)
    setResult(null)
    setImportError(null)
    setConfirming(false)
  }

  async function handleConfirmApply(): Promise<void> {
    if (file === null) return
    const yamlText = await file.text()
    importMutation.mutate(yamlText)
  }

  /** Root의 onOpenChange — 토큰 미확인 상태의 닫기 시도(X 버튼 포함)를 가로채 2단계 확인을 요구한다. */
  function handleOpenChangeAttempt(next: boolean): void {
    if (!next && hasUnackedTokens) {
      setCloseConfirming(true)
      return
    }
    onOpenChange(next)
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChangeAttempt}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          data-testid="automation-yaml-import-overlay"
          className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0"
        />
        <DialogPrimitive.Content
          data-testid="automation-yaml-import-dialog"
          onEscapeKeyDown={(event) => {
            if (hasUnackedTokens) {
              event.preventDefault()
              setCloseConfirming(true)
            }
          }}
          onPointerDownOutside={(event) => {
            if (hasUnackedTokens) {
              event.preventDefault()
              setCloseConfirming(true)
            }
          }}
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-1">{labels.title}</DialogPrimitive.Title>

          <div className="mt-4">
            <label htmlFor="automation-yaml-import-file" className="mb-2 block text-sm font-medium">
              {labels.fileInputLabel}
            </label>
            <Input
              id="automation-yaml-import-file"
              type="file"
              accept=".yaml,.yml"
              aria-label={labels.fileInputLabel}
              onChange={handleFileChange}
            />
          </div>

          <p className="mt-3 text-xs text-muted-foreground">{labels.copyHint}</p>

          {sizeError && <p role="alert" className="mt-3 text-sm text-destructive">{labels.tooLarge}</p>}

          {result !== null && (
            <div className="mt-4 space-y-3">
              {result.webhookTokens !== undefined && result.webhookTokens.length > 0 && (
                <ImportedTokensSection
                  tokens={result.webhookTokens}
                  closeConfirming={closeConfirming}
                  onConfirmClose={() => { onOpenChange(false) }}
                  onCancelClose={() => { setCloseConfirming(false) }}
                />
              )}
              <ImportResultSummary result={result} />
              <ImportConflictsWarning conflicts={result.conflicts ?? []} />
            </div>
          )}

          {importError !== null && (
            <p role="alert" className="mt-3 text-sm text-destructive">{buildImportErrorMessage(importError)}</p>
          )}

          <ApplySection
            confirming={confirming}
            isPending={importMutation.isPending}
            canApply={file !== null && !sizeError}
            onApplyClick={() => { setConfirming(true) }}
            onConfirm={() => { void handleConfirmApply() }}
            onCancel={() => { setConfirming(false) }}
          />

          <div className="mt-4 flex justify-end">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm" data-testid="automation-yaml-import-close-button">
                {labels.closeButton}
              </Button>
            </DialogPrimitive.Close>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
