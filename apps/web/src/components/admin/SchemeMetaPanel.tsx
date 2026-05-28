// 워크플로우 스킴 우 메타 패널 — name/description 편집 + 삭제 버튼 + 표준 보호
import type { JSX } from 'react'
import { useState, useId } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useUpdateWorkflowScheme, useDeleteWorkflowScheme } from '@/hooks/use-workflow-schemes'
import { SchemeInUseModal } from './SchemeInUseModal'
import { WorkflowSchemeApiError } from '@/api/workflow-schemes'
import type { SchemeDetailResponse } from '@/api/workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 표준 스킴 안내 메시지 */
const STANDARD_SCHEME_NOTICE = '표준 스킴 — 키/이름 변경 + 삭제 불가. 매핑만 자유 변경 가능'

/** 표준 스킴 name/description disabled 이유 tooltip */
const STANDARD_FIELD_TOOLTIP = '표준 스킴은 키/이름/설명 변경 불가'

/** 표준 스킴 삭제 disabled 이유 tooltip */
const STANDARD_DELETE_TOOLTIP = '표준 스킴은 삭제 불가'

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** SchemeMetaPanel 컴포넌트 props */
export interface SchemeMetaPanelProps {
  /** 스킴 상세 응답 */
  scheme: SchemeDetailResponse
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
  const [description, setDescription] = useState(scheme.description)
  const [isInUseModalOpen, setIsInUseModalOpen] = useState(false)

  const nameInputId = useId()
  const descInputId = useId()
  const standardTooltipId = useId()
  const deleteTooltipId = useId()

  const updateMutation = useUpdateWorkflowScheme(scheme.schemeKey)
  const deleteMutation = useDeleteWorkflowScheme()

  const handleSave = () => {
    updateMutation.mutate({ name, description })
  }

  const handleDelete = () => {
    deleteMutation.mutate(scheme.schemeKey, {
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
          className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800 dark:border-amber-900/50 dark:bg-amber-950/20 dark:text-amber-400"
        >
          {STANDARD_SCHEME_NOTICE}
        </div>
      )}

      {/* 스킴 키 표시 (변경 불가) */}
      <div className="space-y-1">
        <Label className="text-xs font-medium text-muted-foreground">스킴 키</Label>
        <div className="rounded-md border border-border bg-muted px-3 py-2 font-mono text-sm text-muted-foreground">
          {scheme.schemeKey}
        </div>
      </div>

      {/* 이름 편집 */}
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <Label htmlFor={nameInputId} className="text-sm font-medium">
            이름
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
          설명
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
          <div className="text-[11px] text-muted-foreground">매핑 수</div>
        </div>
        <div className="rounded-lg border border-border bg-muted/50 px-3 py-2 text-center">
          <div className="text-lg font-semibold">{scheme.usedByProjectsCount}</div>
          <div className="text-[11px] text-muted-foreground">사용 중 프로젝트</div>
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
          {updateMutation.isPending ? '저장 중...' : '저장'}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          disabled={scheme.isStandard || deleteMutation.isPending}
          onClick={handleDelete}
          aria-describedby={scheme.isStandard ? deleteTooltipId : undefined}
          title={scheme.isStandard ? STANDARD_DELETE_TOOLTIP : undefined}
        >
          {deleteMutation.isPending ? '삭제 중...' : '삭제'}
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
