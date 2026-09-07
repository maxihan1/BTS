// 이슈 테이블 컬럼 정의 — key·header·sortable·required·render 메타데이터 (FR-UX-06 Phase 5 PR18 Task 4)
import { createElement } from 'react'
import type { MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import { ISSUE_SORT_FIELDS } from '@/api/issues'
import type { IssueResponse, IssueSortField } from '@/api/issues'
// 열람 숨김 판정 정본 — 상세 화면(`IssueMetaPanel.tsx:306`)과 같은 술어를 재사용한다
import { isFieldHidden } from '@/components/issue/IssueMetaPanel'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { AssigneeCell, AssigneeCellDisplay } from './cells/AssigneeCell'
import { PriorityCell, PriorityCellDisplay } from './cells/PriorityCell'
import { StatusCell, StatusCellDisplay } from './cells/StatusCell'
import { issueTypeColorClass } from './issue-visuals'
import type { StateCategory } from './issue-visuals'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 테이블 컬럼 고유 키.
 *
 * GAP-4(디자인 결정)가 확정한 6개 데이터 컬럼 — 체크박스(선택) 열은 컬럼 시스템에
 * 포함하지 않는다(구조적 고정 열, {@link IssueTable}이 직접 렌더). 이는
 * `use-column-visibility.test.ts`의 예시 컬럼 키 목록(key·summary·status·assignee·
 * priority·updatedAt, select 미포함)과도 일치한다.
 */
export type IssueColumnKey =
  | 'type'
  | 'key'
  | 'summary'
  | 'status'
  | 'assignee'
  | 'priority'
  | 'updatedAt'

/**
 * 셀 인라인 편집에 필요한 컨텍스트 (FR-UX-11 F9).
 *
 * 없거나(`undefined`) `enabled: false`면 3종 셀(담당자·우선순위·상태)이 **기존 읽기 전용
 * 마크업 그대로** 렌더된다 — 이것이 회귀 0 의 장치다.
 */
export interface IssueCellEditContext {
  /**
   * 목록 queryKey — mutation 훅이 이 캐시를 낙관적으로 patch 한다.
   *
   * ★`issues.index.tsx`의 `useQuery({ queryKey })`와 **같은 배열**이어야 한다. 두 곳에서
   * 따로 만들면 값이 어긋나 patch 가 아무 캐시에도 닿지 않고, 테스트는 초록인데 화면만
   * 안 바뀌는 가짜 그린이 난다.
   */
  listQueryKey: QueryKey
  /** 편집 기능 자체를 끌 때 false (상위 판단) */
  enabled: boolean
}

/**
 * 컬럼 렌더 함수가 행 단위로 필요로 하는 컨텍스트.
 * - assigneeName/formatDate — 훅 호출 결과({@link IssueTable}이 계산해 전달)를
 *   그대로 소비한다. `issue-columns.ts`는 훅을 직접 호출하지 않는 순수 함수 모음이다.
 * - edit — 셀 컴포넌트에 **위임만** 한다. 훅은 셀 컴포넌트가 소유하므로 이 모듈의
 *   순수 함수 계약은 유지된다(D-5).
 */
export interface IssueColumnRenderContext {
  /** assigneeId → 표시 이름 해석 결과. 미배정이거나 매핑 실패 시 undefined */
  assigneeName: string | undefined
  /** 해석된 이슈 유형 아이콘 식별자. 미해석이면 null({@link IssueTypeIcon}이 Circle 폴백) */
  typeIconName: string | null
  /** 해석된 이슈 유형 표시 이름. 미해석이면 `issue.typeKey` 원문(FR6 — 뭉개지 않는다) */
  typeName: string
  /** 워크플로우에서 해석한 상태 표시 이름. 미해석이면 undefined → 원시 키로 폴백 */
  statusName: string | undefined
  /** 워크플로우에서 해석한 상태 카테고리. 미해석이면 undefined → 중립색 */
  statusCategory: StateCategory | undefined
  /** updatedAt 등 ISO 날짜 문자열을 사용자 dateFormat 프리셋으로 포맷하는 함수 */
  formatDate: (iso: string | null) => string
  /** 행 네비게이션 콜백 — 키 링크 클릭 시 preventDefault 후 호출 */
  onNavigate: () => void
  /** 셀 인라인 편집 컨텍스트. 미전달이면 읽기 전용(기존 동작) */
  edit?: IssueCellEditContext
}

/**
 * 편집 컨텍스트가 실제로 살아 있는지 좁힌다.
 *
 * @param edit 렌더 컨텍스트의 편집 컨텍스트
 * @returns 편집 셀을 렌더해도 되면 true
 */
function isEditEnabled(edit: IssueCellEditContext | undefined): edit is IssueCellEditContext {
  return edit !== undefined && edit.enabled
}

/** 이슈 테이블 컬럼 정의 */
export interface IssueColumnDef {
  key: IssueColumnKey
  /** 헤더 표시 라벨 */
  header: string
  /** 정렬 헤더로 렌더할지 여부(GAP-1). true면 {@link getSortField}가 유효한 필드를 반환한다 */
  sortable: boolean
  /** 필수 컬럼 여부 — true면 ColumnSelector에서 항상 표시(비활성 체크박스), 숨김 불가 */
  required: boolean
  /** 셀 렌더 함수 */
  render: (issue: IssueResponse, ctx: IssueColumnRenderContext) => ReactNode
  /**
   * 기본 폭(px) — 사용자가 드래그로 바꾸기 전의 값.
   *
   * 폭은 className 이 아니라 {@link IssueTable} 의 `<colgroup>` 이 잡는다. Tailwind 클래스로
   * 두면 사용자 조정값(px)과 정본이 둘로 갈린다.
   */
  defaultWidth: number
  /**
   * 데이터 셀(`<td>`) className — 넘치는 내용 줄임표 처리 등 시각 위계.
   *
   * ★헤더(`<th>`)에는 걸지 않는다. `overflow:hidden` 이 헤더 경계에 걸친 폭 조절 손잡이를
   * 잘라 드래그가 통째로 안 먹는다({@link IssueTable} 주석 참고).
   */
  className: string
}

/**
 * 모든 셀에 거는 넘침 처리 — Jira 리스트 뷰처럼 폭을 넘치면 줄임표로 자른다.
 *
 * `ui/table.tsx` 의 `TableCell` 이 이미 `whitespace-nowrap` 이라 여기서는 넘침·줄임표만
 * 더한다. 실제로 잘리려면 셀 폭이 확정돼야 하므로 {@link IssueTable} 의 `table-fixed` +
 * `<colgroup>` 과 **짝**이다 — 셋 중 하나만 빠져도 텍스트가 열을 밀어낸다.
 */
const TRUNCATE_CELL_CLASS = 'overflow-hidden text-ellipsis'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 스타일 상수 — 셀 렌더 함수 간 className 중복 제거
// ─────────────────────────────────────────────────────────────────────────────

/** 보조 정보 텍스트(GAP-4) — 키·상태·수정일 등 시각 위계상 subtle한 컬럼 */
const SUBTLE_TEXT_CLASS = 'text-(--text-subtle)'

/** 기본 본문 텍스트 — 담당자·우선순위 등 일반 정보 컬럼 */
const DEFAULT_TEXT_CLASS = 'text-(--text-default)'

// ─────────────────────────────────────────────────────────────────────────────
// 셀 렌더 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 유형 셀 렌더 — 아이콘만(Jira 리스트 뷰의 Type 열과 같다).
 *
 * 아이콘 컴포넌트는 보드·백로그 카드가 이미 쓰는 {@link IssueTypeIcon} 을 그대로 재사용한다
 * (계약 §4 — 새로 만들지 않는다). 색은 `currentColor` 를 타고 내려가므로 감싸는 span 의
 * `text-type-*` 하나로 칠해진다 — `IssueTypeIcon` 을 건드리지 않는 이유다.
 */
function renderTypeCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  return createElement(
    'span',
    { className: `inline-flex ${issueTypeColorClass(issue.typeKey)}` },
    createElement(IssueTypeIcon, { iconName: ctx.typeIconName, typeName: ctx.typeName }),
  )
}

/**
 * 키 셀 렌더 — 행 링크(★e2e 계약 보존).
 *
 * href·aria-label={issue.key}·onClick(preventDefault→onNavigate)는 기존 IssueCard
 * (routes/issues.index.tsx)의 링크 계약을 verbatim 유지한다. 여기에 stopPropagation을
 * 추가로 더해 {@link IssueTable}의 행(tr) 클릭 핸들러가 중복 호출되지 않게 한다
 * (행 전체 클릭 네비게이션은 IssueTable이 tr onClick으로 별도 제공).
 */
function renderKeyCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  return createElement(
    'a',
    {
      href: `/issues/${issue.key}`,
      'aria-label': issue.key,
      className: `font-mono text-xs font-medium ${SUBTLE_TEXT_CLASS} hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded`,
      onClick: (event: ReactMouseEvent<HTMLAnchorElement>) => {
        event.preventDefault()
        event.stopPropagation()
        ctx.onNavigate()
      },
    },
    issue.key,
  )
}

/** 요약 셀 렌더 — ★e2e 계약 보존(data-testid=issue-summary-{key}). 시각 주역(GAP-4) */
function renderSummaryCell(issue: IssueResponse): ReactNode {
  return createElement(
    'span',
    {
      'data-testid': `issue-summary-${issue.key}`,
      title: issue.summary,
      className: `block truncate font-medium ${DEFAULT_TEXT_CLASS}`,
    },
    issue.summary,
  )
}

/**
 * 상태 배지 셀 렌더 — ★e2e 계약 보존(role=status).
 *
 * `ctx.edit`가 있으면 편집 셀로 바뀌지만 배지의 `role="status"`는 그대로 살아 있다
 * (즉사 계약, FR9). 배지 마크업 정본은 {@link StatusCellDisplay} 하나뿐이라 두 경로가
 * 어긋날 수 없다.
 */
function renderStatusCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (!isEditEnabled(ctx.edit)) {
    return createElement(StatusCellDisplay, {
      currentStateKey: issue.currentStateKey,
      statusName: ctx.statusName,
      category: ctx.statusCategory,
    })
  }
  return createElement(StatusCell, {
    issue,
    listQueryKey: ctx.edit.listQueryKey,
    statusName: ctx.statusName,
    category: ctx.statusCategory,
  })
}

/**
 * 담당자 셀 렌더 — assigneeNameMap 해석 결과. 미배정/해석 실패 시 "미배정".
 *
 * ★열람 숨김이면 **읽기 전용 경로에서도** 값을 그리지 않는다. 백엔드가 열람 불가
 * `assigneeId` 를 null 로 마스킹하므로 그대로 그리면 담당자가 있는데 "미배정" 이라고
 * 말하게 된다. 편집 경로의 차단은 {@link AssigneeCell} 이 스스로 한다(직접 렌더돼도
 * 안전하도록) — 여기서는 편집이 꺼진 경로만 메운다.
 */
function renderAssigneeCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (!isEditEnabled(ctx.edit)) {
    return createElement(AssigneeCellDisplay, {
      assigneeName: ctx.assigneeName,
      isRestricted: isFieldHidden('assigneeId', issue.restrictedFields),
    })
  }
  return createElement(AssigneeCell, {
    issue,
    assigneeName: ctx.assigneeName,
    listQueryKey: ctx.edit.listQueryKey,
  })
}

/**
 * 우선순위 셀 렌더.
 *
 * 표기 정본은 `issueDetailStrings.priorityNames`(한국어)이고 해석은
 * {@link PriorityCellDisplay} 안에서 한다 — 백엔드 `issue.priorityName`(영어)은 화면에
 * 쓰지 않는다(Maxi 확정 2026-08-04). 여기서 미리 해석해 넘기면 순수 컬럼 정의 모듈에
 * i18n 결합이 새어 들어간다.
 */
function renderPriorityCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (!isEditEnabled(ctx.edit)) {
    return createElement(PriorityCellDisplay, { priority: issue.priority })
  }
  return createElement(PriorityCell, { issue, listQueryKey: ctx.edit.listQueryKey })
}

/** 수정일 셀 렌더 — 사용자 dateFormat 프리셋 기준(GAP-4, 보조 정보라 subtle) */
function renderUpdatedAtCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  return createElement('span', { className: SUBTLE_TEXT_CLASS }, ctx.formatDate(issue.updatedAt))
}

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 정의 목록
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 테이블 컬럼 정의 전체 목록.
 *
 * 순서 = 렌더 순서(체크박스 다음). 정렬가능 4개(key·summary·priority·updatedAt)는
 * "정렬 필드 계약" 5종(key·summary·priority·createdAt·updatedAt) 중 createdAt을 제외한
 * 부분집합이다 — createdAt은 헤더가 없는 백엔드 기본 정렬(무지정 시 fallback) 전용 토큰이라
 * 이 컬럼 목록에 노출하지 않는다.
 */
export const ISSUE_COLUMNS: readonly IssueColumnDef[] = [
  // ★`required: true` — 「목록에 이슈 유형이 안 나온다」의 재발 방지다. 컬럼 표시 목록은
  //   localStorage 에 영속되는데(`use-column-visibility`), 이미 저장된 배열에는 `type` 이
  //   없다. 선택 가능(required:false)으로 두면 **기존 사용자에게는 영영 안 보인다.**
  //   Jira 리스트 뷰도 Type·Key·Summary 를 앞머리 식별 3열로 고정 노출한다.
  {
    key: 'type',
    header: '유형',
    sortable: false,
    required: true,
    defaultWidth: 52,
    className: TRUNCATE_CELL_CLASS,
    render: renderTypeCell,
  },
  {
    key: 'key',
    header: '키',
    sortable: true,
    required: true,
    defaultWidth: 92,
    className: TRUNCATE_CELL_CLASS,
    render: renderKeyCell,
  },
  {
    key: 'summary',
    header: '요약',
    sortable: true,
    required: true,
    // ★종전 `w-full`(=100%) 은 auto 레이아웃에서 이 열을 **가장 긴 요약의 실제 텍스트
    //   폭**까지 늘려, 요약과 상태 배지 사이에 화면 절반짜리 공백을 만들고 뒤 열
    //   (담당자·우선순위·수정일)을 가로 스크롤 밖으로 밀어냈다(Maxi 지적 2026-09-07).
    //   이제 폭은 {@link IssueTable} 의 `<colgroup>` 이 px 로 못박고 사용자가 드래그로
    //   바꾼다. `table-fixed` 와 **짝**이다 — 하나만 있으면 다시 내용 폭으로 늘어난다.
    defaultWidth: 280,
    className: TRUNCATE_CELL_CLASS,
    render: renderSummaryCell,
  },
  {
    key: 'status',
    header: '상태',
    sortable: false,
    required: false,
    defaultWidth: 128,
    className: TRUNCATE_CELL_CLASS,
    render: renderStatusCell,
  },
  {
    key: 'assignee',
    header: '담당자',
    sortable: false,
    required: false,
    defaultWidth: 128,
    className: TRUNCATE_CELL_CLASS,
    render: renderAssigneeCell,
  },
  {
    key: 'priority',
    header: '우선순위',
    sortable: true,
    required: false,
    // 아이콘이 붙어 종전 92px 로는 「가장 낮음」이 잘린다
    defaultWidth: 112,
    className: TRUNCATE_CELL_CLASS,
    render: renderPriorityCell,
  },
  {
    key: 'updatedAt',
    header: '수정일',
    sortable: true,
    required: false,
    defaultWidth: 112,
    className: TRUNCATE_CELL_CLASS,
    render: renderUpdatedAtCell,
  },
] as const

/**
 * 컬럼 기본 폭 맵 — `useColumnWidths` 의 `defaults` 인자로 그대로 넘긴다.
 *
 * 정본은 {@link ISSUE_COLUMNS} 의 `defaultWidth` 하나다. 호출부가 따로 상수를 만들면
 * 컬럼을 추가할 때 한쪽만 늘어나 새 컬럼이 폭 0 으로 렌더된다.
 */
export const ISSUE_COLUMN_DEFAULT_WIDTHS: Readonly<Record<string, number>> = Object.fromEntries(
  ISSUE_COLUMNS.map((column) => [column.key, column.defaultWidth]),
)

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * column.key가 "정렬 필드 계약" 5종에 속하는지 좁히는 타입 가드.
 *
 * 반환 타입은 `IssueColumnKey`와 `IssueSortField`의 교집합(key·summary·priority·
 * updatedAt 4종 — createdAt은 IssueColumnKey에 없음)이어야 타입 가드 제약(예측 타입이
 * 매개변수 타입의 부분집합)을 만족한다.
 */
function isIssueSortField(key: IssueColumnKey): key is Extract<IssueColumnKey, IssueSortField> {
  return (ISSUE_SORT_FIELDS as readonly string[]).includes(key)
}

/**
 * 컬럼이 정렬 헤더로 동작할 때 사용할 {@link IssueSortField}를 반환한다.
 * `column.sortable`이 false이거나 column.key가 정렬 필드 계약에 없으면 undefined.
 *
 * @param column 컬럼 정의
 * @returns 정렬 가능하면 IssueSortField, 아니면 undefined
 */
export function getSortField(column: IssueColumnDef): IssueSortField | undefined {
  return column.sortable && isIssueSortField(column.key) ? column.key : undefined
}
