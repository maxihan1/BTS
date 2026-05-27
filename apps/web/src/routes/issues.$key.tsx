// 이슈 상세 페이지 — 시안 2 사이드 메타패널 (좌 본문 / 우 메타패널, 상태 읽기전용 배지)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { fetchIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { useUpdateIssueSummary, issueQueryKey } from '@/api/useUpdateIssueSummary'
import { useDeleteIssue } from '@/api/useDeleteIssue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 포매터 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO 날짜 문자열을 "YYYY-MM-DD HH:mm" 형식으로 포맷한다.
 * null이면 "—"를 반환한다.
 */
function formatDate(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  const date = d.toISOString().slice(0, 10)
  const time = d.toISOString().slice(11, 16)
  return `${date} ${time}`
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueMetaPanel — 우측 메타 패널 서브 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssueMetaPanelProps {
  /** 렌더할 이슈 데이터 */
  issue: IssueResponse
  /** 삭제 버튼 클릭 핸들러 */
  onDeleteClick: () => void
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 (전이 UI 없음 — D6 제외)
 * - 보고자 UUID, 프로젝트 키, 버전, 생성/수정 날짜 표시
 * - 생성/수정 날짜 null → "—" 표기
 * - 하단 "이슈 삭제" 버튼 → onDeleteClick 호출
 */
export function IssueMetaPanel({ issue, onDeleteClick }: IssueMetaPanelProps): JSX.Element {
  return (
    <aside className="flex flex-col gap-3">
      {/* 메타 패널 카드 */}
      <div className="border border-border rounded-xl overflow-hidden">
        {/* 상태 — 읽기전용 배지 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.statLabel}</p>
          <span
            data-testid="issue-state-badge"
            className="inline-flex items-center gap-1.5 text-sm font-medium"
          >
            <span className="size-2 rounded-full bg-primary/60 shrink-0" aria-hidden="true" />
            {issue.currentStateKey}
          </span>
        </div>

        {/* 보고자 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.reporterLabel}</p>
          <p className="text-sm font-medium truncate">{issue.reporterId}</p>
        </div>

        {/* 프로젝트 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.projectLabel}</p>
          <p className="text-sm font-medium">{issue.projectKey}</p>
        </div>

        {/* 버전 (낙관락 OCC 버전 번호) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.versionLabel}</p>
          <p className="text-sm font-medium">v{issue.version}</p>
        </div>

        {/* 생성일 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.createdAtLabel}</p>
          <p className="text-sm font-medium">{formatDate(issue.createdAt)}</p>
        </div>

        {/* 수정일 */}
        <div className="px-3.5 py-3">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.updatedAtLabel}</p>
          <p className="text-sm font-medium">{formatDate(issue.updatedAt)}</p>
        </div>
      </div>

      {/* 삭제 버튼 */}
      <Button
        variant="destructive"
        className="w-full min-h-[44px]"
        onClick={onDeleteClick}
        aria-label={issueDetailStrings.deleteButton}
      >
        {issueDetailStrings.deleteButton}
      </Button>
    </aside>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueDetailPage — props 기반 메인 컴포넌트 (useParams 비의존 — 단위 테스트 가능)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueDetailPageProps {
  /** URL params에서 추출한 이슈 식별 키 (예: "ATLAS-1") */
  issueKey: string
}

/**
 * 이슈 상세 페이지 컴포넌트 (시안 2 — 사이드 메타패널).
 *
 * - useQuery로 fetchIssue(issueKey)를 호출한다.
 * - 3 상태 분기: 로딩 → 에러/미존재 → 성공.
 * - 성공 레이아웃: 좌측 본문(breadcrumb + 제목 인라인 편집) + 우측 메타패널.
 * - 상태는 읽기전용 배지만 (전이 UI 없음 — D6 제외).
 * - 삭제: 확인 UI → useDeleteIssue → 목록으로 navigate.
 *
 * 라우터 의존 없이 props로 issueKey를 받아 단위 테스트가 가능하다.
 */
export function IssueDetailPage({ issueKey }: IssueDetailPageProps): JSX.Element {
  const navigate = useNavigate()
  const [isEditingTitle, setIsEditingTitle] = useState(false)
  const [editSummary, setEditSummary] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)

  const { data: issue, isLoading, error } = useQuery({
    queryKey: issueQueryKey(issueKey),
    queryFn: () => fetchIssue(issueKey),
    retry: false,
  })

  const updateMutation = useUpdateIssueSummary()
  const deleteMutation = useDeleteIssue({
    onSuccess: () => {
      // '/issues' 경로는 router.ts 등록 완료 시 타입 추론됨 — 현재 string cast로 우회
      void navigate({ to: '/issues' as string })
    },
  })

  // ── 로딩 상태 ──────────────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        {issueDetailStrings.loading}
      </div>
    )
  }

  // ── 에러 / 미존재 상태 ────────────────────────────────────────────────────
  if (error !== null || issue === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        {issueDetailStrings.notFound}
      </div>
    )
  }

  // ── 편집 모드 핸들러 ──────────────────────────────────────────────────────
  function handleEditStart() {
    setEditSummary(issue?.summary ?? '')
    setIsEditingTitle(true)
  }

  function handleEditCancel() {
    setIsEditingTitle(false)
    setEditSummary('')
  }

  function handleEditSave() {
    if (issue === undefined) return
    updateMutation.mutate(
      { key: issue.key, summary: editSummary, expectedVersion: issue.version },
      { onSuccess: () => setIsEditingTitle(false) },
    )
  }

  // ── 삭제 핸들러 ───────────────────────────────────────────────────────────
  function handleDeleteClick() {
    setConfirmDelete(true)
  }

  function handleDeleteConfirm() {
    if (issue === undefined) return
    deleteMutation.mutate(issue.key)
  }

  function handleDeleteCancel() {
    setConfirmDelete(false)
  }

  // ── 성공 레이아웃 ─────────────────────────────────────────────────────────
  return (
    <div className="max-w-[960px] mx-auto px-6 py-10">
      {/* breadcrumb */}
      <nav aria-label="이동 경로" className="mb-4 text-sm text-muted-foreground">
        <span>{issue.projectKey}</span>
        <span className="mx-1.5">/</span>
        <span className="font-medium text-foreground">{issue.key}</span>
      </nav>

      {/* 2-컬럼 그리드 — 좌 본문 / 우 메타패널 */}
      <div className="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_280px] lg:items-start">
        {/* 좌측 본문 */}
        <main>
          {/* 제목 영역 */}
          {isEditingTitle ? (
            <div className="flex flex-col gap-2">
              <Input
                aria-label={issueDetailStrings.titleEditLabel}
                value={editSummary}
                onChange={(e) => setEditSummary(e.target.value)}
                className="text-xl font-semibold"
              />
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={handleEditSave}
                  disabled={updateMutation.isPending}
                  aria-label={issueDetailStrings.saveButton}
                >
                  {issueDetailStrings.saveButton}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleEditCancel}
                  aria-label={issueDetailStrings.cancelButton}
                >
                  {issueDetailStrings.cancelButton}
                </Button>
              </div>
            </div>
          ) : (
            <>
              <h1 className="text-2xl font-semibold leading-snug mb-1">{issue.summary}</h1>
              <button
                type="button"
                onClick={handleEditStart}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                aria-label={issueDetailStrings.editTitleButton}
              >
                {issueDetailStrings.editTitleButton}
              </button>
            </>
          )}

          {/* 설명 자리표시자 — FR-IS-04 본문 단계에서 추가 예정 */}
          <div className="mt-6 text-sm text-muted-foreground rounded-xl bg-muted p-3">
            {issueDetailStrings.descriptionPlaceholder}
          </div>
        </main>

        {/* 우측 메타패널 + 삭제 확인 UI */}
        {confirmDelete ? (
          <aside className="flex flex-col gap-3">
            <div className="border border-destructive/40 rounded-xl p-4 text-sm text-destructive space-y-3">
              <p>{issueDetailStrings.deleteConfirmMessage}</p>
              <div className="flex gap-2">
                <Button
                  variant="destructive"
                  size="sm"
                  className="flex-1 min-h-[44px]"
                  onClick={handleDeleteConfirm}
                  disabled={deleteMutation.isPending}
                  aria-label={issueDetailStrings.confirmButton}
                >
                  {issueDetailStrings.confirmButton}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="flex-1 min-h-[44px]"
                  onClick={handleDeleteCancel}
                >
                  {issueDetailStrings.cancelButton}
                </Button>
              </div>
            </div>
          </aside>
        ) : (
          <IssueMetaPanel issue={issue} onDeleteClick={handleDeleteClick} />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueDetailRouteAdapter — router.ts에 등록되는 라우트 어댑터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * useParams로 URL의 $key param을 추출하여 IssueDetailPage에 전달한다.
 *
 * 등록 예시.
 * ```ts
 * import { IssueDetailRouteAdapter } from './routes/issues.$key'
 *
 * const issuesKeyRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/issues/$key',
 *   component: IssueDetailRouteAdapter,
 * })
 * ```
 */
export function IssueDetailRouteAdapter(): JSX.Element {
  const { key } = useParams({ strict: false })
  return <IssueDetailPage issueKey={key ?? ''} />
}
