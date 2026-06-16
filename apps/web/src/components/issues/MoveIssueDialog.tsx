// 이슈 이동 마법사 Dialog — 대상 프로젝트 선택 → 노드별 매핑 확인 → 이동 실행 (FR-MV-01 Task 4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { previewMove, useMoveIssue, extractMoveErrorCode, MOVE_ERROR_CODES } from '@/api/issue-move'
import type { MovePreview, SubtaskPreviewNode, WorkflowStateView } from '@/api/issue-move'
import type { Component } from '@/api/components.types'
import type { Version } from '@/api/versions.types'
import type { CustomField } from '@/api/custom-fields.types'
import { issueMoveStrings as s } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 — 노드별 매핑 상태
// ─────────────────────────────────────────────────────────────────────────────

/** 단일 노드(루트 또는 자식)의 사용자 매핑 선택 상태 */
interface NodeMappingState {
  /** 사용자가 선택한 대상 상태 키. null이면 미선택(비호환 시 invalid) */
  selectedStateKey: string | null
  /** 컴포넌트 매핑. 원본 id → 대상 id | null(제거) */
  componentMapping: Record<string, string | null>
  /** affectsVersions 매핑 */
  affectsVersionMapping: Record<string, string | null>
  /** fixVersions 매핑 */
  fixVersionMapping: Record<string, string | null>
  /** 필수 커스텀필드 값 입력 */
  customFieldValues: Record<string, string>
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 초기 NodeMappingState 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * preview 응답의 각 섹션에서 초기 매핑 상태를 생성한다.
 * autoMapping 값을 기본값으로 설정하고, compatible이면 suggestedStateKey를 초기 선택한다.
 */
function buildInitialNodeState(node: {
  workflow: MovePreview['workflow']
  components: MovePreview['components']
  affectsVersions: MovePreview['affectsVersions']
  fixVersions: MovePreview['fixVersions']
  customFields: MovePreview['customFields']
}): NodeMappingState {
  // 워크플로우 compatible이면 suggestedStateKey가 현재 상태 키 → 선택 가능
  const selectedStateKey = node.workflow.suggestedStateKey

  // autoMapping을 Record<string, string | null>로 변환
  const componentMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.components.autoMapping)) {
    componentMapping[srcId] = tgtId ?? null
  }

  const affectsVersionMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.affectsVersions.autoMapping)) {
    affectsVersionMapping[srcId] = tgtId ?? null
  }

  const fixVersionMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.fixVersions.autoMapping)) {
    fixVersionMapping[srcId] = tgtId ?? null
  }

  return {
    selectedStateKey,
    componentMapping,
    affectsVersionMapping,
    fixVersionMapping,
    customFieldValues: {},
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 노드 유효성 검사
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 노드 매핑이 완전한지 검사한다.
 * - 비호환 상태(compatible=false)이면 selectedStateKey가 있어야 한다.
 * - 필수 커스텀필드(requiredMissing)가 있으면 모두 입력되어야 한다.
 */
function isNodeMappingValid(
  nodeState: NodeMappingState,
  compatible: boolean,
  requiredMissing: CustomField[],
): boolean {
  if (!compatible && nodeState.selectedStateKey === null) return false
  for (const field of requiredMissing) {
    const val = nodeState.customFieldValues[field.key]
    if (val === undefined || val.trim() === '') return false
  }
  return true
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — NodeMappingSection
// ─────────────────────────────────────────────────────────────────────────────

interface NodeMappingSectionProps {
  /** 섹션 식별자 — 루트이면 "root", 자식이면 issueKey */
  readonly nodeId: string
  /** 노드 레이블 (루트 이슈 또는 이슈 키) */
  readonly label: string
  /** 대상 상태 목록 */
  readonly targetStates: WorkflowStateView[]
  /** 워크플로우 호환 여부 */
  readonly compatible: boolean
  /** 현재 컴포넌트 목록 */
  readonly currentComponents: Component[]
  /** 대상 컴포넌트 목록 */
  readonly targetComponents: Component[]
  /** 현재 affectsVersions 목록 */
  readonly currentAffectsVersions: Version[]
  /** 대상 affectsVersions 목록 */
  readonly targetAffectsVersions: Version[]
  /** 현재 fixVersions 목록 */
  readonly currentFixVersions: Version[]
  /** 대상 fixVersions 목록 */
  readonly targetFixVersions: Version[]
  /** 제거될 커스텀 필드 목록 */
  readonly removedFields: CustomField[]
  /** 필수이지만 값 없는 커스텀 필드 목록 */
  readonly requiredMissingFields: CustomField[]
  /** 현재 매핑 상태 */
  readonly mappingState: NodeMappingState
  /** 매핑 상태 변경 핸들러 */
  readonly onChange: (next: NodeMappingState) => void
}

/**
 * 단일 이슈 노드(루트 또는 자식)의 매핑 섹션.
 * 워크플로우 상태 select, 컴포넌트/버전 매핑 select, 필수 커스텀필드 입력을 렌더한다.
 */
function NodeMappingSection({
  nodeId,
  label,
  targetStates,
  compatible,
  currentComponents,
  targetComponents,
  currentAffectsVersions,
  targetAffectsVersions,
  currentFixVersions,
  targetFixVersions,
  removedFields,
  requiredMissingFields,
  mappingState,
  onChange,
}: NodeMappingSectionProps): JSX.Element {
  function handleStateChange(e: React.ChangeEvent<HTMLSelectElement>) {
    onChange({ ...mappingState, selectedStateKey: e.target.value || null })
  }

  function handleComponentMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      componentMapping: { ...mappingState.componentMapping, [srcId]: tgtId },
    })
  }

  function handleAffectsVersionMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      affectsVersionMapping: { ...mappingState.affectsVersionMapping, [srcId]: tgtId },
    })
  }

  function handleFixVersionMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      fixVersionMapping: { ...mappingState.fixVersionMapping, [srcId]: tgtId },
    })
  }

  function handleCustomFieldChange(key: string, value: string) {
    onChange({
      ...mappingState,
      customFieldValues: { ...mappingState.customFieldValues, [key]: value },
    })
  }

  return (
    <div
      className="border rounded-lg p-4 space-y-4"
      data-testid={`node-section-${nodeId}`}
    >
      {/* 노드 레이블 */}
      <h3 className="font-medium text-sm">{label}</h3>

      {/* 워크플로우 상태 선택 */}
      {!compatible && (
        <div>
          <p className="text-xs text-amber-600 mb-2">{s.workflowIncompatible}</p>
          <label
            htmlFor={`state-select-${nodeId}`}
            className="text-sm font-medium block mb-1"
          >
            {s.targetStateLabel}
          </label>
          <select
            id={`state-select-${nodeId}`}
            aria-label={s.targetStateLabel}
            role="combobox"
            value={mappingState.selectedStateKey ?? ''}
            onChange={handleStateChange}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm"
          >
            <option value="">-- 선택 --</option>
            {targetStates.map((st) => (
              <option key={st.key} value={st.key}>
                {st.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* 호환일 때도 상태 변경 가능 */}
      {compatible && targetStates.length > 0 && (
        <div>
          <label
            htmlFor={`state-select-${nodeId}`}
            className="text-sm font-medium block mb-1"
          >
            {s.targetStateLabel}
          </label>
          <select
            id={`state-select-${nodeId}`}
            aria-label={s.targetStateLabel}
            role="combobox"
            value={mappingState.selectedStateKey ?? ''}
            onChange={handleStateChange}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm"
          >
            {targetStates.map((st) => (
              <option key={st.key} value={st.key}>
                {st.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* 컴포넌트 매핑 */}
      {currentComponents.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.componentMappingTitle}</p>
          {currentComponents.map((comp) => (
            <div key={comp.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{comp.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${comp.name} 컴포넌트 매핑`}
                value={mappingState.componentMapping[comp.id] ?? ''}
                onChange={(e) =>
                  handleComponentMappingChange(comp.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetComponents.map((tc) => (
                  <option key={tc.id} value={tc.id}>
                    {tc.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* affectsVersions 매핑 */}
      {currentAffectsVersions.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.affectsVersionMappingTitle}</p>
          {currentAffectsVersions.map((ver) => (
            <div key={ver.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{ver.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${ver.name} 영향 버전 매핑`}
                value={mappingState.affectsVersionMapping[ver.id] ?? ''}
                onChange={(e) =>
                  handleAffectsVersionMappingChange(ver.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetAffectsVersions.map((tv) => (
                  <option key={tv.id} value={tv.id}>
                    {tv.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* fixVersions 매핑 */}
      {currentFixVersions.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.fixVersionMappingTitle}</p>
          {currentFixVersions.map((ver) => (
            <div key={ver.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{ver.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${ver.name} 수정 버전 매핑`}
                value={mappingState.fixVersionMapping[ver.id] ?? ''}
                onChange={(e) =>
                  handleFixVersionMappingChange(ver.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetFixVersions.map((tv) => (
                  <option key={tv.id} value={tv.id}>
                    {tv.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* 제거될 커스텀 필드 안내 */}
      {removedFields.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-1 text-destructive">{s.removedFieldsLabel}</p>
          <ul className="text-xs text-muted-foreground list-disc list-inside">
            {removedFields.map((f) => (
              <li key={f.key}>{f.name}</li>
            ))}
          </ul>
        </div>
      )}

      {/* 필수 커스텀 필드 입력 */}
      {requiredMissingFields.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.requiredMissingLabel}</p>
          {requiredMissingFields.map((f) => (
            <div key={f.key} className="mb-2">
              <label
                htmlFor={`cf-${nodeId}-${f.key}`}
                className="text-sm font-medium block mb-1"
              >
                {f.name}
              </label>
              <Input
                id={`cf-${nodeId}-${f.key}`}
                aria-label={f.name}
                value={mappingState.customFieldValues[f.key] ?? ''}
                onChange={(e) => handleCustomFieldChange(f.key, e.target.value)}
                placeholder={f.name}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface MoveIssueDialogProps {
  /** 이동할 이슈 키 */
  readonly issueKey: string
  /** 이슈 현재 OCC 버전 */
  readonly issueVersion: number
  /** Dialog 열림 여부 */
  readonly open: boolean
  /** Dialog 열림 상태 변경 핸들러 */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트 — MoveIssueDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 마법사 Dialog.
 *
 * Step 1: 대상 프로젝트 키 직접 입력 (목록 API 부재, FR-LK-01 선례).
 * Step 2: preview 응답 기반 노드별(루트+subtasks) 매핑 섹션 표시.
 *   - 비호환 상태 select (compatible=false 시)
 *   - 컴포넌트/버전 autoMapping 기본값 + select 또는 제거
 *   - 필수 커스텀필드 입력
 * Step 3(실행): move 호출 → 성공 시 새 키로 navigate + toast.
 *
 * Dialog는 radix-ui Dialog 직접 사용 (AddMemberDialog 선례).
 * 의존성 추가 없음 (절대규칙 #17).
 */
export function MoveIssueDialog({
  issueKey,
  issueVersion,
  open,
  onOpenChange,
}: MoveIssueDialogProps): JSX.Element {
  const navigate = useNavigate()

  // ── 단계 상태 ─────────────────────────────────────────────────────────
  /** 현재 마법사 단계. 1=대상 선택, 2=매핑 확인 */
  const [step, setStep] = useState<1 | 2>(1)
  const [targetProjectKey, setTargetProjectKey] = useState('')
  const [isPreviewLoading, setIsPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [preview, setPreview] = useState<MovePreview | null>(null)

  // ── 노드별 매핑 상태 ──────────────────────────────────────────────────
  /** 루트 이슈 매핑 상태 */
  const [rootMapping, setRootMapping] = useState<NodeMappingState | null>(null)
  /** 자식별 매핑 상태. key = issueKey */
  const [subtaskMappings, setSubtaskMappings] = useState<Record<string, NodeMappingState>>({})

  const moveMutation = useMoveIssue(issueKey)

  // ── Dialog 닫힘 시 상태 초기화 ────────────────────────────────────────
  function handleOpenChange(next: boolean) {
    if (!next) {
      setStep(1)
      setTargetProjectKey('')
      setPreviewError(null)
      setPreview(null)
      setRootMapping(null)
      setSubtaskMappings({})
    }
    onOpenChange(next)
  }

  // ── Step 1 → Step 2: preview 호출 ────────────────────────────────────
  async function handleNext() {
    if (targetProjectKey.trim() === '') return
    setIsPreviewLoading(true)
    setPreviewError(null)
    try {
      const result = await previewMove(issueKey, targetProjectKey.trim())
      setPreview(result)

      // 루트 초기 매핑 상태
      setRootMapping(buildInitialNodeState(result))

      // 자식별 초기 매핑 상태
      const childMappings: Record<string, NodeMappingState> = {}
      for (const child of result.subtasks) {
        childMappings[child.issueKey] = buildInitialNodeState(child)
      }
      setSubtaskMappings(childMappings)

      setStep(2)
    } catch {
      setPreviewError(s.errorPreview)
    } finally {
      setIsPreviewLoading(false)
    }
  }

  // ── Step 2: 이동 유효성 검사 ─────────────────────────────────────────
  function isMoveEnabled(): boolean {
    if (preview === null || rootMapping === null) return false

    // 루트 유효성
    if (!isNodeMappingValid(
      rootMapping,
      preview.workflow.compatible,
      preview.customFields.requiredMissing,
    )) return false

    // 자식 유효성
    for (const child of preview.subtasks) {
      const childMapping = subtaskMappings[child.issueKey]
      if (childMapping === undefined) return false
      if (!isNodeMappingValid(
        childMapping,
        child.workflow.compatible,
        child.customFields.requiredMissing,
      )) return false
    }

    return true
  }

  // ── Step 2: move 실행 ────────────────────────────────────────────────
  function handleMove() {
    if (preview === null || rootMapping === null) return

    // 루트 targetStateKey 결정
    const rootStateKey = rootMapping.selectedStateKey

    // 루트 targetStateIsDone — 선택한 상태의 isDone
    const rootStateDef = preview.workflow.targetStates.find(
      (st) => st.key === rootStateKey,
    )
    const rootStateIsDone = rootStateDef?.isDone ?? false

    // 자식 매핑 목록 구성
    const subtasksPayload = preview.subtasks.map((child: SubtaskPreviewNode) => {
      const childMapping = subtaskMappings[child.issueKey]
      const childStateKey = childMapping?.selectedStateKey ?? null
      const childStateDef = child.workflow.targetStates.find(
        (st) => st.key === childStateKey,
      )
      const childStateIsDone = childStateDef?.isDone ?? false

      return {
        issueKey: child.issueKey,
        expectedVersion: child.version,
        targetStateKey: childStateKey,
        targetStateIsDone: childStateIsDone,
        componentMapping: childMapping?.componentMapping ?? {},
        affectsVersionMapping: childMapping?.affectsVersionMapping ?? {},
        fixVersionMapping: childMapping?.fixVersionMapping ?? {},
        customFieldValues: childMapping?.customFieldValues ?? {},
      }
    })

    moveMutation.mutate(
      {
        targetProjectKey: targetProjectKey.trim(),
        expectedVersion: issueVersion,
        targetStateKey: rootStateKey,
        targetStateIsDone: rootStateIsDone,
        componentMapping: rootMapping.componentMapping,
        affectsVersionMapping: rootMapping.affectsVersionMapping,
        fixVersionMapping: rootMapping.fixVersionMapping,
        customFieldValues: rootMapping.customFieldValues,
        subtasks: subtasksPayload,
      },
      {
        onSuccess: (data) => {
          const totalMoved = 1 + data.movedSubtasks.length
          if (data.movedSubtasks.length > 0) {
            toast.success(s.moveSuccessWithSubtasksToast(totalMoved))
          } else {
            toast.success(s.moveSuccessToast)
          }
          handleOpenChange(false)
          void navigate({
            to: '/issues/$key' as string,
            params: { key: data.issueKey },
            replace: true,
          })
        },
        onError: (err) => {
          const code = extractMoveErrorCode(err)
          if (code === MOVE_ERROR_CODES.MOVE_SAME_PROJECT) {
            toast.error(s.errorSameProject)
          } else if (code === MOVE_ERROR_CODES.PROJECT_NOT_FOUND) {
            toast.error(s.errorProjectNotFound)
          } else if (code === MOVE_ERROR_CODES.VERSION_CONFLICT) {
            toast.error(s.errorVersionConflict)
          } else if (code === MOVE_ERROR_CODES.INVALID_TARGET_STATE) {
            toast.error(s.errorInvalidTargetState)
          } else if (code === MOVE_ERROR_CODES.SUBTASK_HAS_OWN_SUBTASKS) {
            toast.error(s.errorSubtaskHasOwnSubtasks)
          } else {
            const status = err instanceof Error ? (err as { status?: number }).status : undefined
            if (status === 403) {
              toast.error(s.errorForbidden)
            } else {
              toast.error(s.errorDefault)
            }
          }
        },
      },
    )
  }

  // ── 렌더 ──────────────────────────────────────────────────────────────
  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out max-h-[90vh] overflow-y-auto"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            {s.dialogTitle}
          </DialogPrimitive.Title>

          {/* ── Step 1 — 대상 프로젝트 키 입력 ── */}
          {step === 1 && (
            <div className="space-y-4">
              <h2 className="text-sm font-medium text-muted-foreground">{s.step1Title}</h2>
              <div>
                <label
                  htmlFor="move-target-project-key"
                  className="text-sm font-medium block mb-1"
                >
                  {s.targetProjectKeyLabel}
                </label>
                <Input
                  id="move-target-project-key"
                  aria-label={s.targetProjectKeyLabel}
                  value={targetProjectKey}
                  onChange={(e) => setTargetProjectKey(e.target.value)}
                  placeholder={s.targetProjectKeyPlaceholder}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && targetProjectKey.trim() !== '') {
                      void handleNext()
                    }
                  }}
                />
              </div>

              {previewError !== null && (
                <p className="text-sm text-destructive" role="alert">
                  {previewError}
                </p>
              )}

              {isPreviewLoading && (
                <p className="text-sm text-muted-foreground">{s.previewLoading}</p>
              )}

              <div className="flex justify-end gap-2 mt-4">
                <DialogPrimitive.Close asChild>
                  <Button variant="outline" size="sm">
                    {s.cancelButton}
                  </Button>
                </DialogPrimitive.Close>
                <Button
                  size="sm"
                  disabled={targetProjectKey.trim() === '' || isPreviewLoading}
                  onClick={() => { void handleNext() }}
                >
                  {s.nextButton}
                </Button>
              </div>
            </div>
          )}

          {/* ── Step 2 — 매핑 확인 ── */}
          {step === 2 && preview !== null && rootMapping !== null && (
            <div className="space-y-4">
              <h2 className="text-sm font-medium text-muted-foreground">{s.step2Title}</h2>

              {/* 루트 이슈 섹션 */}
              <NodeMappingSection
                nodeId="root"
                label={s.rootIssueSectionHeader}
                targetStates={preview.workflow.targetStates}
                compatible={preview.workflow.compatible}
                currentComponents={preview.components.current}
                targetComponents={preview.components.target}
                currentAffectsVersions={preview.affectsVersions.current}
                targetAffectsVersions={preview.affectsVersions.target}
                currentFixVersions={preview.fixVersions.current}
                targetFixVersions={preview.fixVersions.target}
                removedFields={preview.customFields.removed}
                requiredMissingFields={preview.customFields.requiredMissing}
                mappingState={rootMapping}
                onChange={setRootMapping}
              />

              {/* 서브태스크 섹션 */}
              {preview.subtasks.length > 0 && (
                <div>
                  <p className="text-xs font-medium mb-2">{s.subtaskSectionHeader}</p>
                  {preview.subtasks.map((child: SubtaskPreviewNode) => {
                    const childState = subtaskMappings[child.issueKey]
                    if (childState === undefined) return null
                    return (
                      <NodeMappingSection
                        key={child.issueKey}
                        nodeId={child.issueKey}
                        label={child.issueKey}
                        targetStates={child.workflow.targetStates}
                        compatible={child.workflow.compatible}
                        currentComponents={child.components.current}
                        targetComponents={child.components.target}
                        currentAffectsVersions={child.affectsVersions.current}
                        targetAffectsVersions={child.affectsVersions.target}
                        currentFixVersions={child.fixVersions.current}
                        targetFixVersions={child.fixVersions.target}
                        removedFields={child.customFields.removed}
                        requiredMissingFields={child.customFields.requiredMissing}
                        mappingState={childState}
                        onChange={(next) =>
                          setSubtaskMappings((prev) => ({ ...prev, [child.issueKey]: next }))
                        }
                      />
                    )
                  })}
                </div>
              )}

              <div className="flex justify-end gap-2 mt-4">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setStep(1)}
                >
                  {s.backButton}
                </Button>
                <Button
                  size="sm"
                  disabled={!isMoveEnabled() || moveMutation.isPending}
                  onClick={handleMove}
                >
                  {s.moveButton}
                </Button>
              </div>
            </div>
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
