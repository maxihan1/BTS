// 모바일 사이드바 오프캔버스 드로어 열림 상태 + 아이콘 레일 판정 (jira-parity F24)
import { create } from 'zustand'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { useSidebarWidth } from '@/hooks/use-sidebar-width'
import { useMediaQuery } from '@/hooks/use-media-query'

// ─────────────────────────────────────────────────────────────────────────────
// 브레이크포인트 정본
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「모바일 폭」 판정 미디어쿼리 — Tailwind `md`(768px) **미만**.
 *
 * 🛑 이 값과 컴포넌트가 쓰는 `max-md:` 클래스는 **같은 경계**를 가리켜야 한다.
 * 한쪽만 바꾸면 JS 는 드로어라고 믿는데 CSS 는 데스크톱 레일을 그리는(또는 그 반대) 구간이
 * 생기고, 그 구간은 어느 단위 테스트에도 걸리지 않는다 — 저장소의 지배적 결함 양식
 * (`two-lists-never-check-each-other`)이다. 짝은 `sidebar-breakpoint-alignment.test.ts` 가 묶는다.
 */
export const MOBILE_MEDIA_QUERY = '(max-width: 767.98px)' as const

/** 위 미디어쿼리와 짝이 되는 Tailwind 변형 접두사 — 판별식이 두 값을 대조한다 */
export const MOBILE_TAILWIND_VARIANT = 'max-md' as const

// ─────────────────────────────────────────────────────────────────────────────
// 스토어
// ─────────────────────────────────────────────────────────────────────────────

/** useSidebarDrawer 스토어 형태 */
export interface SidebarDrawerState {
  /** 모바일 드로어가 열려 있는지 */
  open: boolean
  /** 열림 상태를 직접 지정 — 라우트 이동·백드롭 클릭이 닫을 때 사용 */
  setOpen: (open: boolean) => void
  /** 열림 상태 반전 — 상단바 토글 버튼·`[` 단축키가 사용 */
  toggle: () => void
}

/**
 * 모바일 사이드바 드로어 열림 상태.
 *
 * 🛑 `use-sidebar-collapsed` 와 달리 **localStorage 에 저장하지 않는다.** 드로어는 화면을
 * 덮는 오버레이라, 저장했다면 다음 방문 때 콘텐츠가 가려진 채로 시작한다. 접힘(collapsed)은
 * 데스크톱 레일 폭이라 저장이 맞고, 드로어는 매번 닫힌 채로 시작하는 것이 맞다 — 두 상태를
 * 하나로 합치지 마라.
 */
export const useSidebarDrawer = create<SidebarDrawerState>((set) => ({
  open: false,
  setOpen: (open: boolean): void => {
    set({ open })
  },
  toggle: (): void => {
    set((state) => ({ open: !state.open }))
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 아이콘 레일 판정 — 라벨을 sr-only 로 감출지의 유일한 정본
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바가 **아이콘 레일**(64px)로 접혀 있는지.
 *
 * 🛑 라벨을 감출지 정할 때 `useSidebarCollapsed().collapsed` 를 **직접 읽지 마라.**
 * 모바일 드로어는 264px 전체 폭으로 열리므로 라벨이 보여야 하는데, 저장된 `collapsed` 가
 * true 인 채로 폭만 좁아지면 아이콘만 남은 드로어가 나온다. 모바일에서는 항상 false 다.
 *
 * @returns 데스크톱이면서 접힘 상태일 때만 true
 */
export function useSidebarRailCollapsed(): boolean {
  const collapsed = useSidebarCollapsed((state) => state.collapsed)
  const isMobile = useMediaQuery(MOBILE_MEDIA_QUERY)
  return !isMobile && collapsed
}

// ─────────────────────────────────────────────────────────────────────────────
// 토글 라우팅 — 폭에 따라 다른 상태를 건드린다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 토글 — 모바일이면 드로어 열기/닫기, 데스크톱이면 아이콘 레일 접기/펼치기.
 *
 * 🛑 `useSidebarCollapsed().toggle` 을 버튼에 **직접 물리지 마라.** 모바일에서는 폭 계산이
 * `collapsed` 를 보지 않으므로 눌러도 화면에 아무 일이 일어나지 않는다(무동작 버튼).
 * 토글 지점이 상단바 버튼 · `[` 단축키 · 사이드바 하단 버튼으로 셋이라, 판정을 각자 두면
 * 하나만 고쳐지고 나머지가 조용히 썩는다 — 여기 한 곳에서만 고른다.
 *
 * @returns 현재 폭에 맞는 토글 함수
 */
export function useSidebarToggle(): () => void {
  const toggleCollapsed = useSidebarCollapsed((state) => state.toggle)
  const toggleDrawer = useSidebarDrawer((state) => state.toggle)
  const isMobile = useMediaQuery(MOBILE_MEDIA_QUERY)
  return isMobile ? toggleDrawer : toggleCollapsed
}

/**
 * 사이드바가 지금 「펼쳐져 보이는」 상태인지 — 토글 버튼의 라벨·아이콘 방향을 정한다.
 *
 * 데스크톱은 접힘의 반대(레일이어도 폭은 남아 있지만 라벨 기준은 기존 계약을 그대로 잇는다),
 * 모바일은 드로어 열림 여부다. 🛑 두 축을 한 식(`collapsed || !drawerOpen`)으로 합치지 마라 —
 * 데스크톱에서 `drawerOpen` 이 항상 false 라 라벨이 「펼치기」로 굳는다.
 *
 * @returns 펼쳐져 보이면 true
 */
export function useSidebarShown(): boolean {
  const collapsed = useSidebarCollapsed((state) => state.collapsed)
  const drawerOpen = useSidebarDrawer((state) => state.open)
  const isMobile = useMediaQuery(MOBILE_MEDIA_QUERY)
  return isMobile ? drawerOpen : !collapsed
}

// ─────────────────────────────────────────────────────────────────────────────
// 폭 판정 — 지금 실제로 그릴 px 을 정하는 유일한 정본 (Jira 패리티 J6)
// ─────────────────────────────────────────────────────────────────────────────

/** 아이콘 레일 폭(px) — Tailwind `w-16` 과 같은 값이다 */
export const RAIL_WIDTH_PX = 64

/**
 * 지금 사이드바에 실제로 실어야 할 폭(px).
 *
 * 세 상태가 각자 다른 폭을 쓴다.
 * - **모바일 드로어** — `undefined`. 폭은 `max-md:w-[264px]` 클래스가 지배한다.
 *   🛑 저장된 폭을 그대로 실으면 안 된다 — 480px 로 맞춰 둔 사용자의 드로어가 화면을 거의
 *   다 덮는다. 드로어는 오버레이라 「내가 맞춰 둔 폭」이 적용될 자리가 아니다.
 * - **데스크톱 아이콘 레일** — {@link RAIL_WIDTH_PX}. 저장 폭과 무관한 고정값이다.
 * - **데스크톱 펼침** — 저장된 폭.
 *
 * @returns 인라인 `style.width` 에 실을 px, 또는 클래스에 맡길 때 `undefined`
 */
export function useSidebarEffectiveWidth(): number | undefined {
  const width = useSidebarWidth((state) => state.width)
  const railCollapsed = useSidebarRailCollapsed()
  const isMobile = useMediaQuery(MOBILE_MEDIA_QUERY)
  if (isMobile) return undefined
  return railCollapsed ? RAIL_WIDTH_PX : width
}

/**
 * 지금 폭을 드래그로 바꿀 수 있는 상태인지 — 리사이즈 핸들을 렌더할지의 유일한 정본.
 *
 * 🛑 레일(64px)·모바일 드로어(264px)에서는 **핸들을 렌더하지 않는다.** 둘 다 고정 폭이라
 *    `aria-valuenow` 가 거짓말이 되고, 끌어도 아무 일이 일어나지 않는 죽은 컨트롤이 된다.
 *    스크린리더에는 조작 가능한 splitter 로 읽히므로 「보이지만 안 되는」 것보다 나쁘다.
 *
 * @returns 데스크톱이면서 펼쳐져 있을 때만 true
 */
export function useSidebarResizable(): boolean {
  const railCollapsed = useSidebarRailCollapsed()
  const isMobile = useMediaQuery(MOBILE_MEDIA_QUERY)
  return !isMobile && !railCollapsed
}
