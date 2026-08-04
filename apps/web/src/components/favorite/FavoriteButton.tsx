// 즐겨찾기 추가/제거 토글 버튼 컴포넌트 — FR-UX-02 D6/D7
import type { RefObject } from 'react'
import { Star } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { toast } from 'sonner'
import {
  useFavorites,
  useAddFavorite,
  useRemoveFavorite,
  type FavoriteTargetType,
  type FavoriteResponse,
} from '@/api/favorites'
import { favoriteLabels } from '@/i18n/favorite-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

export interface FavoriteButtonProps {
  /** 즐겨찾기 대상 타입 (ISSUE / DASHBOARD / PROJECT / FILTER) */
  targetType: FavoriteTargetType
  /** 즐겨찾기 대상 id (이슈 키, 대시보드 id 등) */
  targetId: string
  /**
   * 토글 버튼으로 가는 ref — FR-UX-10 F11 단축키 `s`가 여기에 포커스를 준 뒤 click()한다.
   * 소비처는 routes/issues.$key.tsx(IssueMetaPanel.favoriteToggleRef 경유).
   *
   * 핸들러를 복제하지 않고 버튼을 미는 이유는 이 버튼이 이미 가진 in-flight disabled 판정과
   * 에러 토스트를 그대로 재사용하기 위해서다 — 로직이 두 벌이 되면 규칙이 어긋난다.
   *
   * 이 ref의 유무가 `aria-keyshortcuts` 노출 조건이기도 하다. 이 버튼은 이슈 상세 외에
   * SavedFilterMenu·대시보드·보드도 쓰는데 거기엔 `s`가 없으므로, 무조건 붙이면
   * 스크린리더가 없는 단축키를 안내한다.
   */
  focusRef?: RefObject<HTMLButtonElement | null>
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록에서 특정 (targetType AND targetId) 쌍이 존재하는지 판정한다.
 *
 * targetId만 비교하면 ISSUE키와 PROJECT키가 동일한 문자열일 때 오판이 발생한다.
 * 반드시 targetType과 targetId를 모두 비교해야 한다.
 *
 * @param items 즐겨찾기 목록
 * @param targetType 비교할 대상 타입
 * @param targetId 비교할 대상 id
 * @returns 목록에 해당 쌍이 있으면 true
 */
function isFavoriteMatch(
  items: ReadonlyArray<FavoriteResponse>,
  targetType: FavoriteTargetType,
  targetId: string,
): boolean {
  return items.some((f) => f.targetType === targetType && f.targetId === targetId)
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 토글 버튼 컴포넌트.
 *
 * - 전체 즐겨찾기 목록에서 (targetType AND targetId) 일치 여부로 isFavorited를 도출한다.
 * - isFavorited=true이면 채운 별(★, aria-pressed=true), false이면 빈 별(☆, aria-pressed=false).
 * - mutation in-flight 동안 aria-disabled로 비활성을 알리고, 중복 발행은 handleClick의
 *   isMutating 가드가 막는다 (네이티브 disabled를 쓰지 않는 이유는 아래 주석 참조).
 * - 낙관적 업데이트 없이 invalidate-only 방식으로 캐시를 갱신한다.
 *
 * @param targetType 즐겨찾기 대상 타입
 * @param targetId 즐겨찾기 대상 id
 * @param focusRef 토글 버튼으로 가는 ref (단축키 `s`)
 */
export const FavoriteButton = ({ targetType, targetId, focusRef }: FavoriteButtonProps) => {
  const { data: favorites } = useFavorites()
  const addFavorite = useAddFavorite()
  const removeFavorite = useRemoveFavorite()

  const isMutating = addFavorite.isPending || removeFavorite.isPending
  /** 즐겨찾기 여부 — 목록 미로드(undefined) 시 false로 보수적 초기화 */
  const isFavorited = favorites !== undefined && isFavoriteMatch(favorites, targetType, targetId)

  const handleClick = () => {
    // 중복 발행 차단의 유일한 증인 — 네이티브 disabled를 벗겼으므로 브라우저가 클릭을
    // 막아주지 않는다. 이 한 줄을 지우면 FavoriteButton.test.tsx S7c가 red다.
    if (isMutating) return

    if (isFavorited) {
      removeFavorite.mutate(
        { targetType, targetId },
        { onError: () => { toast.error(favoriteLabels.removeError) } },
      )
    } else {
      addFavorite.mutate(
        { targetType, targetId },
        { onError: () => { toast.error(favoriteLabels.addError) } },
      )
    }
  }

  const ariaLabel = isFavorited ? favoriteLabels.removeAriaLabel : favoriteLabels.addAriaLabel

  return (
    <Button
      ref={focusRef}
      variant="ghost"
      size="icon"
      aria-label={ariaLabel}
      aria-pressed={isFavorited}
      // 단축키 존재를 알 경로가 `?` 도움말 모달뿐이라 표준 속성으로도 알린다.
      // 키 문자 정본은 CONTEXT_SHORTCUTS(FR-UX-10 F11) — 재배치하면 여기도 같이 고친다.
      // 시각 툴팁은 만들지 않는다(신규 UI 0 제약 + 툴팁은 키보드 사용자에게 닿지 않는다).
      aria-keyshortcuts={focusRef !== undefined ? 's' : undefined}
      // ★네이티브 disabled가 아니라 aria-disabled인 이유 (FR-UX-10 F11 Task-5b).
      //   브라우저는 disabled로 전환된 요소의 포커스를 <body>로 떨어뜨리고, 다시
      //   enabled가 돼도 되돌려주지 않는다. 그러면 뮤테이션이 끝나 aria-pressed가
      //   뒤집히는 순간 포커스가 이 버튼에 없어 스크린리더가 상태 변화를 읽지 않는다
      //   (`s` 단축키 사용자에게는 아무 일도 안 일어난 것과 같다).
      //   실측 추적: 뮤테이션 시작 active=BODY → 완료 후에도 active=BODY.
      //   aria-disabled는 포커스를 유지하면서 비활성만 알린다. 중복 발행은 handleClick의
      //   isMutating 가드가 막는다. pointer-events-none은 쓰지 않는다 — 클릭이 죽어
      //   같은 문제가 형태만 바꿔 재발한다.
      aria-disabled={isMutating}
      // disabled: 스타일은 aria-disabled로 발동하지 않으므로 등가 표기를 직접 붙인다.
      // (프리미티브 button.tsx는 14개 화면이 공유하므로 여기서만 처리한다.)
      className="aria-disabled:opacity-50 aria-disabled:cursor-not-allowed"
      onClick={handleClick}
      data-testid="favorite-button"
    >
      <Star
        className={isFavorited ? 'fill-current text-favorite' : 'text-muted-foreground'}
        aria-hidden
      />
    </Button>
  )
}
