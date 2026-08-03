// 이슈 생성 진입점 버튼 — 보드·백로그·스프린트 3곳이 공유하는 fail-closed 게이트 버튼 (FR-UX-09 F3)
import type { JSX } from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'

/** CreateIssueEntryButton props */
export interface CreateIssueEntryButtonProps {
  /**
   * 버튼의 접근 가능한 이름.
   *
   * 🛑 **같은 화면의 다른 버튼 이름을 부분 문자열로 포함하면 안 된다.**
   * Playwright `getByRole(name)` 과 Testing Library 정규식은 둘 다 기본이 부분 일치라
   * 포함 관계가 생기면 멀쩡한 구현인데도 기존 테스트가 strict mode 로 깨진다.
   * 전수 판별식이 `i18n/__tests__/create-entry-point-names.test.ts` 에 있다.
   */
  label: string
  /**
   * 시각 형태 (design 리뷰 DR-1·DR-3).
   *
   * - `icon` — 칸 헤더용. 아이콘만 그리고 이름은 `aria-label` 로만 준다.
   *   칸 폭이 288px 이고 제목 행에 이미 제목·개수·상태 배지가 있어 텍스트를 넣을 자리가 없다.
   * - `text` — 보드 헤더용. 아이콘 + 보이는 텍스트.
   */
  variant: 'icon' | 'text'
  /**
   * CREATE 권한 보유 여부. **fail-closed** 다.
   *
   * 호출자는 `permissions.CREATE === true` 이고 **조회가 끝났을 때만** `true` 를 넘긴다.
   * 로딩·에러·미보유는 전부 `false` 이며 버튼이 비활성이 된다.
   * 선례 — `routes/issues.index.tsx` 의 `NewIssueButton`.
   */
  canCreate: boolean
  /** 클릭 콜백 — 모달 열기. 이 컴포넌트는 모달을 소유하지 않는다 (FR-15) */
  onClick: () => void
}

/**
 * 이슈 생성 진입점 버튼.
 *
 * ### 왜 공용 컴포넌트인가
 * 진입점이 3곳(백로그 칸 · 스프린트 칸 · 보드 헤더)인데 **권한 게이트 규칙은 하나**다.
 * 각 자리에서 따로 그리면 fail-closed 판정이 3벌로 갈라지고, 한 곳만 빠뜨려도 눈에 안 띈다.
 * 여기 하나를 시험대로 두면 게이트 검증이 한 곳에서 끝난다.
 *
 * ### 모달을 소유하지 않는다
 * 클릭을 알릴 뿐이다. 모달은 **화면당 1개**를 부모가 갖는다 (FR-15) —
 * 칸마다 두면 `role="dialog"` 가 N개가 되어 strict mode 로 충돌한다.
 */
export function CreateIssueEntryButton({
  label,
  variant,
  canCreate,
  onClick,
}: CreateIssueEntryButtonProps): JSX.Element {
  if (variant === 'icon') {
    return (
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        aria-label={label}
        disabled={!canCreate}
        onClick={onClick}
      >
        <Plus />
      </Button>
    )
  }

  return (
    // 🛑 `default`(primary)를 쓰면 안 된다 — 상단바 「만들기」가 모든 페이지에 primary 로
    //    있어서 같은 화면에 주 액션이 2개가 된다 (design 리뷰 DR-1).
    <Button type="button" variant="outline" disabled={!canCreate} onClick={onClick}>
      <Plus />
      {label}
    </Button>
  )
}
