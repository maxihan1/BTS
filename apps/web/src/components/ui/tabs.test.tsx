// Tabs 컴포넌트 단위 테스트 — tablist/tab/tabpanel 역할 노출 및 탭 전환 검증
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { Tabs, TabsList, TabsTrigger, TabsContent } from './tabs'

function renderTabs(): void {
  render(
    <Tabs defaultValue="tab-1">
      <TabsList>
        <TabsTrigger value="tab-1">첫 번째 탭</TabsTrigger>
        <TabsTrigger value="tab-2">두 번째 탭</TabsTrigger>
      </TabsList>
      <TabsContent value="tab-1">첫 번째 패널 내용</TabsContent>
      <TabsContent value="tab-2">두 번째 패널 내용</TabsContent>
    </Tabs>,
  )
}

describe('TC-1: 접근성 역할 노출', () => {
  it('role="tablist"와 role="tab" 2개, role="tabpanel"을 노출한다', () => {
    renderTabs()

    expect(screen.getByRole('tablist')).toBeInTheDocument()
    expect(screen.getAllByRole('tab')).toHaveLength(2)
    expect(screen.getByRole('tabpanel')).toBeInTheDocument()
  })
})

describe('TC-2: 탭 전환', () => {
  it('두 번째 탭 클릭 시 첫 패널이 사라지고 둘째 패널이 노출된다', async () => {
    const user = userEvent.setup()
    renderTabs()

    expect(screen.getByText('첫 번째 패널 내용')).toBeInTheDocument()
    expect(screen.queryByText('두 번째 패널 내용')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '두 번째 탭' }))

    expect(screen.queryByText('첫 번째 패널 내용')).not.toBeInTheDocument()
    expect(screen.getByText('두 번째 패널 내용')).toBeInTheDocument()
  })
})
