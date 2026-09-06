// 보드 설정 — 상세 보기 탭 본문 (그룹 4종 · 추가 · 삭제 · 드래그 정렬 · 부채 177 Task 19 · J46~J48)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { DndContext, useDraggable, useDroppable } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { GripVertical, Trash2 } from 'lucide-react'
import type { BoardDetail, BoardDetailViewFields } from '@/api/boards'
import { ApiError } from '@/api/client'
import { DETAIL_VIEW_FIELD_GROUPS, replaceDetailViewFields } from '@/api/board-settings'
import type { DetailViewFieldGroup } from '@/api/board-settings'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import { Button } from '@/components/ui/button'
import { Combobox } from '@/components/ui/combobox'
import { cn } from '@/lib/utils'
import { useBoardDragSensors } from '../board-drag-sensors'

/** 화면 문구 — 정본은 `i18n/board-labels.ts` 다. 화면에 문자열을 박지 않는다. */
const labels = boardLabels.settings.detailView

/**
 * 후보 카탈로그에 쓸 수 있는 필드 키.
 *
 * ★**`fieldLabels` 의 키가 곧 허용값이다.** 후보 배열을 이 타입으로 묶으면 이름 없는 키를
 * 후보에 넣는 순간 `tsc` 가 막는다 — 「두 목록이 서로를 검사하지 않는」 자리를 타입으로 닫았다.
 */
type DetailViewFieldKey = keyof typeof labels.fieldLabels

/**
 * 그룹별 후보 필드 (J47 · J48 — *"select the field from one of the dropdown menus"*).
 *
 * ★**요약(summary)은 후보가 아니다.** 이슈 상세에서 요약은 제목 그 자체라 끄고 켤 대상이
 * 아니고, 카드 레이아웃이 같은 이유로 요약을 후보에서 뺐다(J19 의 1층 · 스펙 R2).
 *
 * ★**커스텀 필드는 이 task 범위 밖이다.** 계획 Task 19 의 GREEN 은 「그룹 4종 · 드롭다운 추가 ·
 * 삭제 · 드래그 정렬」이고, 커스텀 필드 후보를 명시한 것은 Task 16(카드 레이아웃)뿐이다.
 * 저장 형식은 이미 `cf_` 접두 키를 담을 수 있으므로(스펙 §API 예시) 나중에 후보만 늘리면 된다 —
 * 그때까지 다른 경로로 저장된 `cf_` 키는 [fieldLabel] 이 원문 키로 그려 **삭제되지 않게** 지킨다.
 */
const FIELD_CANDIDATES: Record<DetailViewFieldGroup, readonly DetailViewFieldKey[]> = {
  GENERAL: [
    'status',
    'issueType',
    'priority',
    'impact',
    'resolution',
    'labels',
    'components',
    'environment',
    'securityLevel',
    'fixVersions',
    'affectsVersions',
  ],
  DATE: ['createdAt', 'updatedAt', 'startDate', 'dueDate'],
  PEOPLE: ['assignee', 'reporter', 'watchers'],
  LINKS: ['issueLinks', 'parent', 'epic'],
}

/** 구성이 하나도 없는 보드의 시작값 — 그룹 4종이 **항상** 있는 모양이다(R7c). */
const EMPTY_FIELDS: BoardDetailViewFields = { GENERAL: [], DATE: [], PEOPLE: [], LINKS: [] }

/**
 * 필드 키의 표시 이름.
 *
 * ★**모르는 키는 숨기지 않고 원문 그대로 그린다.** 다른 경로(API·후속 task 의 커스텀 필드)로
 * 저장된 키를 화면이 못 그리면, 사용자가 그 그룹을 한 번 건드리는 순간 **그 키가 사라진다** —
 * 저장이 그룹 통째 교체이기 때문이다. 보이면 적어도 지우는 것이 사용자의 선택이 된다.
 *
 * @param key 저장된 필드 키.
 * @returns 사람이 읽는 이름. 카탈로그에 없으면 키 원문.
 */
function fieldLabel(key: string): string {
  const known: Record<string, string> = labels.fieldLabels
  return known[key] ?? key
}

/**
 * 한 그룹 안에서 필드 하나를 다른 필드 자리로 옮긴 뒤의 **전체 순서**를 만든다 (J48).
 *
 * 저장이 그룹 통째 교체라 부분 이동 명령이 없다 — 여기서 최종 배열을 만든다.
 * 제자리·모르는 키는 `null` 이고, 호출자는 아무 요청도 보내지 않는다.
 *
 * ★`state-mapping-drop.ts` 의 `planColumnReorder` 와 같은 배열 이동이지만 그것을 부르지 않는다.
 * 그 함수의 시그니처는 `{ columnId }[]` 라 필드 키에 쓰려면 감싸야 하고, 무엇보다 그것은
 * **컬럼 순서(R10 · J25)의 계약**이라 컬럼 쪽 규칙이 늘면 이 화면이 조용히 따라 바뀐다.
 *
 * ★**export 하지 않는다.** 판정은 이 화면의 드롭 이벤트로 재는 것이 맞다(`DetailViewPanel.test`
 * 의 T-DV-7·8·9) — 순수 함수만 직접 부르면 「함수는 맞는데 화면이 안 부르는」 자리가 열린다.
 * 컴포넌트 파일이 컴포넌트 아닌 것을 export 하면 Fast Refresh 도 깨진다.
 *
 * @param fields 그 그룹의 현재 필드 키 목록(표시 순서).
 * @param draggedKey 끌던 필드 키.
 * @param overKey 놓은 자리의 필드 키.
 * @returns 새 순서의 필드 키 전량. 바뀌는 것이 없으면 `null`.
 */
function planFieldReorder(
  fields: readonly string[],
  draggedKey: string,
  overKey: string,
): string[] | null {
  if (draggedKey === overKey) return null
  const from = fields.indexOf(draggedKey)
  const to = fields.indexOf(overKey)
  // 둘 중 하나라도 이 그룹 것이 아니면 아무 일도 하지 않는다 — 던지면 드래그가 화면을 죽인다.
  if (from === -1 || to === -1) return null

  const next = [...fields]
  next.splice(from, 1)
  next.splice(to, 0, draggedKey)
  return next
}

/** 드래그 노드 id — 그룹과 필드 키를 함께 싣는다. 그룹이 넷이라 필드 키만으로는 겹칠 수 있다. */
function dragId(group: DetailViewFieldGroup, fieldKey: string): string {
  return `detail-view:${group}:${fieldKey}`
}

/** 드래그 노드에 싣는 데이터 — `onDragEnd` 가 어느 그룹의 무엇인지 알아야 계획을 세운다. */
interface FieldDragData {
  /** 이 필드가 속한 그룹. */
  group: DetailViewFieldGroup
  /** 필드 키. */
  fieldKey: string
}

/** 한 번의 저장 시도. 실패 되돌림과 재시도가 같은 값을 쓴다. */
interface DetailViewSave {
  /** 저장할 그룹. 한 번에 한 그룹씩 보낸다(요청에 없는 그룹은 서버가 안 건드린다). */
  group: DetailViewFieldGroup
  /** 그 그룹의 필드 키 목록 — 통째 교체다. 순서가 곧 상세 화면에서의 자리다. */
  fields: readonly string[]
  /** 실패했을 때 되돌릴 직전 값. */
  previous: readonly string[]
}

/** FieldRow props */
interface FieldRowProps {
  /** 이 줄이 속한 그룹. */
  group: DetailViewFieldGroup
  /** 그룹 표시 이름 — 접근성 이름에 들어간다(넷이 같은 이름이면 셀렉터가 즉사한다). */
  groupLabel: string
  /** 필드 키. */
  fieldKey: string
  /** 끌 수 있는가. 권한 없음(S7) 또는 저장 중(E8 잠금)이면 false. */
  draggable: boolean
  /** 삭제. */
  onRemove: (fieldKey: string) => void
}

/**
 * 필드 한 줄 — 순서 핸들 · 이름 · 삭제 (J48).
 *
 * 드래그는 **핸들에만** 건다(`ColumnSettingsCard` 와 같은 규칙) — 줄 전체를 잡히게 하면
 * 삭제 버튼을 누르려던 손짓이 드래그로 먹힌다.
 *
 * 못 끄는 줄에는 dnd-kit 의 `listeners` 를 붙이지 않는다. `attributes` 는 그대로 두어
 * 버튼의 `disabled` 와 함께 「있지만 지금은 못 쓴다」가 보조기술에 그대로 전해지게 한다.
 */
function FieldRow({
  group,
  groupLabel,
  fieldKey,
  draggable,
  onRemove,
}: FieldRowProps): JSX.Element {
  const id = dragId(group, fieldKey)
  const data: FieldDragData = { group, fieldKey }
  const drag = useDraggable({ id, disabled: !draggable, data })
  const drop = useDroppable({ id, data })
  const name = fieldLabel(fieldKey)

  return (
    <li
      ref={drop.setNodeRef}
      className={cn(
        'flex min-h-11 items-center gap-2 rounded-md px-1 md:min-h-9',
        drop.isOver && 'bg-accent',
        drag.isDragging && 'opacity-50',
      )}
    >
      <Button
        type="button"
        variant="ghost"
        size="icon"
        ref={drag.setNodeRef}
        disabled={!draggable}
        aria-label={labels.reorderHandle(groupLabel, name)}
        className={cn('text-muted-foreground shrink-0', draggable && 'cursor-grab')}
        {...(draggable ? drag.listeners : {})}
        {...drag.attributes}
      >
        <GripVertical aria-hidden="true" />
      </Button>

      <span className="flex-1 text-sm">{name}</span>

      <Button
        type="button"
        variant="ghost"
        size="icon"
        disabled={!draggable}
        aria-label={labels.removeField(groupLabel, name)}
        className="text-muted-foreground shrink-0"
        onClick={() => {
          onRemove(fieldKey)
        }}
      >
        <Trash2 aria-hidden="true" />
      </Button>
    </li>
  )
}

/** FieldGroupSection props */
interface FieldGroupSectionProps {
  /** 그릴 그룹. */
  group: DetailViewFieldGroup
  /** 그 그룹의 현재 필드 키 목록(표시 순서). */
  fields: readonly string[]
  /** 편집이 잠겼는가 — 권한 없음(S7) 또는 저장 중(E8). */
  locked: boolean
  /** 후보 드롭다운에서 고른 값. 아직 안 골랐으면 null. */
  draft: string | null
  /** 후보 선택. */
  onDraftChange: (fieldKey: string) => void
  /** 「추가」 — 고른 후보를 목록 끝에 붙인다. */
  onAdd: () => void
  /** 삭제. */
  onRemove: (fieldKey: string) => void
}

/**
 * 그룹 한 구획 — 목록 + 후보 드롭다운 + 추가 버튼 (J47 · J48).
 *
 * ### 상태 3종 중 「빈」이 여기 있다
 * 필드가 없는 그룹을 회색 빈칸으로 두면 사용자가 조회 실패로 읽는다. 그룹 이름을 넣은 문구를
 * 그려 넷이 동시에 비어도 문구가 겹치지 않게 한다.
 */
function FieldGroupSection({
  group,
  fields,
  locked,
  draft,
  onDraftChange,
  onAdd,
  onRemove,
}: FieldGroupSectionProps): JSX.Element {
  const groupLabel = labels.groupLabels[group]
  // 이미 담긴 필드는 후보에서 뺀다 — 같은 필드를 두 번 담으면 접근성 이름이 겹친다.
  const candidates = FIELD_CANDIDATES[group].filter((key) => !fields.includes(key))
  const options = candidates.map((key) => ({ value: key, label: fieldLabel(key) }))

  return (
    <div className="ring-foreground/10 space-y-3 rounded-lg p-4 ring-1">
      <h3 className="text-muted-foreground text-xs font-semibold">{groupLabel}</h3>

      {fields.length === 0 ? (
        <p className="text-muted-foreground text-sm">{labels.emptyGroup(groupLabel)}</p>
      ) : (
        <ul aria-label={labels.listLabel(groupLabel)}>
          {fields.map((fieldKey) => (
            <FieldRow
              key={fieldKey}
              group={group}
              groupLabel={groupLabel}
              fieldKey={fieldKey}
              draggable={!locked}
              onRemove={onRemove}
            />
          ))}
        </ul>
      )}

      {candidates.length === 0 ? (
        // 비활성 드롭다운만 남기면 사용자가 고장으로 읽는다 — 사유를 문장으로 준다.
        <p className="text-muted-foreground text-sm">{labels.allAdded(groupLabel)}</p>
      ) : (
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Combobox
              options={options}
              value={draft}
              onChange={onDraftChange}
              ariaLabel={labels.candidateLabel(groupLabel)}
              placeholder={labels.candidateSearch}
              emptyText={labels.candidateEmpty}
              triggerPlaceholder={labels.candidateLabel(groupLabel)}
              disabled={locked}
            />
          </div>
          <Button type="button" variant="outline" disabled={locked || draft === null} onClick={onAdd}>
            {labels.addField(groupLabel)}
          </Button>
        </div>
      )}
    </div>
  )
}

/** DetailViewPanel props */
export interface DetailViewPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회를 만들지 않는다(N1). */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 조작만 잠기고 구성은 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Issue Detail View 탭** 본문 (J46·J47·J48).
 *
 * 이슈 상세 화면이 어떤 필드를 **어느 그룹에 어떤 순서로** 보여줄지를 보드 단위로 정한다.
 * 그룹은 4종이다 — `일반 필드`·`날짜 필드`·`사람`·`링크`(J47).
 *
 * ### 조작 3종 (J48)
 * 드롭다운에서 골라 **추가** · **삭제**(= 상세에서 숨김) · **드래그로 순서 변경**.
 * 셋 다 누르는 즉시 저장한다 — 「저장」 버튼을 두면 그룹 넷을 오가는 편집에서
 * 「다른 그룹으로 옮겼더니 앞의 것이 사라졌다」가 생긴다.
 *
 * ### ★개수 상한이 없다
 * 카드 레이아웃의 0..2(J17)는 **카드**의 제약이다. J48 은 한 그룹에 여러 필드를 순서대로 두는
 * 것을 명시하므로 그 상한을 복사해 오지 않는다 — 화면이 서버보다 좁아진다.
 *
 * ### ★★저장돼 있던 구성을 **초기값으로 읽는다** (Task 31 · N1)
 * 보드 조회 응답이 `detailViewFields` 를 실어 온다 — 별도 GET 을 만들지 않는 것이 N1 이다.
 *
 * **표시 문제가 아니라 데이터 보존 문제다.** `replaceDetailViewFields` 는 **그룹 통째 교체**라,
 * 빈 구성에서 시작하면 마운트 후 첫 조작이 서버에 저장돼 있던 그 그룹을 그 한 필드로 덮는다.
 * `SettingsTabs` 가 `forceMount` 없는 Radix `TabsContent` 라 탭을 옮겼다 돌아오기만 해도 다시
 * 마운트되므로 한 번 지나가고 끝이 아니다 — Task 16 이 카드 레이아웃에서 실제로 밟은 자리다.
 * 판정은 `DetailViewPanel.test.tsx` 의 **T-DV-10 · T-DV-13 · T-DV-14** 가 진다.
 *
 * 저장이 정착하면 **보드 조회를 무효화**한다 — 다음 마운트가 서버 값에서 다시 시작하게 하는
 * 유일한 근거다. 캐시를 `setQueryData` 로 덮지 않는다(응답에 없는 파생 필드가 null 로 덮여
 * 화면이 플리커한 사고가 있다 — PR #46).
 *
 * ### 상태 3종
 * - **로딩** — 이 패널은 스스로 조회하지 않는다. 보드 조회의 로딩은 부모 라우트가 그린다.
 * - **에러** — 저장 실패는 `role="alert"` 로 **화면에 남는다.** 토스트 단독은 쓰지 않는다.
 * - **빈** — 그룹이 비면 [labels.emptyGroup] 을 그린다. 회색 빈칸은 조회 실패로 읽힌다.
 */
export function DetailViewPanel({ board, canConfigure }: DetailViewPanelProps): JSX.Element {
  const domId = useId()
  const sensors = useBoardDragSensors()
  const queryClient = useQueryClient()

  // ★보드 조회가 실어 온 구성에서 시작한다. 빈 값에서 시작하면 첫 조작이 그 그룹을 통째로
  //   덮는다(위 KDoc — 데이터 소실). `SettingsTabs` 의 `key={board.boardId}` 가 재마운트를
  //   걸어 주므로 보드가 바뀌면 이 초기값도 다시 잡힌다.
  const [fields, setFields] = useState<BoardDetailViewFields>(board.detailViewFields ?? EMPTY_FIELDS)
  const [drafts, setDrafts] = useState<Record<DetailViewFieldGroup, string | null>>({
    GENERAL: null,
    DATE: null,
    PEOPLE: null,
    LINKS: null,
  })
  const [failure, setFailure] = useState<{ save: DetailViewSave; status: number | null } | null>(
    null,
  )

  const mutation = useMutation<BoardDetailViewFields, unknown, DetailViewSave>({
    mutationFn: (save) => replaceDetailViewFields(board.boardId, save.group, save.fields),
    onSuccess: (saved) => {
      // 응답은 요청 echo 가 아니라 **저장 후 다시 읽은 전체 구성**이다(그룹 4종). 자기가 보낸
      // 값을 화면 상태로 삼으면 저장이 안 돼도 성공처럼 보이고, 다른 그룹의 최신 값도 못 받는다.
      setFields(saved)
      setFailure(null)
    },
    onError: (error, save) => {
      // 낙관 반영을 되돌린다. 그대로 두면 사용자는 저장된 줄 안다.
      setFields((prev) => ({ ...prev, [save.group]: [...save.previous] }))
      // ★오류 **본문**을 읽지 않는다. 탭마다 봉투가 달라(부채 177 Task 29 가 통일 예정)
      //   본문 구조에 기대면 통일되는 날 조용히 어긋난다. 상태 코드로만 가른다.
      setFailure({ save, status: error instanceof ApiError ? error.status : null })
    },
    onSettled: async () => {
      // ★실패해도 재조회한다. 실패의 흔한 원인이 동시 편집이고, 그때야말로 화면이 아니라
      //   서버가 정본이다. 이 무효화가 없으면 탭을 옮겼다 돌아왔을 때 **저장 전 캐시**가
      //   초기값이 되어, 그 다음 조작이 방금 저장한 것을 도로 덮는다.
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(board.boardId) })
    },
  })

  const locked = !canConfigure || mutation.isPending

  function submit(save: DetailViewSave): void {
    setFields((prev) => ({ ...prev, [save.group]: [...save.fields] }))
    setFailure(null)
    mutation.mutate(save)
  }

  function handleAdd(group: DetailViewFieldGroup): void {
    const fieldKey = drafts[group]
    if (fieldKey === null) return
    const current = fields[group]
    if (current.includes(fieldKey)) return
    // 고른 순서가 곧 상세 화면에서의 자리다 — 뒤에 붙인다(J48 의 *Add*).
    submit({ group, fields: [...current, fieldKey], previous: current })
    setDrafts((prev) => ({ ...prev, [group]: null }))
  }

  function handleRemove(group: DetailViewFieldGroup, fieldKey: string): void {
    const current = fields[group]
    submit({ group, fields: current.filter((key) => key !== fieldKey), previous: current })
  }

  function handleDragEnd(event: DragEndEvent): void {
    const active = event.active.data.current as FieldDragData | undefined
    const over = event.over?.data.current as FieldDragData | undefined
    if (active === undefined || over === undefined) return
    // 그룹을 넘나드는 드롭은 다루지 않는다 — J48 은 *"up or down in the list"* 다.
    if (active.group !== over.group) return

    const current = fields[active.group]
    const next = planFieldReorder(current, active.fieldKey, over.fieldKey)
    if (next === null) return

    setFields((prev) => ({ ...prev, [active.group]: next }))
  }

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.heading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.description}</p>
      </header>

      {/* 실패는 **화면에 남는다.** 토스트만 띄우면 사용자가 저장된 줄 알고 화면을 떠난다. */}
      {failure !== null && (
        <div
          role="alert"
          className="border-destructive/40 flex items-center gap-3 rounded-md border p-3 text-sm"
        >
          <p className="text-destructive flex-1">
            {failure.status === 403
              ? labels.saveForbidden
              : failure.status === 400
                ? labels.saveInvalid
                : labels.saveFailed}
          </p>
          {/* 400 은 같은 값을 다시 보내도 같은 400 이다 — 재시도 버튼을 주지 않는다. */}
          {failure.status !== 400 && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={mutation.isPending}
              onClick={() => {
                submit(failure.save)
              }}
            >
              {labels.saveRetry}
            </Button>
          )}
        </div>
      )}

      <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
        <div className="space-y-4">
          {DETAIL_VIEW_FIELD_GROUPS.map((group) => (
            <FieldGroupSection
              key={group}
              group={group}
              fields={fields[group]}
              locked={locked}
              draft={drafts[group]}
              onDraftChange={(fieldKey) => {
                setDrafts((prev) => ({ ...prev, [group]: fieldKey }))
              }}
              onAdd={() => {
                handleAdd(group)
              }}
              onRemove={(fieldKey) => {
                handleRemove(group, fieldKey)
              }}
            />
          ))}
        </div>
      </DndContext>
    </section>
  )
}
