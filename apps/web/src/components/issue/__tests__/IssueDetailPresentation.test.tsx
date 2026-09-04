// 상세 표시 방식 배타 렌더 판별식 — 모달과 사이드패널은 동시에 뜨지 않는다 (Jira 패리티 J1)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueDetailModal } from '../IssueDetailModal'
import { IssueDetailSidePanel } from '../IssueDetailSidePanel'
import { useIssueDetailModalStore } from '../issueDetailModalStore'

const mockNavigate = vi.fn()
let mockPathname = '/issues'
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useRouterState: ({ select }: { select: (s: unknown) => unknown }) =>
      select({ location: { pathname: mockPathname } }),
  }
})

const mockIsWide = vi.fn(() => true)
vi.mock('@/hooks/use-media-query', () => ({ useMediaQuery: () => mockIsWide() }))

/**
 * 상세 본문은 이 판별식의 대상이 아니다 — 어느 껍데기가 떴는지와, 그 안으로 무엇이 넘어가는지만 잰다.
 *
 * ★ `issueKey`·`variant` 를 data 속성으로 흘려 단언한다. mock 이 prop 을 통째로 삼키면
 *   「껍데기는 맞는데 안쪽이 빈」 상태가 유닛에서 영영 안 보인다.
 */
vi.mock('@/routes/issues.$key', () => ({
  IssueDetailPage: ({ issueKey, variant }: { issueKey: string; variant?: string }) => (
    <div data-testid="issue-detail-body" data-issue-key={issueKey} data-variant={variant} />
  ),
}))

/** 두 껍데기를 함께 마운트한다 — 배타는 한쪽만 봐서는 잴 수 없다. */
function renderBoth() {
  return render(
    <>
      <IssueDetailModal />
      <IssueDetailSidePanel />
    </>,
  )
}

/**
 * 사이드패널 영역 — `complementary` 가 아니라 `region` 이다(아래 P5 주석).
 *
 * ★`hidden: true` 가 필수다. Radix `Dialog` 는 열릴 때 **형제 노드에 `aria-hidden` 을 건다** —
 * 기본 조회는 접근성 트리만 보므로, 배타가 깨져 모달과 패널이 함께 렌더돼도 패널이 조회에서
 * 빠져 「패널 없음」이 통과한다. 뮤테이션 프로브가 이 공허를 실제로 잡아냈다(패널 배타를 끊고도
 * 전 판정 GREEN). 여기서 재려는 것은 「눈에 보이나」가 아니라 「DOM 에 아예 없나」다.
 */
function sidePanel() {
  return screen.queryByRole('region', { name: issueDetailStrings.sidePanelLabel, hidden: true })
}

describe('상세 표시 방식 — 모달 ↔ 사이드패널 배타 (J1)', () => {
  beforeEach(() => {
    mockNavigate.mockClear()
    mockIsWide.mockReturnValue(true)
    mockPathname = '/issues'
    useIssueDetailModalStore.setState({ openKey: null, presentation: 'modal' })
  })

  /** P1: 사이드바 선호로 열면 오른쪽 패널이 뜨고 모달은 뜨지 않는다. */
  it('P1: presentation=sidePanel 이면 패널만 뜨고 모달은 없다', () => {
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation: 'sidePanel' })
    renderBoth()

    expect(sidePanel()).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /** P2: 기본(모달) 선호면 모달만 뜬다 — 사이드패널이 함께 뜨면 같은 이슈가 두 번 그려진다. */
  it('P2: presentation=modal 이면 모달만 뜨고 패널은 없다', () => {
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation: 'modal' })
    renderBoth()

    expect(screen.getByRole('dialog', { name: '이슈 상세 ATLAS-1' })).toBeInTheDocument()
    expect(sidePanel()).not.toBeInTheDocument()
  })

  /** P3: 닫혀 있으면(`openKey === null`) 어느 선호에서도 아무것도 뜨지 않는다. */
  it.each(['modal', 'sidePanel'] as const)(
    'P3: openKey 가 null 이면 %s 에서도 아무것도 안 뜬다',
    (presentation) => {
      useIssueDetailModalStore.setState({ openKey: null, presentation })
      renderBoth()

      expect(sidePanel()).not.toBeInTheDocument()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    },
  )

  /**
   * P4: 패널이 상세를 `variant='pane'` 으로 넘긴다.
   * `pane` 이 아니면 헤더 닫기 버튼도 `⋯` 토글도 없어 **패널에서 빠져나올 길이 사라진다**.
   */
  it('P4: 패널은 상세를 variant="pane" 으로, 열린 키 그대로 넘긴다', () => {
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-7', presentation: 'sidePanel' })
    renderBoth()

    // ★ 패널 **안쪽**으로 좁힌다. 전역 조회로 두면 배타가 깨져 모달이 함께 떠 있어도
    //   모달 쪽 본문이 잡혀 초록이 된다 — 지키려던 것을 못 지키는 가짜 그린이다.
    const panel = screen.getByRole('region', { name: issueDetailStrings.sidePanelLabel, hidden: true })
    const body = within(panel).getByTestId('issue-detail-body')
    expect(body).toHaveAttribute('data-variant', 'pane')
    expect(body).toHaveAttribute('data-issue-key', 'ATLAS-7')
  })

  /**
   * P6: 좁은 화면에서는 사이드바 선호여도 **모달**이 맡는다.
   *
   * 상세 안쪽은 `1fr + 340px` 2단 그리드이고 그 분기는 뷰포트 기준이라, 패널이 좁으면
   * 메타패널과 `⋯` 가 화면 밖으로 밀려 **되돌아올 길이 사라진다**. 바로 옆 split view 도
   * 같은 이유로 `min-width: 1024px` 가드를 이미 갖고 있다.
   */
  it('P6: 좁은 화면이면 사이드바 선호여도 패널 대신 모달이 뜬다', () => {
    mockIsWide.mockReturnValue(false)
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation: 'sidePanel' })
    renderBoth()

    expect(sidePanel()).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '이슈 상세 ATLAS-1' })).toBeInTheDocument()
  })

  /**
   * P7: 화면을 옮기면 패널이 닫힌다.
   *
   * 패널은 `ShellLayout` 소유라 라우트를 모른다 — 닫아 주지 않으면 보드에서 연 상세가
   * `/admin` 옆에 그대로 붙어 있고, `/issues/KEY` 전체화면으로 가면 같은 이슈가 두 벌 뜬다.
   */
  it('P7: pathname 이 바뀌면 패널이 닫힌다', () => {
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation: 'sidePanel' })
    const { rerender } = renderBoth()
    expect(sidePanel()).toBeInTheDocument()

    mockPathname = '/dashboards'
    rerender(
      <>
        <IssueDetailModal />
        <IssueDetailSidePanel />
      </>,
    )

    expect(useIssueDetailModalStore.getState().openKey).toBeNull()
    expect(sidePanel()).not.toBeInTheDocument()
  })

  /**
   * P5: 패널은 `complementary` 랜드마크를 만들지 않는다.
   *
   * 사이드바(`Sidebar` 의 `<aside>`)가 이미 유일한 complementary 이고, 그 유일성에
   * `landmark.spec` L1 과 `ShellLayout.test` C3 가 걸려 있다. 여기서 `<aside>` 를 쓰면
   * 이 PR 과 무관한 두 판정이 함께 red 가 되고, `getByRole('complementary')` 로 사이드바를
   * 잡는 e2e 들(`sidebar-resize` 등)이 strict 위반으로 죽는다.
   */
  it('P5: 패널은 complementary 가 아니라 region 이다', () => {
    useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation: 'sidePanel' })
    renderBoth()

    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(sidePanel()).toBeInTheDocument()
  })
})
