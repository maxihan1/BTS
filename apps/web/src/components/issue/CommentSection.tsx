// 이슈 상세 활동 영역의 댓글 섹션 — 목록 조회 + 작성 폼 (FR-CO-01) + 수정·삭제 (FR-CO-02)
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertDialog } from 'radix-ui'
import { toast } from 'sonner'
import {
  addComment,
  commentQueryKey,
  deleteComment,
  fetchComments,
  updateComment,
} from '@/api/comments'
import type { CommentResponse } from '@/api/comments'
import { useAuthUser } from '@/auth/authStore'
import { useUsersByIds } from '@/hooks/use-users'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { useDateFormat } from '@/hooks/use-date-format'
import { commentStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// 작성 폼
// ─────────────────────────────────────────────────────────────────────────────

interface CommentAddFormProps {
  /** 대상 이슈 키 */
  issueKey: string
}

/**
 * 댓글 작성 폼.
 *
 * 성공 시에만 입력을 비운다 — 실패 시 사용자가 쓴 글을 잃지 않아야 한다.
 * 캐시는 `invalidateQueries` 로만 갱신한다. 낙관적 갱신·`setQueryData` 는 쓰지 않는다
 * (부분 응답이 본문을 placeholder 로 덮는 플리커 전력 — `WorklogSection` 과 동일 방침).
 * 이슈 단건 쿼리는 무효화하지 않는다 — 댓글은 이슈 행을 바꾸지 않으므로 불필요한 재조회다.
 *
 * @param issueKey 대상 이슈 키
 */
function CommentAddForm({ issueKey }: CommentAddFormProps): JSX.Element {
  const [body, setBody] = useState('')
  const queryClient = useQueryClient()

  const { mutate, isPending } = useMutation({
    mutationFn: () => addComment(issueKey, body.trim()),
    onSuccess: () => {
      toast.success(commentStrings.commentAddSuccess)
      void queryClient.invalidateQueries({ queryKey: commentQueryKey(issueKey) })
      setBody('')
    },
    onError: () => {
      toast.error(commentStrings.commentAddError)
      // 입력을 비우지 않는다 — 재시도할 때 다시 쓰게 만들지 않는다
    },
  })

  const isSubmitDisabled = body.trim() === '' || isPending

  return (
    <div className="space-y-2">
      <label htmlFor="comment-body" className="block text-sm font-medium text-foreground">
        {commentStrings.commentBodyLabel}
      </label>
      <textarea
        id="comment-body"
        aria-label={commentStrings.commentBodyLabel}
        placeholder={commentStrings.commentBodyPlaceholder}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        rows={3}
        className="w-full rounded border border-border bg-background p-2 text-sm"
      />
      <Button
        type="button"
        size="sm"
        disabled={isSubmitDisabled}
        onClick={() => mutate()}
        aria-label={commentStrings.commentAddButton}
        className="min-h-[44px]"
      >
        {isPending ? commentStrings.commentAddPending : commentStrings.commentAddButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 인라인 편집 폼
// ─────────────────────────────────────────────────────────────────────────────

interface CommentEditFormProps {
  /** 편집 시작 시점의 원문 Markdown */
  initialBody: string
  /** 저장 진행 중 여부 */
  isPending: boolean
  /** 저장 클릭 시 호출 — 편집된 본문 전달 */
  onSave: (body: string) => void
  /** 취소 클릭 시 호출 */
  onCancel: () => void
}

/**
 * 댓글 인라인 편집 폼.
 *
 * 초안(draft)은 이 컴포넌트의 지역 상태다. 취소하면 컴포넌트가 언마운트되면서 초안이 사라지고
 * 다시 열 때 `initialBody` 로 새로 초기화되므로, 별도 복원 로직 없이 "취소 = 원문 유지" 가 성립한다.
 * 편집 중 목록이 재조회돼 본문이 바뀌어도 초안을 덮지 않는다 — 사용자가 쓰던 글을 잃지 않아야 한다.
 *
 * @param initialBody 편집 시작 시점의 원문
 * @param isPending 저장 진행 중 여부
 * @param onSave 저장 콜백
 * @param onCancel 취소 콜백
 */
function CommentEditForm({
  initialBody,
  isPending,
  onSave,
  onCancel,
}: CommentEditFormProps): JSX.Element {
  const [draft, setDraft] = useState(initialBody)
  const isSaveDisabled = draft.trim() === '' || isPending

  return (
    <div className="space-y-2">
      <textarea
        aria-label={commentStrings.commentEditBodyLabel}
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value)
        }}
        rows={3}
        className="w-full rounded border border-border bg-background p-2 text-sm"
      />
      <div className="flex gap-2">
        <Button
          type="button"
          size="xs"
          disabled={isSaveDisabled}
          onClick={() => {
            onSave(draft)
          }}
          aria-label={commentStrings.commentEditSaveButton}
          className="min-h-[32px]"
        >
          {isPending ? commentStrings.commentEditPending : commentStrings.commentEditSaveButton}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="xs"
          disabled={isPending}
          onClick={onCancel}
          aria-label={commentStrings.commentEditCancelButton}
          className="min-h-[32px]"
        >
          {commentStrings.commentEditCancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 확인 다이얼로그
// ─────────────────────────────────────────────────────────────────────────────

interface CommentDeleteDialogProps {
  /** 삭제 진행 중 여부 — true 이면 트리거 버튼 비활성 */
  isDeleting: boolean
  /** 확인 클릭 시 호출 */
  onConfirm: () => void
}

/**
 * 댓글 삭제 확인 다이얼로그 — Radix `AlertDialog` (`AccountLinkCard` 관례 동형).
 *
 * 삭제는 되돌릴 수 없으므로 확인 단계를 반드시 거친다. 트리거 버튼 클릭만으로는
 * 서버를 부르지 않는다.
 *
 * @param isDeleting 삭제 진행 중 여부
 * @param onConfirm 확인 콜백
 */
function CommentDeleteDialog({ isDeleting, onConfirm }: CommentDeleteDialogProps): JSX.Element {
  const [isOpen, setIsOpen] = useState(false)

  function handleConfirm(): void {
    setIsOpen(false)
    onConfirm()
  }

  return (
    <AlertDialog.Root open={isOpen} onOpenChange={setIsOpen}>
      <AlertDialog.Trigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          disabled={isDeleting}
          aria-label={commentStrings.commentDeleteButton}
          className="min-h-[32px] px-1.5 text-muted-foreground hover:text-destructive"
        >
          {commentStrings.commentDeleteButton}
        </Button>
      </AlertDialog.Trigger>

      <AlertDialog.Portal>
        <AlertDialog.Overlay
          className={cn(
            'fixed inset-0 z-50 bg-black/50',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          )}
        />
        <AlertDialog.Content
          className={cn(
            'fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2',
            'rounded-xl border border-border bg-background p-6 shadow-lg',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          )}
        >
          <AlertDialog.Title className="text-base font-semibold text-foreground">
            {commentStrings.commentDeleteDialogTitle}
          </AlertDialog.Title>

          <AlertDialog.Description className="mt-2 text-sm text-muted-foreground">
            {commentStrings.commentDeleteDialogBody}
          </AlertDialog.Description>

          <div className="mt-5 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <Button variant="outline" size="sm">
                {commentStrings.commentDeleteDialogCancel}
              </Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button variant="destructive" size="sm" onClick={handleConfirm}>
                {commentStrings.commentDeleteDialogConfirm}
              </Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 댓글 1건
// ─────────────────────────────────────────────────────────────────────────────

interface CommentRowProps {
  /** 렌더할 댓글 */
  comment: CommentResponse
  /** 대상 이슈 키 */
  issueKey: string
  /** 작성자 표시 이름. undefined 이면 authorId UUID 를 그대로 표시한다 */
  displayName: string | undefined
  /** 현재 로그인 사용자 id. undefined 이면 작성자 판정이 항상 false 다 (fail-closed) */
  currentUserId: string | undefined
  /** 이슈 UPDATE 권한 여부 — false 이면 수정·삭제 어포던스를 모두 숨긴다 */
  canUpdate: boolean
  /** SOFT_DELETE(모더레이터) 보유 여부 — 남의 댓글 **삭제만** 열어준다 */
  canModerate: boolean
}

/**
 * 댓글 1건 렌더.
 *
 * 작성자 이름을 해석할 수 없으면(탈퇴·삭제) `authorId` UUID 를 그대로 보여준다 —
 * 이름이 없다고 화면이 깨지거나 항목이 사라지면 안 된다 (`WorklogSection` 동형 폴백).
 *
 * ## 본문은 `bodyHtml` 로 렌더한다
 * 백엔드가 `MarkdownRenderer.renderSafe` 로 정화한 HTML 을 `dangerouslySetInnerHTML` 로 넣는다
 * (`IssueDescription` 의 NFR1 관례 동형). 정화 단일 지점은 백엔드이며 **프론트에서 정화를
 * 재수행하지 않는다** — 두 곳에서 정화하면 규칙이 갈라져 어느 쪽이 정본인지 알 수 없게 된다.
 *
 * ## 수정과 삭제의 노출 조건은 다르다
 * 수정 = `작성자` 뿐, 삭제 = `작성자 OR SOFT_DELETE 보유자` 다 (백엔드
 * `CommentApplicationService.update`/`delete`). 두 조건을 합치면 모더레이터에게 눌러도 403 나는
 * 수정 버튼이 보이는 거짓 어포던스가 된다.
 *
 * @param comment 렌더할 댓글
 * @param issueKey 대상 이슈 키
 * @param displayName 작성자 표시 이름 (없으면 UUID 폴백)
 * @param currentUserId 현재 로그인 사용자 id
 * @param canUpdate 이슈 UPDATE 권한 여부
 * @param canModerate SOFT_DELETE 보유 여부
 */
function CommentRow({
  comment,
  issueKey,
  displayName,
  currentUserId,
  canUpdate,
  canModerate,
}: CommentRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const queryClient = useQueryClient()
  const [isEditing, setIsEditing] = useState(false)

  const isAuthor = currentUserId !== undefined && comment.authorId === currentUserId
  const canEdit = canUpdate && isAuthor
  const canDelete = canUpdate && (isAuthor || canModerate)
  const isEdited = comment.updatedAt !== comment.createdAt

  const { mutate: updateMutate, isPending: isUpdating } = useMutation({
    mutationFn: (body: string) => updateComment(issueKey, comment.id, body),
    onSuccess: () => {
      toast.success(commentStrings.commentEditSuccess)
      void queryClient.invalidateQueries({ queryKey: commentQueryKey(issueKey) })
      setIsEditing(false)
    },
    onError: () => {
      toast.error(commentStrings.commentEditError)
      // 편집 모드를 유지한다 — 실패했다고 사용자가 고쳐 쓴 본문을 버리지 않는다
    },
  })

  const { mutate: deleteMutate, isPending: isDeleting } = useMutation({
    mutationFn: () => deleteComment(issueKey, comment.id),
    onSuccess: () => {
      toast.success(commentStrings.commentDeleteSuccess)
      void queryClient.invalidateQueries({ queryKey: commentQueryKey(issueKey) })
    },
    onError: () => {
      toast.error(commentStrings.commentDeleteError)
    },
  })

  return (
    <li className="border-b border-border py-3 last:border-b-0">
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">{displayName ?? comment.authorId}</span>
        <span>{formatDateTime(comment.createdAt)}</span>
        {isEdited && <span>{commentStrings.commentEditedBadge}</span>}
      </div>

      {isEditing ? (
        <CommentEditForm
          initialBody={comment.body}
          isPending={isUpdating}
          onSave={(body) => {
            updateMutate(body)
          }}
          onCancel={() => {
            setIsEditing(false)
          }}
        />
      ) : (
        <div className="flex items-start justify-between gap-2">
          {/* NFR1: 백엔드 정화 HTML만 dangerouslySetInnerHTML 로 렌더 (IssueDescription 선례) */}
          <div
            data-testid={`comment-body-${comment.id}`}
            className="prose prose-sm min-w-0 max-w-none text-sm text-foreground"
            // biome-ignore lint/security/noDangerouslySetInnerHtml: 백엔드 OWASP 정화 HTML만 허용
            dangerouslySetInnerHTML={{ __html: comment.bodyHtml }}
          />

          {(canEdit || canDelete) && (
            <div className="flex shrink-0 gap-1">
              {canEdit && (
                <Button
                  type="button"
                  variant="ghost"
                  size="xs"
                  onClick={() => {
                    setIsEditing(true)
                  }}
                  aria-label={commentStrings.commentEditButton}
                  className="min-h-[32px] px-1.5 text-muted-foreground hover:text-foreground"
                >
                  {commentStrings.commentEditButton}
                </Button>
              )}
              {canDelete && (
                <CommentDeleteDialog
                  isDeleting={isDeleting}
                  onConfirm={() => {
                    deleteMutate()
                  }}
                />
              )}
            </div>
          )}
        </div>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 섹션
// ─────────────────────────────────────────────────────────────────────────────

interface CommentSectionProps {
  /** 대상 이슈 키 */
  issueKey: string
  /** 쓰기 권한 여부 — false 이면 작성 폼 대신 안내를 표시한다 */
  canUpdate: boolean
}

/**
 * 이슈 상세 활동 영역의 댓글 섹션 (FR-CO-01).
 *
 * `WorklogSection` 을 미러링한다 — 같은 위치·같은 구조·같은 캐시 방침.
 *
 * ## `canUpdate=false` 는 조용히 숨기지 않는다
 * `WorklogSection` 은 폼을 그냥 렌더하지 않지만, 이 섹션은 **안내 문구를 표시**한다.
 * 읽기 전용 참여자가 "댓글을 못 쓰는 이유" 를 알아야 하고, 눌러본 뒤 403 으로 알게 되는
 * 방식은 피한다 (스펙 FR-20 / EC-20). 형제와의 의도적 차이다.
 *
 * ## 빈 상태 / 로드 실패 / 권한 없음은 서로 다른 문구다
 * 셋 다 "댓글이 안 보인다" 는 같은 증상으로 나타나므로 원인을 구분할 수 있어야 한다.
 *
 * @param issueKey 대상 이슈 키
 * @param canUpdate 쓰기 권한 여부
 */
export function CommentSection({ issueKey, canUpdate }: CommentSectionProps): JSX.Element {
  const currentUser = useAuthUser()

  /**
   * 모더레이터 여부. `data` 는 로딩 중·에러일 때 undefined 이므로 `=== true` 비교 자체가
   * fail-closed 다 — 권한이 확정되기 전에는 남의 댓글 삭제 버튼을 열지 않는다.
   */
  const { data: permissionsData } = useIssuePermissions(issueKey)
  const canModerate = permissionsData?.permissions.SOFT_DELETE === true

  const { data, isLoading, isError } = useQuery<CommentResponse[]>({
    queryKey: commentQueryKey(issueKey),
    queryFn: () => fetchComments(issueKey),
    staleTime: 30_000,
  })

  const authorIds = data?.map((c) => c.authorId) ?? []
  const { data: usersData } = useUsersByIds(authorIds)

  /** authorId → displayName 맵. displayName 이 null 인 사용자는 제외해 UUID 폴백을 타게 한다 */
  const displayNameMap = new Map<string, string>(
    (usersData ?? [])
      .filter((u): u is typeof u & { displayName: string } => u.displayName !== null)
      .map((u) => [u.id, u.displayName]),
  )

  return (
    <section aria-label={commentStrings.commentSectionTitle} className="mt-6">
      <h2 className="mb-3 text-sm font-semibold text-foreground">
        {commentStrings.commentSectionTitle}
      </h2>

      {/* 작성 폼 (canUpdate=true) 또는 권한 안내 (canUpdate=false) */}
      <div className="mb-4">
        {canUpdate ? (
          <CommentAddForm issueKey={issueKey} />
        ) : (
          <p className="text-sm text-muted-foreground">{commentStrings.commentNoPermission}</p>
        )}
      </div>

      {isLoading && (
        <p className="text-sm text-muted-foreground" role="status">
          {commentStrings.commentLoading}
        </p>
      )}

      {isError && !isLoading && (
        <p className="text-sm text-destructive">{commentStrings.commentLoadError}</p>
      )}

      {data !== undefined && data.length === 0 && (
        <p className="text-sm text-muted-foreground">{commentStrings.commentEmptyState}</p>
      )}

      {data !== undefined && data.length > 0 && (
        <ul className="space-y-0">
          {data.map((comment) => (
            <CommentRow
              key={comment.id}
              comment={comment}
              issueKey={issueKey}
              displayName={displayNameMap.get(comment.authorId)}
              currentUserId={currentUser?.userId}
              canUpdate={canUpdate}
              canModerate={canModerate}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
