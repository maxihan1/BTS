// 대시보드 상세 상단 헤더 — 이름·즐겨찾기·미저장 표시 + 소유자 전용 액션 버튼 그룹
//
// ★라우트에서 **그대로** 떼어 온 것이다. `lint-ratchet` R4 가 `DashboardDetailPage` 를 332줄로
//   얼려 뒀는데 보기/편집 모드(JD-1)가 391줄로 키웠고, 베이스라인 정책이 「늘렸으면 쪼개라」다
//   (`lint-ratchet-baseline.ts` 머리말 · `IssueMetaPanel` 317→333 을 추출로 298 로 내린 선례).
//
// ★DOM 을 한 글자도 바꾸지 않았다. 바깥 `<div>` 의 class 문자열이 e2e 의 헤더 스코프
//   (`dashboard-gadgets.spec.ts` 의 `headerOf()`)이자 시각 계약이다 — 여기서 정리욕을 내면
//   추출이 리팩터가 아니라 회귀가 된다.
import type { JSX } from 'react'
import { Check, Pencil, Plus, Settings, Trash2, Share2 } from 'lucide-react'
import { dashboardLabels, dashboardModeLabels } from '@/i18n/dashboard-labels'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { Button } from '@/components/ui/button'

interface DashboardDetailHeaderProps {
  /** 대시보드 UUID — 즐겨찾기 토글 대상 */
  readonly dashboardId: string
  /** 화면에 낼 대시보드 이름 */
  readonly name: string
  /** 저장 안 된 변경이 있나 */
  readonly dirty: boolean
  /** 편집 **권한**이 있나. 모드와 다른 축이다 */
  readonly editable: boolean
  /** 편집 **모드**인가 (JD-1) */
  readonly isEditing: boolean
  /** 삭제 인라인 확인 UI 가 열렸나 */
  readonly showDeleteConfirm: boolean
  /** 삭제 요청 진행 중 */
  readonly isDeleting: boolean
  /** 저장 요청 진행 중 */
  readonly isSaving: boolean
  readonly onToggleEditing: () => void
  readonly onOpenCatalog: () => void
  readonly onOpenShare: () => void
  readonly onOpenSettings: () => void
  readonly onRequestDelete: () => void
  readonly onCancelDelete: () => void
  readonly onConfirmDelete: () => void
  readonly onSave: () => void
}

/** 상세 화면 상단 헤더. 상태는 전부 부모가 쥐고 여기는 그리기만 한다. */
export function DashboardDetailHeader({
  dashboardId,
  name,
  dirty,
  editable,
  isEditing,
  showDeleteConfirm,
  isDeleting,
  isSaving,
  onToggleEditing,
  onOpenCatalog,
  onOpenShare,
  onOpenSettings,
  onRequestDelete,
  onCancelDelete,
  onConfirmDelete,
  onSave,
}: DashboardDetailHeaderProps): JSX.Element {
  return (
    <div className="flex items-center justify-between px-6 py-4 border-b">
      <div className="flex items-center gap-3 min-w-0">
        <h1 className="text-xl font-semibold truncate">{name}</h1>
        <FavoriteButton targetType="DASHBOARD" targetId={dashboardId} />
        {dirty && editable && (
          <span
            className="text-xs text-warning-text font-medium shrink-0"
            role="status"
            aria-live="polite"
          >
            {dashboardLabels.detail.unsavedChanges}
          </span>
        )}
      </div>

      {/* 소유자 전용 액션 버튼 그룹 */}
      {editable && (
        <div className="flex items-center gap-2 shrink-0">
          {/* 삭제 인라인 확인 UI — VersionRow/ComponentRow 동형 패턴 */}
          {showDeleteConfirm ? (
            <>
              <span className="text-sm text-muted-foreground">{dashboardLabels.detail.deleteConfirm}</span>
              {/* PR22 — 원본이 bg-destructive 솔리드라 variant="destructive"(연한 배경)와 다르다.
                  className 으로 솔리드를 유지해 삭제 확인의 강조 의도를 보존한다. */}
              <Button
                type="button"
                variant="destructive"
                size="default"
                className="min-h-[44px] gap-1 rounded-md bg-destructive px-3 text-destructive-foreground hover:bg-destructive/90"
                aria-label={dashboardLabels.detail.confirmDeleteAriaLabel}
                disabled={isDeleting}
                onClick={onConfirmDelete}
              >
                {dashboardLabels.detail.confirmButton}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="default"
                className="min-h-[44px] gap-1 rounded-md px-3"
                aria-label={dashboardLabels.detail.cancelDeleteAriaLabel}
                disabled={isDeleting}
                onClick={onCancelDelete}
              >
                {dashboardLabels.detail.cancelButton}
              </Button>
            </>
          ) : (
            <>
              {/*
               * 보기 ↔ 편집 모드 토글 (Jira 패리티 JD-1).
               *
               * ★권한이 없으면 이 버튼 자체가 없다 — 이 블록 전체가 `editable` 분기 안이라
               *   모드로 진입할 수단이 아예 생기지 않는다. 권한과 모드는 다른 축이고,
               *   권한이 없는 사람에게 「편집」 버튼을 보여주고 눌렀을 때 막는 것은
               *   goodwill 을 깎는 설계다.
               */}
              <Button
                type="button"
                variant={isEditing ? 'default' : 'outline'}
                size="default"
                className="min-h-[44px] gap-2 rounded-md px-3"
                aria-label={
                  isEditing ? dashboardModeLabels.exitEdit : dashboardModeLabels.enterEdit
                }
                aria-pressed={isEditing}
                onClick={onToggleEditing}
              >
                {isEditing ? (
                  <Check className="h-4 w-4" aria-hidden="true" />
                ) : (
                  <Pencil className="h-4 w-4" aria-hidden="true" />
                )}
                {isEditing ? dashboardModeLabels.exitEdit : dashboardModeLabels.enterEdit}
              </Button>

              {/* 가젯 추가 버튼 — 편집 모드에서만 (C4: 위젯 추가 일원화) */}
              {isEditing && (
                <Button
                  type="button"
                  variant="outline"
                  size="default"
                  className="min-h-[44px] gap-2 rounded-md px-3"
                  aria-label="가젯 추가"
                  onClick={onOpenCatalog}
                >
                  <Plus className="h-4 w-4" aria-hidden="true" />
                  가젯 추가
                </Button>
              )}

              {/* 공유 버튼 — 소유자 전용 (FR-8), 공유 모달을 조건부 마운트 */}
              <Button
                type="button"
                variant="outline"
                size="default"
                className="min-h-[44px] gap-2 rounded-md px-3"
                aria-label={dashboardLabels.share.modalTitle}
                onClick={onOpenShare}
              >
                <Share2 className="h-4 w-4" aria-hidden="true" />
                {dashboardLabels.share.modalTitle}
              </Button>

              {/* 설정 버튼 */}
              <Button
                type="button"
                variant="outline"
                size="default"
                className="min-h-[44px] gap-2 rounded-md px-3"
                aria-label={dashboardLabels.detail.settings}
                onClick={onOpenSettings}
              >
                <Settings className="h-4 w-4" aria-hidden="true" />
                {dashboardLabels.detail.settings}
              </Button>

              {/* 삭제 버튼 */}
              <Button
                type="button"
                variant="outline"
                size="default"
                className="min-h-[44px] gap-2 rounded-md border-destructive/50 px-3 text-destructive hover:bg-destructive/10"
                aria-label={dashboardLabels.detail.delete}
                onClick={onRequestDelete}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
                {dashboardLabels.detail.delete}
              </Button>

              {/* 저장 버튼 */}
              <Button
                type="button"
                variant="default"
                size="default"
                className="min-h-[44px] gap-2 rounded-md px-4 hover:bg-primary/90 disabled:cursor-not-allowed"
                aria-label={isSaving ? dashboardLabels.detail.saving : dashboardLabels.detail.save}
                disabled={isSaving || !dirty}
                onClick={onSave}
              >
                {isSaving ? dashboardLabels.detail.saving : dashboardLabels.detail.save}
              </Button>
            </>
          )}
        </div>
      )}
    </div>
  )
}
