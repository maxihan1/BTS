// 확인 다이얼로그 프리미티브 — 되돌리기 어려운 조작 앞에 한 번 묻는 공용 껍데기
import * as React from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from './dialog'
import { Button } from './button'

interface ConfirmDialogProps {
  /** 열림 상태 — 제어 컴포넌트다 */
  open: boolean
  /** 열림 상태 변경 요청 */
  onOpenChange: (open: boolean) => void
  /**
   * 제목이자 **dialog 의 접근성 이름**이다.
   *
   * ★ 화면 전체에서 고유해야 한다. 같은 이름의 dialog 가 둘이면 Playwright
   * `getByRole('dialog', { name })` 가 strict mode 로 즉사한다(§2 즉사 계약).
   *
   * ★★ 별도 `aria-label` 프롭을 두지 않는다. Radix `DialogContent` 가 `DialogTitle` 을
   * `aria-labelledby` 로 자동 연결하고 그것이 `aria-label` 을 **이긴다** — 실측으로
   * 확인했다. 두 경로를 두면 「지정한 이름과 실제 이름이 다른」 자리가 생긴다
   * (`CreateIssueDialog.tsx:59` 가 세운 관례와 같다).
   */
  title: string
  /** 설명 — 무엇이 일어나는지 한 줄 */
  description?: string
  /** 확인 버튼 문구 */
  confirmLabel: string
  /** 취소 버튼 문구 */
  cancelLabel: string
  /**
   * 확인을 눌렀을 때.
   *
   * ★ **닫는 책임은 소비자에게 있다.** 이 프리미티브는 확인 뒤 스스로 닫지 않는다 —
   * 종전에는 `onConfirm()` 직후 `onOpenChange(false)` 를 불러서 [confirming] 이 **구조적으로
   * 도달 불가**였고 실패해도 확인 맥락이 남지 않았다. 소비자는 성공했을 때만 닫는다.
   *
   * 취소는 여전히 이 프리미티브가 닫는다. 취소에는 「진행 중」도 「실패」도 없다.
   */
  onConfirm: () => void
  /**
   * 처리 중 — 이중 제출을 막고 **닫힘 경로를 전부 잠근다.**
   *
   * ★ 확인 버튼만 잠그면 취소·Esc·오버레이·X 로 창을 닫을 수 있고, 그 뒤 도착한 실패는
   * **보여줄 창이 없다.** 소비자가 배너나 toast 를 따로 두지 않았다면 사용자는 실패한 사실을
   * 통보받지 못하고, 다음에 연 창에 지난 실패가 되살아난다.
   *
   * 처리 중에 못 닫게 하면 실패가 갈 곳이 **구조적으로 보장**된다 — 창은 성공했을 때만 닫히고,
   * 실패하면 그 자리에 [error] 가 뜬다. 파괴적 조작이고 이미 확인을 누른 뒤라 기다림은 짧다.
   */
  confirming?: boolean
  /**
   * 실패 사유 — 창 **안**에 싣는다.
   *
   * ★ 소비자가 자기 화면에 배너로 그리면 모달 오버레이가 그것을 가린다. 확인 창이 열린 채
   * 실패하는 것이 이제 정상 경로이므로, 사유도 그 창 안에 있어야 사용자가 읽는다.
   * toast 로 알리는 소비자는 이 prop 이 필요 없다 — toast 는 오버레이 위에 뜬다.
   */
  error?: string
  /** 파괴적 조작이면 확인 버튼을 경고 색으로 */
  destructive?: boolean
}

/**
 * 되돌리기 어려운 조작을 한 번 묻는다.
 *
 * 화면마다 제각각 만들던 것을 하나로 모은 것이다 — 문구만 주입하고 구조·포커스는 이
 * 프리미티브가 고정한다.
 *
 * ### 확인 뒤 닫는 것은 소비자다
 * 취소·바깥 클릭·Esc 는 이 프리미티브가 [onOpenChange] 로 닫지만, **확인은 닫지 않는다.**
 * 종전에는 `onConfirm()` 직후 스스로 닫아서 두 가지가 구조적으로 불가능했다 —
 * [confirming] 을 아무도 볼 수 없었고(도달 불가 조합을 지키는 판정이 되었다),
 * 실패해도 확인 맥락이 사라져 사용자가 무엇이 안 됐는지 창 밖에서 찾아야 했다.
 *
 * 소비자는 성공했을 때만 닫는다 — `mutate(vars, { onSuccess: () => setTarget(null) })`.
 *
 * ### 처리 중에는 아무 경로로도 닫히지 않는다
 * [confirming] 이 참인 동안 취소·Esc·오버레이·X 가 모두 잠긴다. 확인 버튼만 잠그면 그 사이
 * 창을 닫을 수 있고, 뒤늦게 도착한 실패는 **보여줄 창이 없어 조용히 사라진다.**
 */
function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  confirming = false,
  error,
  destructive = false,
}: ConfirmDialogProps): React.JSX.Element {
  /**
   * 처리 중이면 닫힘 요청을 삼킨다.
   *
   * Radix 는 Esc · 오버레이 클릭 · 우상단 X 를 모두 이 한 콜백으로 보내므로, 여기서 막으면
   * 네 경로가 함께 잠긴다(취소 버튼은 아래에서 따로 `disabled` 다 — 눌리지 않는다는 사실이
   * 화면에 보여야 한다).
   */
  const handleOpenChange = (next: boolean): void => {
    if (confirming && !next) return
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description !== undefined ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>
        {error !== undefined ? (
          <div
            role="alert"
            className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive ring-1 ring-foreground/10"
          >
            {error}
          </div>
        ) : null}
        <DialogFooter>
          <Button variant="ghost" disabled={confirming} onClick={() => onOpenChange(false)}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            disabled={confirming}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { ConfirmDialog }
export type { ConfirmDialogProps }
