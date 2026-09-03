// 이슈 상세를 모달로 띄우는 전역 단일 마운트 컴포넌트 (Jira 패리티 J1)
import type { JSX } from 'react'
import { useNavigate } from '@tanstack/react-router'
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
  const close = useIssueDetailModalStore((s) => s.close)
  const open = useIssueDetailModalStore((s) => s.open)
  const navigate = useNavigate()

  if (openKey === null) return null

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
        className="max-w-[1024px] w-[92vw] max-h-[90vh] overflow-y-auto p-0"
        // ★이슈 키를 포함해 고유하게. 계약 §2 — `getByRole('dialog')` 가 e2e 224곳에 있어
        // 이름이 겹치면 strict mode 충돌로 무관한 spec 이 죽는다.
        aria-label={`이슈 상세 ${openKey}`}
      >
        {/* Radix 는 DialogContent 에 접근 가능한 제목을 요구한다. 시각적 제목은 pane 이
            자기 <h2> 로 그리므로 여기서는 스크린리더 전용으로만 둔다. */}
        <DialogTitle className="sr-only">{`이슈 상세 ${openKey}`}</DialogTitle>
        <div className="px-2 py-2">
          <IssueDetailPage
            issueKey={openKey}
            variant="pane"
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
