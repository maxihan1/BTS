// 이슈 상세를 모달로 띄우는 전역 단일 마운트 컴포넌트 (Jira 패리티 J1)
import type { JSX } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useMediaQuery } from '@/hooks/use-media-query'
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { IssueDetailPage } from '@/routes/issues.$key'
import { useIssueDetailModalStore } from './issueDetailModalStore'

/**
 * 앱 전역에 하나만 마운트되는 이슈 상세 모달(`__root.tsx` 소유).
 *
 * ## 왜 새 variant 를 만들지 않았나
 *
 * `IssueDetailPage` 는 이미 `variant='pane'` 을 갖고 있고(FR-UX-06 PR20), 그것이 모달에 필요한
 * 것을 **전부** 한다 — 헤더 닫기 버튼 · `Escape` 닫기 · 제목을 `<h2>` 로 강등(문서 `<h1>` 단일
 * 계약) · redirect/삭제를 콜백에 위임. `'modal'` 을 새로 만들면 같은 분기가 두 벌이 되고, 한쪽만
 * 고치는 순간 「페인에서는 되는데 모달에서는 안 되는」 차이가 생긴다.
 *
 * 그래서 이 컴포넌트는 `Dialog` 껍데기만 대고 안쪽은 `pane` 을 그대로 쓴다.
 * `showCloseButton={false}` 는 pane 이 자기 헤더에 이미 닫기 버튼을 그리기 때문이다 — 두 개를
 * 겹쳐 두면 어느 쪽이 진짜인지 사용자가 알 수 없다.
 *
 * ## 열림 조건
 *
 * 스토어의 `openKey` 하나가 소유한다. 진입점이 13곳이라 각자 상태를 들면 모달이 여러 개
 * 마운트될 수 있다 — `loginPromptStore`/`LoginDialog` 와 같은 구조다.
 *
 * ## Escape 가 두 번 처리되는 것에 대해
 *
 * Radix `Dialog` 가 `Escape` 를 잡아 `onOpenChange(false)` 를 부르고, `pane` 의 자체 핸들러도
 * 돈다. 둘 다 같은 `close()` 로 수렴하므로 무해하다 — 상태가 이미 null 이면 재설정이 no-op 이다.
 */
export function IssueDetailModal(): JSX.Element | null {
  const openKey = useIssueDetailModalStore((s) => s.openKey)
  const openCommentId = useIssueDetailModalStore((s) => s.openCommentId)
  const presentation = useIssueDetailModalStore((s) => s.presentation)
  const close = useIssueDetailModalStore((s) => s.close)
  const open = useIssueDetailModalStore((s) => s.open)
  const navigate = useNavigate()
  const isWide = useMediaQuery('(min-width: 1024px)')

  // 표시 방식이 사이드바면 `IssueDetailSidePanel` 이 대신 그린다. 둘 다 스토어의 같은
  // `openKey` 를 보므로 이 분기가 없으면 **같은 이슈가 두 곳에 동시에 그려진다**(J1).
  //
  // ★단, **좁은 화면에서는 선호와 무관하게 모달이 맡는다.** 패널은 상세의 2단 그리드가
  //   뷰포트 기준으로 펼쳐져 좁은 폭에서 메타패널과 `⋯` 를 화면 밖으로 밀어낸다 — 되돌릴
  //   길이 사라지므로 패널 쪽이 스스로 물러나고(같은 `isWide` 판정), 이 조건이 그 폴백을 받는다.
  if (openKey === null || (presentation !== 'modal' && isWide)) return null

  return (
    <Dialog
      open
      onOpenChange={(next) => {
        if (!next) close()
      }}
    >
      <DialogContent
        showCloseButton={false}
        // Jira 의 모달은 넓다 — 2단 그리드(본문 1fr + 메타 340px)가 접히지 않을 만큼 확보한다.
        // 뷰포트를 넘지 않도록 상한을 두고, 내용이 길면 모달 안에서 스크롤한다.
        //
        // ★`overflow-y-auto` 가 아니라 `overflow-clip` + `flex-col` 이다(J26). 모달 자신이
        //   스크롤하면 본문·메타가 **함께** 밀려 올라가 Jira 의 「right scroll area」가 성립하지
        //   않는다. 스크롤은 안쪽 두 열이 각자 갖고, 모달은 높이 경계만 준다.
        //
        // 🛑 `overflow-hidden` 으로 되돌리지 마라 — 그것은 **스크롤 컨테이너를 만든다.**
        //    사용자가 굴리지 못할 뿐 스크립트는 굴린다(실측 — `dialog.scrollTop = 900` 이 그대로
        //    먹혔고 `scrollHeight` 는 2519px 였다). 그 상태에서 댓글 딥링크의 `scrollIntoView`
        //    (`CommentSection.tsx` — `focusCommentId` 로 이 모달에 그대로 들어온다)가 한 번
        //    걸리면 헤더가 모달 밖으로 밀리고, `hidden` 이라 **휠로 되돌릴 수도 없다.**
        //    `overflow: clip` 은 스크롤 컨테이너를 만들지 않아 `scrollIntoView` 가 이 상자를
        //    건너뛴다 — 잘라내는 효과는 같고 유령 스크롤 범위만 사라진다.
        //    E2E `issue-detail-split-scroll.spec.ts` 의 `isUnscrollable` 이 이 차이를 잰다.
        //
        // ★`lg:` 로 거는 이유. 안쪽 2단 격자가 `lg:` 분기라 그 미만에서는 1열 2행이 되고,
        //   그때도 두 행이 각자 스크롤하면 좁은 화면에서 **각각 반쪽짜리 구멍**이 된다
        //   (900×700 실측 — 메타 2459px 를 229px 창으로 본다). 그 폭에서는 종전처럼 껍데기가
        //   한 덩어리로 스크롤해야 하므로 `overflow-y-auto` 를 기본값으로 두고 `lg:` 에서만
        //   `clip` 으로 덮는다. 좁은 폭은 미지원이 아니다 — 목록이 그 폭에서 이 화면으로 보낸다.
        className="flex max-h-[90vh] w-[92vw] max-w-[1024px] flex-col overflow-y-auto p-0 lg:overflow-clip"
        // ★이슈 키를 포함해 고유하게. 계약 §2 — `getByRole('dialog')` 가 e2e 224곳에 있어
        // 이름이 겹치면 strict mode 충돌로 무관한 spec 이 죽는다.
        aria-label={`이슈 상세 ${openKey}`}
      >
        {/* Radix 는 DialogContent 에 접근 가능한 제목을 요구한다. 시각적 제목은 pane 이
            자기 <h2> 로 그리므로 여기서는 스크린리더 전용으로만 둔다. */}
        <DialogTitle className="sr-only">{`이슈 상세 ${openKey}`}</DialogTitle>
        {/* `min-h-0` 이 없으면 flex 자식의 최소 높이가 콘텐츠 높이라 모달이 늘어나고,
            안쪽 `overflow-y-auto` 가 한 번도 발동하지 않는다.
            ★`flex flex-col` 이 이 줄의 **핵심**이다. 상세 루트는 `max-h` 만 있고 확정 높이가
              없는 껍데기 안에서 `h-full`(백분율)로는 높이를 못 받는다 — 이 줄을 block 으로
              되돌리면 루트가 414px 대신 2646px 로 늘어 안쪽 두 열의 스크롤이 통째로 죽는다
              (뮤테이션으로 red 확인. 그때 루트에 `flex-1` 이 있어도 소용없었다). */}
        <div className="px-2 pb-2 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col">
          <IssueDetailPage
            issueKey={openKey}
            variant="pane"
            // 인박스 알림에서 왔으면 그 댓글까지 데려간다 (딥링크).
            focusCommentId={openCommentId ?? undefined}
            onClose={close}
            // 옛 키로 열었을 때 새 키로 갈아탄다 — 모달을 닫고 다시 열 필요가 없다.
            onIssueRedirect={(newKey) => { open(newKey) }}
            // 삭제되면 모달을 닫는다. 배경이 목록이면 그 목록이 스스로 갱신된다.
            onIssueClosed={() => {
              close()
              void navigate({ to: '/issues' })
            }}
          />
        </div>
      </DialogContent>
    </Dialog>
  )
}
