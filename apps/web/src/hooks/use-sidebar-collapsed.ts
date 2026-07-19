// 사이드바 접기 상태 localStorage 영속 훅 — zustand 공유 스토어 (FR-UX-06 PR11 Task 3/8)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 접힘 여부를 저장하는 localStorage 키.
 *
 * ⚠️ 이 키에 저장되는 값은 인증 토큰이 아니라 UI 선호값(boolean, 접힘/펼침)뿐이다.
 * 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증 토큰을 대상으로 하며,
 * 이 훅은 토큰이나 개인정보(PII)를 저장하지 않으므로 §1.18과 무관하다.
 */
export const SIDEBAR_COLLAPSED_STORAGE_KEY = 'bts.sidebar.collapsed' as const

/** localStorage 미설정 시 기본값 — 펼침(collapsed=false) */
const DEFAULT_COLLAPSED = false

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * localStorage에서 사이드바 접힘 상태를 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 스토리지 접근 예외·
 * 잘못된 JSON 저장값 모두 기본값(펼침)으로 폴백한다(fail-safe).
 *
 * @returns 저장된 접힘 상태, 또는 미설정·파싱 실패·접근 불가 시 기본값
 */
function readStoredCollapsed(): boolean {
  if (typeof window === 'undefined') return DEFAULT_COLLAPSED

  try {
    const raw = window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY)
    if (raw === null) return DEFAULT_COLLAPSED
    const parsed: unknown = JSON.parse(raw)
    return typeof parsed === 'boolean' ? parsed : DEFAULT_COLLAPSED
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 기본값(펼침)으로 폴백
    return DEFAULT_COLLAPSED
  }
}

/**
 * localStorage에 사이드바 접힘 상태를 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 React 상태는 정상 유지된다.
 *
 * @param collapsed 저장할 접힘 상태
 */
function writeStoredCollapsed(collapsed: boolean): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, JSON.stringify(collapsed))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useSidebarCollapsed 반환 타입 */
export interface SidebarCollapsedResult {
  /** 현재 사이드바 접힘 여부 */
  collapsed: boolean
  /** 접힘 상태를 반전하고 localStorage에 동기화하는 함수 */
  toggle: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 접힘 상태를 localStorage에 영속하는 zustand 스토어.
 *
 * Sidebar·TopBar 등 여러 컴포넌트가 각자 이 훅을 호출해도 zustand 스토어는
 * 모듈 전역 단일 인스턴스라 상태가 공유된다(컴포넌트-로컬 `useState`였을 때는
 * 인스턴스가 서로 격리돼 토글이 다른 컴포넌트에 반영되지 않았다).
 *
 * - 초기값: 모듈 로드 시 localStorage {@link SIDEBAR_COLLAPSED_STORAGE_KEY} 1회 파싱
 *   (미설정·파싱 실패·SSR 환경 → 펼침(false))
 * - `toggle()`: 접힘 상태 반전 + localStorage 동시 갱신(fail-safe)
 */
export const useSidebarCollapsed = create<SidebarCollapsedResult>((set) => ({
  collapsed: readStoredCollapsed(),
  toggle: (): void =>
    set((state) => {
      const next = !state.collapsed
      writeStoredCollapsed(next)
      return { collapsed: next }
    }),
}))
