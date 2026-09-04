// 이슈 상세를 화면 오른쪽에 붙여 띄우는 전역 단일 마운트 패널 (Jira 패리티 J1)
import type { JSX } from 'react'
import { useNavigate } from '@tanstack/react-router'
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
 * ## `complementary` 를 쓰지 않는다
 *
 * 사이드바(`Sidebar`)가 이미 유일한 complementary 이고 `landmark.spec` L1 · `ShellLayout.test`
 * C3 가 그 유일성에 걸려 있다. 여기서 `<aside>` 를 쓰면 이 PR 과 무관한 판정들이 함께 red 가
 * 되고, `getByRole('complementary')` 로 사이드바를 잡는 e2e 가 strict 위반으로 죽는다.
 */
export function IssueDetailSidePanel(): JSX.Element | null {
  const openKey = useIssueDetailModalStore((s) => s.openKey)
  const presentation = useIssueDetailModalStore((s) => s.presentation)
  const close = useIssueDetailModalStore((s) => s.close)
  const open = useIssueDetailModalStore((s) => s.open)
  const navigate = useNavigate()

  if (openKey === null || presentation !== 'sidePanel') return null

  return (
    <section
      aria-label={issueDetailStrings.sidePanelLabel}
      // 고정폭 + 뷰포트 상한. 좁은 화면에서 본문을 다 먹지 않도록 45vw 로 묶는다.
      className="flex w-[560px] min-w-0 max-w-[45vw] shrink-0 flex-col overflow-y-auto border-l border-border bg-background"
    >
      <div className="px-2 py-2">
        {/* key 로 이슈 전환 시 인스턴스를 강제 재마운트한다 — confirmDelete/isEditingTitle 같은
            잔여 state 가 다음 이슈로 이월되면 **잘못된 이슈가 삭제된다**(split view CONCERNS-3
            과 같은 이유). */}
        <IssueDetailPage
          key={openKey}
          issueKey={openKey}
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
