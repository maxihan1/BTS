// BoardCard 컴포넌트 단위 테스트 — 카드 정보 표시·담당자 3-상태·링크·useSortable 배선 + 카드 레이아웃(부채 177 Task 20)
//
// ## S8 이 지는 판정 — 각 축이 무엇과 무엇을 가르나 (부채 177 Task 20 · J19·J18·E4)
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 뷰 축(보드) | **같은** `cardLayout` 에서 보드 카드는 `BOARD` 만 (T-CX-1) | 한 목록을 두 뷰가 공유 ↔ 스코프를 갈라 읽음 (R3·J18) |
// | ② 뷰 축(백로그) | **같은** `cardLayout` 에서 백로그 카드는 `BACKLOG` 만 (T-CX-2) | 한 목록을 두 뷰가 공유 ↔ 스코프를 갈라 읽음 |
// | ③ 구성 반영 | 구성을 바꾸면 카드가 바뀐다 (T-CX-3) | 구성을 무시한 고정 카드 ↔ 구성을 읽음 |
// | ④ 뷰 fallback 부재 | 보드 카드가 `BACKLOG` 로 넘어가지 않는다 (T-CX-4) | `BOARD ?? BACKLOG` ↔ 자기 스코프만 (칸반엔 백로그가 없다) |
// | ⑤ 미설정 뷰 | 구성이 없으면 층의 DOM 자체가 없다 (T-CX-5) | 빈 `<div/>`·기본값 채움 ↔ `?? []` 후 층 생략 |
// | ⑥ 3층 순서 | 요약 → 추가 필드 → 상세 (T-CX-6) | 추가 필드가 요약 위·하단 행 아래 ↔ J19 층 순서 |
// | ⑦ 구성 순서 | 추가 필드가 **구성 순서** 그대로 (T-CX-7) | `sorted()`·카탈로그 순서·집합 ↔ 받은 순서 보존 |
// | ⑧ E4 대조군 | 같은 목록에서 값이 **있는** 카드만 그 칸을 갖는다 (T-CX-8) | 전부 생략 / 전부 렌더(빈 칸) ↔ 카드별 판정 |
// | ⑨ 커스텀 접두사 | `cf_x` 를 `customFields['x']` 로 찾는다 (T-CX-9) | 접두사째 조회(값 못 찾아 전부 생략) ↔ `cf_` 제거 |
// | ⑩ 커스텀 부재 | 값이 없는 커스텀 필드는 생략 (T-CX-10) | 빈 칸 렌더 ↔ 생략 |
// | ⑪ 백로그 커스텀 | 백로그 카드도 접두사를 뗀다 (T-CX-11) | 보드만 구현 ↔ 두 카드가 같은 규약 |
// | ⑫ 요약 비-토글 | 구성에 `SUMMARY` 가 섞여도 추가 필드가 되지 않는다 (T-CX-12) | 요약이 두 번 ↔ 후보에서 탈락 (R2·J19 1층) |
// | ⑬ 재-마스킹 부재 | 두 카드에 권한 판정 코드가 0줄 (T-CX-13) | 화면이 다시 거름 ↔ 어댑터 결과를 그대로 신뢰 (Task 25) |
//
// ★**①②는 짝으로만 산다.** 한쪽만 두면 「한 목록을 두 뷰가 공유하는」 구현이 통과한다 —
//   둘은 **같은 객체**를 두 카드에 주고 각자 자기 것만 그리는지를 잰다(계획의 RED 문장).
// ★**⑧은 양쪽에서 죽인다.** 「전부 생략하는」 구현은 값이 있는 카드에서, 「전부 그리는」 구현은
//   값이 없는 카드에서 죽는다. 한 카드만 렌더하면 둘 중 하나가 살아남는다.
// ★**⑦의 기대값은 원래(카탈로그) 순서와도 사전순과도 다르게** 골랐다 —
//   `['PRIORITY', 'cf_story_points', 'EPIC']` 은 카탈로그 순서(`EPIC`→`PRIORITY`→커스텀)와도
//   키 사전순(`EPIC`<`PRIORITY`<`cf_…`)과도 어긋나므로 `sorted()` 구현이 여기서 죽는다.
import { describe, it, expect, vi } from 'vitest'
import type { ReactNode } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen, within } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import * as sortableModule from '@dnd-kit/sortable'
import type { BoardCard as BoardCardType, CardLayout } from '@/api/boards'
import type { BacklogIssue } from '@/api/backlog'

// @dnd-kit/sortable — useSortable 호출 인자(id/data) 검증을 위해 실제 구현을 감싼 spy로 mock
vi.mock('@dnd-kit/sortable', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/sortable')>()
  return { ...actual, useSortable: vi.fn(actual.useSortable) }
})

// TanStack Router Link — 라우터 컨텍스트 없이 단위 테스트 가능하도록 mock
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => (
    <a
      href={params ? to.replace('$key', params['key'] ?? '') : to}
      className={className}
      onClick={onClick}
      data-testid="issue-link"
    >
      {children}
    </a>
  ),
}))

import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { BacklogCard } from '@/components/backlog/BacklogCard'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const baseCard: BoardCardType = {
  issueKey: 'ATLAS-42',
  summary: '칸반 보드 드래그앤드롭 구현',
  assigneeId: 'user-uuid-0001',
  version: 3,
  priority: 1,
  epicKey: null,
  rank: null,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const COLUMN_ID = 'col-uuid-0001'

function renderCard(
  card: BoardCardType = baseCard,
  assignee: CardAssigneeDisplay = { state: 'named', name: '김철수' },
  typeIconName: string | null = 'task',
  typeName = '작업',
) {
  return render(
    <DndContext>
      <BoardCard
        card={card}
        columnId={COLUMN_ID}
        assignee={assignee}
        typeIconName={typeIconName}
        typeName={typeName}
      />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S1 기본 렌더', () => {
  it('S1a: issueKey를 보조 텍스트로 표시한다', () => {
    renderCard()
    expect(screen.getByText('ATLAS-42')).toBeInTheDocument()
  })

  it('S1b: summary를 주 텍스트로 표시한다', () => {
    renderCard()
    expect(screen.getByText('칸반 보드 드래그앤드롭 구현')).toBeInTheDocument()
  })

  it('S1c: 이슈 상세 링크가 /issues/ATLAS-42 href를 갖는다', () => {
    renderCard()
    const link = screen.getByTestId('issue-link')
    expect(link).toHaveAttribute('href', '/issues/ATLAS-42')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 담당자 표시 3-상태
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S2 담당자 표시 3-상태', () => {
  it('S2a: assignee={state:"named"} 이면 이름 첫 글자 이니셜을 아바타로 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    expect(screen.getByText('김')).toBeInTheDocument()
  })

  it('S2b: assignee={state:"named"} 이면 title 속성에 전체 이름을 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    const avatar = screen.getByTitle('김철수')
    expect(avatar).toBeInTheDocument()
  })

  it('S2c: assignee={state:"named"} 이면 aria-label에 "담당자: 김철수"를 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    expect(screen.getByLabelText('담당자: 김철수')).toBeInTheDocument()
  })

  it('S2d: assignee={state:"unassigned"} 이면 "미배정" 텍스트를 표시한다', () => {
    const unassigned: BoardCardType = { ...baseCard, assigneeId: null }
    renderCard(unassigned, { state: 'unassigned' })
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S2e: assignee={state:"unassigned"} 이면 아바타가 없다', () => {
    const unassigned: BoardCardType = { ...baseCard, assigneeId: null }
    renderCard(unassigned, { state: 'unassigned' })
    expect(screen.queryByTitle('담당자 (이름 미확인)')).not.toBeInTheDocument()
    // named 아바타도 없어야 함 — 어떤 title도 없음
    expect(document.querySelector('[title]')).not.toBeInTheDocument()
  })

  it('S2f: assignee={state:"unknown"} 이면 "?" 아바타를 표시한다 — 미배정 아님', () => {
    renderCard(baseCard, { state: 'unknown' })
    // "?" 텍스트가 표시되어야 함
    expect(screen.getByText('?')).toBeInTheDocument()
    // "미배정" 텍스트는 없어야 함 — 배정됐으나 이름 미확인
    expect(screen.queryByText('미배정')).not.toBeInTheDocument()
  })

  it('S2g: assignee={state:"unknown"} 이면 title="담당자 (이름 미확인)"을 표시한다', () => {
    renderCard(baseCard, { state: 'unknown' })
    expect(screen.getByTitle('담당자 (이름 미확인)')).toBeInTheDocument()
  })

  it('S2h: assignee={state:"unknown"} 이면 aria-label="담당자 이름 미확인"을 표시한다', () => {
    renderCard(baseCard, { state: 'unknown' })
    expect(screen.getByLabelText('담당자 이름 미확인')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드래그 affordance
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S3 드래그 affordance', () => {
  it('S3a: aria-roledescription="draggable card" 속성이 있다', () => {
    renderCard()
    // @dnd-kit이 role="button" + aria-roledescription="draggable card"를 자동 부여
    expect(
      document.querySelector('[aria-roledescription="draggable card"]'),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. useSortable 배선 — id/data(fromColumnId)
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S4 useSortable 배선', () => {
  it('S4a: useSortable을 id=issueKey, data={fromColumnId}로 호출한다', () => {
    renderCard()
    expect(sortableModule.useSortable).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'ATLAS-42',
        data: { fromColumnId: COLUMN_ID },
      }),
    )
  })

  it('S4b: 드래그 중(isDragging)이면 원위치 카드에 opacity 저하 클래스가 적용된다', () => {
    renderCard()
    const card = document.querySelector('[aria-roledescription="draggable card"]')
    // 렌더 직후(isDragging=false)에는 opacity-50이 없어야 함 — 회귀 방지용 음성 가드
    expect(card?.className).not.toMatch(/opacity-50/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 유형 아이콘 · 라벨 칩 · 추정 배지 (FR-UX-14 F14 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S5 유형 아이콘·라벨 칩·추정 배지 (FR-UX-14 F14 Task 3)', () => {
  it('S5a: 유형 아이콘이 이슈 키 왼쪽에 유형 이름으로 읽힌다', () => {
    renderCard(baseCard, undefined, 'bug', '버그') // S1 · NFR4
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })

  it('S5b: 유형을 해석 못하면 typeKey 원문이 접근성 이름이 된다', () => {
    renderCard(baseCard, undefined, null, 'custom-type') // FR6 · E6
    expect(screen.getByRole('img', { name: 'custom-type' })).toBeInTheDocument()
  })

  it('S5c: 라벨이 없으면 칩 행이 DOM 에 없다', () => {
    renderCard({ ...baseCard, labels: [] }) // S3 · E1
    expect(document.querySelector('[data-slot="badge"]')).not.toBeInTheDocument()
  })

  it('S5d: 라벨이 있으면 칩 행이 라벨 텍스트로 표시된다', () => {
    renderCard({ ...baseCard, labels: ['백엔드'] })
    expect(screen.getByText('백엔드')).toBeInTheDocument()
  })

  it('S5e: 추정이 null 이면 배지가 DOM 에 없다', () => {
    renderCard({ ...baseCard, originalEstimateSeconds: null }) // S6 · E2
    expect(screen.queryByLabelText(/^추정 /)).not.toBeInTheDocument()
  })

  it('S5f: 추정이 있으면 배지가 "시간h 분m" 형식으로 표시된다', () => {
    renderCard({ ...baseCard, originalEstimateSeconds: 9000 })
    expect(screen.getByLabelText('추정 2h 30m')).toBeInTheDocument()
  })

  it('S5g: 카드 루트의 aria-label 과 aria-roledescription 이 변하지 않는다', () => {
    // FR13 · jira-parity-contract.md §2 — 새 요소는 카드 내부에만, 루트 속성은 문자열 그대로 보존
    renderCard()
    const cardEl = document.querySelector('[aria-roledescription="draggable card"]')
    expect(cardEl).toHaveAttribute('aria-label', `${baseCard.issueKey} — ${baseCard.summary}`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 카드 레이아웃 — 뷰 스코프 · 3층 순서 · E4 생략 (부채 177 Task 20 · J19·J18)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 카드 픽스처.
 *
 * 보드 카드와 **다른 이슈 키**를 쓴다 — 한 테스트에서 두 카드를 함께 렌더할 때
 * 루트 `aria-label`(`{키} — {요약}`)이 겹치면 `getByLabelText` 가 즉사한다.
 */
const backlogIssueFixture: BacklogIssue = {
  key: 'ATLAS-77',
  summary: '백로그 카드 레이아웃',
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 2,
  rank: null,
  version: 1,
  epicKey: null,
  typeKey: 'story',
  labels: [],
  originalEstimateSeconds: null,
}

/** 카드가 나르는 커스텀 필드 값 맵. **키에 `cf_` 접두사가 없다**(Task 8 규약). */
type CustomFieldValues = Record<string, unknown>

function renderBoardCardWithLayout(options: {
  card?: Partial<BoardCardType> & { customFields?: CustomFieldValues }
  cardLayout?: CardLayout
  assignee?: CardAssigneeDisplay
} = {}) {
  const { card, cardLayout, assignee } = options
  return render(
    <DndContext>
      <BoardCard
        card={{ ...baseCard, ...card }}
        columnId={COLUMN_ID}
        assignee={assignee ?? { state: 'named', name: '김철수' }}
        typeIconName="task"
        typeName="작업"
        cardLayout={cardLayout}
      />
    </DndContext>,
  )
}

function renderBacklogCardWithLayout(options: {
  issue?: Partial<BacklogIssue> & { customFields?: CustomFieldValues }
  cardLayout?: CardLayout
} = {}) {
  const { issue, cardLayout } = options
  return render(
    <DndContext>
      <BacklogCard
        issue={{ ...backlogIssueFixture, ...issue }}
        context="backlog"
        assigneeName="김철수"
        typeIconName="story"
        typeName="스토리"
        cardLayout={cardLayout}
      />
    </DndContext>,
  )
}

/** 추가 필드 층에 실제로 그려진 필드 키를 **DOM 순서 그대로** 뽑는다. */
function extraFieldKeys(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('[data-card-field]')).map(
    (el) => el.getAttribute('data-card-field') ?? '',
  )
}

/**
 * ★뷰 축 대조군이 쓰는 **하나의** 구성 객체.
 *
 * 보드와 백로그에 이 **같은 객체**를 주고 각자 자기 스코프만 그리는지 잰다 —
 * 한 목록을 두 뷰가 공유하는 구현은 둘 중 한쪽에서 반드시 죽는다.
 */
const VIEW_AXIS_LAYOUT: CardLayout = { BOARD: ['EPIC'], BACKLOG: ['LABELS'] }

describe('BoardCard/BacklogCard — S8 카드 레이아웃 (부채 177 Task 20)', () => {
  it('T-CX-1: 보드 카드는 같은 구성에서 BOARD 스코프만 그린다 (①)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100', labels: ['ui'] },
      cardLayout: VIEW_AXIS_LAYOUT,
    })
    expect(extraFieldKeys(container)).toEqual(['EPIC'])
  })

  it('T-CX-2: 백로그 카드는 같은 구성에서 BACKLOG 스코프만 그린다 (②)', () => {
    const { container } = renderBacklogCardWithLayout({
      issue: { epicKey: 'ATLAS-100', labels: ['ui'] },
      cardLayout: VIEW_AXIS_LAYOUT,
    })
    expect(extraFieldKeys(container)).toEqual(['LABELS'])
  })

  it('T-CX-3: 구성을 바꾸면 카드가 바뀐다 (③)', () => {
    const first = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100', originalEstimateSeconds: 9000 },
      cardLayout: { BOARD: ['EPIC'] },
    })
    expect(extraFieldKeys(first.container)).toEqual(['EPIC'])
    first.unmount()

    const second = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100', originalEstimateSeconds: 9000 },
      cardLayout: { BOARD: ['ESTIMATE'] },
    })
    expect(extraFieldKeys(second.container)).toEqual(['ESTIMATE'])
  })

  it('T-CX-4: 보드 카드는 BACKLOG 구성으로 넘어가지 않는다 — 칸반에는 백로그 스코프가 없다 (④)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100' },
      cardLayout: { BACKLOG: ['EPIC'] },
    })
    expect(extraFieldKeys(container)).toEqual([])
  })

  it('T-CX-5: 구성이 없는 뷰는 추가 필드 층의 DOM 자체가 없다 (⑤)', () => {
    const { container } = renderBoardCardWithLayout({ card: { epicKey: 'ATLAS-100' } })
    expect(container.querySelector('[data-testid="card-extra-fields"]')).toBeNull()
    expect(extraFieldKeys(container)).toEqual([])
  })

  it('T-CX-6: 요약 → 추가 필드 → 상세 순서다 (⑥ · J19 3층)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100' },
      cardLayout: { BOARD: ['EPIC'] },
    })
    const summary = screen.getByTestId('issue-link')
    const field = container.querySelector('[data-card-field="EPIC"]')
    if (field === null) throw new Error('추가 필드 층이 없다')
    const issueKeyText = screen.getByText(baseCard.issueKey)

    // 요약이 추가 필드보다 **앞**
    expect(summary.compareDocumentPosition(field) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 추가 필드가 하단 상세 행보다 **앞**
    expect(field.compareDocumentPosition(issueKeyText) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('T-CX-7: 추가 필드는 구성 순서 그대로다 — 카탈로그 순서도 사전순도 아니다 (⑦)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100', customFields: { story_points: 8 } },
      cardLayout: { BOARD: ['PRIORITY', 'cf_story_points', 'EPIC'] },
    })
    expect(extraFieldKeys(container)).toEqual(['PRIORITY', 'cf_story_points', 'EPIC'])
  })

  it('T-CX-8: 같은 목록에서 에픽이 있는 카드만 그 칸을 갖는다 — 빈 칸을 그리지 않는다 (⑧ · E4)', () => {
    render(
      <DndContext>
        <BoardCard
          card={{ ...baseCard, issueKey: 'ATLAS-1', summary: '에픽 있음', epicKey: 'ATLAS-100' }}
          columnId={COLUMN_ID}
          assignee={{ state: 'unassigned' }}
          typeIconName="task"
          typeName="작업"
          cardLayout={{ BOARD: ['EPIC'] }}
        />
        <BoardCard
          card={{ ...baseCard, issueKey: 'ATLAS-2', summary: '에픽 없음', epicKey: null }}
          columnId={COLUMN_ID}
          assignee={{ state: 'unassigned' }}
          typeIconName="task"
          typeName="작업"
          cardLayout={{ BOARD: ['EPIC'] }}
        />
      </DndContext>,
    )

    const withEpic = screen.getByLabelText('ATLAS-1 — 에픽 있음')
    const withoutEpic = screen.getByLabelText('ATLAS-2 — 에픽 없음')

    expect(extraFieldKeys(withEpic)).toEqual(['EPIC'])
    expect(within(withEpic).getByText('ATLAS-100')).toBeInTheDocument()

    expect(extraFieldKeys(withoutEpic)).toEqual([])
    expect(withoutEpic.querySelector('[data-testid="card-extra-fields"]')).toBeNull()
  })

  it('T-CX-9: cf_ 를 떼고 customFields 에서 값을 찾는다 (⑨)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { customFields: { story_points: 8 } },
      cardLayout: { BOARD: ['cf_story_points'] },
    })
    expect(extraFieldKeys(container)).toEqual(['cf_story_points'])
    expect(screen.getByText('8')).toBeInTheDocument()
  })

  it('T-CX-10: 그 이슈에 없는 커스텀 필드는 그 카드에서만 생략한다 (⑩ · E4)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { customFields: {} },
      cardLayout: { BOARD: ['cf_story_points'] },
    })
    expect(extraFieldKeys(container)).toEqual([])
    expect(container.querySelector('[data-testid="card-extra-fields"]')).toBeNull()
  })

  it('T-CX-11: 백로그 카드도 cf_ 를 떼고 값을 찾는다 (⑪)', () => {
    const { container } = renderBacklogCardWithLayout({
      issue: { customFields: { severity: 'critical' } },
      cardLayout: { BACKLOG: ['cf_severity'] },
    })
    expect(extraFieldKeys(container)).toEqual(['cf_severity'])
    expect(screen.getByText('critical')).toBeInTheDocument()
  })

  it('T-CX-12: 구성에 SUMMARY 가 섞여 와도 추가 필드가 되지 않는다 (⑫ · R2 · J19 1층)', () => {
    const { container } = renderBoardCardWithLayout({
      card: { epicKey: 'ATLAS-100' },
      cardLayout: { BOARD: ['SUMMARY', 'EPIC'] },
    })
    expect(extraFieldKeys(container)).toEqual(['EPIC'])
    expect(screen.getAllByText(baseCard.summary)).toHaveLength(1)
  })

  it('T-CX-13: 두 카드에 권한 판정 코드가 한 줄도 없다 — 마스킹은 어댑터가 끝냈다 (⑬ · Task 25)', () => {
    const sources = [
      readFileSync(resolve(__dirname, 'BoardCard.tsx'), 'utf8'),
      readFileSync(resolve(__dirname, '../backlog/BacklogCard.tsx'), 'utf8'),
    ]
    for (const source of sources) {
      // 주석은 벗겨 낸다 — KDoc 은 "다시 거르지 않는다" 를 **설명**해야 하므로 낱말이 등장한다.
      const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
      expect(code).not.toMatch(/permission|canView|hasPermission|mask|redact/i)
    }
  })
})
