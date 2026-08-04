// 편집 가능 셀 공통 래퍼 테스트 — 전파 차단 · 지연 마운트 · 어포던스 토큰 (FR-UX-11 F9 FR2·FR3·FR12)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EditableCell } from './EditableCell'

describe('EditableCell', () => {
  it('셀 클릭이 행 클릭 핸들러로 전파되지 않는다 (FR3)', async () => {
    const onRowClick = vi.fn()
    render(
      <table>
        <tbody>
          <tr onClick={onRowClick}>
            <td>
              <EditableCell label="우선순위 편집" display={<span>보통</span>}>
                <div>편집 내용</div>
              </EditableCell>
            </td>
          </tr>
        </tbody>
      </table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))

    expect(onRowClick).not.toHaveBeenCalled()
    expect(screen.getByText('편집 내용')).toBeInTheDocument()
  })

  it('닫혀 있는 동안 자식(popover 내용)을 마운트하지 않는다 (FR12·NFR1)', () => {
    const spy = vi.fn()
    function Probe() {
      spy()
      return <div>편집 내용</div>
    }

    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <Probe />
      </EditableCell>,
    )

    expect(spy).not.toHaveBeenCalled()
  })

  it('Esc 로 닫아도 행 클릭 핸들러가 불리지 않는다 (E2)', async () => {
    const onRowClick = vi.fn()
    render(
      <table>
        <tbody>
          <tr onClick={onRowClick}>
            <td>
              <EditableCell label="우선순위 편집" display={<span>보통</span>}>
                <div>편집 내용</div>
              </EditableCell>
            </td>
          </tr>
        </tbody>
      </table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('편집 내용')).not.toBeInTheDocument()
    expect(onRowClick).not.toHaveBeenCalled()
  })

  it('hover 어포던스가 실재하는 토큰을 참조한다 (Pass 5 실버그 회귀 가드)', () => {
    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <div />
      </EditableCell>,
    )

    const trigger = screen.getByRole('button', { name: '우선순위 편집' })
    // index.css 의 실제 토큰은 --border 다. --border-default 는 존재하지 않는다.
    // 계산값이 아니라 클래스 문자열을 본다 — jsdom 은 커스텀 프로퍼티를 해석하지 않아
    // 계산값 단언은 공허해진다 (F8 커서 단언 사고와 같은 함정).
    expect(trigger.className).toContain('hover:ring-(--border)')
    expect(trigger.className).not.toContain('--border-default')
  })
})
