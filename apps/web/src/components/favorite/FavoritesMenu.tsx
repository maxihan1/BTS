// 즐겨찾기 드롭다운 메뉴 컴포넌트 — 타입별 그룹 렌더 + SPA Link 이동 (FR-UX-02 D6/D7)
import { Fragment, type ComponentType } from 'react'
import { Link } from '@tanstack/react-router'
import { Star, FileText, LayoutDashboard, FolderKanban, SlidersHorizontal } from 'lucide-react'
import { useQueries } from '@tanstack/react-query'
import { useFavorites } from '@/api/favorites'
import type { FavoriteResponse, FavoriteTargetType } from '@/api/favorites'
import { FAVORITE_TARGET_TYPES } from '@/api/favorites'
import { fetchFilter, savedFiltersKey } from '@/api/saved-filters'
import type { SavedFilterResponse } from '@/api/saved-filters'
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

// ─────────────────────────────────────────────────────────────────────────────
// 타입별 메타 데이터 — 아이콘·라우트·그룹명 매핑 테이블 (동기 타입 3종)
// ─────────────────────────────────────────────────────────────────────────────

interface FavoriteTypeMeta {
  /** 항목 옆에 표시할 lucide 아이콘 컴포넌트 */
  Icon: ComponentType<{ className?: string }>
  /** 그룹 헤더 라벨 (favoriteLabels.group*) */
  groupLabel: string
  /** targetId를 받아 SPA 경로를 반환하는 함수 */
  toPath: (targetId: string) => string
}

/**
 * 동기 타입 3종(ISSUE/DASHBOARD/PROJECT) 메타 테이블.
 * FILTER는 비동기 이름 조회가 필요해 별도 컴포넌트로 처리.
 */
const TYPE_META: Partial<Record<FavoriteTargetType, FavoriteTypeMeta>> = {
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

/** 동기 그룹 렌더 순서 — 이슈 → 대시보드 → 프로젝트 */
const GROUP_ORDER: FavoriteTargetType[] = [
  FAVORITE_TARGET_TYPES.ISSUE,
  FAVORITE_TARGET_TYPES.DASHBOARD,
  FAVORITE_TARGET_TYPES.PROJECT,
]

/** 필터 그룹 헤더 라벨 */
const FILTER_GROUP_LABEL = '필터'

/**
 * 저장 필터 즐겨찾기의 SPA 경로를 반환한다.
 * 반환 타입을 string으로 명시해 TanStack Router Link to 타입 호환을 보장한다.
 *
 * @param filterId 필터 UUID
 * @returns /search?filterId=<filterId>
 */
function toFilterPath(filterId: string): string {
  return `/search?filterId=${filterId}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 목록을 타입별 그룹으로 분류 (FILTER 제외)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록을 동기 타입별로 그룹핑한다.
 * FILTER 타입은 FilterFavoritesGroup에서 별도 처리하므로 제외한다.
 *
 * @param items 즐겨찾기 목록 (created_at DESC)
 * @returns 타입별 그룹 맵
 */
function groupByType(items: FavoriteResponse[]): Map<FavoriteTargetType, FavoriteResponse[]> {
  const map = new Map<FavoriteTargetType, FavoriteResponse[]>()
  for (const item of items) {
    if (!(item.targetType in TYPE_META)) continue
    const type = item.targetType as FavoriteTargetType
    const bucket = map.get(type) ?? []
    bucket.push(item)
    map.set(type, bucket)
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// FilterFavoritesGroup — 비동기 필터 이름 조회 + 404 숨김 + 헤더 플리커 방지
// ─────────────────────────────────────────────────────────────────────────────

interface FilterFavoritesGroupProps {
  /** FILTER 타입 즐겨찾기 목록 */
  items: FavoriteResponse[]
  /** 앞에 동기 그룹이 존재하면 구분선을 렌더한다 */
  showSeparatorBefore: boolean
}

/**
 * FILTER 즐겨찾기 그룹.
 * useQueries로 모든 필터를 병렬 조회하고, 전체 settle 후 렌더한다.
 * 404 항목은 숨기고, 전체 404 시 그룹 헤더도 표시하지 않는다.
 */
const FilterFavoritesGroup = ({ items, showSeparatorBefore }: FilterFavoritesGroupProps) => {
  const queries = useQueries({
    queries: items.map((fav) => ({
      queryKey: savedFiltersKey.detail(fav.targetId),
      queryFn: () => fetchFilter(fav.targetId),
      retry: false,
    })),
  })

  const allSettled = queries.every((q) => q.status !== 'pending')
  const visiblePairs = items
    .map((fav, i) => ({ fav, filter: queries[i]?.data }))
    .filter((pair): pair is { fav: FavoriteResponse; filter: SavedFilterResponse } =>
      pair.filter !== undefined,
    )

  if (!allSettled || visiblePairs.length === 0) return null

  return (
    <Fragment>
      {showSeparatorBefore && <DropdownMenuSeparator />}
      <DropdownMenuGroup>
        <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
          {FILTER_GROUP_LABEL}
        </DropdownMenuLabel>
        {visiblePairs.map(({ fav, filter }) => (
          <DropdownMenuItem key={fav.id} asChild>
            <Link
              to={toFilterPath(fav.targetId)}
              className="flex items-center gap-2"
            >
              <SlidersHorizontal className="size-3.5 shrink-0 text-muted-foreground" />
              <span className="truncate">{filter.name}</span>
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuGroup>
    </Fragment>
  )
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
  const filterFavs = items.filter((item) => item.targetType === FAVORITE_TARGET_TYPES.FILTER)

  const hasSyncItems = items.some((item) => item.targetType in TYPE_META)
  const showEmpty = !hasSyncItems && filterFavs.length === 0

  const hasSyncGroups = GROUP_ORDER.some((t) => (grouped.get(t)?.length ?? 0) > 0)

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

        {showEmpty && (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">
            {favoriteLabels.emptyMessage}
          </p>
        )}

        {GROUP_ORDER.reduce<React.ReactNode[]>((acc, type) => {
          const group = grouped.get(type)
          if (group === undefined || group.length === 0) return acc
          const meta = TYPE_META[type]
          if (!meta) return acc
          const { Icon, groupLabel, toPath } = meta

          const node = (
            <Fragment key={type}>
              {acc.length > 0 && <DropdownMenuSeparator />}
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
            </Fragment>
          )
          return [...acc, node]
        }, [])}

        {filterFavs.length > 0 && (
          <FilterFavoritesGroup
            items={filterFavs}
            showSeparatorBefore={hasSyncGroups}
          />
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
