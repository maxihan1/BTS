// 워크플로우 스킴 우 메타 패널 — name/description 편집 + 삭제 버튼 + 표준 보호
import type { JSX } from 'react'
import { useState, useId } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useUpdateWorkflowScheme, useDeleteWorkflowScheme } from '@/hooks/use-workflow-schemes'
import { SchemeInUseModal } from './SchemeInUseModal'
import { WorkflowSchemeApiError } from '@/api/workflow-schemes'
import type { SchemeDetail } from '@/api/workflow-schemes'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 표준 스킴 안내 메시지 */
const STANDARD_SCHEME_NOTICE = workflowSchemeLabels.standardProtect.notice

/** 표준 스킴 name/description disabled 이유 tooltip */
const STANDARD_FIELD_TOOLTIP = workflowSchemeLabels.standardProtect.fieldTooltip

/** 표준 스킴 삭제 disabled 이유 tooltip */
const STANDARD_DELETE_TOOLTIP = workflowSchemeLabels.standardProtect.deleteTooltip

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** SchemeMetaPanel 컴포넌트 props */
export interface SchemeMetaPanelProps {
  /** 스킴 상세 응답 */
  scheme: SchemeDetail
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 우 메타 패널.
 *
 * - name/description 편집 폼 + 「저장」 버튼.
 * - 「삭제」 버튼 — 성공 시 스킴 목록으로 이동, 409 SCHEME_IN_USE 시 SchemeInUseModal 표시.
 * - isStandard === true 시 name/description input + 삭제 버튼 disabled + tooltip.
 * - isStandard 시 안내 카드 표시.
 * - usedByProjectsCount / mappingsCount 표시.
 */
export function SchemeMetaPanel({ scheme }: SchemeMetaPanelProps): JSX.Element {
  const [name, setName] = useState(scheme.name)
  // 백엔드 description 은 nullable 이다(계약 스냅샷 기준). textarea 는 null 을 못 받으므로 빈 문자열로 정규화한다.
  const [description, setDescription] = useState(scheme.description ?? '')
  const [isInUseModalOpen, setIsInUseModalOpen] = useState(false)

  const nameInputId = useId()
  const descInputId = useId()
  const standardTooltipId = useId()
  const deleteTooltipId = useId()

  const updateMutation = useUpdateWorkflowScheme(scheme.key)
  const deleteMutation = useDeleteWorkflowScheme()

  const handleSave = () => {
    // ★ 위 useState 의 `?? ''` 정규화를 여기서 **되돌린다**.
    // 안 되돌리면 이름만 고쳐도 DB 의 NULL 이 '' 로 바뀐다 — 두 값은 DB 에서 구분되고
    // (`description TEXT`, V201) 백엔드 DTO 도 `String?` 라 받은 값을 그대로 저장한다.
    // 어떤 화면도 NULL 과 '' 를 다르게 그리지 않아 눈으로는 못 잡는 변질이다.
    updateMutation.mutate({ name, description: description.trim() === '' ? null : description })
  }

  const handleDelete = () => {
    deleteMutation.mutate(scheme.key, {
      onError: (error) => {
        if (error instanceof WorkflowSchemeApiError) {
          // SCHEME_IN_USE 또는 파서가 errorCode → UNKNOWN으로 변환한 경우 모두 모달로 처리
          // (백엔드가 409 응답 시 SCHEME_IN_USE 또는 SCHEME_STANDARD_NOT_DELETABLE)
          // 표준 스킴 보호는 UI 레이어에서 이미 disabled로 차단하므로 409는 SCHEME_IN_USE로 간주
          if (
            error.errorCode === 'SCHEME_IN_USE' ||
            (error.status === 409 && !scheme.isStandard)
          ) {
            setIsInUseModalOpen(true)
            return
          }
        }
        // 다른 에러(SCHEME_STANDARD_NOT_DELETABLE 등)는 useDeleteWorkflowScheme의 notifySchemeError가 처리
      },
    })
  }

  return (
    <aside className="flex w-80 shrink-0 flex-col gap-4 border-l border-border bg-background p-5">
      {/* 표준 스킴 안내 카드 */}
      {scheme.isStandard && (
        <div
          role="note"
          className="rounded-lg border border-warning bg-warning/10 px-4 py-3 text-xs text-warning-text"
        >
          {STANDARD_SCHEME_NOTICE}
        </div>
      )}

      {/* 스킴 키 표시 (변경 불가) */}
      <div className="space-y-1">
        <Label className="text-xs font-medium text-muted-foreground">{workflowSchemeLabels.metaPanel.schemeKeyLabel}</Label>
        <div className="rounded-md border border-border bg-muted px-3 py-2 font-mono text-sm text-muted-foreground">
          {scheme.key}
        </div>
      </div>

      {/* 이름 편집 */}
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <Label htmlFor={nameInputId} className="text-sm font-medium">
            {workflowSchemeLabels.metaPanel.nameLabel}
          </Label>
          {scheme.isStandard && (
            <span
              id={standardTooltipId}
              className="text-[11px] text-muted-foreground"
              title={STANDARD_FIELD_TOOLTIP}
            >
              ({STANDARD_FIELD_TOOLTIP})
            </span>
          )}
        </div>
        <Input
          id={nameInputId}
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={scheme.isStandard}
          aria-describedby={scheme.isStandard ? standardTooltipId : undefined}
          className="text-sm"
        />
      </div>

      {/* 설명 편집 */}
      <div className="space-y-1.5">
        <Label htmlFor={descInputId} className="text-sm font-medium">
          {workflowSchemeLabels.metaPanel.descriptionLabel}
        </Label>
        <textarea
          id={descInputId}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          disabled={scheme.isStandard}
          rows={3}
          aria-describedby={scheme.isStandard ? standardTooltipId : undefined}
          className="w-full resize-none rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
        />
      </div>

      {/* 통계 카드 */}
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-lg border border-border bg-muted/50 px-3 py-2 text-center">
          <div className="text-lg font-semibold">{scheme.mappingsCount}</div>
          <div className="text-[11px] text-muted-foreground">{workflowSchemeLabels.metaPanel.mappingsCountLabel}</div>
        </div>
        <div className="rounded-lg border border-border bg-muted/50 px-3 py-2 text-center">
          <div className="text-lg font-semibold">{scheme.usedByProjectsCount}</div>
          <div className="text-[11px] text-muted-foreground">{workflowSchemeLabels.metaPanel.usedByProjectsLabel}</div>
        </div>
      </div>

      {/* 액션 버튼 */}
      <div className="mt-auto flex flex-col gap-2 pt-2">
        <Button
          variant="default"
          size="sm"
          disabled={scheme.isStandard || updateMutation.isPending}
          onClick={handleSave}
        >
          {updateMutation.isPending ? workflowSchemeLabels.metaPanel.savingButton : workflowSchemeLabels.metaPanel.saveButton}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          disabled={scheme.isStandard || deleteMutation.isPending}
          onClick={handleDelete}
          aria-describedby={scheme.isStandard ? deleteTooltipId : undefined}
          title={scheme.isStandard ? STANDARD_DELETE_TOOLTIP : undefined}
        >
          {deleteMutation.isPending ? workflowSchemeLabels.metaPanel.deletingButton : workflowSchemeLabels.metaPanel.deleteButton}
        </Button>
        {scheme.isStandard && (
          <span id={deleteTooltipId} className="sr-only">
            {STANDARD_DELETE_TOOLTIP}
          </span>
        )}
      </div>

      {/* SCHEME_IN_USE 모달 */}
      <SchemeInUseModal
        isOpen={isInUseModalOpen}
        onClose={() => setIsInUseModalOpen(false)}
        usedByProjectsCount={scheme.usedByProjectsCount}
      />
    </aside>
  )
}
