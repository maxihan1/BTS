// 칸반 보드 카드 컴포넌트 — 이슈 요약 표시 + @dnd-kit/sortable 정렬 가능 드래그 핸들
import { memo } from 'react'
import { Link } from '@tanstack/react-router'
import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { cn } from '@/lib/utils'
import type { BoardCard as BoardCardType, CardLayout } from '@/api/boards'
import type { CardLayoutViewScope } from '@/api/board-settings'
import { formatSeconds } from '@/lib/duration'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { CardLabelChips } from '@/components/issue/CardLabelChips'
import { CardEstimateBadge } from '@/components/issue/CardEstimateBadge'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 표시 3-상태 discriminated union
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드 담당자 표시 상태.
 *
 * - `unassigned`: assigneeId가 null — 아직 배정되지 않음.
 * - `named`: assigneeId가 있고 displayName/username을 해석함.
 * - `unknown`: assigneeId가 있으나 userMap에서 이름을 찾지 못함(fetchUsers 상한 초과 등).
 *   미배정과 시각적으로 구분해 "?" 아바타로 표시한다.
 */
export type CardAssigneeDisplay =
  | { state: 'unassigned' }
  | { state: 'named'; name: string }
  | { state: 'unknown' }

// ─────────────────────────────────────────────────────────────────────────────
// J19 2층 — 카드 추가 필드 (부채 177 Task 20 · R2·R3 · J17·J18)
//
// 지라 카드는 3층이다 (J19). 1층 요약 → 2층 **추가 필드** → 3층 상세(유형·우선순위·담당자·추정).
// 이 블록이 2층만 소유한다 — 1층은 아래 `<Link>`, 3층은 라벨 칩 행 + 하단 행이 이미 그린다.
//
// ★**보드 카드와 백로그 카드가 이 조각을 공유한다.** `BacklogCard.tsx` 가 여기서 import 한다 —
//   `CardLabelChips`·`CardEstimateBadge` 가 같은 이유로 한 벌만 존재하는 것과 같은 판단이다.
//   두 벌로 두면 「보드에선 뜨는데 백로그에선 안 뜬다」가 조용히 생긴다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 키 접두사 — 백엔드 `CardLayoutSettingsService.CUSTOM_FIELD_PREFIX` 미러.
 *
 * ★**`custom_field_definitions.key` 에는 접두사가 없다.** 표준 카탈로그와 커스텀 필드를 한
 * 배열에 담기 위한 표식이라 **설정이 보낼 때 붙이고 카드가 그릴 때 뗀다**
 * (붙이는 쪽은 `settings/CardLayoutPanel.tsx`, 떼는 쪽이 여기다).
 * 접두사를 안 떼면 값 조회가 전부 빗나가 모든 커스텀 필드가 조용히 생략된다.
 */
const CUSTOM_FIELD_PREFIX = 'cf_'

/**
 * 카드 2층에 얹을 수 있는 **표준** 필드와 그 표시 이름 —
 * `settings/CardLayoutPanel.STANDARD_FIELDS` 와 같은 카탈로그다.
 *
 * ★**`SUMMARY` 가 없다.** J19 의 1층이라 항상 최상단이고 토글 대상이 아니다(R2).
 * 설정 화면이 후보에서 뺐고 서버도 400 을 내지만 그 둘은 **런타임** 방어라,
 * 여기서는 {@link CardExtraField} 가 타입으로 막는다.
 *
 * ★키 선언 순서는 **카드에서의 순서가 아니다.** 카드 순서는 저장된 구성 배열의 순서다.
 *
 * 문구를 `i18n/card-labels.ts` 가 아니라 이 파일이 소유하는 이유는 부채 177 Task 20 의
 * 허용 파일이 셋뿐이기 때문이다 — `CardLayoutPanel` 이 같은 이유로 쓴 관용구다.
 */
const EXTRA_STANDARD_FIELDS = {
  EPIC: '에픽',
  PRIORITY: '우선순위',
  ASSIGNEE: '담당자',
  LABELS: '라벨',
  ESTIMATE: '추정치',
  ISSUE_TYPE: '이슈 종류',
} as const

/** 카드 2층에 얹을 수 있는 표준 필드 키. */
export type CardExtraStandardField = keyof typeof EXTRA_STANDARD_FIELDS

/** 커스텀 필드의 **전송 형태** 키. 언제나 `cf_` 가 붙어 있다. */
export type CardCustomFieldKey = `${typeof CUSTOM_FIELD_PREFIX}${string}`

/**
 * 카드 2층에 그릴 수 있는 필드 키 (R2 · J19 2층).
 *
 * ★★**`'SUMMARY'` 는 이 union 의 원소가 아니다.** 그래서 `extraFieldEntry('SUMMARY', …)` 나
 * `fieldKeys={['SUMMARY']}` 같은 코드가 **컴파일되지 않는다** — 요약이 토글 대상이 아니라는
 * 규약(R2)이 화면 코드에서도 타입으로 닫힌다. 서버에서 온 문자열은 {@link toExtraField} 가
 * 런타임에 떨어뜨린다.
 */
export type CardExtraField = CardExtraStandardField | CardCustomFieldKey

/** `[T] extends [never]` — 조건부 타입의 분배를 막아 `never` 판정을 정확히 한다. */
type IsNever<T> = [T] extends [never] ? true : false

/** `T` 가 `true` 가 아니면 컴파일 에러가 되는 정적 판정용 별칭. */
type AssertTrue<T extends true> = T

/**
 * 타입 판정 — **요약은 추가 필드가 될 수 없다** (R2 · J19 1층 · Task 20 REFACTOR).
 *
 * `CardExtraField` 에 `'SUMMARY'` 가 섞여 들어오는 순간 `Extract<…>` 가 `'SUMMARY'` 가 되고,
 * `IsNever<…>` 가 `false` 가 되어 이 별칭이 **TS2344 로 깨진다.** 값이 아니라 타입이므로
 * 런타임 비용이 0 이고, 카탈로그를 늘리는 사람이 실수로 요약을 넣으면 `tsc` 가 먼저 막는다.
 */
export type SummaryIsNotAnExtraField = AssertTrue<IsNever<Extract<CardExtraField, 'SUMMARY'>>>

/**
 * 카드가 나르는 커스텀 필드 값 맵 (J19 2층 · R2b).
 *
 * ★**키에 `cf_` 접두사가 없다** — `custom_field_definitions.key` 원문이다.
 * ★**값은 이미 마스킹돼 있다.** `BoardIssueLookupAdapter` 가 열람 권한으로 걸러 넘긴 결과이므로
 *   화면이 **다시 거르지 않는다**(부채 177 Task 25). 여기에 권한 판정을 얹으면 두 곳이 서로를
 *   검사하지 않은 채 갈린다.
 * ★`?` 인 이유. 아직 이 키를 싣지 않는 응답이 있다(백로그 조회 `BacklogIssueResponse`).
 *   값이 없으면 커스텀 필드 칸이 **그 카드에서만** 생략된다(E4).
 */
export interface CardCustomFieldValues {
  readonly customFields?: Readonly<Record<string, unknown>>
}

/**
 * 추가 필드 한 칸이 읽는 값 원천.
 *
 * 보드 카드(`BoardCardType`)와 백로그 카드(`BacklogIssue`)의 필드 이름이 서로 조금씩 달라
 * **두 카드가 각자 자기 데이터에서 뽑아** 이 모양으로 넘긴다. 이 조각은 두 응답 타입을 모른다.
 */
export interface CardExtraFieldSource {
  /** 속한 에픽 이슈 키. 에픽 미소속이면 null → 그 칸을 생략한다(E4). */
  readonly epicKey: string | null
  /** 우선순위 정수. 항상 값이 있다. */
  readonly priority: number
  /** 라벨 이름 목록. 비어 있으면 그 칸을 생략한다(E4). */
  readonly labels: readonly string[]
  /** 최초 추정(초). 미추정이면 null → 그 칸을 생략한다(E4). */
  readonly originalEstimateSeconds: number | null
  /** **해석된** 담당자 표시 이름. 미배정이거나 이름 미확인이면 null → 생략한다(E4). */
  readonly assigneeName: string | null
  /** **해석된** 이슈 종류 표시 이름. 항상 값이 있다(해석 실패 시 `typeKey` 원문). */
  readonly typeName: string
  /** 커스텀 필드 값 맵. 키에 `cf_` 가 없다. */
  readonly customFields: Readonly<Record<string, unknown>> | undefined
}

/** 표준 카탈로그에 있는 키인지. */
function isStandardExtraField(key: string): key is CardExtraStandardField {
  return Object.prototype.hasOwnProperty.call(EXTRA_STANDARD_FIELDS, key)
}

/** 전송 형태의 커스텀 필드 키인지. 접두사만 있고 뒤가 비면 아니다. */
function isCustomFieldKey(key: string): key is CardCustomFieldKey {
  return key.startsWith(CUSTOM_FIELD_PREFIX) && key.length > CUSTOM_FIELD_PREFIX.length
}

/**
 * 서버가 준 문자열 키를 카드가 그릴 수 있는 필드로 좁힌다.
 *
 * 카탈로그 밖 키(`SUMMARY` 포함)는 **조용히 떨어뜨린다** — 서버가 새 필드를 먼저 알게 되는
 * 배포 순서가 있으므로, 모르는 키 하나에 카드가 죽으면 안 된다.
 */
function toExtraField(key: string): CardExtraField | null {
  if (isStandardExtraField(key)) return key
  if (isCustomFieldKey(key)) return key
  return null
}

/** `unknown` 배열 좁힘 — `Array.isArray` 만 쓰면 `any[]` 가 새어 나온다. */
function isUnknownArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value)
}

/**
 * 커스텀 필드 원시값(`Any?`)을 카드 문자열로 만든다.
 *
 * 그릴 값이 없으면 **null** 이고 그 칸은 카드에서 사라진다(E4 — 빈 칸을 그리지 않는다).
 * 빈 문자열도 「값 없음」으로 본다 — 라벨만 남은 칸은 층 구조를 무너뜨린다.
 */
function formatCustomValue(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'string') return raw.trim() === '' ? null : raw
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw)
  if (isUnknownArray(raw)) {
    const parts = raw.filter((v) => v !== null && v !== undefined).map((v) => String(v))
    return parts.length === 0 ? null : parts.join(', ')
  }
  return null
}

/** 추가 필드 한 칸의 라벨과 값. 값이 없으면 null 이고 **그 카드에서만** 생략된다(E4). */
interface CardExtraFieldEntry {
  label: string
  value: string
}

/** 필드 키와 값 원천으로 한 칸을 만든다. 그 이슈에 값이 없으면 null. */
function extraFieldEntry(
  field: CardExtraField,
  source: CardExtraFieldSource,
): CardExtraFieldEntry | null {
  if (isCustomFieldKey(field)) {
    // ★여기서 접두사를 뗀다 — 값 맵의 키에는 `cf_` 가 없다.
    const name = field.slice(CUSTOM_FIELD_PREFIX.length)
    const value = formatCustomValue(source.customFields?.[name])
    return value === null ? null : { label: name, value }
  }

  const label = EXTRA_STANDARD_FIELDS[field]
  switch (field) {
    case 'EPIC':
      return source.epicKey === null ? null : { label, value: source.epicKey }
    case 'PRIORITY':
      return { label, value: `P${source.priority}` }
    case 'ASSIGNEE':
      return source.assigneeName === null ? null : { label, value: source.assigneeName }
    case 'LABELS':
      return source.labels.length === 0 ? null : { label, value: source.labels.join(', ') }
    case 'ESTIMATE':
      return source.originalEstimateSeconds === null
        ? null
        : { label, value: formatSeconds(source.originalEstimateSeconds) }
    case 'ISSUE_TYPE':
      return { label, value: source.typeName }
  }
}

/** CardExtraFields props */
interface CardExtraFieldsProps {
  /**
   * 보드 조회 응답이 실어 온 **뷰별** 구성 (`BoardDetail.cardLayout`).
   * 구성이 없는 뷰는 **키 자체가 없다**(백엔드 계약) — `?? []` 로 받는다.
   */
  layout: CardLayout | undefined
  /**
   * 이 카드가 사는 뷰. 보드 카드는 `BOARD`, 백로그 카드는 `BACKLOG` 다 (R3 · J18).
   *
   * ★**다른 뷰로 넘어가지 않는다.** `BOARD ?? BACKLOG` 같은 fallback 을 두면 칸반 보드가
   *   존재하지도 않는 백로그 구성을 그린다 — 칸반에는 백로그 스코프 자체가 없다.
   */
  view: CardLayoutViewScope
  /** 값 원천. */
  source: CardExtraFieldSource
}

/**
 * 카드 2층 — 구성이 고른 추가 필드 (J19 · J17 · R2·R3).
 *
 * - 자기 뷰의 구성을 **순서 그대로** 그린다. 정렬하거나 집합으로 만들지 않는다 —
 *   순서가 곧 설정 화면에서 사용자가 정한 자리다.
 * - 그 이슈에 값이 없는 필드는 **그 카드에서만** 생략한다(E4). 빈 칸을 그리지 않는다.
 * - 그릴 칸이 하나도 없으면 **DOM 자체를 만들지 않는다** — 빈 `<div/>` 는 카드 간격만 벌린다.
 */
export function CardExtraFields({
  layout,
  view,
  source,
}: CardExtraFieldsProps): React.ReactElement | null {
  const entries = (layout?.[view] ?? []).flatMap((key) => {
    const field = toExtraField(key)
    if (field === null) return []
    const entry = extraFieldEntry(field, source)
    return entry === null ? [] : [{ field, ...entry }]
  })

  if (entries.length === 0) return null

  return (
    <div data-testid="card-extra-fields" className="flex flex-wrap items-center gap-1">
      {entries.map(({ field, label, value }) => (
        <span
          key={field}
          data-card-field={field}
          className="flex min-w-0 items-center gap-1 rounded-sm px-1 py-0.5 text-xs ring-1 ring-border"
        >
          <span className="shrink-0 text-muted-foreground">{label}</span>
          <span className="truncate text-foreground">{value}</span>
        </span>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardCard 컴포넌트 Props */
export interface BoardCardProps {
  /**
   * 카드에 표시할 이슈 데이터.
   *
   * `CardCustomFieldValues` 를 교차한 이유. 커스텀 필드 값은 백엔드 `BoardCardResponse` 가
   * 실어 보내지만(부채 177 Task 26) `boardCardSchema` 는 아직 그 키를 모른다 — 스키마가
   * 넓어지면 이 교차는 무해한 중복이 되고, 그 전까지는 값이 없어 그 칸만 생략된다(E4).
   */
  card: BoardCardType & CardCustomFieldValues
  /** 카드가 속한 컬럼 UUID */
  columnId: string
  /**
   * 담당자 표시 상태 (3-상태 discriminated union).
   * 페이지가 userId → displayName 해석 후 주입한다.
   */
  assignee: CardAssigneeDisplay
  /**
   * 이슈 타입 아이콘 식별자 (IssueTypeIcon 원시 prop).
   * BoardColumn이 issueTypesByKey로 card.typeKey를 해석해 주입한다.
   * 해석 실패(매핑 없음)면 null — IssueTypeIcon이 Circle로 fallback한다.
   * 객체가 아닌 원시 값으로 받는 이유는 FR3 — memo 얕은 비교가 매 렌더 새 객체로 무력화되지 않도록 한다.
   */
  typeIconName: string | null
  /**
   * 이슈 타입 표시 이름. IssueTypeIcon의 aria-label로 그대로 쓰인다.
   * 해석 실패 시 card.typeKey 원문을 그대로 전달한다(FR6) — 빈 문자열이나 "알 수 없음"으로 뭉개지 않는다.
   */
  typeName: string
  /**
   * 이 보드의 **뷰별** 카드 레이아웃 구성 (`BoardDetail.cardLayout` · R3 · J18).
   *
   * 보드 카드는 이 중 **`BOARD` 스코프만** 읽는다. 미지정이거나 그 뷰의 키가 없으면
   * 추가 필드 층(J19 2층)의 DOM 자체가 생기지 않는다.
   */
  cardLayout?: CardLayout
}

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 아바타 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 담당자 표시 상태에 따른 아바타 또는 "미배정" 텍스트를 렌더한다. */
function AssigneeSlot({ assignee }: { assignee: CardAssigneeDisplay }): React.ReactElement {
  if (assignee.state === 'named') {
    const initial = assignee.name[0] ?? ''
    return (
      <span
        title={assignee.name}
        aria-label={`담당자: ${assignee.name}`}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-primary text-xs font-medium text-primary-foreground',
        )}
      >
        {initial}
      </span>
    )
  }

  if (assignee.state === 'unknown') {
    return (
      <span
        title="담당자 (이름 미확인)"
        aria-label="담당자 이름 미확인"
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-muted border border-border text-xs font-medium text-muted-foreground',
        )}
      >
        ?
      </span>
    )
  }

  // unassigned
  return (
    <span className="text-xs text-muted-foreground" aria-label="담당자 미배정">
      미배정
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BoardCardInner({
  card,
  columnId,
  assignee,
  typeIconName,
  typeName,
  cardLayout,
}: BoardCardProps) {
  const openIssueDetail = useOpenIssueDetail()
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: card.issueKey,
    data: { fromColumnId: columnId },
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      aria-roledescription="draggable card"
      aria-label={`${card.issueKey} — ${card.summary}`}
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-sm',
        'cursor-grab active:cursor-grabbing',
        isDragging && 'opacity-50',
      )}
    >
      {/* 주 정보: summary (2줄 truncate). 드래그 중 클릭 방지 */}
      <Link
        to="/issues/$key"
        params={{ key: card.issueKey }}
        className="line-clamp-2 text-sm font-medium leading-snug text-foreground hover:underline"
        onClick={(e) => {
          if (isDragging) {
            e.preventDefault()
          }
          // 평범한 좌클릭이면 모달로 가로챈다(J1). 드래그 중이면 위에서 이미
          // preventDefault 됐고 훅이 `defaultPrevented` 를 보고 물러난다.
          openIssueDetail(card.issueKey, e)
        }}
      >
        {card.summary}
      </Link>

      {/* J19 2층 — 구성이 고른 추가 필드. 요약 바로 아래이고 상세(3층)보다 위다 */}
      <CardExtraFields
        layout={cardLayout}
        view="BOARD"
        source={{
          epicKey: card.epicKey,
          priority: card.priority,
          labels: card.labels,
          originalEstimateSeconds: card.originalEstimateSeconds,
          assigneeName: assignee.state === 'named' ? assignee.name : null,
          typeName,
          customFields: card.customFields,
        }}
      />

      {/* 라벨 칩 행 — labels가 있을 때만 생성(FR9), 빈 배열이면 DOM 미생성 */}
      <CardLabelChips labels={card.labels} />

      {/* 하단 행: [유형 아이콘][issueKey] … [추정][담당자] (FR7) */}
      <div className="flex items-center justify-between gap-2">
        {/* min-w-0 — 이슈 키가 길어도 우측(추정·담당자)을 밀어내지 않게 한다. 백로그 카드와 동형. */}
        <span className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
          <IssueTypeIcon iconName={typeIconName} typeName={typeName} />
          {card.issueKey}
        </span>
        <div className="flex items-center gap-1">
          <CardEstimateBadge seconds={card.originalEstimateSeconds} />
          <AssigneeSlot assignee={assignee} />
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — 보드 전체 재렌더 시 props 변화 없는 카드 렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 이슈 카드.
 *
 * - summary를 주 정보로(2줄 truncate), issueKey를 보조 정보로 표시한다.
 * - assignee 3-상태에 따라 이니셜 아바타(named) / "?" 아바타(unknown) / "미배정"(unassigned)을 표시한다.
 * - `useSortable`(`@dnd-kit/sortable`)로 정렬 가능한 드래그 핸들을 제공한다.
 *   부모(BoardColumn)가 셀(컬럼 × 스윔레인 그룹) 단위 `SortableContext`로 감싸면
 *   같은 셀 내 포인터/키보드 순서변경에 참여한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동하며, 드래그 중엔 네비게이션이 막힌다.
 * - 드래그 중(`isDragging`)에는 원위치 카드에 `opacity-50`을 적용해 placeholder처럼 흐리게 표시한다.
 * - FR-UX-14 F14 3요소 — `CardLabelChips`(FR9, 라벨 없으면 DOM 미생성) · 하단 행의
 *   `IssueTypeIcon`(FR6, 해석 실패 시 typeKey 원문 + Circle fallback) · `CardEstimateBadge`
 *   (FR10, null이면 DOM 미생성)를 렌더한다. 카드 루트의 `aria-label`/`aria-roledescription`은
 *   Jira 패리티 계약 §2에 따라 변경하지 않는다(FR13).
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다. `typeIconName`/`typeName`을
 *   객체가 아닌 원시 값 2개로 받는 이유도 이 얕은 비교(shallow compare)가 매 렌더 새 객체로
 *   무력화되지 않게 하기 위함이다(FR3).
 */
export const BoardCard = memo(BoardCardInner)
