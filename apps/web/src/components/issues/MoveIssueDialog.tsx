// 이슈 이동 마법사 Dialog — 대상 프로젝트 선택 → 노드별 매핑 확인 → 이동 실행 (FR-MV-01 Task 4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/api/client'
import { previewMove, useMoveIssue, extractMoveErrorCode, MOVE_ERROR_CODES } from '@/api/issue-move'
import type { MovePreview, SubtaskPreviewNode } from '@/api/issue-move'
import { issueMoveStrings as s } from '@/i18n/ko'
import {
  NodeMappingSection,
  buildInitialNodeState,
  isNodeMappingValid,
  type NodeMappingState,
} from './NodeMappingSection'

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
 * Dialog는 ui/dialog 흡수 래퍼(shadcn) 사용 — 우상단 X 닫기 버튼 공통 제공.
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
    } catch (err) {
      // 403 하나에 세 원인(대상 키 오타/미존재 · 원본 UPDATE 없음 · 대상 CREATE 없음)이
      // 합쳐진다 — `MovePreviewService.kt:185-186` 이 권한을 존재 확인(`:188` 이슈 ·
      // `:190-192` 대상 프로젝트)보다 먼저 하고, 운영 리졸버가 미존재 프로젝트를 거부로
      // 판정하기 때문이다.
      // 그래서 전용 문구를 쓰고, 403 이외(404·409·500·네트워크 단절)는 기존 문구를 유지한다.
      // 근거 전문(게이트를 안 붙이는 이유 포함). TODOS.md 「이동·임포트 진입점」 ②.
      const isForbidden = err instanceof ApiError && err.status === 403
      setPreviewError(isForbidden ? s.errorPreviewForbidden : s.errorPreview)
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
          } else if (err instanceof ApiError && err.status === 403) {
            // preview 와 같은 두 assert(`IssueMoveService.kt:189-190`)가 낸 같은 403 이다.
            // 원인 설명도 같은 어휘를 쓴다 — 옛 문구는 원인을 원본 이슈 쪽으로 단정했다.
            toast.error(s.errorForbidden)
          } else {
            toast.error(s.errorDefault)
          }
        },
      },
    )
  }

  // ── 렌더 ──────────────────────────────────────────────────────────────
  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className="max-w-lg max-h-[90vh] overflow-y-auto"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>{s.dialogTitle}</DialogTitle>
        </DialogHeader>

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
                onChange={(e) => {
                  setTargetProjectKey(e.target.value)
                  // 「키를 확인해 주세요」를 따르는 내내 그 경고가 남아 있으면
                  // 고쳤는지 아닌지를 알 수 없다. 다시 치기 시작하면 걷는다.
                  setPreviewError(null)
                }}
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

            <DialogFooter>
              <DialogClose asChild>
                <Button variant="outline" size="sm">
                  {s.cancelButton}
                </Button>
              </DialogClose>
              <Button
                size="sm"
                disabled={targetProjectKey.trim() === '' || isPreviewLoading}
                onClick={() => { void handleNext() }}
              >
                {s.nextButton}
              </Button>
            </DialogFooter>
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

            <DialogFooter>
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
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
