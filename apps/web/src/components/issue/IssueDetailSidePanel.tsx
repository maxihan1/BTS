// 이슈 상세를 화면 오른쪽에 붙여 띄우는 전역 단일 마운트 패널 (Jira 패리티 J1)
import type { JSX } from 'react'
import { useEffect, useRef } from 'react'
import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useMediaQuery } from '@/hooks/use-media-query'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueDetailPage } from '@/routes/issues.$key'
import { useIssueDetailModalStore } from './issueDetailModalStore'

/**
 * 표시 방식이 `sidePanel` 일 때 상세가 사는 자리(`ShellLayout` 소유).
 *
 * ## 왜 모달과 한 컴포넌트로 묶지 않았나
 *
 * 마운트 지점이 다르다. 모달은 `__root.tsx` 의 전역 오버레이 자리에 살아도 되지만, 이 패널은
 * **본문을 밀어내야** 한다 — `ShellLayout` 의 `<main>` 과 형제인 flex 아이템이라야 목록·보드가
 * 좁아지면서 나란히 보인다. 오버레이로 덮으면 「옆에서 같이 본다」는 사이드바의 목적 자체가
 * 사라진다. 한 컴포넌트가 두 자리에 마운트될 수는 없으므로 껍데기를 둘로 나누고, 어느 쪽이
 * 뜰지는 스토어의 `presentation` 하나가 정한다(배타는 `IssueDetailPresentation.test` 가 지킨다).
 *
 * ## 안쪽이 `variant='pane'` 인 이유
 *
 * `IssueDetailModal` 과 같다 — pane 이 헤더 닫기 · `Escape` · `<h2>` 강등 · 콜백 위임을 전부
 * 갖고 있다. `⋯` 표시 방식 토글도 pane 에만 달리므로, pane 이 아니면 **패널에서 모달로 돌아갈
 * 길이 없어진다**.
 *
 * ## 좁은 화면에는 뜨지 않는다
 *
 * 상세 안쪽은 `1fr + 340px` 2단 그리드이고 그 분기는 **뷰포트** 기준이라, 패널이 좁아도 2단으로
 * 펼쳐져 메타패널이 화면 밖으로 밀린다 — 되돌릴 `⋯` 도 거기 있어 빠져나올 길이 사라진다.
 * 같은 이유로 바로 옆 split view 도 `min-width: 1024px` 가드를 이미 걸어 뒀다
 * (`issues.index.tsx` 의 `isWide`). 좁은 화면에서는 표시 방식과 무관하게 **모달**이 맡는다
 * (`IssueDetailModal` 이 같은 판정을 뒤집어 갖는다).
 *
 * ## `complementary` 를 쓰지 않는다
 *
 * 사이드바(`Sidebar`)가 이미 유일한 complementary 이고 `landmark.spec` L1 · `ShellLayout.test`
 * C3 가 그 유일성에 걸려 있다. 여기서 `<aside>` 를 쓰면 이 PR 과 무관한 판정들이 함께 red 가
 * 되고, `getByRole('complementary')` 로 사이드바를 잡는 e2e 가 strict 위반으로 죽는다.
 */
export function IssueDetailSidePanel(): JSX.Element | null {
  const openKey = useIssueDetailModalStore((s) => s.openKey)
  const openCommentId = useIssueDetailModalStore((s) => s.openCommentId)
  const presentation = useIssueDetailModalStore((s) => s.presentation)
  const close = useIssueDetailModalStore((s) => s.close)
  const open = useIssueDetailModalStore((s) => s.open)
  const navigate = useNavigate()
  const isWide = useMediaQuery('(min-width: 1024px)')
  const pathname = useRouterState({ select: (st) => st.location.pathname })

  /**
   * 화면을 옮기면 패널을 닫는다.
   *
   * 이 컴포넌트는 `ShellLayout` 소유라 라우트를 모른다 — 닫아 주지 않으면 보드에서 연 상세가
   * `/admin`·`/dashboards` 옆에 그대로 붙어 있고, `/issues/KEY` 전체화면으로 가면 **같은 이슈가
   * 전체화면과 패널에 동시에** 그려진다. 검색 파라미터(`?selected=`·필터)는 같은 화면 안의
   * 이동이므로 `pathname` 만 본다.
   */
  const prevPathRef = useRef(pathname)
  useEffect(() => {
    // ★첫 렌더에서는 닫지 않는다. 조건 없이 부르면 패널이 뜨는 그 순간 스스로 꺼진다
    //   (실측 — P1·P2·P4~P7 이 한꺼번에 red 였다). 실제로 **바뀐** 경우만 닫는다.
    if (prevPathRef.current === pathname) return
    prevPathRef.current = pathname
    close()
  }, [pathname, close])

  if (openKey === null || presentation !== 'sidePanel' || !isWide) return null

  return (
    <section
      aria-label={issueDetailStrings.sidePanelLabel}
      // 고정폭 + 뷰포트 상한. 상세는 안쪽이 `1fr + 340px` 2단 그리드이고 그 분기는 **뷰포트**
      // 기준(`lg:`)이라 패널이 좁아도 2단으로 펼쳐진다 — 560px 로 뒀더니 우측 메타패널이 잘려
      // 저장 버튼이 화면 밖으로 나갔다(눈확인에서 발견). 본문 400px + 메타 340px + gap 이
      // 들어갈 만큼 준다. 좁은 화면에서 목록을 다 먹지 않도록 55vw 로 묶는다.
      // ★`overflow-y-auto` 가 아니라 `overflow-hidden` 이다(J26). 패널 자신이 스크롤하면
      //   본문과 메타가 함께 밀려 Jira 의 「right scroll area」가 성립하지 않는다 — 스크롤은
      //   안쪽 두 열이 각자 갖고, 패널은 높이 경계만 준다.
      className="flex w-[800px] min-w-0 max-w-[55vw] shrink-0 flex-col overflow-hidden border-l border-border bg-background"
    >
      {/* `min-h-0` 이 없으면 flex 자식의 최소 높이가 콘텐츠 높이라 안쪽 스크롤이 죽는다. */}
      <div className="min-h-0 flex-1 px-2 pb-2">
        {/* key 로 이슈 전환 시 인스턴스를 강제 재마운트한다 — confirmDelete/isEditingTitle 같은
            잔여 state 가 다음 이슈로 이월되면 **잘못된 이슈가 삭제된다**(split view CONCERNS-3
            과 같은 이유). */}
        <IssueDetailPage
          key={openKey}
          issueKey={openKey}
          // 모달과 같은 스토어 값에서 온다 — 표시 방식이 달라도 딥링크는 같게 동작해야 한다.
          focusCommentId={openCommentId ?? undefined}
          variant="pane"
          onClose={close}
          // 옛 키로 열었을 때 새 키로 갈아탄다 — 패널을 닫고 다시 열 필요가 없다.
          onIssueRedirect={(newKey) => { open(newKey) }}
          // 삭제되면 패널을 닫는다. 배경이 목록이면 그 목록이 스스로 갱신된다.
          onIssueClosed={() => {
            close()
            void navigate({ to: '/issues' })
          }}
        />
      </div>
    </section>
  )
}
