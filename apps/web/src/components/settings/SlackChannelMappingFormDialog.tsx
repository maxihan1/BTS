// Slack 채널↔프로젝트 매핑 생성/수정 Dialog — 채널 ID 직접입력(C1) + 이벤트 필터 다중선택 (FR-SL-06 D6 Task 4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { createChannelMapping, updateChannelMapping } from '@/api/slack'
import type { ChannelMapping, CreateChannelMappingInput, UpdateChannelMappingInput } from '@/api/slack'
import { SlackEventFilterSelect } from './SlackEventFilterSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (SlackUserConnectionCard.tsx 선례, i18n 미도입 BC 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  createTitle: '채널 매핑 추가',
  editTitle: '채널 매핑 수정',
  channelIdLabel: '채널 ID',
  channelIdPlaceholder: 'C0123456789',
  channelIdHelper: 'Slack 채널 세부정보에서 채널 ID(C…)를 복사해 붙여넣으세요',
  channelNameLabel: '채널 표시명 (선택)',
  channelNamePlaceholder: '#general',
  eventsLabel: '이벤트 유형',
  eventsEmptyError: '이벤트 유형을 1개 이상 선택해주세요.',
  saveButton: '저장',
  cancelButton: '취소',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 에러 처리 — ApiError.body(`{code, message}`, SlackChannelMappingExceptionHandler 1:1) → 한국어 메시지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `ApiError.body.code` → 한국어 안내 메시지 매핑 (스펙 §4, 409 두 종류 우선 처리).
 *
 * 코드값은 백엔드 `SlackChannelMappingErrorCode`(SlackChannelMappingExceptionHandler.kt) 실제 값과
 * 1:1 — `WORKSPACE_NOT_INSTALLED`는 다른 코드와 달리 `SLACK_CHANNEL_MAPPING_` 접두어가 없다
 * (backend DTO invent 금지, 메모리 frontend-zod-backend-dto-contract-gap).
 * 매핑에 없는 코드는 {@link resolveErrorMessage}가 상태코드 기반으로 폴백한다.
 */
const CHANNEL_MAPPING_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  SLACK_CHANNEL_MAPPING_CONFLICT: '이미 동일한 채널 매핑이 존재합니다.',
  WORKSPACE_NOT_INSTALLED: 'Slack 워크스페이스가 먼저 연결돼야 합니다.',
}

const DEFAULT_ERROR_MESSAGE = '저장에 실패했습니다. 다시 시도해주세요.'
const VALIDATION_FALLBACK_MESSAGE = '입력을 확인해주세요.'

/** ApiError.body에서 `code`/`message` 문자열 필드를 안전하게 읽는다 (백엔드 `{code, message}` 계약). */
function extractChannelMappingErrorBody(body: unknown): { code: string | null; message: string | null } {
  if (typeof body !== 'object' || body === null) return { code: null, message: null }
  const record = body as Record<string, unknown>
  const code = typeof record['code'] === 'string' ? record['code'] : null
  const message = typeof record['message'] === 'string' ? record['message'] : null
  return { code, message }
}

/**
 * 저장 mutation 실패를 사용자 노출 메시지로 변환한다 (submitError는 이 컴포넌트 자체 state —
 * 메모리 dialog-submiterror-ownership-dead-path·form-occ-409-parent-usestate-staleness).
 *
 * 우선순위: 409 두 종류(고정 안내) → 400(응답 message, 없으면 검증 폴백) → 그 외(일반 실패 문구).
 */
function resolveErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return DEFAULT_ERROR_MESSAGE
  const { code, message } = extractChannelMappingErrorBody(error.body)
  if (code !== null && CHANNEL_MAPPING_ERROR_MESSAGES[code] !== undefined) {
    return CHANNEL_MAPPING_ERROR_MESSAGES[code]
  }
  if (error.status === 400) return message ?? VALIDATION_FALLBACK_MESSAGE
  return DEFAULT_ERROR_MESSAGE
}

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마 — 채널 ID만 필수(react-hook-form). eventTypes는 SlackEventFilterSelect가 별도 관리한다.
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z.object({
  channelId: z.string().trim().min(1, '채널 ID를 입력해주세요.'),
  channelName: z.string(),
})

type FormValues = z.infer<typeof formSchema>

/** 빈 문자열(공백만 포함)이면 undefined로 치환한다 — optional 필드(channelName) 미입력 시 요청에서 생략한다. */
function undefinedIfBlank(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SlackChannelMappingFormDialog props (AutomationRuleFormDialogProps 대응) */
export interface SlackChannelMappingFormDialogProps {
  /** 채널 매핑이 소속된 프로젝트 키 */
  readonly projectKey: string
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 값이 있으면 수정 모드(PATCH), null이면 생성 모드(POST) */
  readonly editingMapping: ChannelMapping | null
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리 — 메모리 react-usestate-stale-key-prop)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly projectKey: string
  readonly editingMapping: ChannelMapping | null
  readonly onOpenChange: (open: boolean) => void
}

function FormBody({ projectKey, editingMapping, onOpenChange }: FormBodyProps): JSX.Element {
  const [eventTypes, setEventTypes] = useState<string[]>(editingMapping?.eventTypes ?? [])
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [eventsError, setEventsError] = useState<string | null>(null)

  const queryClient = useQueryClient()

  const createMutation = useMutation({
    mutationFn: (input: CreateChannelMappingInput) => createChannelMapping(input),
  })
  const updateMutation = useMutation({
    mutationFn: (args: { id: string; input: UpdateChannelMappingInput }) =>
      updateChannelMapping(args.id, args.input),
  })

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      channelId: editingMapping?.channelId ?? '',
      channelName: editingMapping?.channelName ?? '',
    },
  })

  function handleEventTypesChange(next: string[]): void {
    setEventTypes(next)
    if (next.length > 0) setEventsError(null)
  }

  async function onValid(values: FormValues): Promise<void> {
    setSubmitError(null)
    if (eventTypes.length === 0) {
      setEventsError(labels.eventsEmptyError)
      return
    }
    setEventsError(null)

    const sharedFields = {
      channelId: values.channelId,
      channelName: undefinedIfBlank(values.channelName),
      eventTypes,
    }

    try {
      if (editingMapping !== null) {
        await updateMutation.mutateAsync({ id: editingMapping.id, input: sharedFields })
      } else {
        await createMutation.mutateAsync({ projectKey, ...sharedFields })
      }
      await queryClient.invalidateQueries({ queryKey: ['slack-channel-mappings', projectKey] })
      onOpenChange(false)
    } catch (error) {
      // 폼을 유지한 채 재시도 가능하도록 로컬 state에만 담는다(폼 자체 소유 — 부모 전달 없음).
      setSubmitError(resolveErrorMessage(error))
    }
  }

  return (
    <form data-testid="slack-channel-mapping-form" onSubmit={handleSubmit(onValid)} noValidate>
      <div className="mb-4">
        <label htmlFor="channel-mapping-channel-id" className="block text-sm font-medium mb-1">
          {labels.channelIdLabel}
        </label>
        <input
          id="channel-mapping-channel-id"
          type="text"
          aria-label={labels.channelIdLabel}
          placeholder={labels.channelIdPlaceholder}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...register('channelId')}
        />
        <p className="text-xs text-muted-foreground mt-1">{labels.channelIdHelper}</p>
        {errors.channelId !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.channelId.message}
          </p>
        )}
      </div>

      <div className="mb-4">
        <label htmlFor="channel-mapping-channel-name" className="block text-sm font-medium mb-1">
          {labels.channelNameLabel}
        </label>
        <input
          id="channel-mapping-channel-name"
          type="text"
          aria-label={labels.channelNameLabel}
          placeholder={labels.channelNamePlaceholder}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...register('channelName')}
        />
      </div>

      <div className="mb-4">
        <span className="block text-sm font-medium mb-1">{labels.eventsLabel}</span>
        <SlackEventFilterSelect value={eventTypes} onChange={handleEventTypesChange} />
        {eventsError !== null && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {eventsError}
          </p>
        )}
      </div>

      {submitError !== null && (
        <p className="text-sm text-destructive mb-4" role="alert">
          {submitError}
        </p>
      )}

      <div className="flex justify-end gap-2 mt-6">
        <Button type="button" variant="outline" size="sm" onClick={() => { onOpenChange(false) }}>
          {labels.cancelButton}
        </Button>
        <Button type="submit" size="sm" disabled={eventTypes.length === 0}>
          {labels.saveButton}
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SlackChannelMappingFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Slack 채널↔프로젝트 매핑 생성/수정 겸용 Dialog (AutomationRuleFormDialog 구조 미러).
 *
 * - `editingMapping`이 있으면 수정 모드(PATCH, 프리필). 없으면 생성 모드(POST).
 * - 채널 ID는 자유 텍스트 입력이다(게이트1 C1 확정 — picker는 백엔드 채널 목록 API 부재로 범위 밖).
 *   {@link labels.channelIdHelper}로 Slack 채널 세부정보에서 ID를 복사하도록 안내한다.
 * - 이벤트 유형은 {@link SlackEventFilterSelect}(10종, 그룹별 체크박스)로 선택한다. 0개 선택 시
 *   저장 버튼을 비활성화하고, 우회 제출(강제 submit)도 {@link FormBody.onValid}가 재검증해 막는다(EC1).
 * - 저장 실패는 {@link resolveErrorMessage}로 409(중복 매핑/워크스페이스 미설치)·400(응답 message)·
 *   그 외(일반 실패 문구)를 구분해 폼 내부 `submitError` state로 보여준다(폼 유지, 재시도 가능).
 * - 성공 시 `['slack-channel-mappings', projectKey]` 쿼리를 invalidate한다 — 목록 컴포넌트(Task 3)와
 *   동일 키를 공유하는 계약이다.
 * - `open`/`editingMapping.id` 조합을 key로 사용해 {@link FormBody}를 재마운트한다 — Dialog가 열린
 *   채로 편집 대상이 바뀌어도 이전 입력이 잔존하지 않는다(메모리 react-usestate-stale-key-prop).
 */
export const SlackChannelMappingFormDialog = ({
  projectKey,
  open,
  onOpenChange,
  editingMapping,
}: SlackChannelMappingFormDialogProps): JSX.Element => {
  const formKey = `${open ? 'open' : 'closed'}:${editingMapping?.id ?? 'new'}`
  const title = editingMapping !== null ? labels.editTitle : labels.createTitle

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 overflow-y-auto max-h-[90vh]"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">{title}</DialogPrimitive.Title>

          <FormBody key={formKey} projectKey={projectKey} editingMapping={editingMapping} onOpenChange={onOpenChange} />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
