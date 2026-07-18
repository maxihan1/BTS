// Table 프리미티브 단위 테스트 — role="table" 노출, 캡션·컬럼헤더·셀·푸터 렌더 검증
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableRow,
  TableHead,
  TableCell,
  TableCaption,
} from './table'

describe('Table', () => {
  it('role="table"로 노출되고 캡션을 렌더한다', () => {
    render(
      <Table>
        <TableCaption>이슈 목록</TableCaption>
        <TableBody>
          <TableRow>
            <TableCell>버그 수정</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('이슈 목록')).toBeInTheDocument()
  })

  it('TableHead가 role="columnheader"로 렌더된다', () => {
    render(
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>요약</TableHead>
            <TableHead>상태</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow>
            <TableCell>버그 수정</TableCell>
            <TableCell>진행중</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    )

    expect(screen.getByRole('columnheader', { name: '요약' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '상태' })).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader')).toHaveLength(2)
    expect(screen.getByText('버그 수정')).toBeInTheDocument()
    expect(screen.getByText('진행중')).toBeInTheDocument()
  })

  it('TableFooter가 렌더된다', () => {
    render(
      <Table>
        <TableBody>
          <TableRow>
            <TableCell>버그 수정</TableCell>
          </TableRow>
        </TableBody>
        <TableFooter>
          <TableRow>
            <TableCell>합계 1건</TableCell>
          </TableRow>
        </TableFooter>
      </Table>,
    )

    expect(screen.getByText('합계 1건')).toBeInTheDocument()
  })
})
