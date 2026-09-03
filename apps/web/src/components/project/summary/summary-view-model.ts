// 요약 화면 순수 변환 — 응답 조각을 화면이 쓰는 형태로 (Fast Refresh 경고 해소 · project-list-paths 선례)
import {
  resolveWindowDelta,
  type AssigneeSlice,
  type PrioritySlice,
  type ProjectActivityEntry,
  type ProjectSummary,
  type StatusSlice,
  type TypeSlice,
  type WindowCount,
} from '@/api/project-summary'
import { resolveFieldLabel, type ChangelogRefs } from '@/lib/changelog-labels'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 카드 문구
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 창 카운트 쌍에서 카드 하단에 쓸 델타 문구를 만든다.
 *
 * 목업이 요구하는 표기는 `'+4'` · `'변동 없음'` 두 가지다. 부호는 계산 결과로 붙이고
 * 「변동 없음」은 문자열 상수를 쓴다 — `'+0'` 은 목업에 없는 표기다.
 *
 * @param count 현재·직전 창 값 쌍
 * @returns 델타 문구
 */
export function formatWindowDelta(count: WindowCount): string {
  const { diff, direction } = resolveWindowDelta(count)
  if (direction === 'flat') return labels.delta.flat
  const sign = diff > 0 ? '+' : ''
  return `${sign}${String(diff)} ${labels.delta.comparedTo}`
}

/**
 * 마감 카드의 하단 문구를 만든다 — 지연 건수가 델타 자리를 대신한다.
 *
 * @param overdue 마감일이 지난 미완료 이슈 수
 * @returns 지연 문구
 */
export function formatOverdue(overdue: number): string {
  if (overdue === 0) return labels.delta.noOverdue
  return `${String(overdue)}${labels.delta.overdueSuffix}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 분포 행 — 4종이 공유하는 최소 형태
// ─────────────────────────────────────────────────────────────────────────────

/** 분포 막대 한 줄 */
export interface DistributionRow {
  /** 행 식별자 — React key */
  readonly id: string
  /** 표시명 */
  readonly label: string
  /** 이슈 수 */
  readonly count: number
}

/**
 * 상태 조각을 막대 행으로 바꾼다. 표시명이 없으면 상태 키로 폴백한다 —
 * 워크플로우 스킴 해석이 실패해도 화면이 빈칸을 보여주지 않는다.
 */
export function statusRows(slices: readonly StatusSlice[]): DistributionRow[] {
  return slices.map((s) => ({
    id: s.statusKey,
    label: s.statusName ?? s.statusKey,
    count: s.count,
  }))
}

/** 우선순위 조각을 막대 행으로. 표시명이 없으면 숫자를 그대로 쓴다 */
export function priorityRows(slices: readonly PrioritySlice[]): DistributionRow[] {
  return slices.map((s) => ({
    id: String(s.priority),
    label: s.priorityName ?? String(s.priority),
    count: s.count,
  }))
}

/** 작업 유형 조각을 막대 행으로. 두 필드 모두 백엔드가 non-null 로 보장한다 */
export function typeRows(slices: readonly TypeSlice[]): DistributionRow[] {
  return slices.map((s) => ({ id: s.typeKey, label: s.typeName, count: s.count }))
}

/**
 * 담당자 조각을 막대 행으로.
 *
 * 두 가지 부재를 **다르게** 표시한다 — `assigneeId` 부재는 미할당 묶음이고,
 * `assigneeName` 부재는 사용자 조회 실패다. 둘을 같은 문구로 뭉치면 「미할당이 갑자기
 * 늘었다」는 오독을 만든다.
 */
export function assigneeRows(slices: readonly AssigneeSlice[]): DistributionRow[] {
  return slices.map((s, index) => {
    if (s.assigneeId === null || s.assigneeId === undefined) {
      return { id: 'unassigned', label: labels.distribution.unassigned, count: s.count }
    }
    return {
      id: s.assigneeId,
      label: s.assigneeName ?? `${labels.distribution.unknownAssignee} ${String(index + 1)}`,
      count: s.count,
    }
  })
}

/** 요약 응답에서 분포 위젯 4종의 행을 한 번에 만든다 */
export function distributionRowsOf(summary: ProjectSummary): {
  status: DistributionRow[]
  priority: DistributionRow[]
  types: DistributionRow[]
  assignees: DistributionRow[]
} {
  return {
    status: statusRows(summary.statusOverview),
    priority: priorityRows(summary.priorityBreakdown),
    types: typeRows(summary.typesOfWork),
    assignees: assigneeRows(summary.teamWorkload),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 활동 피드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 표시명 해석에 쓰는 빈 참조 데이터.
 *
 * 요약 화면의 활동 위젯은 **개요**다 — 값의 상세 해석(담당자 UUID·컴포넌트명 등)은
 * 이슈 상세가 한다. `resolveFieldLabel` 은 정적 라벨 맵을 먼저 보고 못 찾으면 필드 키로
 * 폴백하므로 빈 refs 로도 안전하고, 커스텀 필드는 키가 그대로 보인다.
 *
 * 이 위젯이 refs 를 채우려면 유형·컴포넌트·버전·커스텀필드 4종을 더 조회해야 하는데,
 * 그 비용은 착지 화면이 감당할 것이 아니다.
 */
const EMPTY_REFS: ChangelogRefs = {
  types: [],
  components: [],
  versions: [],
  priorityMap: {},
  impactMap: {},
  customFieldDefinitions: [],
}

/**
 * 활동 항목 하나를 한 줄 요약 문구로 만든다.
 *
 * 첫 변경 필드의 표시명을 쓰고, 나머지가 있으면 `외 N건` 을 붙인다. 항목이 0개인
 * 그룹(마스킹으로 전부 제거된 경우)은 빈 문자열을 낸다 — 호출자가 그 줄을 감춘다.
 *
 * @param entry 활동 항목
 * @returns 한 줄 요약. 표시할 변경이 없으면 빈 문자열
 */
export function summarizeEntry(entry: ProjectActivityEntry): string {
  const first = entry.items[0]
  if (first === undefined) return ''
  const head = resolveFieldLabel(first.field, EMPTY_REFS)
  const rest = entry.items.length - 1
  if (rest <= 0) return head
  return `${head} ${labels.activity.moreItemsPrefix}${String(rest)}${labels.activity.moreItemsSuffix}`
}

/**
 * 활동 시각을 화면 표기로 바꾼다 — 파싱 실패는 원문을 그대로 낸다.
 *
 * @param iso ISO-8601 시각 문자열
 * @returns 로컬 표기. 파싱 불가면 입력 그대로
 */
export function formatActivityTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString()
}
