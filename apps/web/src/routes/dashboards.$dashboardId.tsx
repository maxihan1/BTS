// 대시보드 상세 라우트 — 그리드 편집, 타일 CRUD, OCC 409, 권한 게이팅, 가젯 추가 (FR-DB-01 Task 8 / FR-DB-02 Task 7)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { Plus, Settings, Trash2 } from 'lucide-react'
import { useAuthUser } from '@/auth/authStore'
import { useDashboard, useUpdateDashboard, useDeleteDashboard } from '@/hooks/use-dashboards'
import { canEditDashboard } from '@/lib/dashboard-permission'
import { parseLayout, serializeLayout } from '@/lib/dashboard-layout'
import type { DashboardTile } from '@/lib/dashboard-layout'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { DashboardGrid } from '@/components/dashboard/DashboardGrid'
import { DashboardForm } from '@/components/dashboard/DashboardForm'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { GadgetCatalogModal } from '@/components/dashboard/GadgetCatalogModal'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardDetailPage Props — 라우터 비의존 */
export interface DashboardDetailPageProps {
  /** 대시보드 UUID */
  dashboardId: string
  /** 현재 로그인 사용자 ID (권한 게이팅용) */
  currentUserId: string | null | undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 상세 로딩 스켈레톤.
 */
function DashboardDetailSkeleton(): JSX.Element {
  return (
    <div className="p-6 space-y-4" role="status" aria-busy="true">
      <p className="text-sm text-muted-foreground">{dashboardLabels.list.loading}</p>
      <div className="h-7 w-48 rounded bg-muted animate-pulse" aria-hidden="true" />
      <div className="h-4 w-32 rounded bg-muted animate-pulse" aria-hidden="true" />
      <div className="h-64 rounded-lg bg-muted animate-pulse" aria-hidden="true" />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 404 / 에러 표시
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드를 찾을 수 없을 때 렌더.
 */
function DashboardNotFound(): JSX.Element {
  return (
    <div className="p-6 text-center" role="alert">
      <p className="text-lg font-medium text-muted-foreground">
        대시보드를 찾을 수 없습니다.
      </p>
      <p className="text-sm text-muted-foreground mt-1">
        삭제되었거나 접근 권한이 없을 수 있습니다.
      </p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 설정 모달
// ─────────────────────────────────────────────────────────────────────────────

interface SettingsModalProps {
  /** 모달 열림 여부 */
  open: boolean
  /** 닫기 콜백 */
  onClose: () => void
  /** DashboardForm 초기값 */
  initialValues: {
    name: string
    description?: string
    visibility: string
    sharedUserIds: string[]
    version: number
  }
  /** 제출 콜백 */
  onSubmit: (payload: Record<string, unknown>) => void
  /** 제출 중 여부 */
  isPending: boolean
}

/**
 * 대시보드 설정 편집 모달.
 * DashboardForm을 감싸서 다이얼로그 형태로 표시한다.
 */
function SettingsModal({
  open,
  onClose,
  initialValues,
  onSubmit,
  isPending,
}: SettingsModalProps): JSX.Element | null {
  if (!open) return null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="대시보드 설정"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
    >
      <div className="bg-background rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">대시보드 설정</h2>
          <button
            type="button"
            aria-label="설정 닫기"
            className="rounded p-1 hover:bg-muted"
            onClick={onClose}
          >
            ✕
          </button>
        </div>
        <DashboardForm
          mode="edit"
          initialValues={initialValues}
          onSubmit={onSubmit}
          isPending={isPending}
        />
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardDetailPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 상세 페이지 (라우터 비의존 순수 컴포넌트).
 *
 * 상태.
 * - tiles: parseLayout(dashboard.layout) 초기값. 타일 추가/삭제/순서 변경 시 로컬 업데이트.
 * - dirty: tiles가 서버 layout과 다르면 true. 저장 버튼 강조.
 * - settingsOpen: 설정 모달 열림 여부.
 *
 * 권한.
 * - canEditDashboard(dashboard, currentUserId)로 판정.
 * - 비소유자: 편집 UI 전부 숨김 (위젯 추가/삭제/편집/저장/설정 버튼).
 *
 * OCC 409.
 * - onError 시 toast.error만 호출. invalidate/refetch 금지 (로컬 tiles 보존).
 *
 * 손상 layout.
 * - parseLayout이 throw 없이 [] 반환하므로 자동으로 빈 폴백 (EC6).
 *
 * @param dashboardId 대시보드 UUID
 * @param currentUserId 현재 로그인 사용자 ID
 */
export function DashboardDetailPage({
  dashboardId,
  currentUserId,
}: DashboardDetailPageProps): JSX.Element {
  const navigate = useNavigate()
  const { data: dashboard, isLoading, isError } = useDashboard(dashboardId)
  const { mutateAsync, isPending: isSaving } = useUpdateDashboard()
  const { mutateAsync: deleteMutateAsync, isPending: isDeleting } = useDeleteDashboard()

  const [tiles, setTiles] = useState<DashboardTile[]>([])
  const [dirty, setDirty] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)

  /** dashboard.layout이 변경될 때마다 로컬 tiles를 초기화 (key prop 재마운트 불필요 — layout 변경 감지) */
  useEffect(() => {
    if (dashboard !== undefined) {
      setTiles(parseLayout(dashboard.layout))
      setDirty(false)
    }
  }, [dashboard])

  if (isLoading) {
    return <DashboardDetailSkeleton />
  }

  if (isError || dashboard === undefined) {
    return <DashboardNotFound />
  }

  const editable = canEditDashboard(dashboard, currentUserId)
  /** 현재 서버 버전 — OCC 잠금에 사용. closures에서 직접 접근하도록 지역 변수로 캡처 */
  const serverVersion: number = dashboard.version

  // ─── 타일 조작 핸들러 ───────────────────────────────────────────────────

  /**
   * 가젯 추가 — GadgetCatalogModal의 onAdd 콜백에서 호출.
   * gadgetType/config를 포함한 가젯 타일을 생성한다.
   * 가젯 기본 크기: w=4, h=3 (legacy 위젯 6×4보다 작은 표준 가젯 크기).
   */
  function handleAddGadgetTile(partial: { gadgetType: string; config: Record<string, unknown> }): void {
    setTiles((prev) => {
      const maxBottom = prev.reduce((acc, tile) => Math.max(acc, tile.y + tile.h), 0)
      const newTile: DashboardTile = {
        i: crypto.randomUUID(),
        x: 0,
        y: maxBottom,
        w: 4,
        h: 3,
        title: '',
        gadgetType: partial.gadgetType,
        config: partial.config,
      }
      setDirty(true)
      return [...prev, newTile]
    })
    setCatalogOpen(false)
  }

  /** 타일 삭제 */
  function handleDeleteTile(id: string): void {
    setTiles((prev) => {
      const next = prev.filter((t) => t.i !== id)
      setDirty(true)
      return next
    })
  }

  /** 레이아웃 변경 (드래그/리사이즈) */
  function handleLayoutChange(nextTiles: DashboardTile[]): void {
    setTiles(nextTiles)
    setDirty(true)
  }

  /** 제목 인라인 편집 완료 */
  function handleEditTitle(id: string, title: string): void {
    setTiles((prev) =>
      prev.map((t) => (t.i === id ? { ...t, title } : t)),
    )
    setDirty(true)
  }

  // ─── 저장 핸들러 ────────────────────────────────────────────────────────

  /** 레이아웃 저장 (PATCH layout+version) */
  async function handleSave(): Promise<void> {
    try {
      await mutateAsync({
        id: dashboardId,
        body: {
          layout: serializeLayout(tiles),
          version: serverVersion,
        },
      })
      setDirty(false)
      toast.success('대시보드가 저장되었습니다.')
    } catch (err: unknown) {
      const status = (err as { status?: number })?.status
      if (status === 409) {
        toast.error('다른 사용자가 대시보드를 변경했습니다. 충돌을 해결한 후 다시 시도하세요.')
        // ★ OCC 409 onError: invalidate/refetch 금지 — 로컬 tiles 보존 (Task 8 EC4/S8)
        return
      }
      toast.error('저장 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.')
    }
  }

  // ─── 설정 저장 핸들러 ───────────────────────────────────────────────────

  /** 설정 폼 제출 (이름/공개범위 등 변경).
   * ★ layout을 포함해야 invalidate→refetch 시 로컬 tiles 데이터 손실이 발생하지 않는다.
   * (codereview 2번 — Option A: 설정 PATCH에 현재 로컬 layout 함께 전송)
   */
  async function handleSettingsSubmit(payload: Record<string, unknown>): Promise<void> {
    try {
      await mutateAsync({
        id: dashboardId,
        body: {
          ...payload,
          layout: serializeLayout(tiles),
          version: serverVersion,
        } as Parameters<typeof mutateAsync>[0]['body'],
      })
      setSettingsOpen(false)
      toast.success('설정이 저장되었습니다.')
    } catch (err: unknown) {
      const status = (err as { status?: number })?.status
      if (status === 409) {
        toast.error('다른 사용자가 대시보드를 변경했습니다. 다시 시도하세요.')
        return
      }
      toast.error('설정 저장 중 오류가 발생했습니다.')
    }
  }

  // ─── 삭제 핸들러 ────────────────────────────────────────────────────────

  /** 삭제 확인 → API 호출 → 목록으로 이동 */
  async function handleDeleteConfirm(): Promise<void> {
    try {
      await deleteMutateAsync(dashboardId)
      toast.success('대시보드가 삭제되었습니다.')
      await navigate({ to: '/dashboards' })
    } catch {
      toast.error('삭제 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.')
    }
  }

  // ─── 렌더 ───────────────────────────────────────────────────────────────

  return (
    <div className="flex flex-col h-full">
      {/* 상단 헤더 */}
      <div className="flex items-center justify-between px-6 py-4 border-b">
        <div className="flex items-center gap-3 min-w-0">
          <h1 className="text-xl font-semibold truncate">{dashboard.name}</h1>
          <FavoriteButton targetType="DASHBOARD" targetId={dashboardId} />
          {dirty && editable && (
            <span
              className="text-xs text-amber-600 font-medium shrink-0"
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
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded-md bg-destructive px-3 py-1.5 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors min-h-[44px] disabled:opacity-50"
                  aria-label={dashboardLabels.detail.confirmDeleteAriaLabel}
                  disabled={isDeleting}
                  onClick={handleDeleteConfirm}
                >
                  {dashboardLabels.detail.confirmButton}
                </button>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted transition-colors min-h-[44px] disabled:opacity-50"
                  aria-label={dashboardLabels.detail.cancelDeleteAriaLabel}
                  disabled={isDeleting}
                  onClick={() => setShowDeleteConfirm(false)}
                >
                  {dashboardLabels.detail.cancelButton}
                </button>
              </>
            ) : (
              <>
                {/* 가젯 추가 버튼 — 카탈로그 모달 열기 (C4: 위젯 추가 일원화) */}
                <button
                  type="button"
                  className="inline-flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted transition-colors min-h-[44px]"
                  aria-label="가젯 추가"
                  onClick={() => setCatalogOpen(true)}
                >
                  <Plus className="h-4 w-4" aria-hidden="true" />
                  가젯 추가
                </button>

                {/* 설정 버튼 */}
                <button
                  type="button"
                  className="inline-flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted transition-colors min-h-[44px]"
                  aria-label={dashboardLabels.detail.settings}
                  onClick={() => setSettingsOpen(true)}
                >
                  <Settings className="h-4 w-4" aria-hidden="true" />
                  {dashboardLabels.detail.settings}
                </button>

                {/* 삭제 버튼 */}
                <button
                  type="button"
                  className="inline-flex items-center gap-2 rounded-md border border-destructive/50 px-3 py-1.5 text-sm font-medium text-destructive hover:bg-destructive/10 transition-colors min-h-[44px]"
                  aria-label={dashboardLabels.detail.delete}
                  onClick={() => setShowDeleteConfirm(true)}
                >
                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                  {dashboardLabels.detail.delete}
                </button>

                {/* 저장 버튼 */}
                <button
                  type="button"
                  className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors min-h-[44px] disabled:opacity-50 disabled:cursor-not-allowed"
                  aria-label={isSaving ? dashboardLabels.detail.saving : dashboardLabels.detail.save}
                  disabled={isSaving || !dirty}
                  onClick={handleSave}
                >
                  {isSaving ? dashboardLabels.detail.saving : dashboardLabels.detail.save}
                </button>
              </>
            )}
          </div>
        )}
      </div>

      {/* 그리드 영역 */}
      <div className="flex-1 overflow-auto p-4">
        <DashboardGrid
          tiles={tiles}
          canEdit={editable}
          onLayoutChange={handleLayoutChange}
          onDeleteTile={handleDeleteTile}
          onEditTitle={handleEditTitle}
          onAddTile={editable ? () => setCatalogOpen(true) : undefined}
        />
      </div>

      {/* 설정 모달 — 소유자 전용 */}
      {editable && (
        <SettingsModal
          open={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          initialValues={{
            name: dashboard.name,
            description: dashboard.description ?? undefined,
            visibility: dashboard.visibility,
            sharedUserIds: dashboard.sharedUserIds,
            version: serverVersion,
          }}
          onSubmit={handleSettingsSubmit}
          isPending={isSaving}
        />
      )}

      {/*
       * 가젯 카탈로그 모달 — 소유자 + catalogOpen일 때만 마운트.
       * 조건부 마운트로 내부 useQuery가 불필요하게 실행되지 않는다.
       * ★ GadgetCatalogModal 내부의 fetchGadgetCatalog는 catalogOpen=true 시에만 호출된다.
       */}
      {editable && catalogOpen && (
        <GadgetCatalogModal
          open={catalogOpen}
          onAdd={handleAddGadgetTile}
          onClose={() => setCatalogOpen(false)}
        />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardDetailRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TanStack Router useParams + useAuthUser를 주입해 DashboardDetailPage에 연결하는 어댑터.
 *
 * - useParams()에서 dashboardId를 추출한다.
 * - useAuthUser()에서 현재 사용자 userId를 추출한다.
 *
 * ★ router.ts 등록은 Task 9 담당. 이 컴포넌트만 export하고 라우터 파일은 건드리지 않는다.
 */
export function DashboardDetailRouteAdapter(): JSX.Element {
  const { dashboardId } = useParams({ strict: false }) as { dashboardId: string }
  const user = useAuthUser()

  return (
    <DashboardDetailPage
      dashboardId={dashboardId}
      currentUserId={user?.userId ?? null}
    />
  )
}
