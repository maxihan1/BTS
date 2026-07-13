// Slack 채널↔프로젝트 매핑 목록 — 로딩/에러(404)/빈 상태 + 행별 수정·삭제 확인 (FR-SL-06 D6 Task 3)
import { useState } from 'react'
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { listChannelMappings, deleteChannelMapping } from '@/api/slack'
import type { ChannelMapping } from '@/api/slack'
import { ApiError } from '@/api/client'
import { SLACK_EVENT_TYPE_CATALOG } from '@/lib/slack-event-types'

// ─────────────────────────────────────────────────────────────────────────────
// 한국어 라벨 — BC 내 고정 (AutomationRuleList.tsx 관례, 별도 i18n 파일 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  heading: 'Slack 채널 매핑',
  addButton: '채널 추가',
  editButton: '수정',
  deleteButton: '삭제',
  emptyMessage: '아직 등록된 채널 매핑이 없습니다.',
  loadingStatus: '채널 매핑 목록 로딩 중',
  accessDenied: '권한이 없거나 찾을 수 없습니다',
  genericError: '채널 매핑을 불러오지 못했습니다.',
  deleteFailed: '삭제에 실패했습니다.',
  deleteConfirmTitle: '채널 매핑을 삭제하시겠습니까?',
  deleteConfirmMessage: '삭제하면 되돌릴 수 없습니다.',
  deleteConfirmButton: '삭제',
  deleteCancelButton: '취소',
  channelIdLabel: '채널 ID',
} as const

/** wireValue → 한국어 라벨 조회용 맵 — {@link SLACK_EVENT_TYPE_CATALOG}에서 1회 파생 */
const EVENT_LABEL_LOOKUP: ReadonlyMap<string, string> = new Map(
  SLACK_EVENT_TYPE_CATALOG.map((option) => [option.wireValue, option.label]),
)

/** 이벤트 wireValue를 한국어 라벨로 변환한다. 카탈로그에 없는(미지) 값은 원문 그대로 반환한다(fallback). */
function eventLabel(wireValue: string): string {
  return EVENT_LABEL_LOOKUP.get(wireValue) ?? wireValue
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SlackChannelMappingList props */
export interface SlackChannelMappingListProps {
  /** 매핑을 표시할 프로젝트 식별 키 */
  readonly projectKey: string
  /** "채널 추가" 클릭 시 상위에서 생성 폼을 열기 위한 콜백 (이 컴포넌트는 폼을 렌더하지 않는다) */
  readonly onAdd: () => void
  /** 행 "수정" 클릭 시 상위에서 편집 폼을 열기 위한 콜백 — 클릭된 매핑을 전달 */
  readonly onEdit: (mapping: ChannelMapping) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// DeleteConfirmDialog — radix-ui 직접 사용(components/ui에 Dialog 래퍼 부재, AutomationRuleList 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmDialogProps {
  /** 삭제 확인 대상 매핑. null이면 모달을 렌더하지 않는다 */
  readonly mapping: ChannelMapping | null
  /** 삭제 mutation 진행 중 여부 — 확인/취소 버튼을 disabled 처리한다 */
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/** 삭제 확인 모달 — mapping이 null이면 렌더하지 않는다 */
function DeleteConfirmDialog({ mapping, isPending, onConfirm, onCancel }: DeleteConfirmDialogProps): JSX.Element | null {
  if (mapping === null) return null

  return (
    <DialogPrimitive.Root
      open
      onOpenChange={(open) => {
        if (!open) onCancel()
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95">
          <DialogPrimitive.Title className="text-lg font-semibold">
            {labels.deleteConfirmTitle}
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="mt-2 text-sm text-muted-foreground">
            {mapping.channelName ?? mapping.channelId} — {labels.deleteConfirmMessage}
          </DialogPrimitive.Description>

          <div className="mt-6 flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={isPending}
              data-testid="slack-channel-mapping-delete-cancel"
              onClick={onCancel}
            >
              {labels.deleteCancelButton}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={isPending}
              data-testid={`slack-channel-mapping-delete-confirm-${mapping.id}`}
              onClick={onConfirm}
            >
              {labels.deleteConfirmButton}
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChannelMappingRow — 행 서브컴포넌트 (같은 파일 내부 분리 — 이 Task는 별도 파일 생성이 금지됨)
// ─────────────────────────────────────────────────────────────────────────────

interface ChannelMappingRowProps {
  readonly mapping: ChannelMapping
  readonly onEdit: (mapping: ChannelMapping) => void
  readonly onDeleteClick: (mapping: ChannelMapping) => void
}

/** 매핑 단일 행 — 채널명(없으면 채널 ID)·채널 ID·선택 이벤트 라벨 그룹 + 수정/삭제 액션 */
function ChannelMappingRow({ mapping, onEdit, onDeleteClick }: ChannelMappingRowProps): JSX.Element {
  const displayName = mapping.channelName ?? mapping.channelId

  return (
    <li className="flex flex-col gap-2 rounded-md border px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium">{displayName}</span>
          <span className="text-xs text-muted-foreground">
            {labels.channelIdLabel}: {mapping.channelId}
          </span>
        </div>
        <div
          role="group"
          aria-label={`선택 이벤트: ${mapping.eventTypes.map(eventLabel).join(', ')}`}
          className="flex flex-wrap items-center gap-1"
        >
          {/* key: wireValue — 백엔드가 정렬된 집합(중복 없음)을 반환하므로 값 자체로 충분히 안정적이다 */}
          {mapping.eventTypes.map((wireValue) => (
            <span
              key={wireValue}
              className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground"
            >
              {eventLabel(wireValue)}
            </span>
          ))}
        </div>
      </div>

      <div className="flex items-center gap-2 shrink-0">
        <Button
          variant="outline"
          size="sm"
          aria-label={`${displayName} ${labels.editButton}`}
          data-testid={`slack-channel-mapping-edit-${mapping.id}`}
          onClick={() => {
            onEdit(mapping)
          }}
        >
          {labels.editButton}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          aria-label={`${displayName} ${labels.deleteButton}`}
          data-testid={`slack-channel-mapping-delete-${mapping.id}`}
          onClick={() => {
            onDeleteClick(mapping)
          }}
        >
          {labels.deleteButton}
        </Button>
      </div>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SlackChannelMappingList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 Slack 채널↔프로젝트 매핑 목록 컴포넌트.
 *
 * - `useQuery({ queryKey: ['slack-channel-mappings', projectKey], ... })`로 목록을 조회한다.
 *   이 리터럴 키는 Task 4 폼 다이얼로그의 mutation invalidate와 동일해야 하는 task 간 계약이다.
 * - 4분기: 로딩 → 상태 표시, 에러(404) → "권한이 없거나 찾을 수 없습니다"(권한 없음/미존재
 *   비노출 계약, {@link ApiError}.status로 판별), 그 외 에러 → 일반 메시지, 빈 → 빈 상태 문구,
 *   목록 → {@link ChannelMappingRow} 렌더.
 * - "채널 추가" 헤더 버튼은 목록이 비어있어도 항상 노출되어 빈 상태의 CTA를 겸한다 —
 *   onAdd를 그대로 위임한다. 매핑 생성/편집 폼 자체는 이 컴포넌트가 렌더하지 않는다(Task 4).
 * - 행 "수정" 클릭은 onEdit(mapping)을 위임한다.
 * - 삭제는 확인 모달({@link DeleteConfirmDialog}) → 확인 시 `deleteChannelMapping` mutation을
 *   호출하고, 성공하면 목록 쿼리를 invalidate한다(캐시 통째 덮어쓰기 금지 — 부분응답 플리커 회귀 방지).
 *
 * @param projectKey 프로젝트 식별 키
 * @param onAdd "채널 추가" 클릭 콜백
 * @param onEdit 행 "수정" 클릭 콜백 — 클릭된 매핑을 인자로 전달
 */
export function SlackChannelMappingList({ projectKey, onAdd, onEdit }: SlackChannelMappingListProps): JSX.Element {
  const {
    data: mappings,
    isLoading,
    isError,
    error,
  } = useQuery<ChannelMapping[], ApiError>({
    queryKey: ['slack-channel-mappings', projectKey],
    queryFn: () => listChannelMappings(projectKey),
  })
  const queryClient = useQueryClient()

  const [deletingMapping, setDeletingMapping] = useState<ChannelMapping | null>(null)

  const deleteMutation = useMutation<void, ApiError, string>({
    mutationFn: (id: string) => deleteChannelMapping(id),
    onSuccess: () => {
      setDeletingMapping(null)
      void queryClient.invalidateQueries({ queryKey: ['slack-channel-mappings', projectKey] })
    },
    onError: () => {
      setDeletingMapping(null)
      toast.error(labels.deleteFailed)
    },
  })

  function handleDeleteConfirm(): void {
    if (deletingMapping === null) return
    deleteMutation.mutate(deletingMapping.id)
  }

  function handleDeleteCancel(): void {
    setDeletingMapping(null)
  }

  const mappingList = mappings ?? []
  const isNotFound = error instanceof ApiError && error.status === 404

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">{labels.heading}</h2>
        <Button size="sm" data-testid="slack-channel-mapping-add-button" onClick={onAdd}>
          {labels.addButton}
        </Button>
      </div>

      {isLoading && (
        <div role="status" aria-label={labels.loadingStatus} className="py-8 text-center text-sm text-muted-foreground">
          {labels.loadingStatus}
        </div>
      )}

      {!isLoading && isError && (
        <p className="text-sm text-destructive">{isNotFound ? labels.accessDenied : labels.genericError}</p>
      )}

      {!isLoading && !isError && mappingList.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">{labels.emptyMessage}</p>
      )}

      {!isLoading && !isError && mappingList.length > 0 && (
        <ul className="space-y-2">
          {mappingList.map((mapping) => (
            <ChannelMappingRow
              key={mapping.id}
              mapping={mapping}
              onEdit={onEdit}
              onDeleteClick={setDeletingMapping}
            />
          ))}
        </ul>
      )}

      <DeleteConfirmDialog
        mapping={deletingMapping}
        isPending={deleteMutation.isPending}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
      />
    </div>
  )
}
