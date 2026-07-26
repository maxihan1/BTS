// 이슈 상세 활동 영역의 댓글 섹션 — 목록 조회 + 작성 폼 (FR-CO-01)
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { addComment, commentQueryKey, fetchComments } from '@/api/comments'
import type { CommentResponse } from '@/api/comments'
import { useUsersByIds } from '@/hooks/use-users'
import { useDateFormat } from '@/hooks/use-date-format'
import { commentStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'

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
// 댓글 1건
// ─────────────────────────────────────────────────────────────────────────────

interface CommentRowProps {
  /** 렌더할 댓글 */
  comment: CommentResponse
  /** 작성자 표시 이름. undefined 이면 authorId UUID 를 그대로 표시한다 */
  displayName: string | undefined
}

/**
 * 댓글 1건 렌더.
 *
 * 작성자 이름을 해석할 수 없으면(탈퇴·삭제) `authorId` UUID 를 그대로 보여준다 —
 * 이름이 없다고 화면이 깨지거나 항목이 사라지면 안 된다 (`WorklogSection` 동형 폴백).
 *
 * 본문은 백엔드가 `MarkdownRenderer.renderSafe` 로 정화한 `bodyHtml` 이 아니라 **원문 `body`**
 * 를 텍스트로 렌더한다. HTML 주입 표면을 프론트에 만들지 않기 위한 선택이며, Markdown 서식
 * 렌더는 후속 범위다.
 *
 * @param comment 렌더할 댓글
 * @param displayName 작성자 표시 이름 (없으면 UUID 폴백)
 */
function CommentRow({ comment, displayName }: CommentRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()

  return (
    <li className="border-b border-border py-3 last:border-b-0">
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">{displayName ?? comment.authorId}</span>
        <span>{formatDateTime(comment.createdAt)}</span>
      </div>
      <p className="whitespace-pre-wrap text-sm text-foreground">{comment.body}</p>
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
              displayName={displayNameMap.get(comment.authorId)}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
