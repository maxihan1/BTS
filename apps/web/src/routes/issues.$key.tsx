// 이슈 상세 페이지 라우트 — 시안 2 사이드 메타패널 (좌 본문 / 우 메타패널, 상태전이 컨트롤 포함)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchIssue, updateIssue, transitionIssue, IssueRedirectError } from '@/api/issues'
import type { IssueTransition, CustomFieldValues } from '@/api/issues'
import { ApiError } from '@/api/client'
import { useUpdateIssueSummary, issueQueryKey } from '@/api/useUpdateIssueSummary'
import { useChangeAssignee } from '@/api/useChangeAssignee'
import { useDeleteIssue } from '@/api/useDeleteIssue'
import { useChangeComponents } from '@/api/useChangeComponents'
import { useChangeSecurityLevel } from '@/api/useChangeSecurityLevel'
import { useChangeAffectsVersions, useChangeFixVersions } from '@/api/issue-versions'
import { fetchComponents } from '@/api/components'
import { useVersions } from '@/hooks/use-versions'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useIssueTransitions, issueTransitionKeys } from '@/hooks/use-issue-transitions'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useDebounce } from '@/hooks/use-debounce'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { FileDown } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { downloadIssuePdf } from '@/api/issues'
import { triggerBlobDownload } from '@/lib/download'
import { IssueChangelog } from '@/components/issue/IssueChangelog'
import { IssueLinksPanel } from '@/components/issue/IssueLinksPanel'
import { EpicChildrenSection } from '@/components/issue/EpicChildrenSection'
import { LinkGraph } from '@/components/issue/LinkGraph'
import { IssueDescription } from '@/components/issue/IssueDescription'
import { AttachmentSection } from '@/components/issue/AttachmentSection'
import { IssueMetaPanel } from '@/components/issue/IssueMetaPanel'
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { IssueScheduleFields } from '@/components/issue/IssueScheduleFields'
import { IssueEstimatePanel } from '@/components/issue/IssueEstimatePanel'
import { WorklogSection } from '@/components/issue/WorklogSection'
import { ResolutionModal } from '@/components/issue/ResolutionModal'
import { CloneIssueDialog } from '@/components/issues/CloneIssueDialog'
import { MoveIssueDialog } from '@/components/issues/MoveIssueDialog'
import { issueDetailStrings, worklogStrings } from '@/i18n/ko'

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
 * - 상태 전이 셀렉터 + 담당자 셀렉터(useUsersByIds 별도 조회) + debounce 검색 포함.
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
  const [cloneDialogOpen, setCloneDialogOpen] = useState(false)
  const [moveDialogOpen, setMoveDialogOpen] = useState(false)
  const [assigneeSearchQuery, setAssigneeSearchQuery] = useState('')
  const [isPdfDownloading, setIsPdfDownloading] = useState(false)

  // ── Resolution 모달 상태 ──────────────────────────────────────────────────
  /** 현재 DONE 전이 대기 중인 전이 항목. null이면 모달 닫힘. */
  const [pendingDoneTransition, setPendingDoneTransition] = useState<IssueTransition | null>(null)

  const { data: issue, isLoading, error } = useQuery({
    queryKey: issueQueryKey(issueKey),
    queryFn: async () => {
      try {
        return await fetchIssue(issueKey)
      } catch (err: unknown) {
        // 308 옛 키 redirect — 새 키 라우트로 교체 이동
        if (err instanceof IssueRedirectError) {
          void navigate({
            to: '/issues/$key' as string,
            params: { key: err.newKey },
            replace: true,
          })
          // navigate 후 query를 pending 상태로 유지하기 위해 re-throw
          // (undefined를 반환하면 이슈 없음 UI가 잠깐 렌더될 수 있다)
          throw err
        }
        throw err
      }
    },
    retry: false,
  })

  // 권한 조회 — fail-closed: 로딩 중·에러·미확정이면 false(비활성)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useIssuePermissions(issueKey)
  const canEdit =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.UPDATE === true

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
  const changeComponentsMutation = useChangeComponents()
  const changeSecurityLevelMutation = useChangeSecurityLevel()
  const changeAffectsVersionsMutation = useChangeAffectsVersions()
  const changeFixVersionsMutation = useChangeFixVersions()

  /** 프로젝트 컴포넌트 목록 — issue 로드 후 projectKey 기준으로 조회 */
  const { data: projectComponents = [] } = useQuery({
    queryKey: ['components', issue?.projectKey],
    queryFn: () => fetchComponents(issue!.projectKey),
    enabled: issue !== undefined,
    staleTime: 60_000,
  })

  /** 프로젝트 버전 목록 — issue 로드 후 projectKey 기준으로 조회 */
  const { data: projectVersions = [] } = useVersions(issue?.projectKey ?? '')

  /** 커스텀 필드 정의 목록 — changelog refs 주입용. issue 로드 후 활성화 */
  const { data: customFieldDefinitions = [] } = useCustomFields(
    issue?.projectKey ?? '',
    { enabled: issue !== undefined },
  )

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
    mutationFn: (input: { toStatusKey: string; expectedVersion: number; resolutionId?: string }) =>
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
   * - 409 → versionConflictError toast + invalidate (최신 데이터 재조회 유도)
   * - 그 외 → 호출자가 전달한 fallbackMsg toast
   */
  function handleMetaMutationError(err: unknown, fallbackMsg: string) {
    if (err instanceof ApiError && err.status === 409) {
      toast.error(issueDetailStrings.versionConflictError)
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

  const customFieldsMutation = useMutation({
    mutationFn: ({ customFields, expectedVersion }: { customFields: CustomFieldValues; expectedVersion: number }) =>
      updateIssue(issueKey, { customFields, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — invalidate-only로 최신 데이터 refetch
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(issueKey) })
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, '커스텀 필드 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
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

  // ── 커스텀 필드 저장 핸들러 (FR-IS-10) ───────────────────────────────────
  function handleCustomFieldsSave(customFields: CustomFieldValues) {
    if (issue === undefined) return
    customFieldsMutation.mutate({ customFields, expectedVersion: issue.version })
  }

  // ── 담당자 변경 핸들러 ───────────────────────────────────────────────────
  function handleAssigneeChange(userId: string | null) {
    if (issue === undefined) return
    changeAssigneeMutation.mutate({ key: issue.key, assigneeId: userId, expectedVersion: issue.version })
  }

  // ── 보안등급 변경 핸들러 ─────────────────────────────────────────────────
  function handleSecurityLevelChange(levelId: string | null) {
    if (issue === undefined) return
    changeSecurityLevelMutation.mutate({
      key: issue.key,
      securityLevelId: levelId,
      expectedVersion: issue.version,
    })
  }

  // ── 컴포넌트 변경 핸들러 ─────────────────────────────────────────────────
  /**
   * 컴포넌트 다중 할당 변경 핸들러.
   * useChangeComponents mutation을 통해 PATCH /api/v1/issues/{key}/components 호출.
   * expectedVersion은 현재 issue.version을 사용한다 (OCC 낙관락).
   *
   * componentIds는 issueResponseSchema에 추가되어 issue.componentIds로 직접 접근한다(백엔드 단건 응답이 채움).
   *
   * @param ids 새로 할당할 컴포넌트 UUID 배열
   */
  function handleComponentsChange(ids: string[]) {
    if (issue === undefined) return
    changeComponentsMutation.mutate({ key: issue.key, componentIds: ids, expectedVersion: issue.version })
  }

  // ── 영향 버전 변경 핸들러 ─────────────────────────────────────────────────
  function handleAffectsVersionsChange(ids: string[]) {
    if (issue === undefined) return
    changeAffectsVersionsMutation.mutate({ key: issue.key, versionIds: ids, expectedVersion: issue.version })
  }

  // ── 수정 버전 변경 핸들러 ─────────────────────────────────────────────────
  function handleFixVersionsChange(ids: string[]) {
    if (issue === undefined) return
    changeFixVersionsMutation.mutate({ key: issue.key, versionIds: ids, expectedVersion: issue.version })
  }

  // ── 상태전이 핸들러 ───────────────────────────────────────────────────────

  /**
   * 전이 선택 핸들러.
   * - toCategory === 'DONE': Resolution 모달을 오픈하고 전이를 대기한다.
   * - 그 외: 즉시 전이 실행 (resolutionId 없음).
   *
   * IssueMetaPanel.onTransition 시그니처가 toStateKey만 넘기므로,
   * transitions 배열에서 해당 전이 항목을 찾아 toCategory를 판단한다.
   *
   * @param toStateKey 목표 상태 키
   */
  function handleTransition(toStateKey: string) {
    if (issue === undefined) return
    const transition = transitions.find((t) => t.toStateKey === toStateKey)
    if (transition?.toCategory === 'DONE') {
      // DONE 전이 → 모달 오픈 후 resolution 선택 대기
      setPendingDoneTransition(transition)
    } else {
      // 비DONE 전이 → 즉시 실행
      transitionMutation.mutate({ toStatusKey: toStateKey, expectedVersion: issue.version })
    }
  }

  /**
   * Resolution 모달 확인 핸들러.
   * 모달에서 선택된 resolutionId를 포함해 전이를 실행한다.
   */
  function handleResolutionConfirm(resolutionId: string) {
    if (issue === undefined || pendingDoneTransition === null) return
    transitionMutation.mutate({
      toStatusKey: pendingDoneTransition.toStateKey,
      expectedVersion: issue.version,
      resolutionId,
    })
    setPendingDoneTransition(null)
  }

  /**
   * Resolution 모달 취소 핸들러.
   * 대기 중인 전이를 취소하고 모달을 닫는다.
   */
  function handleResolutionCancel() {
    setPendingDoneTransition(null)
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

  /**
   * PDF 다운로드 핸들러.
   *
   * GET /api/v1/issues/{key}/pdf → Blob → 브라우저 앵커 click 다운로드.
   * 진행 중 버튼 disabled, 실패 시 toast.error.
   * GET 요청이므로 CSRF 토큰 불요.
   */
  async function handlePdfDownload(issueKey: string) {
    setIsPdfDownloading(true)
    try {
      const blob = await downloadIssuePdf(issueKey)
      triggerBlobDownload(blob, `${issueKey}.pdf`)
    } catch {
      toast.error(issueDetailStrings.pdfDownloadError)
    } finally {
      setIsPdfDownloading(false)
    }
  }

  // ── 성공 레이아웃 ─────────────────────────────────────────────────────────
  return (
    <div className="max-w-[960px] mx-auto px-6 py-10">
      {/* breadcrumb 행 — 좌측 경로 / 우측 액션 버튼 */}
      <div className="flex items-center justify-between mb-4">
        <nav aria-label="이동 경로" className="text-sm text-muted-foreground">
          <span>{issue.projectKey}</span>
          <span className="mx-1.5">/</span>
          <span className="font-medium text-foreground">{issue.key}</span>
        </nav>
        <div className="flex gap-2">
          {/* 이슈 이동 버튼 — UPDATE 권한 게이팅 (FR-MV-01 D6) */}
          {canEdit && (
            <Button
              variant="outline"
              size="sm"
              aria-label="이슈 이동"
              onClick={() => setMoveDialogOpen(true)}
            >
              이슈 이동
            </Button>
          )}
          <Button
            variant="secondary"
            size="sm"
            disabled={isPdfDownloading}
            aria-label={issueDetailStrings.pdfDownloadAriaLabel}
            onClick={() => { void handlePdfDownload(issue.key) }}
          >
            <FileDown className="size-4 mr-1.5" aria-hidden="true" />
            {issueDetailStrings.pdfDownloadButton}
          </Button>
        </div>
      </div>

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
                  disabled={updateMutation.isPending || !canEdit}
                  aria-label={issueDetailStrings.saveButton}
                  data-testid="issue-title-save"
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
                disabled={!canEdit}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
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
              canEdit={canEdit}
              restrictedFields={issue.restrictedFields}
              noneditableFields={issue.noneditableFields}
            />
          </div>

          {/* 첨부 파일 섹션 — IssueDescription 하단 (FR-AC-01 D6) */}
          <AttachmentSection issueKey={issue.key} canUpdate={canEdit} />
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
          <div className="flex flex-col gap-4">
            <IssueMetaPanel
              issue={issue}
              availableTypes={availableTypes}
              onTypeChange={handleTypeChange}
              onDeleteClick={handleDeleteClick}
              onCloneClick={() => setCloneDialogOpen(true)}
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
              componentIds={issue.componentIds}
              components={projectComponents}
              onComponentsChange={handleComponentsChange}
              versions={projectVersions}
              affectsVersionIds={issue.affectsVersionIds}
              fixVersionIds={issue.fixVersionIds}
              onAffectsVersionsChange={handleAffectsVersionsChange}
              onFixVersionsChange={handleFixVersionsChange}
              onSecurityLevelChange={handleSecurityLevelChange}
              onCustomFieldsSave={handleCustomFieldsSave}
            />
            {/* 일정 필드 — FR-PL-01 시작일·마감일·목표일 (IssueMetaPanel 인근 하단 배치) */}
            <div className="border border-border rounded-xl px-3.5 py-3">
              <p className="text-xs text-muted-foreground mb-3">{issueDetailStrings.scheduleLabel}</p>
              <IssueScheduleFields issue={issue} disabled={!canEdit} />
            </div>
            {/* 추정 카드 — FR-TT-01 원 추정·잔여 추정·기록 시간 (design-C1: wrapper는 route가) */}
            <div className="border border-border rounded-xl px-3.5 py-3">
              <p className="text-xs text-muted-foreground mb-3">{worklogStrings.estimateSectionTitle}</p>
              <IssueEstimatePanel issue={issue} disabled={!canEdit} />
            </div>
          </div>
        )}
      </div>

      {/* 작업 기록 섹션 — 2단 grid 바깥 전체폭 (FR-TT-01 D6) */}
      <WorklogSection issueKey={issue.key} canUpdate={canEdit} />

      {/* 이슈 링크 패널 — 2단 grid 바깥 전체폭, 변경 이력 상단 (FR-LK-01 D6 / FR-EP-01 D6) */}
      <IssueLinksPanel
        issueKey={issue.key}
        parent={issue.parent ?? null}
        epic={issue.epic ?? null}
        showEpicSection={issue.typeKey !== 'epic' && issue.typeKey !== 'subtask'}
        disabled={!canEdit}
      />

      {/* 에픽 자식 이슈 섹션 — 에픽 타입(typeKey='epic')일 때만 렌더 (FR-EP-01 D6) */}
      {issue.typeKey === 'epic' && (
        <EpicChildrenSection epicKey={issue.key} disabled={!canEdit} />
      )}

      {/* 링크 그래프 섹션 — 2단 grid 바깥 전체폭 (FR-LK-02 D6) */}
      <LinkGraph issueKey={issue.key} />

      {/* 변경 이력 섹션 — 2단 grid 바깥 전체폭 (FR-HS-02 Task F5) */}
      <IssueChangelog
        issueKey={issue.key}
        refs={{
          types: availableTypes,
          components: projectComponents,
          versions: projectVersions,
          priorityMap: issueDetailStrings.priorityNames as Record<number, string>,
          impactMap: issueDetailStrings.impactNames as Record<number, string>,
          customFieldDefinitions,
        }}
      />

      {/* DONE 전이 시 Resolution 선택 모달 (B9) */}
      <ResolutionModal
        open={pendingDoneTransition !== null}
        prefilledResolution={issue.resolution ?? null}
        onConfirm={handleResolutionConfirm}
        onCancel={handleResolutionCancel}
      />

      {/* 이슈 클론 Dialog (FR-IS-06) */}
      <CloneIssueDialog
        issueKey={issue.key}
        open={cloneDialogOpen}
        onOpenChange={setCloneDialogOpen}
      />

      {/* 이슈 이동 마법사 Dialog (FR-MV-01 D6) */}
      <MoveIssueDialog
        issueKey={issue.key}
        issueVersion={issue.version}
        open={moveDialogOpen}
        onOpenChange={setMoveDialogOpen}
      />
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
