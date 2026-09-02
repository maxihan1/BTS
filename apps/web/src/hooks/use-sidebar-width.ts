// 사이드바 폭 localStorage 영속 훅 — zustand 공유 스토어 (Jira 패리티 J6)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 폭을 저장하는 localStorage 키.
 *
 * 🛑 `bts.sidebar.collapsed` 키와 **합치지 마라.**
 * 접힘은 레일(64px)로 갈지의 boolean 이고 폭은 펼침 상태의 px 다. 한 값으로 묶으면
 * 접었다 펴는 순간 사용자가 맞춰 둔 폭이 사라진다 — 두 축은 직교한다.
 *
 * ⚠️ 저장값은 UI 선호값(숫자 px)뿐이라 절대 규칙 §1.18(토큰 localStorage 저장 금지)과 무관하다.
 */
export const SIDEBAR_WIDTH_STORAGE_KEY = 'bts.sidebar.width' as const

/** 사이드바 최소 폭 — 이보다 좁으면 프로젝트 이름이 두 글자도 못 버틴다 */
export const MIN_SIDEBAR_WIDTH = 200

/** 사이드바 최대 폭 — 이보다 넓으면 본문이 밀려 보드 컬럼이 잘린다 */
export const MAX_SIDEBAR_WIDTH = 480

/** localStorage 미설정 시 기본 폭 — 디자인 스펙 §3.1 의 264px */
export const DEFAULT_SIDEBAR_WIDTH = 264

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 폭을 허용 범위로 좁힌다.
 *
 * 유한수가 아니면(`NaN`·`Infinity`) `null` 을 돌려 **호출부가 갱신 자체를 건너뛰게** 한다.
 * 0 이나 기본값으로 대체하면 드래그 중 한 프레임의 계산 사고가 폭을 조용히 리셋한다.
 *
 * @param width 검사할 폭(px)
 * @returns 범위 안으로 좁힌 폭, 또는 쓸 수 없는 값이면 `null`
 */
function clampWidth(width: number): number | null {
  if (!Number.isFinite(width)) return null
  return Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, width))
}

/**
 * localStorage 에서 사이드바 폭을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·스토리지 접근 예외·잘못된 JSON·숫자가 아닌 값 모두 기본 폭으로
 * 폴백한다(fail-safe).
 *
 * ★ 읽을 때도 {@link clampWidth} 를 태운다. 「저장할 때 좁혔으니 읽을 땐 안 해도 된다」가
 * 함정이다 — {@link MIN_SIDEBAR_WIDTH}·{@link MAX_SIDEBAR_WIDTH} 를 좁히면 이미 저장된 값이
 * 새 범위 밖이 되는데, 그 값은 사용자가 다시 드래그하기 전까지 아무도 고쳐 쓰지 않는다.
 *
 * @returns 저장된 폭, 또는 미설정·파싱 실패·접근 불가 시 기본 폭
 */
function readStoredWidth(): number {
  if (typeof window === 'undefined') return DEFAULT_SIDEBAR_WIDTH

  try {
    const raw = window.localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)
    if (raw === null) return DEFAULT_SIDEBAR_WIDTH
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'number') return DEFAULT_SIDEBAR_WIDTH
    return clampWidth(parsed) ?? DEFAULT_SIDEBAR_WIDTH
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 기본 폭으로 폴백
    return DEFAULT_SIDEBAR_WIDTH
  }
}

/**
 * localStorage 에 사이드바 폭을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며, 메모리 상의
 * React 상태는 정상 유지된다.
 *
 * @param width 저장할 폭(px)
 */
function writeStoredWidth(width: number): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, JSON.stringify(width))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useSidebarWidth 반환 타입 */
export interface SidebarWidthResult {
  /** 현재 사이드바 폭(px) — 항상 `MIN`~`MAX` 범위 안이다 */
  width: number
  /**
   * 폭을 갱신하되 **영속하지 않는다** — 드래그 중(`pointermove`) 전용.
   *
   * 🛑 여기서 localStorage 를 건드리면 포인터 이동마다 쓰기가 일어난다(60fps).
   *    확정은 {@link SidebarWidthResult.commitWidth} 가 `pointerup` 에서 한 번만 한다.
   */
  setWidth: (width: number) => void
  /** 폭을 갱신하고 localStorage 에 확정한다 — `pointerup`·키보드 조작 전용 */
  commitWidth: (width: number) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 폭을 localStorage 에 영속하는 zustand 스토어.
 *
 * `use-sidebar-collapsed.ts` 와 같은 모양이지만 **그 모듈을 import 하지 않는다** —
 * 두 축(접힘 boolean · 폭 px)은 직교하고, 폭 판정을 한 곳에서만 하도록
 * `sidebar-width-consumer-allowlist.test.ts` 가 소비처를 봉인한다.
 *
 * - 초기값: 모듈 로드 시 {@link SIDEBAR_WIDTH_STORAGE_KEY} 1회 파싱(미설정·실패 → 264px)
 * - `setWidth()`: 스토어만 갱신(드래그 중)
 * - `commitWidth()`: 스토어 + localStorage(드래그 끝·키보드)
 */
export const useSidebarWidth = create<SidebarWidthResult>((set) => ({
  width: readStoredWidth(),
  setWidth: (width: number): void => {
    const next = clampWidth(width)
    if (next === null) return
    set({ width: next })
  },
  commitWidth: (width: number): void => {
    const next = clampWidth(width)
    if (next === null) return
    writeStoredWidth(next)
    set({ width: next })
  },
}))
