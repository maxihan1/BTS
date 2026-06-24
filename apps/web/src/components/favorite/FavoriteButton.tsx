// 즐겨찾기 추가/제거 토글 버튼 컴포넌트 — FR-UX-02 D6/D7
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
  /** 즐겨찾기 대상 타입 (ISSUE / DASHBOARD / PROJECT) */
  targetType: FavoriteTargetType
  /** 즐겨찾기 대상 id (이슈 키, 대시보드 id 등) */
  targetId: string
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
 */
export const FavoriteButton = ({ targetType, targetId }: FavoriteButtonProps) => {
  const { data: favorites } = useFavorites()
  const addFavorite = useAddFavorite()
  const removeFavorite = useRemoveFavorite()

  const isMutating = addFavorite.isPending || removeFavorite.isPending
  const isFavorited = favorites !== undefined
    ? isFavoriteMatch(favorites, targetType, targetId)
    : false

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
      variant="ghost"
      size="icon"
      aria-label={ariaLabel}
      aria-pressed={isFavorited}
      disabled={isMutating}
      onClick={handleClick}
      data-testid="favorite-button"
    >
      <Star
        className={isFavorited ? 'fill-current text-yellow-400' : 'text-muted-foreground'}
        aria-hidden
      />
    </Button>
  )
}
