// 이슈 작업 기록 섹션 컴포넌트 — 워크로그 목록/추가/수정/삭제 + 자동차감 미리보기 (FR-TT-01 D6)
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { useAuthUser } from '@/auth/authStore'
import { fetchWorklogs, addWorklog, updateWorklog, deleteWorklog } from '@/api/worklogs'
import type { WorklogResponse, WorklogListResponse } from '@/api/worklogs'
import { parseHm, formatSeconds } from '@/lib/duration'
import { useUsersByIds } from '@/hooks/use-users'
import { useDateFormat } from '@/hooks/use-date-format'
import { worklogStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리키 팩토리
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 목록 쿼리키 팩토리 */
const worklogQueryKey = (issueKey: string): [string, string, string] => [
  'worklogs',
  'list',
  issueKey,
]

// ─────────────────────────────────────────────────────────────────────────────
// 로컬 datetime 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 로컬 시각을 datetime-local input의 기본값 형식으로 반환한다 */
function getLocalDatetimeDefault(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
    `T${pad(now.getHours())}:${pad(now.getMinutes())}`
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입
// ─────────────────────────────────────────────────────────────────────────────

interface HmState {
  hours: number
  minutes: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — WorklogAddForm
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogAddFormProps {
  /** 이슈 키 */
  issueKey: string
  /** 자동차감 미리보기용 잔여 추정 (null이면 미리보기 숨김) */
  remainingEstimateSeconds: number | null
  /** 추가 성공 콜백 */
  onSuccess: () => void
}

/**
 * 워크로그 추가 폼 컴포넌트.
 *
 * - 시간(h) + 분(m) 입력으로 소요시간 입력
 * - 0h 0m 이면 추가 버튼 disabled (E3)
 * - "잔여 직접 지정" 체크박스로 직접지정 입력 조건부 렌더 (design-C3)
 * - 미체크(자동 모드)이면 잔여 자동차감 미리보기 표시 (FR6)
 * - startedAt은 datetime-local, 빈 값이면 추가 차단
 *
 * @param issueKey 이슈 키
 * @param remainingEstimateSeconds 현재 잔여 추정 초
 * @param onSuccess 추가 성공 시 호출
 */
function WorklogAddForm({
  issueKey,
  remainingEstimateSeconds,
  onSuccess,
}: WorklogAddFormProps): JSX.Element {
  const queryClient = useQueryClient()

  const [spentHm, setSpentHm] = useState<HmState>({ hours: 0, minutes: 0 })
  const [startedAt, setStartedAt] = useState(getLocalDatetimeDefault)
  const [comment, setComment] = useState('')
  const [adjustRemaining, setAdjustRemaining] = useState(false)
  const [remainingHm, setRemainingHm] = useState<HmState>({ hours: 0, minutes: 0 })

  const spentSeconds = parseHm(spentHm)
  const isZero = spentSeconds === 0
  const isStartedAtEmpty = startedAt.trim() === ''
  const isAddDisabled = isZero || isStartedAtEmpty

  /** 자동 모드에서 잔여 미리보기 계산 */
  const previewRemainingSeconds =
    !adjustRemaining && remainingEstimateSeconds !== null
      ? Math.max(0, remainingEstimateSeconds - spentSeconds)
      : null

  const { mutate, isPending } = useMutation({
    mutationFn: () =>
      addWorklog(issueKey, {
        timeSpentSeconds: spentSeconds,
        startedAt: new Date(startedAt).toISOString(),
        comment: comment.trim() !== '' ? comment.trim() : undefined,
        newRemainingEstimateSeconds: adjustRemaining
          ? parseHm(remainingHm)
          : undefined,
      }),
    onSuccess: () => {
      toast.success(worklogStrings.worklogAddSuccess)
      // cross-invalidate: worklog 쿼리 + issue 단건 쿼리 둘 다 (FR9)
      void queryClient.invalidateQueries({ queryKey: worklogQueryKey(issueKey) })
      void invalidateIssueViews(queryClient, issueKey)
      setSpentHm({ hours: 0, minutes: 0 })
      setComment('')
      setAdjustRemaining(false)
      setRemainingHm({ hours: 0, minutes: 0 })
      setStartedAt(getLocalDatetimeDefault())
      onSuccess()
    },
    onError: () => {
      toast.error(worklogStrings.worklogAddError)
    },
  })

  return (
    <div className="border border-border rounded-md p-3 space-y-3 bg-muted/20">
      {/* 소요 시간 입력 */}
      <div className="flex gap-3 items-end">
        <div className="flex flex-col gap-1">
          <label htmlFor="wl-add-hours" className="text-xs text-muted-foreground">
            {worklogStrings.worklogTimeHoursLabel}
          </label>
          <input
            id="wl-add-hours"
            type="number"
            min={0}
            value={spentHm.hours}
            onChange={(e) => {
              setSpentHm((prev) => ({ ...prev, hours: Math.max(0, Number(e.target.value)) }))
            }}
            className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            aria-label={worklogStrings.worklogTimeHoursLabel}
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="wl-add-minutes" className="text-xs text-muted-foreground">
            {worklogStrings.worklogTimeMinutesLabel}
          </label>
          <input
            id="wl-add-minutes"
            type="number"
            min={0}
            max={59}
            value={spentHm.minutes}
            onChange={(e) => {
              setSpentHm((prev) => ({ ...prev, minutes: Math.max(0, Number(e.target.value)) }))
            }}
            className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            aria-label={worklogStrings.worklogTimeMinutesLabel}
          />
        </div>
      </div>

      {/* 시작 시각 */}
      <div className="flex flex-col gap-1">
        <label htmlFor="wl-add-started-at" className="text-xs text-muted-foreground">
          {worklogStrings.worklogStartedAtLabel}
        </label>
        <input
          id="wl-add-started-at"
          type="datetime-local"
          value={startedAt}
          onChange={(e) => { setStartedAt(e.target.value) }}
          className="border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
          aria-label={worklogStrings.worklogStartedAtLabel}
        />
      </div>

      {/* 코멘트 */}
      <div className="flex flex-col gap-1">
        <label htmlFor="wl-add-comment" className="text-xs text-muted-foreground">
          {worklogStrings.worklogCommentLabel}
        </label>
        <input
          id="wl-add-comment"
          type="text"
          value={comment}
          onChange={(e) => { setComment(e.target.value) }}
          placeholder=""
          className="border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
          aria-label={worklogStrings.worklogCommentLabel}
        />
      </div>

      {/* 잔여 직접 지정 체크박스 */}
      <div className="flex items-center gap-2">
        <input
          id="wl-add-adjust-remaining"
          type="checkbox"
          checked={adjustRemaining}
          onChange={(e) => { setAdjustRemaining(e.target.checked) }}
          className="h-4 w-4 cursor-pointer focus:ring-1 focus:ring-ring"
          aria-label={worklogStrings.worklogAdjustRemainingLabel}
        />
        <label htmlFor="wl-add-adjust-remaining" className="text-xs text-muted-foreground cursor-pointer">
          {worklogStrings.worklogAdjustRemainingLabel}
        </label>
      </div>

      {/* 직접 지정 시간/분 입력 (체크 시에만) */}
      {adjustRemaining && (
        <div className="flex gap-3 items-end pl-6">
          <div className="flex flex-col gap-1">
            <label htmlFor="wl-remaining-hours" className="text-xs text-muted-foreground">
              잔여 시간
            </label>
            <input
              id="wl-remaining-hours"
              type="number"
              min={0}
              value={remainingHm.hours}
              onChange={(e) => {
                setRemainingHm((prev) => ({ ...prev, hours: Math.max(0, Number(e.target.value)) }))
              }}
              className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              aria-label="잔여 시간"
            />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="wl-remaining-minutes" className="text-xs text-muted-foreground">
              잔여 분
            </label>
            <input
              id="wl-remaining-minutes"
              type="number"
              min={0}
              max={59}
              value={remainingHm.minutes}
              onChange={(e) => {
                setRemainingHm((prev) => ({ ...prev, minutes: Math.max(0, Number(e.target.value)) }))
              }}
              className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              aria-label="잔여 분"
            />
          </div>
        </div>
      )}

      {/* 자동 차감 미리보기 (자동 모드 + remaining이 null이 아닐 때) */}
      {previewRemainingSeconds !== null && spentSeconds > 0 && (
        <p className="text-xs text-muted-foreground">
          {worklogStrings.worklogAutoAdjustPreview}&nbsp;
          <span className="font-medium text-foreground">
            기록 후 잔여: {formatSeconds(previewRemainingSeconds)}
          </span>
        </p>
      )}

      {/* 추가 버튼 */}
      <Button
        type="button"
        variant="default"
        size="default"
        onClick={() => { mutate() }}
        disabled={isAddDisabled || isPending}
        aria-label={worklogStrings.worklogAddAriaLabel}
        className="min-h-[44px] rounded px-3 hover:bg-primary/90 disabled:opacity-40 disabled:cursor-not-allowed"
      >
        {isPending ? '추가 중...' : worklogStrings.worklogAddButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — WorklogEditForm (수정 인라인 폼)
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogEditFormProps {
  /** 워크로그 ID (input id 네임스페이스용) */
  worklogId: string
  /** 초기 시간/분 */
  initialHm: HmState
  /** 저장 클릭 시 호출 (새 hm 전달) */
  onSave: (hm: HmState) => void
  /** 취소 클릭 시 호출 */
  onCancel: () => void
  /** 저장 중 여부 */
  isPending: boolean
}

/**
 * 워크로그 인라인 수정 폼.
 *
 * - 시간(h) + 분(m) 입력
 * - 0h 0m 이면 저장 버튼 disabled
 * - 저장/취소 버튼
 *
 * @param worklogId 워크로그 UUID (input id 네임스페이스)
 * @param initialHm 초기 시간/분
 * @param onSave 저장 콜백
 * @param onCancel 취소 콜백
 * @param isPending 저장 중 여부
 */
function WorklogEditForm({
  worklogId,
  initialHm,
  onSave,
  onCancel,
  isPending,
}: WorklogEditFormProps): JSX.Element {
  const [hm, setHm] = useState<HmState>(initialHm)
  const isSaveDisabled = isPending || parseHm(hm) === 0

  return (
    <div className="space-y-2">
      <div className="flex gap-3 items-end">
        <div className="flex flex-col gap-1">
          <label htmlFor={`wl-edit-hours-${worklogId}`} className="text-xs text-muted-foreground">
            {worklogStrings.worklogTimeHoursLabel}
          </label>
          <input
            id={`wl-edit-hours-${worklogId}`}
            type="number"
            min={0}
            value={hm.hours}
            onChange={(e) => { setHm((prev) => ({ ...prev, hours: Math.max(0, Number(e.target.value)) })) }}
            className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            aria-label={worklogStrings.worklogTimeHoursLabel}
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor={`wl-edit-minutes-${worklogId}`} className="text-xs text-muted-foreground">
            {worklogStrings.worklogTimeMinutesLabel}
          </label>
          <input
            id={`wl-edit-minutes-${worklogId}`}
            type="number"
            min={0}
            max={59}
            value={hm.minutes}
            onChange={(e) => { setHm((prev) => ({ ...prev, minutes: Math.max(0, Number(e.target.value)) })) }}
            className="w-16 border border-input rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            aria-label={worklogStrings.worklogTimeMinutesLabel}
          />
        </div>
      </div>
      <div className="flex gap-2">
        <Button
          type="button"
          variant="default"
          size="xs"
          onClick={() => { onSave(hm) }}
          disabled={isSaveDisabled}
          className="min-h-[32px] rounded hover:bg-primary/90 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {worklogStrings.worklogSaveButton}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="xs"
          onClick={onCancel}
          disabled={isPending}
          className="min-h-[32px] rounded disabled:opacity-40 disabled:cursor-not-allowed"
        >
          취소
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — WorklogRow
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogRowProps {
  /** 워크로그 단건 */
  worklog: WorklogResponse
  /** 이슈 키 */
  issueKey: string
  /** authorId에 해당하는 displayName (undefined이면 UUID 그대로 표시) */
  displayName: string | undefined
  /** 현재 사용자 ID (undefined이면 버튼 미노출) */
  currentUserId: string | undefined
  /** 수정/삭제 버튼 표시 여부 (canUpdate 기반) */
  canUpdate: boolean
}

/**
 * 워크로그 행 컴포넌트.
 *
 * - 소요시간(formatSeconds) / 작성자(displayName) / 시작 시각 / 코멘트 표시
 * - `canUpdate && authorId===currentUserId` 일 때만 수정/삭제 버튼 노출 (E5, S7)
 * - 수정은 WorklogEditForm 인라인 (저장/취소)
 * - 삭제는 인라인 확인 (확인/취소)
 * - 수정/삭제 성공 시 worklog 쿼리 + issue 쿼리 둘 다 invalidate (FR9)
 *
 * @param worklog 워크로그 단건
 * @param issueKey 이슈 키
 * @param displayName 작성자 표시 이름
 * @param currentUserId 현재 로그인 사용자 ID
 * @param canUpdate 수정 권한 여부
 */
function WorklogRow({
  worklog,
  issueKey,
  displayName,
  currentUserId,
  canUpdate,
}: WorklogRowProps): JSX.Element {
  const queryClient = useQueryClient()
  const { formatDateTime } = useDateFormat()
  const [isEditing, setIsEditing] = useState(false)
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false)

  // 본인 worklog인지 여부 (canUpdate도 체크)
  const isOwner = canUpdate && currentUserId !== undefined && worklog.authorId === currentUserId

  const initialHm: HmState = {
    hours: Math.floor(worklog.timeSpentSeconds / 3600),
    minutes: Math.floor((worklog.timeSpentSeconds % 3600) / 60),
  }

  /** 수정 mutation */
  const { mutate: updateMutate, isPending: isUpdating } = useMutation({
    mutationFn: (hm: HmState) =>
      updateWorklog(issueKey, worklog.id, { timeSpentSeconds: parseHm(hm) }),
    onSuccess: () => {
      toast.success(worklogStrings.worklogEditSuccess)
      void queryClient.invalidateQueries({ queryKey: worklogQueryKey(issueKey) })
      void invalidateIssueViews(queryClient, issueKey)
      setIsEditing(false)
    },
    onError: () => { toast.error(worklogStrings.worklogEditError) },
  })

  /** 삭제 mutation */
  const { mutate: deleteMutate, isPending: isDeleting } = useMutation({
    mutationFn: () => deleteWorklog(issueKey, worklog.id),
    onSuccess: () => {
      toast.success(worklogStrings.worklogDeleteSuccess)
      void queryClient.invalidateQueries({ queryKey: worklogQueryKey(issueKey) })
      void invalidateIssueViews(queryClient, issueKey)
      setIsConfirmingDelete(false)
    },
    onError: () => {
      toast.error(worklogStrings.worklogDeleteError)
      setIsConfirmingDelete(false)
    },
  })

  return (
    <li className="py-2 border-b border-border last:border-b-0">
      {isEditing ? (
        <WorklogEditForm
          worklogId={worklog.id}
          initialHm={initialHm}
          onSave={(hm) => { updateMutate(hm) }}
          onCancel={() => { setIsEditing(false) }}
          isPending={isUpdating}
        />
      ) : (
        <div className="flex items-start justify-between gap-2">
          <div className="space-y-0.5 min-w-0">
            <p className="text-sm font-medium text-foreground">
              {formatSeconds(worklog.timeSpentSeconds)}
            </p>
            <p className="text-xs text-muted-foreground">
              {worklogStrings.worklogAuthorLabel}: {displayName ?? worklog.authorId}
            </p>
            <p className="text-xs text-muted-foreground">
              {formatDateTime(worklog.startedAt)}
            </p>
            {worklog.comment !== null && (
              <p className="text-xs text-foreground">{worklog.comment}</p>
            )}
          </div>

          {/* 수정/삭제 버튼 (본인 + canUpdate) */}
          {isOwner && !isConfirmingDelete && (
            <div className="flex gap-1 shrink-0">
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => { setIsEditing(true) }}
                aria-label={worklogStrings.worklogEditAriaLabel}
                className="min-h-[32px] px-1.5 text-muted-foreground hover:text-foreground"
              >
                {worklogStrings.worklogEditButton}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => { setIsConfirmingDelete(true) }}
                aria-label={worklogStrings.worklogDeleteAriaLabel}
                className="min-h-[32px] px-1.5 text-muted-foreground hover:text-destructive"
              >
                {worklogStrings.worklogDeleteButton}
              </Button>
            </div>
          )}

          {/* 인라인 삭제 확인 */}
          {isOwner && isConfirmingDelete && (
            <div className="flex gap-1 shrink-0">
              {/* PR22 — 원본이 bg-destructive 솔리드라 variant="destructive"(연한 배경)와 다르다.
                  className 으로 솔리드를 유지해 삭제 확인의 강조 의도를 보존한다. */}
              <Button
                type="button"
                variant="destructive"
                size="xs"
                onClick={() => { deleteMutate() }}
                disabled={isDeleting}
                aria-label="확인"
                className="min-h-[32px] rounded bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                확인
              </Button>
              <Button
                type="button"
                variant="outline"
                size="xs"
                onClick={() => { setIsConfirmingDelete(false) }}
                disabled={isDeleting}
                aria-label="취소"
                className="min-h-[32px] rounded disabled:opacity-40 disabled:cursor-not-allowed"
              >
                취소
              </Button>
            </div>
          )}
        </div>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — WorklogSection
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogSectionProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  issueKey: string
  /**
   * 추가/수정/삭제 권한 여부 (fail-closed: false이면 모든 조작 미노출).
   * - true: 추가 폼 표시, 본인 worklog 수정/삭제 가능
   * - false: 목록 읽기 전용
   */
  canUpdate: boolean
}

/**
 * 이슈 작업 기록 섹션 컴포넌트.
 *
 * - `fetchWorklogs`로 worklog 목록 + 집계 요약 조회
 * - `canUpdate=true`이면 추가 폼 표시
 * - 본인 worklog(authorId===user?.userId)이고 `canUpdate=true`이면 수정/삭제 버튼 표시
 * - 추가/수정/삭제 성공 시 worklog 쿼리 + issue 쿼리 둘 다 invalidate (FR9 cross-invalidate)
 * - 자동 모드에서 잔여 추정 자동차감 미리보기 표시 (FR6, eng-C4: summary 단일출처)
 *
 * @param issueKey 이슈 키
 * @param canUpdate 수정 권한 여부
 */
export function WorklogSection({ issueKey, canUpdate }: WorklogSectionProps): JSX.Element {
  const user = useAuthUser()

  const { data, isLoading, isError } = useQuery<WorklogListResponse>({
    queryKey: worklogQueryKey(issueKey),
    queryFn: () => fetchWorklogs(issueKey),
    staleTime: 30_000,
  })

  // authorId 목록 수집 → useUsersByIds로 displayName 조회
  const authorIds = data?.worklogs.map((w) => w.authorId) ?? []
  const { data: usersData } = useUsersByIds(authorIds)

  /** authorId → displayName 맵 (null displayName은 제외 — UUID 폴백 처리) */
  const displayNameMap = new Map<string, string>(
    (usersData ?? [])
      .filter((u): u is typeof u & { displayName: string } => u.displayName !== null)
      .map((u) => [u.id, u.displayName]),
  )

  return (
    <section aria-label={worklogStrings.worklogSectionTitle} className="mt-6">
      {/* 섹션 제목 (design-C2) */}
      <h2 className="text-sm font-semibold text-foreground mb-3">
        {worklogStrings.worklogSectionTitle}
      </h2>

      {/* 추가 폼 (canUpdate=true일 때만) */}
      {canUpdate && data !== undefined && (
        <div className="mb-4">
          <WorklogAddForm
            issueKey={issueKey}
            remainingEstimateSeconds={data.summary.remainingEstimateSeconds}
            onSuccess={() => {}}
          />
        </div>
      )}

      {/* 목록 영역 */}
      {isLoading && (
        <p className="text-sm text-muted-foreground" role="status">
          {worklogStrings.worklogLoading}
        </p>
      )}

      {isError && !isLoading && (
        <p className="text-sm text-destructive">
          {worklogStrings.worklogLoadError}
        </p>
      )}

      {data !== undefined && data.worklogs.length === 0 && (
        /* 빈 상태 (design-C5) */
        <p className="text-sm text-muted-foreground">{worklogStrings.worklogEmptyState}</p>
      )}

      {data !== undefined && data.worklogs.length > 0 && (
        <ul className="space-y-0">
          {data.worklogs.map((wl) => (
            <WorklogRow
              key={wl.id}
              worklog={wl}
              issueKey={issueKey}
              displayName={displayNameMap.get(wl.authorId)}
              currentUserId={user?.userId}
              canUpdate={canUpdate}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
