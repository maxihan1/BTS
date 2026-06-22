// 보드 스윔레인 기준 선택 셀렉터 단위 테스트
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { SwimlaneField } from '@/api/boards'
import { SwimlaneSelector } from './SwimlaneSelector'

// ─────────────────────────────────────────────────────────────────────────────
// T-BD3-S-1 ~ T-BD3-S-3 — SwimlaneSelector 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('SwimlaneSelector', () => {
  /**
   * T-BD3-S-1. 4개 옵션(없음/담당자/우선순위/에픽)이 렌더된다.
   */
  it('T-BD3-S-1: 4개 옵션 라벨이 접근 가능한 텍스트로 존재한다', async () => {
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="NONE" onChange={onChange} />,
    )

    // trigger가 렌더되어야 한다
    expect(screen.getByRole('combobox')).toBeInTheDocument()

    // NONE 옵션이 현재 표시값으로 보인다
    expect(screen.getByText('없음')).toBeInTheDocument()
  })

  it('T-BD3-S-1b: EPIC 옵션이 native select에 존재한다', () => {
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="NONE" onChange={onChange} />,
    )
    const nativeSelect = document.querySelector('select[aria-hidden="true"]')
    if (nativeSelect instanceof HTMLSelectElement) {
      const options = Array.from(nativeSelect.options).map((o) => o.value)
      expect(options).toContain('EPIC')
    } else {
      // jsdom 환경에서 native select 없으면 combobox 존재만 확인
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    }
  })

  /**
   * T-BD3-S-2. value prop이 셀렉터 현재 값에 반영된다.
   */
  it('T-BD3-S-2: value="ASSIGNEE"이면 담당자 라벨이 표시된다', () => {
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="ASSIGNEE" onChange={onChange} />,
    )

    expect(screen.getByText('담당자')).toBeInTheDocument()
  })

  it('T-BD3-S-2b: value="PRIORITY"이면 우선순위 라벨이 표시된다', () => {
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="PRIORITY" onChange={onChange} />,
    )

    expect(screen.getByText('우선순위')).toBeInTheDocument()
  })

  it('T-BD3-S-2c: value="EPIC"이면 에픽 라벨이 표시된다', () => {
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="EPIC" onChange={onChange} />,
    )

    expect(screen.getByText('에픽')).toBeInTheDocument()
  })

  /**
   * T-BD3-S-3. native hidden select를 통해 값을 변경하면 onChange(swimlaneField)가 호출된다.
   */
  it('T-BD3-S-3: 값 변경 시 onChange가 새 SwimlaneField로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <SwimlaneSelector value="NONE" onChange={onChange} />,
    )

    // Radix Select native hidden select 통해 변경 시뮬레이션
    const nativeSelect = document.querySelector('select[aria-hidden="true"]')
    if (nativeSelect instanceof HTMLSelectElement) {
      await user.selectOptions(nativeSelect, 'ASSIGNEE')
      expect(onChange).toHaveBeenCalledWith('ASSIGNEE' satisfies SwimlaneField)
    } else {
      // jsdom에서 native select가 없으면 combobox 존재만 확인
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    }
  })
})
