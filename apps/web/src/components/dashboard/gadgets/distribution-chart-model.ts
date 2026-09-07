// 분포 차트 가젯의 순수 변환 — 컴포넌트에서 분리해 목 없이 단위 테스트한다
import type { distributionRowsOf } from '@/components/project/summary/summary-view-model'
import type { DistributionRow } from '@/components/project/summary/summary-view-model'

/**
 * 그룹 기준 — Jira 의 statistic type 에 대응한다.
 *
 * 값은 백엔드 `GadgetType.PIE_CHART.configFields` 의 `field` ENUM 과 **같아야 한다.**
 * 갈리면 저장은 되는데 화면이 「데이터 없음」으로만 뜬다.
 */
export type DistributionField = 'status' | 'priority' | 'issueType' | 'assignee'

/** 같은 데이터를 그리는 두 마크. */
export type DistributionVariant = 'pie' | 'bar'

/**
 * `field` 값으로 요약 집계 4종 중 하나를 고른다.
 *
 * ★`Record` 가 아니라 `switch` 인 이유 — 이 자리의 `field` 는 **저장된 layout JSON 에서 온
 * 문자열**이라 타입으로 좁혀지지 않는다. `Record` 는 키 누락을 잡지만 런타임에 목록 밖 값이
 * 들어오는 것은 못 막는다. `default` 로 빈 배열을 떨어뜨려 화면이 「표시할 이슈가 없습니다」로
 * 안전하게 착지하게 한다.
 *
 * ★컴포넌트 파일이 아니라 여기 있는 이유 — `react-refresh/only-export-components` 는
 * 컴포넌트 파일이 값을 함께 export 하는 것을 금지한다. `summary-view-model.ts` 가 세운 선례다.
 *
 * @param rows `distributionRowsOf` 결과.
 * @param field 그룹 기준. 저장된 config 값이라 `string | undefined` 다.
 * @returns 해당 분포 행. 알 수 없는 값이면 빈 배열.
 */
export function rowsForField(
  rows: ReturnType<typeof distributionRowsOf>,
  field: string | undefined,
): DistributionRow[] {
  switch (field) {
    case 'status':
      return rows.status
    case 'priority':
      return rows.priority
    case 'issueType':
      return rows.types
    case 'assignee':
      return rows.assignees
    default:
      return []
  }
}
