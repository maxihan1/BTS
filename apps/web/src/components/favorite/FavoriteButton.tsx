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
 * - mutation in-flight 동안 버튼을 disabled 처리해 중복 클릭을 방지한다.
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
      // 단축키 `s`의 존재를 아는 경로가 `?` 도움말 모달뿐이라 스크린리더에 표준 속성으로도
      // 알린다 (FR-UX-10 F11). 시각 툴팁은 만들지 않는다 — 신규 UI 0 제약.
      aria-keyshortcuts={focusRef !== undefined ? 's' : undefined}
      disabled={isMutating}
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
