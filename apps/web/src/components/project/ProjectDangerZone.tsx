// 프로젝트 아카이브/아카이브 해제 danger zone 카드 — archived===true만 아카이브로 취급 (FR-PJ PR-5 Task 6)
import type { JSX } from 'react'
import { useState } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useArchiveProject, useUnarchiveProject } from '@/hooks/use-project-mutations'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 상수 (로컬)
// ─────────────────────────────────────────────────────────────────────────────

const dangerZoneLabels = {
  title: '위험 구역',
  archiveDescription: '프로젝트를 아카이브하면 목록에서 숨겨지고 읽기 전용이 됩니다.',
  unarchiveDescription: '아카이브를 해제하면 프로젝트가 다시 활성 상태가 됩니다.',
  archiveButton: '아카이브',
  archivingButton: '아카이브 처리 중...',
  unarchiveButton: '아카이브 해제',
  unarchivingButton: '해제 처리 중...',
  confirmPrompt: '이 프로젝트를 아카이브할까요?',
  confirmButton: '확인',
  cancelButton: '취소',
  archiveError: '아카이브 처리에 실패했습니다. 다시 시도해주세요.',
  unarchiveError: '아카이브 해제에 실패했습니다. 다시 시도해주세요.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectDangerZoneProps {
  /** 프로젝트 UUID 또는 key */
  readonly projectKey: string
  /**
   * 현재 아카이브 상태 — 호출부가 `project.archived === true`로 판정해 전달한다.
   * (`archived`가 `boolean | undefined`이므로 `undefined`는 활성으로 취급된 뒤 여기 전달된다)
   */
  readonly archived: boolean
  /** MANAGE_COMPONENTS 권한 여부 — false면 액션 버튼을 disabled로 게이팅한다(fail-closed) */
  readonly canManage: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 danger zone — 활성 프로젝트면 "아카이브"(인라인 확인 후 실행), 아카이브된
 * 프로젝트면 "아카이브 해제" 버튼을 보여준다.
 *
 * - 아카이브는 되돌릴 수 있는 작업이지만 프로젝트 전체를 숨기는 영향이 크므로
 *   window.confirm 대신 인라인 확인 UI를 거친다(ComponentRow/VersionRow DeleteConfirm 동형).
 * - 아카이브 해제는 즉시 실행(위험도가 낮은 복구 동작).
 * - canManage=false면 두 액션 모두 disabled(fail-closed).
 */
export function ProjectDangerZone({
  projectKey,
  archived,
  canManage,
}: ProjectDangerZoneProps): JSX.Element {
  const archiveMutation = useArchiveProject()
  const unarchiveMutation = useUnarchiveProject()
  const [confirming, setConfirming] = useState(false)

  const isPending = archiveMutation.isPending || unarchiveMutation.isPending

  function handleArchiveClick(): void {
    setConfirming(true)
  }

  function handleArchiveCancel(): void {
    setConfirming(false)
  }

  function handleArchiveConfirm(): void {
    archiveMutation.mutate(projectKey, { onSuccess: () => { setConfirming(false) } })
  }

  function handleUnarchiveClick(): void {
    unarchiveMutation.mutate(projectKey)
  }

  return (
    <Card className="border-destructive/40">
      <CardHeader>
        <CardTitle className="text-destructive">{dangerZoneLabels.title}</CardTitle>
        <CardDescription>
          {archived ? dangerZoneLabels.unarchiveDescription : dangerZoneLabels.archiveDescription}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {archiveMutation.isError && (
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {dangerZoneLabels.archiveError}
          </p>
        )}
        {unarchiveMutation.isError && (
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {dangerZoneLabels.unarchiveError}
          </p>
        )}

        {archived ? (
          <Button variant="outline" disabled={!canManage || isPending} onClick={handleUnarchiveClick}>
            {unarchiveMutation.isPending
              ? dangerZoneLabels.unarchivingButton
              : dangerZoneLabels.unarchiveButton}
          </Button>
        ) : confirming ? (
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">{dangerZoneLabels.confirmPrompt}</span>
            <Button
              variant="destructive"
              size="sm"
              disabled={isPending}
              onClick={handleArchiveConfirm}
            >
              {archiveMutation.isPending ? dangerZoneLabels.archivingButton : dangerZoneLabels.confirmButton}
            </Button>
            <Button variant="outline" size="sm" disabled={isPending} onClick={handleArchiveCancel}>
              {dangerZoneLabels.cancelButton}
            </Button>
          </div>
        ) : (
          <Button variant="destructive" disabled={!canManage || isPending} onClick={handleArchiveClick}>
            {dangerZoneLabels.archiveButton}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}
