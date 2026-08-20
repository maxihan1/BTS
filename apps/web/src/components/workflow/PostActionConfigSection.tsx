// 워크플로우 전환별 post-action 목록 + Webhook 추가/수정/삭제 섹션 — isSystemAdmin 게이팅
import type { JSX } from 'react'
import { useState } from 'react'
import { toast } from 'sonner'
import { useAuthUser } from '@/auth/authStore'
import {
  usePostActions,
  useAddPostAction,
  useUpdatePostAction,
  useRemovePostAction,
} from '@/hooks/use-post-actions'
import type { PostActionResponse } from '@/api/post-actions'
import { PostActionFormDialog } from '@/components/workflow/PostActionFormDialog'
import type { PostActionFormValues } from '@/components/workflow/PostActionFormDialog'
import type { WorkflowTransitionView } from '@/components/workflow/workflow.types'
import { postActionLabels } from '@/i18n/post-action-labels'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** PostActionConfigSection 컴포넌트 props */
export interface PostActionConfigSectionProps {
  /** 워크플로우 키 */
  workflowKey: string
  /** 상위(workflows.$key)가 로드한 전환 목록 */
  transitions: WorkflowTransitionView[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 유틸리티 — config에서 url/method 안전 추출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PostActionResponse.config(Record<string, unknown>)에서 url과 method를 안전하게 추출한다.
 * `typeof` 체크를 통해 non-null assertion과 any 캐스팅 없이 처리한다.
 *
 * @param config  post-action config 맵
 * @returns url, method가 모두 string이면 PostActionFormValues, 아니면 null
 */
function extractWebhookValues(config: Record<string, unknown>): PostActionFormValues | null {
  const url = config['url']
  const method = config['method']
  if (typeof url === 'string' && typeof method === 'string') {
    return { url, method }
  }
  return null
}

/** backend `split("__")` 가 요구하는 전환 키 조각 수 */
const TRANSITION_KEY_SEGMENTS = 2

/**
 * 전환의 `key` 가 backend post-action 경로에서 되돌려질 수 있는 모양인지 판정한다.
 *
 * backend `PostActionAdminService.resolveOrThrow` 가 URL 세그먼트를 `split("__")` 하고
 * **정확히 2조각**이 아니면 `PostActionNotFoundException` 을 던진다. 그러므로 「설정 가능 여부」의
 * 정확한 기준은 조각 수 하나뿐이다 — 종전처럼 `fromStateKey`·`toStateKey` 각각에서 `'__'` 를
 * 찾던 방식은 출발 상태가 없는 전환(GLOBAL·INITIAL)에서 null 을 만나 터졌고, 판정하려던 대상
 * (합성 키의 조각 수)을 간접적으로 흉내 내던 것이라 종류가 늘 때마다 어긋난다.
 *
 * @param transition 전환 view 모델
 * @returns 조각 수가 2가 아니어서 규칙을 붙일 수 없으면 true
 */
function isAmbiguousTransitionKey(transition: WorkflowTransitionView): boolean {
  return transition.key.split('__').length !== TRANSITION_KEY_SEGMENTS
}

/** configSummary 반환 타입 — 절단 여부와 전체 원본을 함께 반환 */
interface ConfigSummaryResult {
  /** 화면에 표시할 요약 문자열 (절단 시 '…' 포함) */
  display: string
  /**
   * hover title에 노출할 전체 원본 문자열.
   * url 케이스(절단 없음)나 60자 이하이면 undefined.
   */
  fullTitle: string | undefined
}

/**
 * config에서 표시용 요약 문자열을 만든다 (테이블 config 컬럼용).
 * url이 있으면 "[method] url" 형식, 없으면 JSON 요약.
 * 60자 초과 시 말줄임(…)과 함께 전체 값을 fullTitle로 반환한다 (데이터 손실 방지).
 */
function configSummary(config: Record<string, unknown>): ConfigSummaryResult {
  const url = config['url']
  const method = config['method']
  if (typeof url === 'string') {
    const methodStr = typeof method === 'string' ? `[${method}] ` : ''
    return { display: `${methodStr}${url}`, fullTitle: undefined }
  }
  const full = JSON.stringify(config)
  if (full.length <= 60) {
    return { display: full, fullTitle: undefined }
  }
  return { display: `${full.slice(0, 60)}…`, fullTitle: full }
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 목록 테이블
// ─────────────────────────────────────────────────────────────────────────────

interface PostActionTableProps {
  actions: PostActionResponse[]
  onEditClick: (action: PostActionResponse) => void
  onDeleteClick: (id: string) => void
  isDeleting: boolean
}

/**
 * post-action 목록 테이블.
 * CALL_WEBHOOK 행만 수정 버튼 활성 (config에서 url/method 추출 가능한 경우).
 * 타 type 행은 type 컬럼만 표시, 편집 불가.
 */
function PostActionTable({ actions, onEditClick, onDeleteClick, isDeleting }: PostActionTableProps): JSX.Element {
  return (
    <table className="w-full border-collapse text-left text-sm">
      <thead className="border-b border-border bg-muted/50">
        <tr>
          <th className="px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {postActionLabels.list.typeColumn}
          </th>
          <th className="px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {postActionLabels.list.configColumn}
          </th>
          <th className="px-4 py-2 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {postActionLabels.list.orderColumn}
          </th>
          <th className="px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {postActionLabels.list.actionColumn}
          </th>
        </tr>
      </thead>
      <tbody>
        {actions.map((action) => {
          const canEdit = action.type === 'CALL_WEBHOOK' && extractWebhookValues(action.config) !== null

          const summary = configSummary(action.config)

          return (
            <tr key={action.id} className="border-b border-border transition-colors hover:bg-muted/30">
              <td className="px-4 py-2.5 font-mono text-xs text-foreground">{action.type}</td>
              <td
                className="max-w-[280px] truncate px-4 py-2.5 text-muted-foreground"
                title={summary.fullTitle}
              >
                {summary.display}
              </td>
              <td className="px-4 py-2.5 text-right text-muted-foreground">{action.displayOrder}</td>
              <td className="px-4 py-2.5">
                <div className="flex items-center gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    onClick={() => onEditClick(action)}
                    disabled={!canEdit}
                    aria-label={`${action.type} post-action 수정`}
                    className={cn(
                      'rounded',
                      canEdit
                        ? 'text-primary hover:bg-primary/10'
                        : 'cursor-not-allowed text-muted-foreground opacity-40',
                    )}
                  >
                    {postActionLabels.list.editButton}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    onClick={() => onDeleteClick(action.id)}
                    disabled={isDeleting}
                    aria-label={`${action.type} post-action 삭제`}
                    className={cn(
                      'rounded text-destructive',
                      'hover:bg-destructive/10 disabled:cursor-not-allowed disabled:opacity-40',
                    )}
                  >
                    {postActionLabels.list.deleteButton}
                  </Button>
                </div>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — isSystemAdmin 게이팅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 전환별 post-action 설정 섹션.
 *
 * - isSystemAdmin=true 인 경우에만 렌더 (아니면 null 반환).
 * - 전환 선택 → usePostActions(workflowKey, transitionKey) 쿼리 활성.
 * - 0건 → 빈 상태 안내, N건 → 테이블 (type/config 요약/displayOrder/수정·삭제).
 * - CALL_WEBHOOK 행만 수정 버튼 활성 (config에서 url/method 추출 가능 시).
 * - "Webhook 추가" 버튼 → PostActionFormDialog(create 모드).
 * - 수정 버튼 → PostActionFormDialog(edit 모드, initialValues 프리필).
 * - 삭제 버튼 → useRemovePostAction.mutate(id).
 */
export function PostActionConfigSection({ workflowKey, transitions }: PostActionConfigSectionProps): JSX.Element | null {
  const user = useAuthUser()

  // isSystemAdmin이 아니면 미렌더
  if (user?.isSystemAdmin !== true) {
    return null
  }

  return <PostActionConfigSectionContent workflowKey={workflowKey} transitions={transitions} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트 (게이팅 통과 후) — hook 규칙 준수를 위해 별도 분리
// ─────────────────────────────────────────────────────────────────────────────

interface DialogState {
  open: boolean
  mode: 'create' | 'edit'
  /** edit 모드에서의 대상 post-action id */
  editingId: string
  /** edit 모드에서의 초기값 */
  initialValues: PostActionFormValues | undefined
}

const CLOSED_DIALOG: DialogState = {
  open: false,
  mode: 'create',
  editingId: '',
  initialValues: undefined,
}

function PostActionConfigSectionContent({ workflowKey, transitions }: PostActionConfigSectionProps): JSX.Element {
  const [selectedTxKey, setSelectedTxKey] = useState<string>('')
  const [dialog, setDialog] = useState<DialogState>(CLOSED_DIALOG)

  const { data: actions = [], isLoading, isError } = usePostActions(workflowKey, selectedTxKey)

  // 모든 mutation hook은 컴포넌트 최상단에서 호출 (hook 규칙)
  const addMutation = useAddPostAction(workflowKey, selectedTxKey)
  const updateMutation = useUpdatePostAction(workflowKey, selectedTxKey, dialog.editingId)
  const removeMutation = useRemovePostAction(workflowKey, selectedTxKey)

  // ─── 이벤트 핸들러 ──────────────────────────────────────────────────────

  function handleTransitionChange(e: React.ChangeEvent<HTMLSelectElement>) {
    setSelectedTxKey(e.target.value)
  }

  function handleAddClick() {
    setDialog({ open: true, mode: 'create', editingId: '', initialValues: undefined })
  }

  function handleEditClick(action: PostActionResponse) {
    const webhookValues = extractWebhookValues(action.config)
    if (webhookValues === null) {
      // CALL_WEBHOOK이 아니거나 url/method 추출 불가 → 편집 불가
      return
    }
    setDialog({ open: true, mode: 'edit', editingId: action.id, initialValues: webhookValues })
  }

  function handleDeleteClick(id: string) {
    // C1: 삭제 실패 toast
    removeMutation.mutate(id, {
      onError: () => {
        toast.error(postActionLabels.error.removeFailed)
      },
    })
  }

  function handleDialogCancel() {
    setDialog(CLOSED_DIALOG)
  }

  function handleDialogSubmit(values: PostActionFormValues) {
    // C2/D2: 중간 행 삭제 후 추가 시 기존 order와 충돌 방지
    // actions.length 대신 max(displayOrder)+1 사용
    const nextDisplayOrder =
      actions.length > 0
        ? Math.max(...actions.map((a) => a.displayOrder)) + 1
        : 0

    if (dialog.mode === 'create') {
      addMutation.mutate(
        {
          type: 'CALL_WEBHOOK',
          config: { url: values.url, method: values.method },
          displayOrder: nextDisplayOrder,
        },
        {
          onSuccess: () => setDialog(CLOSED_DIALOG),
          // C1: 추가 실패 toast
          onError: () => {
            toast.error(postActionLabels.error.addFailed)
          },
        },
      )
    } else {
      const existingOrder = actions.find((a) => a.id === dialog.editingId)?.displayOrder ?? 0
      updateMutation.mutate(
        {
          type: 'CALL_WEBHOOK',
          config: { url: values.url, method: values.method },
          displayOrder: existingOrder,
        },
        {
          onSuccess: () => setDialog(CLOSED_DIALOG),
          // C1: 수정 실패 toast
          onError: () => {
            toast.error(postActionLabels.error.updateFailed)
          },
        },
      )
    }
  }

  const isSubmitting = addMutation.isPending || updateMutation.isPending

  // ─── 렌더 ───────────────────────────────────────────────────────────────

  return (
    <section className="space-y-4">
      {/* 섹션 헤더 */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">
          {postActionLabels.section.title}
        </h3>
        {/* D1: 전환 미선택 시 disabled + 안내 텍스트 */}
        <Button
          type="button"
          variant="default"
          size="xs"
          onClick={handleAddClick}
          disabled={selectedTxKey === ''}
          aria-label={postActionLabels.section.addWebhookButton}
          title={selectedTxKey === '' ? postActionLabels.error.selectTransitionFirst : undefined}
          className={cn(
            'rounded-md px-3',
            'hover:bg-primary/90',
            'disabled:cursor-not-allowed',
          )}
        >
          {postActionLabels.section.addWebhookButton}
        </Button>
      </div>

      {/* 전환 선택 */}
      <div className="space-y-1">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="post-action-transition-select">
          {postActionLabels.section.transitionSelectLabel}
        </label>
        <select
          id="post-action-transition-select"
          value={selectedTxKey}
          onChange={handleTransitionChange}
          className={cn(
            'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
            'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
          )}
        >
          <option value="">{postActionLabels.section.transitionSelectPlaceholder}</option>
          {/*
            value 는 응답의 `key` 를 그대로 쓴다 — backend 게터가 NORMAL 은 `from__to`,
            GLOBAL·INITIAL 은 `KIND__to` 로 만들고 그 문자열이 post-action 경로 세그먼트다.
            프론트가 다시 합성하면 규칙이 두 벌이 되고 출발 상태가 없는 전환에서 즉시 어긋난다.
            React key 는 `id` — 같은 (from, to) 쌍에 이름이 다른 전환을 여럿 둘 수 있어
            `key` 는 더 이상 유일하지 않다 (ADR 2026-08-18 §D1).
          */}
          {transitions.map((t) => (
            <option key={t.id} value={t.key} disabled={isAmbiguousTransitionKey(t)}>
              {t.name}
            </option>
          ))}
        </select>
        {/* D6: ambiguous 전환이 하나라도 있으면 안내 문구 노출 */}
        {transitions.some(isAmbiguousTransitionKey) && (
          <p className="text-xs text-muted-foreground">
            {postActionLabels.section.ambiguousKeyHint}
          </p>
        )}
      </div>

      {/* 목록 영역 — 전환 선택 시만 표시 */}
      {selectedTxKey !== '' && (
        <div className="overflow-hidden rounded-lg border border-border">
          {isLoading ? (
            <div className="px-4 py-6 text-center text-sm text-muted-foreground">
              로딩 중...
            </div>
          ) : isError ? (
            // D9: 목록 조회 실패 에러 UI
            <div
              role="alert"
              className="px-4 py-6 text-center text-sm text-destructive"
            >
              {postActionLabels.error.loadFailed}
            </div>
          ) : actions.length === 0 ? (
            <div className="px-4 py-6 text-center text-sm text-muted-foreground">
              {postActionLabels.list.emptyState}
            </div>
          ) : (
            <PostActionTable
              actions={actions}
              onEditClick={handleEditClick}
              onDeleteClick={handleDeleteClick}
              isDeleting={removeMutation.isPending}
            />
          )}
        </div>
      )}

      {/* Dialog — B1: key prop으로 열릴 때마다/대상 바뀔 때마다 재마운트해 stale state 방지 */}
      <PostActionFormDialog
        key={dialog.open ? `${dialog.mode}-${dialog.editingId !== '' ? dialog.editingId : 'new'}` : 'closed'}
        open={dialog.open}
        mode={dialog.mode}
        initialValues={dialog.initialValues}
        submitting={isSubmitting}
        onSubmit={handleDialogSubmit}
        onCancel={handleDialogCancel}
      />
    </section>
  )
}
