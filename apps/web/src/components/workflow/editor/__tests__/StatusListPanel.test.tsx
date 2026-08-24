// 상태 패널 판정 — 순서 계산(순수) · 제거 버튼 고유 이름 · 잠금
import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { workflowEditorLabels as L } from '@/i18n/workflow-editor-labels'
import { StatusListPanel, reorderIds } from '../StatusListPanel'
import type { PanelStatus } from '../StatusListPanel'

const STATUSES: PanelStatus[] = [
  { id: 'a', key: 'open', name: 'Open', category: 'TODO' },
  { id: 'b', key: 'wip', name: 'In Progress', category: 'IN_PROGRESS' },
  { id: 'c', key: 'done', name: 'Done', category: 'DONE' },
]

describe('reorderIds — 순서 계산', () => {
  it('앞의 것을 뒤로 옮긴다', () => {
    expect(reorderIds(['a', 'b', 'c'], 'a', 'c')).toEqual(['b', 'c', 'a'])
  })

  it('뒤의 것을 앞으로 옮긴다', () => {
    expect(reorderIds(['a', 'b', 'c'], 'c', 'a')).toEqual(['c', 'a', 'b'])
  })

  it('이웃끼리 맞바꾼다', () => {
    expect(reorderIds(['a', 'b', 'c'], 'a', 'b')).toEqual(['b', 'a', 'c'])
  })

  it('★ 결과가 전체 목록이다 — 부분 목록을 보내면 백엔드가 400 이다', () => {
    const out = reorderIds(['a', 'b', 'c'], 'b', 'c')
    expect(out).toHaveLength(3)
    expect([...out].sort()).toEqual(['a', 'b', 'c'])
  })

  it('제자리에 놓으면 원본을 그대로 준다 (참조 동일 — 호출부가 무동작으로 가른다)', () => {
    const ids = ['a', 'b', 'c']
    expect(reorderIds(ids, 'b', 'b')).toBe(ids)
  })

  it('모르는 id 면 원본을 그대로 준다', () => {
    const ids = ['a', 'b', 'c']
    expect(reorderIds(ids, 'zzz', 'a')).toBe(ids)
    expect(reorderIds(ids, 'a', 'zzz')).toBe(ids)
  })
})

describe('StatusListPanel', () => {
  function setup(overrides: Partial<React.ComponentProps<typeof StatusListPanel>> = {}) {
    const onAdd = vi.fn()
    const onRemove = vi.fn()
    const onReorder = vi.fn()
    render(
      <StatusListPanel
        statuses={STATUSES}
        onAdd={onAdd}
        onRemove={onRemove}
        onReorder={onReorder}
        {...overrides}
      />,
    )
    return { onAdd, onRemove, onReorder }
  }

  it('주어진 순서 그대로 그린다 — 패널은 정렬하지 않는다', () => {
    setup()
    const rows = within(screen.getByRole('list', { name: L.statusPanel.list })).getAllByRole('listitem')
    expect(rows.map((r) => r.textContent ?? '')).toEqual([
      expect.stringContaining('Open'),
      expect.stringContaining('In Progress'),
      expect.stringContaining('Done'),
    ])
  })

  it('제거·드래그핸들 버튼 이름이 상태마다 다르다 — strict mode 충돌 방지', () => {
    setup()
    const names = screen.getAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent ?? '')
    const scoped = names.filter((n) => n.startsWith(L.statusPanel.remove) || n.startsWith(L.statusPanel.dragHandle))
    expect(scoped).toHaveLength(STATUSES.length * 2)
    expect(new Set(scoped).size).toBe(scoped.length)
  })

  it('제거를 누르면 그 상태 객체가 콜백에 실린다', async () => {
    const { onRemove } = setup()
    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} In Progress` }))
    expect(onRemove).toHaveBeenCalledWith(STATUSES[1])
  })

  it('비어 있으면 빈 상태를 보여주고 목록을 그리지 않는다', () => {
    setup({ statuses: [] })
    expect(screen.getByText(L.statusPanel.empty)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: L.statusPanel.list })).not.toBeInTheDocument()
  })

  it('disabled 면 추가·제거·드래그핸들이 전부 잠긴다', () => {
    setup({ disabled: true })
    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
  })
})
