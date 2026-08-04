// 이슈 테이블 컬럼 정의 — key·header·sortable·required·render 메타데이터 (FR-UX-06 Phase 5 PR18 Task 4)
import { createElement } from 'react'
import type { MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import { ISSUE_SORT_FIELDS } from '@/api/issues'
import type { IssueResponse, IssueSortField } from '@/api/issues'
import { AssigneeCell, AssigneeCellDisplay } from './cells/AssigneeCell'
import { PriorityCell, PriorityCellDisplay } from './cells/PriorityCell'
import { StatusCell, StatusCellDisplay } from './cells/StatusCell'

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
export type IssueColumnKey = 'key' | 'summary' | 'status' | 'assignee' | 'priority' | 'updatedAt'

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
  /** `<th>`/`<td>` 공통 className(GAP-4 폭·시각 위계) */
  className: string
}

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
    return createElement(StatusCellDisplay, { currentStateKey: issue.currentStateKey })
  }
  return createElement(StatusCell, { issue, listQueryKey: ctx.edit.listQueryKey })
}

/** 담당자 셀 렌더 — assigneeNameMap 해석 결과. 미배정/해석 실패 시 "미배정" */
function renderAssigneeCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (!isEditEnabled(ctx.edit)) {
    return createElement(AssigneeCellDisplay, { assigneeName: ctx.assigneeName })
  }
  return createElement(AssigneeCell, {
    issue,
    assigneeName: ctx.assigneeName,
    listQueryKey: ctx.edit.listQueryKey,
  })
}

/** 우선순위 셀 렌더 — priorityName 텍스트 그대로 */
function renderPriorityCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (!isEditEnabled(ctx.edit)) {
    return createElement(PriorityCellDisplay, { priorityName: issue.priorityName })
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
  { key: 'key', header: '키', sortable: true, required: true, className: 'w-24', render: renderKeyCell },
  {
    key: 'summary',
    header: '요약',
    sortable: true,
    required: true,
    className: 'w-full',
    render: renderSummaryCell,
  },
  { key: 'status', header: '상태', sortable: false, required: false, className: 'w-28', render: renderStatusCell },
  {
    key: 'assignee',
    header: '담당자',
    sortable: false,
    required: false,
    className: 'w-36',
    render: renderAssigneeCell,
  },
  {
    key: 'priority',
    header: '우선순위',
    sortable: true,
    required: false,
    className: 'w-24',
    render: renderPriorityCell,
  },
  {
    key: 'updatedAt',
    header: '수정일',
    sortable: true,
    required: false,
    className: 'w-32',
    render: renderUpdatedAtCell,
  },
] as const

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
