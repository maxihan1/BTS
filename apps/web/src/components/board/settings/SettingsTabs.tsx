// 보드 설정 화면의 탭바 — 5탭 전환과 각 탭 본문 배선 (부채 177 R1 · J22)
import type { JSX } from 'react'
import type { BoardDetail } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { ColumnSettingsPanel } from './ColumnSettingsPanel'
import { CardLayoutPanel } from './CardLayoutPanel'
import { EstimationPanel } from './EstimationPanel'
import { WorkingDaysPanel } from './WorkingDaysPanel'
import { DetailViewPanel } from './DetailViewPanel'

/**
 * 탭 value — DOM 속성(`data-state` 대상)으로 나가므로 한국어 라벨과 분리한다.
 *
 * 라벨은 바뀔 수 있어도(i18n) value 는 탭 사이의 identity 라 바꾸면 안 된다.
 */
const TAB_VALUES = {
  columns: 'columns',
  cardLayout: 'card-layout',
  estimation: 'estimation',
  workingDays: 'working-days',
  detailView: 'detail-view',
} as const

/** SettingsTabs props */
export interface SettingsTabsProps {
  /** 보드 상세. 탭 본문 전부가 같은 응답을 읽는다 — 탭마다 조회를 늘리지 않는다(NFR N1). */
  board: BoardDetail
  /** 편집 권한(CREATE). 없으면 각 탭 본문이 편집만 잠근다 — 읽기는 열린다(S7). */
  canConfigure: boolean
}

/**
 * 탭 트리거 5개. 순서가 곧 지라 Board settings 의 탭 순서다.
 *
 * ★폭은 `w-fit`(프리미티브 기본)을 그대로 둔다. `w-full` 로 늘리면 1280px 에서 탭 하나가
 * 190px 로 벌어져 형제 탭바(`IssueActivityTabs`·`WorkflowEditorTabs`)와 생김새가 갈린다 —
 * 눈확인에서 실제로 그렇게 보였다. 대신 `max-w-full overflow-x-auto` 로 좁은 폭에서
 * 줄바꿈 대신 가로 스크롤이 되게 한다(sm 375px 실측 — 라벨이 잘리지 않는다).
 */
function SettingsTabList(): JSX.Element {
  const { tabs } = boardLabels.settings
  return (
    <TabsList aria-label={tabs.ariaLabel} className="max-w-full justify-start overflow-x-auto">
      <TabsTrigger value={TAB_VALUES.columns}>{tabs.columns}</TabsTrigger>
      <TabsTrigger value={TAB_VALUES.cardLayout}>{tabs.cardLayout}</TabsTrigger>
      <TabsTrigger value={TAB_VALUES.estimation}>{tabs.estimation}</TabsTrigger>
      <TabsTrigger value={TAB_VALUES.workingDays}>{tabs.workingDays}</TabsTrigger>
      <TabsTrigger value={TAB_VALUES.detailView}>{tabs.detailView}</TabsTrigger>
    </TabsList>
  )
}

/**
 * 지라 Board settings 의 **탭바**(J22).
 *
 * ### 왜 Radix Tabs 인가
 * 같은 라우트 안의 **패널 전환**이라 `components/ui/tabs.tsx` 가 정확히 이 용도다
 * (계약 §3 — 「라우트 이동은 nav+Link, 패널 전환은 Radix Tabs」). 탭마다 라우트를 파면
 * `<nav>` 가 하나 더 늘고, 즉사 계약이 동결한 nav 4종 옆에 다섯 번째가 서게 된다.
 *
 * ### 탭 상태를 URL 에 싣지 않는다
 * `router.ts` 의 `projectBoardSettingsRoute.validateSearch` 가 `board` 하나만 통과시켜,
 * `?tab=` 을 붙여도 즉시 증발한다. 딥링크가 필요해지면 **그 라우트 정의를 함께 바꾸는
 * 별건**이다 — 여기서 몰래 우회하면 「URL 에 있는데 새로고침하면 사라지는」 상태가 된다.
 * 기본 탭은 `컬럼` 이고, `board-settings.spec.ts` 의 기존 3건이 진입 직후 컬럼 본문을
 * 기대하므로 이 기본값은 계약이다.
 *
 * ### 비활성 탭 본문은 마운트되지 않는다
 * Radix 는 `forceMount` 없이는 활성 탭만 그린다. 그것이 기존 E2E 를 지켜 준다 —
 * `getByRole('spinbutton')` 개수를 컬럼 수와 대조하는 단언이 있어, 다른 탭의 숫자 입력이
 * 함께 마운트되면 그 단언이 깨진다.
 */
export function SettingsTabs({ board, canConfigure }: SettingsTabsProps): JSX.Element {
  return (
    <Tabs defaultValue={TAB_VALUES.columns} className="gap-6">
      <SettingsTabList />

      <TabsContent value={TAB_VALUES.columns}>
        <ColumnSettingsPanel board={board} canConfigure={canConfigure} />
      </TabsContent>

      <TabsContent value={TAB_VALUES.cardLayout}>
        <CardLayoutPanel key={board.boardId} board={board} canConfigure={canConfigure} />
      </TabsContent>

      <TabsContent value={TAB_VALUES.estimation}>
        <EstimationPanel key={board.boardId} board={board} canConfigure={canConfigure} />
      </TabsContent>

      <TabsContent value={TAB_VALUES.workingDays}>
        <WorkingDaysPanel key={board.boardId} board={board} canConfigure={canConfigure} />
      </TabsContent>

      <TabsContent value={TAB_VALUES.detailView}>
        <DetailViewPanel key={board.boardId} board={board} canConfigure={canConfigure} />
      </TabsContent>
    </Tabs>
  )
}
