// 이슈 상세 페이지 라우트 — 시안 2 사이드 메타패널 (좌 본문 / 우 메타패널, 상태전이 컨트롤 포함)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchIssue, updateIssue, transitionIssue } from '@/api/issues'
import { ApiError } from '@/api/client'
import { useUpdateIssueSummary, issueQueryKey } from '@/api/useUpdateIssueSummary'
import { useChangeAssignee } from '@/api/useChangeAssignee'
import { useDeleteIssue } from '@/api/useDeleteIssue'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { useIssueTransitions, issueTransitionKeys } from '@/hooks/use-issue-transitions'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useDebounce } from '@/hooks/use-debounce'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { IssueDescription } from '@/components/issue/IssueDescription'
import { IssueMetaPanel } from '@/components/issue/IssueMetaPanel'
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 전이 컨트롤 사유 계산 (스펙 E5)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /transitions 응답 상태를 바탕으로 전이 불가 사유를 결정한다.
 * - 에러 + 422 → 'no-workflow' (워크플로우 미설정)
 * - 정상 + 빈 배열 → 'terminal' (종료상태)
 * - 정상 + 전이 있음 → null (전이 가능)
 * - 에러 + 비422 → null (에러는 별도 처리, 전이 불가 사유 없음으로 처리)
 */
function resolveTransitionUnavailableReason({
  isError,
  error,
  transitionCount,
}: {
  isError: boolean
  error: unknown
  transitionCount: number
}): TransitionUnavailableReason {
  if (isError) {
    return error instanceof ApiError && error.status === 422 ? 'no-workflow' : null
  }
  return transitionCount === 0 ? 'terminal' : null
}

// ─────────────────────────────────────────────────────────────────────────────
// router.ts 등록 방법 (code-based 패턴 — PR #11 컨벤션).
//
//   import { IssueDetailRouteAdapter } from './routes/issues.$key'
//
//   const issuesKeyRoute = createRoute({
//     getParentRoute: () => rootRoute,
//     path: '/issues/$key',
//     component: IssueDetailRouteAdapter,
//   })
//
// IssueDetailRouteAdapter는 useParams({ strict: false })로 key를 추출해
// IssueDetailPage에 전달한다. 라우터 등록은 router.ts 담당.
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
  const queryClient = useQueryClient()
  const [isEditingTitle, setIsEditingTitle] = useState(false)
  const [editSummary, setEditSummary] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [assigneeSearchQuery, setAssigneeSearchQuery] = useState('')

  const { data: issue, isLoading, error } = useQuery({
    queryKey: issueQueryKey(issueKey),
    queryFn: () => fetchIssue(issueKey),
    retry: false,
  })

  const { data: availableTypes = [] } = useIssueTypes()
  /** S2: debounce 적용 — 1000명 규모 매 키스트로크 요청 방지 (250ms) */
  const debouncedAssigneeSearchQuery = useDebounce(assigneeSearchQuery, 250)
  const { data: users = [] } = useUsers(debouncedAssigneeSearchQuery)

  /**
   * C1 버그 수정: 현재 담당자를 id 조회로 별도 확보.
   * useUsers(검색결과)에서 find()하면 검색어 변경 시 / 50건 한도 이외 담당자가 "미지정"으로 오표시됨.
   * assigneeId가 있을 때만 enabled — issue가 로드되기 전에는 빈 배열로 호출하지 않음.
   */
  const assigneeIdForLookup = issue?.assigneeId ?? null
  const { data: assigneeList = [] } = useUsersByIds(
    assigneeIdForLookup !== null ? [assigneeIdForLookup] : [],
  )
  const currentAssignee = assigneeList[0] ?? null

  const changeAssigneeMutation = useChangeAssignee()
  const {
    data: transitions = [],
    isError: isTransitionsError,
    error: transitionsError,
  } = useIssueTransitions(issueKey)

  // 스펙 E5: 422(워크플로우 미설정) vs 200+빈 배열(종료상태) 구분
  const transitionUnavailableReason = resolveTransitionUnavailableReason({
    isError: isTransitionsError,
    error: transitionsError,
    transitionCount: transitions.length,
  })

  // 전이 실행 mutation — D6 typeChangeMutation과 동일 패턴 (onError 훅 레벨 처리)
  const transitionMutation = useMutation({
    mutationFn: (input: { toStatusKey: string; expectedVersion: number }) =>
      transitionIssue(issueKey, input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) }),
        queryClient.invalidateQueries({ queryKey: issueTransitionKeys.list(issueKey) }),
      ])
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.status === 409) {
        // errorCode 구분: TRANSITION_NOT_ALLOWED(S3) vs VERSION_CONFLICT(S4)
        const body = err.body as Record<string, unknown> | undefined
        const errorCode = typeof body?.['errorCode'] === 'string' ? body['errorCode'] : ''
        if (errorCode === 'TRANSITION_NOT_ALLOWED') {
          toast.error(issueDetailStrings.transitionNotAllowedError)
        } else {
          // VERSION_CONFLICT(S4) — 최신 데이터 + 전이 목록 재조회 유도
          void Promise.all([
            queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) }),
            queryClient.invalidateQueries({ queryKey: issueTransitionKeys.list(issueKey) }),
          ])
          toast.error(issueDetailStrings.transitionVersionConflictError)
        }
      } else if (err instanceof ApiError && err.status === 422) {
        toast.error(issueDetailStrings.transitionWorkflowNotConfiguredError)
      } else {
        toast.error(issueDetailStrings.transitionNotAllowedError)
      }
    },
  })

  const updateMutation = useUpdateIssueSummary()
  const deleteMutation = useDeleteIssue({
    onSuccess: () => {
      // '/issues' 경로는 router.ts 등록 완료 시 타입 추론됨 — 현재 string cast로 우회
      void navigate({ to: '/issues' as string })
    },
  })

  /**
   * 메타필드 mutation 공통 onError 처리기.
   * - 409 → typeChangeConflictError toast + invalidate (최신 데이터 재조회 유도)
   * - 그 외 → 호출자가 전달한 fallbackMsg toast
   */
  function handleMetaMutationError(err: unknown, fallbackMsg: string) {
    if (err instanceof ApiError && err.status === 409) {
      toast.error(issueDetailStrings.typeChangeConflictError)
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    } else {
      toast.error(fallbackMsg)
    }
  }

  const typeChangeMutation = useMutation({
    mutationFn: ({ typeId, expectedVersion }: { typeId: number; expectedVersion: number }) =>
      updateIssue(issueKey, { typeId, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.typeChangeError)
    },
  })

  const descriptionMutation = useMutation({
    mutationFn: ({ description, expectedVersion }: { description: string; expectedVersion: number }) =>
      updateIssue(issueKey, { description, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.typeChangeError)
    },
  })

  const priorityMutation = useMutation({
    mutationFn: ({ priority, expectedVersion }: { priority: number; expectedVersion: number }) =>
      updateIssue(issueKey, { priority, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.priorityChangeError)
    },
  })

  const impactMutation = useMutation({
    mutationFn: ({ impact, expectedVersion }: { impact: number; expectedVersion: number }) =>
      updateIssue(issueKey, { impact, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.impactChangeError)
    },
  })

  const environmentMutation = useMutation({
    mutationFn: ({ environment, expectedVersion }: { environment: string; expectedVersion: number }) =>
      updateIssue(issueKey, { environment, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.environmentSaveError)
    },
  })

  const labelsMutation = useMutation({
    mutationFn: ({ labels, expectedVersion }: { labels: string[]; expectedVersion: number }) =>
      updateIssue(issueKey, { labels, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.labelsSaveError)
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

  // ── 타입 변경 핸들러 ─────────────────────────────────────────────────────
  function handleTypeChange(typeId: number) {
    if (issue === undefined) return
    typeChangeMutation.mutate({ typeId, expectedVersion: issue.version })
  }

  // ── 본문 저장 핸들러 ─────────────────────────────────────────────────────
  function handleDescriptionSave(markdown: string) {
    if (issue === undefined) return
    descriptionMutation.mutate({ description: markdown, expectedVersion: issue.version })
  }

  // ── 우선순위 변경 핸들러 ─────────────────────────────────────────────────
  function handlePriorityChange(priority: number) {
    if (issue === undefined) return
    priorityMutation.mutate({ priority, expectedVersion: issue.version })
  }

  // ── 영향도 변경 핸들러 ───────────────────────────────────────────────────
  function handleImpactChange(impact: number) {
    if (issue === undefined) return
    impactMutation.mutate({ impact, expectedVersion: issue.version })
  }

  // ── 환경 저장 핸들러 ─────────────────────────────────────────────────────
  function handleEnvironmentSave(environment: string) {
    if (issue === undefined) return
    environmentMutation.mutate({ environment, expectedVersion: issue.version })
  }

  // ── 라벨 저장 핸들러 ─────────────────────────────────────────────────────
  function handleLabelsSave(labels: string[]) {
    if (issue === undefined) return
    labelsMutation.mutate({ labels, expectedVersion: issue.version })
  }

  // ── 담당자 변경 핸들러 ───────────────────────────────────────────────────
  function handleAssigneeChange(userId: string | null) {
    if (issue === undefined) return
    changeAssigneeMutation.mutate({ key: issue.key, assigneeId: userId, expectedVersion: issue.version })
  }

  // ── 상태전이 핸들러 ───────────────────────────────────────────────────────
  function handleTransition(toStateKey: string) {
    if (issue === undefined) return
    transitionMutation.mutate({ toStatusKey: toStateKey, expectedVersion: issue.version })
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

          {/* 본문 — IssueDescription 컴포넌트 (FR-IS-04 Task 6) */}
          <div className="mt-6">
            <IssueDescription
              descriptionHtml={issue.descriptionHtml}
              description={issue.description}
              onSave={handleDescriptionSave}
              isSaving={descriptionMutation.isPending}
            />
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
          <IssueMetaPanel
            issue={issue}
            availableTypes={availableTypes}
            onTypeChange={handleTypeChange}
            onDeleteClick={handleDeleteClick}
            transitions={transitions}
            onTransition={handleTransition}
            isTransitioning={transitionMutation.isPending}
            unavailableReason={transitionUnavailableReason}
            onPriorityChange={handlePriorityChange}
            onImpactChange={handleImpactChange}
            onEnvironmentSave={handleEnvironmentSave}
            onLabelsSave={handleLabelsSave}
            users={users}
            onAssigneeSearch={setAssigneeSearchQuery}
            onAssigneeChange={handleAssigneeChange}
            currentAssignee={currentAssignee}
          />
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
