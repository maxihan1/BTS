// 이슈 상세 활동 영역(작업로그/연결/이력) 탭 컨테이너 — ui/tabs 첫 소비자 (FR-UX-06 PR19 Task 1)
import type { JSX } from 'react'
import type { IssueResponse } from '@/api/issues'
import type { ChangelogRefs } from '@/lib/changelog-labels'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { WorklogSection } from '@/components/issue/WorklogSection'
import { IssueLinksPanel } from '@/components/issue/IssueLinksPanel'
import { EpicChildrenSection } from '@/components/issue/EpicChildrenSection'
import { LinkGraph } from '@/components/issue/LinkGraph'
import { IssueChangelog } from '@/components/issue/IssueChangelog'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 활동 탭 value (매직스트링 금지, DEVELOPMENT.md §2.3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활동 탭 value 상수.
 * TabsList 렌더 순서(작업로그 → 연결 → 이력)와 일치한다.
 */
export const ACTIVITY_TABS = {
  WORKLOG: 'worklog',
  LINKS: 'links',
  HISTORY: 'history',
} as const

/** ACTIVITY_TABS 값 유니온 타입 */
export type ActivityTabValue = (typeof ACTIVITY_TABS)[keyof typeof ACTIVITY_TABS]

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueActivityTabs props */
export interface IssueActivityTabsProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  issueKey: string
  /** 수정 권한 여부 — 작업로그 추가·연결 편집 활성화에 전파 */
  canUpdate: boolean
  /** 부모/에픽/타입 판정에 필요한 이슈 데이터 (연결 탭 조건부 렌더용) */
  issue: IssueResponse
  /** 변경 이력 값 표시명 해석용 참조 데이터 (이력 탭 — route가 수집해 주입) */
  changelogRefs: ChangelogRefs
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — IssueActivityTabs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 활동 영역(작업로그/연결/이력)을 Radix Tabs 3탭으로 접는 컨테이너.
 *
 * - 같은 라우트 내 패널 전환(ADR §D4 정본) — uncontrolled `defaultValue`, URL 미동기화.
 * - 기본 활성 탭=이력(변경 이력은 생성 이벤트가 항상 있어 빈 첫인상 회피, Maxi 게이트 D1).
 * - 비활성 탭 콘텐츠는 Radix 기본 동작(lazy)대로 언마운트 — 연결/이력 탭은 활성 시점에 fetch된다.
 * - 각 하위 섹션에 전달하는 props는 기존 route 배선과 동일하게 verbatim 보존한다.
 *
 * @param issueKey 이슈 식별 키
 * @param canUpdate 수정 권한 여부
 * @param issue 부모/에픽/타입 판정에 필요한 이슈 데이터
 * @param changelogRefs 변경 이력 값 표시명 해석용 참조 데이터
 */
export function IssueActivityTabs({
  issueKey,
  canUpdate,
  issue,
  changelogRefs,
}: IssueActivityTabsProps): JSX.Element {
  const showEpicSection = issue.typeKey !== 'epic' && issue.typeKey !== 'subtask'

  return (
    <Tabs defaultValue={ACTIVITY_TABS.HISTORY} className="mt-8">
      <TabsList>
        <TabsTrigger value={ACTIVITY_TABS.WORKLOG}>
          {issueDetailStrings.activityWorklogTabLabel}
        </TabsTrigger>
        <TabsTrigger value={ACTIVITY_TABS.LINKS}>
          {issueDetailStrings.activityLinksTabLabel}
        </TabsTrigger>
        <TabsTrigger value={ACTIVITY_TABS.HISTORY}>
          {issueDetailStrings.activityHistoryTabLabel}
        </TabsTrigger>
      </TabsList>

      <TabsContent value={ACTIVITY_TABS.WORKLOG}>
        <WorklogSection issueKey={issueKey} canUpdate={canUpdate} />
      </TabsContent>

      <TabsContent value={ACTIVITY_TABS.LINKS}>
        <IssueLinksPanel
          issueKey={issueKey}
          parent={issue.parent ?? null}
          epic={issue.epic ?? null}
          showEpicSection={showEpicSection}
          disabled={!canUpdate}
        />
        {issue.typeKey === 'epic' && (
          <EpicChildrenSection epicKey={issueKey} disabled={!canUpdate} />
        )}
        <LinkGraph issueKey={issueKey} />
      </TabsContent>

      <TabsContent value={ACTIVITY_TABS.HISTORY}>
        <IssueChangelog issueKey={issueKey} refs={changelogRefs} />
      </TabsContent>
    </Tabs>
  )
}
