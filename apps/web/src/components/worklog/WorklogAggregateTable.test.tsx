// WorklogAggregateTable 단위 테스트 — 행 렌더·label 폴백·합계·빈 배열·formatSeconds 위임
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WorklogAggregateBucket, AggregateDimension } from '@/api/worklog-aggregate'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'
import { WorklogAggregateTable } from './WorklogAggregateTable'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

const bucketAlice: WorklogAggregateBucket = {
  key: 'a1b2c3d4-0000-4000-8000-aabbccddeef0',
  label: '김앨리스',
  timeSpentSeconds: 50400, // 14h 0m
  worklogCount: 3,
}

const bucketBob: WorklogAggregateBucket = {
  key: 'b2c3d4e5-0000-4000-8000-aabbccddeef1',
  label: '이밥',
  timeSpentSeconds: 9000, // 2h 30m
  worklogCount: 1,
}

const bucketNoLabel: WorklogAggregateBucket = {
  key: 'c3d4e5f6-0000-4000-8000-aabbccddeef2',
  label: '',
  timeSpentSeconds: 3600, // 1h 0m
  worklogCount: 2,
}

const dimension: AggregateDimension = 'user'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateTable', () => {
  it('buckets가 빈 배열이어도 throw 없이 빈 tbody를 렌더한다', () => {
    render(
      <WorklogAggregateTable buckets={[]} total={0} dimension={dimension} />,
    )
    // 테이블 헤더는 존재해야 한다
    expect(
      screen.getByText(worklogAggregateLabels.table.headerLabel),
    ).toBeInTheDocument()
  })

  it('각 행에 label이 표시된다', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketAlice, bucketBob]}
        total={59400}
        dimension={dimension}
      />,
    )
    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.getByText('이밥')).toBeInTheDocument()
  })

  it('label이 빈 문자열이면 placeholder "(알 수 없음)"을 표시한다 (key UUID는 노출 금지)', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketNoLabel]}
        total={3600}
        dimension={dimension}
      />,
    )
    expect(
      screen.getByText(worklogAggregateLabels.table.unknownDisplayName),
    ).toBeInTheDocument()
    // UUID key가 화면에 직접 노출되지 않아야 한다
    expect(screen.queryByText(bucketNoLabel.key)).not.toBeInTheDocument()
  })

  it('소요 시간은 formatSeconds 결과(50400 → "14h 0m")로 표시한다', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketAlice]}
        total={50400}
        dimension={dimension}
      />,
    )
    // 행 셀 + 합계 행 양쪽에 "14h 0m"이 있으므로 getAllByText 사용
    const cells = screen.getAllByText('14h 0m')
    expect(cells.length).toBeGreaterThanOrEqual(1)
  })

  it('9000초는 "2h 30m"으로 표시한다', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketBob]}
        total={9000}
        dimension={dimension}
      />,
    )
    const cells = screen.getAllByText('2h 30m')
    expect(cells.length).toBeGreaterThanOrEqual(1)
  })

  it('worklogCount가 각 행에 표시된다', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketAlice, bucketBob]}
        total={59400}
        dimension={dimension}
      />,
    )
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('total을 formatSeconds로 합계 행/footer에 표시한다', () => {
    // 59400 = 16h 30m
    render(
      <WorklogAggregateTable
        buckets={[bucketAlice, bucketBob]}
        total={59400}
        dimension={dimension}
      />,
    )
    expect(screen.getByText('16h 30m')).toBeInTheDocument()
    expect(
      screen.getByText(worklogAggregateLabels.table.totalRow),
    ).toBeInTheDocument()
  })

  it('헤더 3개(레이블·소요 시간·건수)가 모두 렌더된다', () => {
    render(
      <WorklogAggregateTable buckets={[]} total={0} dimension={dimension} />,
    )
    expect(
      screen.getByText(worklogAggregateLabels.table.headerLabel),
    ).toBeInTheDocument()
    expect(
      screen.getByText(worklogAggregateLabels.table.headerTimeSpent),
    ).toBeInTheDocument()
    expect(
      screen.getByText(worklogAggregateLabels.table.headerCount),
    ).toBeInTheDocument()
  })

  it('여러 버킷이 모두 렌더된다', () => {
    render(
      <WorklogAggregateTable
        buckets={[bucketAlice, bucketBob, bucketNoLabel]}
        total={63000}
        dimension={dimension}
      />,
    )
    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.getByText('이밥')).toBeInTheDocument()
    expect(
      screen.getByText(worklogAggregateLabels.table.unknownDisplayName),
    ).toBeInTheDocument()
  })
})
