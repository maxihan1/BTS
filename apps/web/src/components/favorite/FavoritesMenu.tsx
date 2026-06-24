// 즐겨찾기 드롭다운 메뉴 컴포넌트 — 타입별 그룹 렌더 + SPA Link 이동 (FR-UX-02 D6/D7)
import { Link } from '@tanstack/react-router'
import { Star, FileText, LayoutDashboard, FolderKanban } from 'lucide-react'
import { useFavorites } from '@/api/favorites'
import type { FavoriteResponse, FavoriteTargetType } from '@/api/favorites'
import { FAVORITE_TARGET_TYPES } from '@/api/favorites'
import { favoriteLabels } from '@/i18n/favorite-labels'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuGroup,
  DropdownMenuSeparator,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import type { ComponentType } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// 타입별 메타 데이터 — 아이콘·라우트·그룹명 매핑 테이블
// ─────────────────────────────────────────────────────────────────────────────

interface FavoriteTypeMeta {
  /** 항목 옆에 표시할 lucide 아이콘 컴포넌트 */
  Icon: ComponentType<{ className?: string }>
  /** 그룹 헤더 라벨 (favoriteLabels.group*) */
  groupLabel: string
  /** targetId를 받아 SPA 경로를 반환하는 함수 */
  toPath: (targetId: string) => string
}

/** 프론트엔드가 사용하는 타입 3종에 대한 메타 테이블 */
const TYPE_META: Record<FavoriteTargetType, FavoriteTypeMeta> = {
  [FAVORITE_TARGET_TYPES.ISSUE]: {
    Icon: FileText,
    groupLabel: favoriteLabels.groupIssue,
    toPath: (id) => `/issues/${id}`,
  },
  [FAVORITE_TARGET_TYPES.DASHBOARD]: {
    Icon: LayoutDashboard,
    groupLabel: favoriteLabels.groupDashboard,
    toPath: (id) => `/dashboards/${id}`,
  },
  [FAVORITE_TARGET_TYPES.PROJECT]: {
    Icon: FolderKanban,
    groupLabel: favoriteLabels.groupProject,
    toPath: (id) => `/projects/${id}/board`,
  },
}

/** 그룹 렌더 순서 — 이슈 → 대시보드 → 프로젝트 */
const GROUP_ORDER: FavoriteTargetType[] = [
  FAVORITE_TARGET_TYPES.ISSUE,
  FAVORITE_TARGET_TYPES.DASHBOARD,
  FAVORITE_TARGET_TYPES.PROJECT,
]

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 목록을 타입별 그룹으로 분류
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록을 타입별로 그룹핑한다.
 * 반환 맵은 FavoriteTargetType → FavoriteResponse[] 형태이며,
 * 각 배열은 서버에서 받은 created_at DESC 순서를 유지한다.
 *
 * @param items 즐겨찾기 목록 (created_at DESC)
 * @returns 타입별 그룹 맵
 */
function groupByType(items: FavoriteResponse[]): Map<FavoriteTargetType, FavoriteResponse[]> {
  const map = new Map<FavoriteTargetType, FavoriteResponse[]>()
  for (const item of items) {
    // FILTER 타입은 프론트엔드 미사용 — 스킵
    if (!(item.targetType in TYPE_META)) continue
    const type = item.targetType as FavoriteTargetType
    const bucket = map.get(type) ?? []
    bucket.push(item)
    map.set(type, bucket)
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 드롭다운 메뉴.
 * Header에서 계정 드롭다운 좌측에 삽입된다.
 * useFavorites() 훅으로 전체 목록을 조회 후 타입별 그룹으로 렌더한다.
 */
export const FavoritesMenu = () => {
  const { data: items = [] } = useFavorites()

  const grouped = groupByType(items)
  const hasAny = items.some((item) => item.targetType in TYPE_META)

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium hover:bg-accent"
          aria-label={favoriteLabels.dropdownTriggerAriaLabel}
        >
          <Star className="size-4" />
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel>{favoriteLabels.dropdownTitle}</DropdownMenuLabel>
        <DropdownMenuSeparator />

        {!hasAny && (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">
            {favoriteLabels.emptyMessage}
          </p>
        )}

        {GROUP_ORDER.map((type, idx) => {
          const group = grouped.get(type)
          if (group === undefined || group.length === 0) return null
          const { Icon, groupLabel } = TYPE_META[type]
          const { toPath } = TYPE_META[type]

          return (
            <span key={type}>
              {idx > 0 && <DropdownMenuSeparator />}
              <DropdownMenuGroup>
                <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
                  {groupLabel}
                </DropdownMenuLabel>
                {group.map((fav) => (
                  <DropdownMenuItem key={fav.id} asChild>
                    <Link
                      to={toPath(fav.targetId)}
                      className="flex items-center gap-2"
                    >
                      <Icon className="size-3.5 shrink-0 text-muted-foreground" />
                      <span className="truncate">{fav.targetId}</span>
                    </Link>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuGroup>
            </span>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
