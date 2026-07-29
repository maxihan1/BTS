// 활성 프로젝트 키 localStorage 영속 훅 — zustand 공유 스토어 (FR-UX-07 Task 2, ADR D4)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 프로젝트 키를 저장하는 localStorage 키.
 *
 * ⚠️ 이 키에 저장되는 값은 인증 토큰이 아니라 **UI 선호값**(사용자가 마지막으로 본
 * 프로젝트 키 문자열)뿐이다. 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증
 * 토큰을 대상으로 하며, 이 훅은 토큰이나 개인정보(PII)를 저장하지 않으므로
 * §1.18과 무관하다 (`use-sidebar-collapsed.ts`·`use-column-visibility.ts` 선례).
 *
 * 사용자 스코프가 없어 로그아웃 후에도 남지만, 해소 시 접근 가능 목록과 대조하므로
 * 다른 사용자에게는 탈락하고 폴백이 자가 치유한다 (스펙 §10 L4).
 */
export const ACTIVE_PROJECT_STORAGE_KEY = 'bts.active-project' as const

/** localStorage 미설정·읽기 실패 시 기본값 — 미지정(호출자가 폴백 처리) */
const DEFAULT_ACTIVE_PROJECT: string | null = null

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * localStorage에서 활성 프로젝트 키를 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 스토리지 접근 예외·잘못된 JSON·
 * 문자열이 아닌 값·빈 문자열을 모두 null로 폴백한다(fail-safe).
 *
 * 빈 문자열을 접는 이유 — 그대로 흘러가면 백엔드가 빈 스코프로 권한을 평가해
 * 조용히 차단된다(`lib/active-project.ts`의 `nonEmpty`와 같은 근거).
 *
 * @returns 저장된 프로젝트 키, 또는 미설정·파싱 실패·접근 불가 시 null
 */
function readStoredActiveProject(): string | null {
  if (typeof window === 'undefined') return DEFAULT_ACTIVE_PROJECT

  try {
    const raw = window.localStorage.getItem(ACTIVE_PROJECT_STORAGE_KEY)
    if (raw === null) return DEFAULT_ACTIVE_PROJECT
    const parsed: unknown = JSON.parse(raw)
    return typeof parsed === 'string' && parsed.length > 0 ? parsed : DEFAULT_ACTIVE_PROJECT
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 미지정으로 폴백
    return DEFAULT_ACTIVE_PROJECT
  }
}

/**
 * localStorage에 활성 프로젝트 키를 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 상태는 정상 유지된다(영속만 생략).
 *
 * @param key 저장할 프로젝트 키
 */
function writeStoredActiveProject(key: string): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(ACTIVE_PROJECT_STORAGE_KEY, JSON.stringify(key))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useActiveProject 스토어 형태 */
export interface ActiveProjectStore {
  /** 마지막으로 본 프로젝트 키 — 미설정이면 null */
  activeProjectKey: string | null
  /** 활성 프로젝트를 갱신하고 localStorage에 동기화한다 (같은 값이면 write 생략) */
  setActiveProject: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 프로젝트 키를 localStorage에 영속하는 zustand 스토어.
 *
 * `ShellLayout`(경로 파라미터 기록기)과 라우트 어댑터가 각자 이 훅을 호출해도
 * zustand 스토어는 모듈 전역 단일 인스턴스라 상태가 공유된다.
 *
 * - 초기값: 모듈 로드 시 {@link ACTIVE_PROJECT_STORAGE_KEY} 1회 파싱
 *   (미설정·파싱 실패·SSR 환경 → null)
 * - `setActiveProject(key)`: 상태 갱신 + localStorage 동시 갱신(fail-safe).
 *   **같은 값이면 아무것도 하지 않는다** — 불필요한 write와 리렌더를 막는다(E7)
 *
 * 이 스토어는 "무엇이 활성인가"를 **저장만** 한다. 우선순위 해소(URL > 저장값 >
 * 첫 프로젝트)는 `lib/active-project.ts`의 순수 함수가, 둘을 묶는 조합은
 * `use-resolved-active-project.ts`가 담당한다.
 */
export const useActiveProject = create<ActiveProjectStore>((set, get) => ({
  activeProjectKey: readStoredActiveProject(),
  setActiveProject: (key: string): void => {
    if (get().activeProjectKey === key) return
    writeStoredActiveProject(key)
    set({ activeProjectKey: key })
  },
}))
