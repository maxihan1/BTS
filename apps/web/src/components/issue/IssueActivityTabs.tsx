// 이슈 상세 활동 영역(작업로그/연결/이력/댓글) 탭 컨테이너 — ui/tabs 첫 소비자 (FR-UX-06 PR19 Task 1 / FR-CO-01)
import type { JSX, RefObject } from 'react'
import type { IssueResponse } from '@/api/issues'
import type { ChangelogRefs } from '@/lib/changelog-labels'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { WorklogSection } from '@/components/issue/WorklogSection'
import { CommentSection } from '@/components/issue/CommentSection'
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
 * TabsList 렌더 순서(댓글 → 이력 → 작업로그 → 연결)와 일치한다.
 *
 * ★순서가 Jira Cloud 의 Activity 필터 순서다(J3) — Comments · History · Work log.
 * 「연결」은 Jira 에 대응이 없는 BTS 고유 탭이라 끝에 둔다.
 */
export const ACTIVITY_TABS = {
  COMMENT: 'comment',
  HISTORY: 'history',
  WORKLOG: 'worklog',
  LINKS: 'links',
} as const

/** ACTIVITY_TABS 값 유니온 타입 */
export type ActivityTabValue = (typeof ACTIVITY_TABS)[keyof typeof ACTIVITY_TABS]

/** 탭 value 전량 — Radix 가 돌려주는 `string` 을 유니온으로 좁히는 데 쓴다 */
const ACTIVITY_TAB_VALUES = Object.values(ACTIVITY_TABS)

/**
 * Radix `onValueChange` 가 주는 `string` 을 {@link ActivityTabValue} 로 좁힌다.
 *
 * `as` 캐스팅 대신 실제 목록 조회로 좁히는 이유. 탭 value 상수를 고치면서 소비처를
 * 빠뜨리면 캐스팅은 조용히 통과하지만 이 조회는 `null` 을 내 상위로 전달되지 않는다.
 *
 * @param value Radix 가 돌려준 탭 value 문자열
 * @returns 알려진 탭이면 그 값, 아니면 null
 */
function toActivityTab(value: string): ActivityTabValue | null {
  return ACTIVITY_TAB_VALUES.find((tab) => tab === value) ?? null
}

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
  // ── FR-UX-10 F11 단축키 `m` 배선 3종 ────────────────────────────────────────
  //
  // ★왜 탭 소유권을 밖으로 내보내는가. Radix Tabs 는 **비활성 탭 콘텐츠를 언마운트**한다.
  // 기본 활성 탭이 「이력」이라 댓글 작성 textarea 는 평소 DOM 에 없고, ref 만 통과시키면
  // `m` 이 언제나 null 을 만나 조용히 무동작이 된다(스펙 S3 는 포커스 이동을 요구한다).
  // 단축키가 탭을 먼저 열어야 하므로 활성 탭을 라우트가 소유한다.
  /** 활성 탭 값 — 전달하면 controlled, 미전달이면 기존대로 「이력」 기본 uncontrolled */
  value?: ActivityTabValue
  /** 탭 전환 콜백 — `value` 와 짝으로만 의미가 있다 */
  onValueChange?: (value: ActivityTabValue) => void
  /** 댓글 작성 textarea 로 통과시킬 ref — 단축키 `m` 의 포커스 대상 */
  commentInputRef?: RefObject<HTMLDivElement | null>
  /** 딥링크로 데려갈 댓글 UUID — 그대로 `CommentSection` 에 넘긴다 */
  focusCommentId?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — IssueActivityTabs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 활동 영역(작업로그/연결/이력)을 Radix Tabs 3탭으로 접는 컨테이너.
 *
 * - 같은 라우트 내 패널 전환(ADR §D4 정본) — uncontrolled `defaultValue`, URL 미동기화.
 * - 기본 활성 탭=**댓글**.
 *
 *   ★2026-09-04 에 「이력」에서 번복했다. 원래 근거는 「변경 이력은 생성 이벤트가 항상 있어 빈
 *   첫인상을 회피한다」(Maxi 게이트 D1)였다. Jira Cloud 실물이 "By default the activity feed shows
 *   comments"(J3, `what-are-the-different-types-of-activity-on-an-issue`, 2026-09-04 조회)라
 *   패리티를 택했다. 빈 첫인상 우려는 유효하지만, 이슈를 열었을 때 가장 먼저 보고 싶은 것이
 *   「누가 뭐라 했나」라는 판단이 그보다 앞선다. `jira-parity-roadmap.md` 의 `F7 댓글 기본탭` 이
 *   이 변경으로 닫힌다.
 * - 비활성 탭 콘텐츠는 Radix 기본 동작(lazy)대로 언마운트 — 연결/이력 탭은 활성 시점에 fetch된다.
 * - 각 하위 섹션에 전달하는 props는 기존 route 배선과 동일하게 verbatim 보존한다.
 *
 * @param issueKey 이슈 식별 키
 * @param canUpdate 수정 권한 여부
 * @param issue 부모/에픽/타입 판정에 필요한 이슈 데이터
 * @param changelogRefs 변경 이력 값 표시명 해석용 참조 데이터
 * @param value 활성 탭 값 (controlled). 미전달이면 「이력」 기본 uncontrolled
 * @param onValueChange 탭 전환 콜백 — `value` 와 짝
 * @param commentInputRef 댓글 작성 textarea 로 통과시킬 ref (단축키 `m`)
 * @param focusCommentId 딥링크로 데려갈 댓글 UUID
 */
export function IssueActivityTabs({
  issueKey,
  canUpdate,
  issue,
  changelogRefs,
  value,
  onValueChange,
  commentInputRef,
  focusCommentId,
}: IssueActivityTabsProps): JSX.Element {
  const showEpicSection = issue.typeKey !== 'epic' && issue.typeKey !== 'subtask'

  return (
    <Tabs
      value={value}
      defaultValue={value === undefined ? ACTIVITY_TABS.COMMENT : undefined}
      onValueChange={(next) => {
        const tab = toActivityTab(next)
        if (tab !== null) onValueChange?.(tab)
      }}
      className="mt-8"
    >
      <TabsList>
        <TabsTrigger value={ACTIVITY_TABS.COMMENT}>
          {issueDetailStrings.activityCommentTabLabel}
        </TabsTrigger>
        <TabsTrigger value={ACTIVITY_TABS.HISTORY}>
          {issueDetailStrings.activityHistoryTabLabel}
        </TabsTrigger>
        <TabsTrigger value={ACTIVITY_TABS.WORKLOG}>
          {issueDetailStrings.activityWorklogTabLabel}
        </TabsTrigger>
        <TabsTrigger value={ACTIVITY_TABS.LINKS}>
          {issueDetailStrings.activityLinksTabLabel}
        </TabsTrigger>
      </TabsList>

      <TabsContent value={ACTIVITY_TABS.COMMENT}>
        <CommentSection
          issueKey={issueKey}
          canUpdate={canUpdate}
          focusRef={commentInputRef}
          focusCommentId={focusCommentId}
        />
      </TabsContent>

      <TabsContent value={ACTIVITY_TABS.HISTORY}>
        <IssueChangelog issueKey={issueKey} refs={changelogRefs} />
      </TabsContent>

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
    </Tabs>
  )
}
