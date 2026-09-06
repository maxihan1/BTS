// 프로젝트 크롬 계약 — 액션 포털 + 제목 소유권 판정 (Jira 패리티 J5-9 · J5-11)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ProjectChromeProvider, ProjectHeaderActions } from '@/components/project/ProjectChrome'
import { useProjectChromePresent } from '@/components/project/project-chrome-context'

/** `useProjectChromePresent` 의 결론을 화면에 찍는 프로브 */
function PresenceProbe(): React.JSX.Element {
  return <span data-testid="presence">{String(useProjectChromePresent())}</span>
}

describe('ProjectHeaderActions — 폴백 인라인', () => {
  it('Provider 가 없으면 자식을 제자리에 그대로 렌더한다', () => {
    // 🛑 이 분기가 전역 화면(`/issues`·`/dashboards`)의 진입점을 지킨다. 포털만 지원하면
    //    크롬 없는 화면에서 「새 이슈」 버튼이 통째로 증발한다.
    render(
      <div data-testid="inline-host">
        <ProjectHeaderActions>
          <button type="button">새 이슈</button>
        </ProjectHeaderActions>
      </div>,
    )

    expect(screen.getByTestId('inline-host')).toContainElement(
      screen.getByRole('button', { name: '새 이슈' }),
    )
  })

  it('크롬이 없다고 선언한 Provider 아래에서도 제자리에 렌더한다', () => {
    render(
      <ProjectChromeProvider present={false} actionHost={null}>
        <div data-testid="inline-host">
          <ProjectHeaderActions>
            <button type="button">새 이슈</button>
          </ProjectHeaderActions>
        </div>
      </ProjectChromeProvider>,
    )

    expect(screen.getByTestId('inline-host')).toContainElement(
      screen.getByRole('button', { name: '새 이슈' }),
    )
  })
})

describe('ProjectHeaderActions — 포털', () => {
  it('크롬이 있고 호스트가 잡혔으면 호스트 안으로 옮긴다', () => {
    const host = document.createElement('div')
    host.setAttribute('data-testid', 'action-host')
    document.body.appendChild(host)

    render(
      <ProjectChromeProvider present actionHost={host}>
        <div data-testid="inline-host">
          <ProjectHeaderActions>
            <button type="button">이슈 추가</button>
          </ProjectHeaderActions>
        </div>
      </ProjectChromeProvider>,
    )

    const button = screen.getByRole('button', { name: '이슈 추가' })
    expect(host).toContainElement(button)
    expect(screen.getByTestId('inline-host')).not.toContainElement(button)
  })

  it('크롬은 있는데 호스트가 아직 null 이면 아무것도 렌더하지 않는다', () => {
    // 콜백 ref 가 채워지기 전 첫 렌더다. 여기서 제자리에 그렸다가 다음 렌더에 포털로
    // 옮기면 버튼이 한 번 깜빡이고 자리를 밀어낸다.
    render(
      <ProjectChromeProvider present actionHost={null}>
        <ProjectHeaderActions>
          <button type="button">이슈 추가</button>
        </ProjectHeaderActions>
      </ProjectChromeProvider>,
    )

    expect(screen.queryByRole('button', { name: '이슈 추가' })).not.toBeInTheDocument()
  })
})

describe('useProjectChromePresent — 제목 소유권', () => {
  it('Provider 가 없으면 false 다 — 페이지가 자기 h1 을 갖는다', () => {
    render(<PresenceProbe />)

    expect(screen.getByTestId('presence')).toHaveTextContent('false')
  })

  it('크롬이 있으면 true 다 — 셸 헤더가 h1 을 갖는다', () => {
    render(
      <ProjectChromeProvider present actionHost={null}>
        <PresenceProbe />
      </ProjectChromeProvider>,
    )

    expect(screen.getByTestId('presence')).toHaveTextContent('true')
  })

  it('호스트가 null 이어도 present 는 그대로 true 다', () => {
    // 🛑 `present` 를 `actionHost !== null` 로 유도하면 안 되는 이유의 판별식이다.
    //    호스트는 콜백 ref 라 첫 렌더에 null 이고, 그때 페이지가 자기 h1 을 그리면
    //    문서에 h1 이 2개가 되는 순간이 생긴다. 유닛은 첫 렌더만 보므로 조용히 통과한다.
    render(
      <ProjectChromeProvider present actionHost={null}>
        <PresenceProbe />
      </ProjectChromeProvider>,
    )

    expect(screen.getByTestId('presence')).toHaveTextContent('true')
  })
})
