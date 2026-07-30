// 최근 방문 프로젝트 MRU 목록 localStorage 영속 훅 — zustand 공유 스토어 (FR-UX-08 PR-A Task 1, ADR D3)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 최근 방문 프로젝트 목록을 저장하는 localStorage 키.
 *
 * ⚠️ 이 키에 저장되는 값은 인증 토큰이 아니라 **UI 선호값**(사용자가 최근 방문한 프로젝트
 * 키 문자열 목록)뿐이다. 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증 토큰을
 * 대상으로 하며, 이 훅은 토큰이나 개인정보(PII)를 저장하지 않으므로 §1.18과 무관하다
 * (`use-active-project.ts`·`use-sidebar-collapsed.ts` 선례).
 *
 * 사용자 스코프가 없어 로그아웃 후에도 남지만, 소비처(`ProjectSwitcher`)가 접근 가능
 * 목록과 대조하므로 다른 사용자에게는 전부 탈락한다 (스펙 E1).
 */
export const RECENT_PROJECTS_STORAGE_KEY = 'bts.recent-projects' as const

/**
 * 최근 목록의 상한 (ADR §D3 — Maxi 확정).
 *
 * 초과 시 가장 오래된 항목부터 축출한다(LRU). 사이드바·팝오버 세로 공간과
 * "최근"의 의미(전체 목록의 대체가 아님) 사이에서 정해진 값이다.
 */
export const MAX_RECENT_PROJECTS = 5

/** localStorage 미설정·읽기 실패 시 기본값 */
const DEFAULT_RECENT_PROJECTS: readonly string[] = []

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알 수 없는 값이 문자열 배열인지 판정한다.
 *
 * **원소가 하나라도 문자열이 아니면 전체를 거부한다** — 일부만 골라 쓰면 수동 변조된
 * 저장값의 절반이 살아남아 "왜 이 프로젝트가 최근 목록에 있지"를 추적할 수 없게 된다.
 */
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/**
 * localStorage에서 최근 프로젝트 목록을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 스토리지 접근 예외·잘못된 JSON·
 * 배열이 아닌 값·원소가 문자열이 아닌 배열을 모두 빈 목록으로 폴백한다(fail-safe).
 *
 * 저장값이 상한을 넘겨 변조된 경우에도 상한까지만 복원한다 — 복원 경로가 상한을
 * 우회하면 축출 로직이 있으나 마나가 된다.
 *
 * @returns 저장된 프로젝트 키 목록(MRU 순, 상한 적용), 또는 폴백 시 빈 목록
 */
function readStoredRecentProjects(): string[] {
  if (typeof window === 'undefined') return [...DEFAULT_RECENT_PROJECTS]

  try {
    const raw = window.localStorage.getItem(RECENT_PROJECTS_STORAGE_KEY)
    if (raw === null) return [...DEFAULT_RECENT_PROJECTS]
    const parsed: unknown = JSON.parse(raw)
    if (!isStringArray(parsed)) return [...DEFAULT_RECENT_PROJECTS]
    return parsed.slice(0, MAX_RECENT_PROJECTS)
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 빈 목록으로 폴백
    return [...DEFAULT_RECENT_PROJECTS]
  }
}

/**
 * localStorage에 최근 프로젝트 목록을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 상태는 정상 유지된다(영속만 생략).
 *
 * @param keys 저장할 프로젝트 키 목록 (MRU 순)
 */
function writeStoredRecentProjects(keys: readonly string[]): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(RECENT_PROJECTS_STORAGE_KEY, JSON.stringify(keys))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useRecentProjects 스토어 형태 */
export interface RecentProjectsStore {
  /** 최근 방문 프로젝트 키 목록 — MRU 순(맨 앞이 가장 최근), 최대 {@link MAX_RECENT_PROJECTS}개 */
  recentProjectKeys: string[]
  /** 프로젝트 키를 최근 목록 맨 앞에 기록한다 (중복 제거 + 상한 축출 + localStorage 동기화) */
  pushRecentProject: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 최근 방문 프로젝트 MRU 목록을 localStorage에 영속하는 zustand 스토어.
 *
 * `useTrackActiveProject`(기록)와 `ProjectSwitcher`(소비)가 각자 이 훅을 호출해도
 * zustand 스토어는 모듈 전역 단일 인스턴스라 상태가 공유된다.
 *
 * - 초기값: 모듈 로드 시 {@link RECENT_PROJECTS_STORAGE_KEY} 1회 파싱
 *   (미설정·파싱 실패·SSR 환경 → 빈 목록)
 * - `pushRecentProject(key)`: 맨 앞으로 이동(이미 있으면 중복 제거) + 상한 축출 +
 *   localStorage 동시 갱신(fail-safe). **이미 맨 앞이면 아무것도 하지 않는다** —
 *   불필요한 write와 리렌더를 막는다(E5)
 *
 * 이 스토어는 "어디를 최근에 갔나"를 **저장만** 한다. 접근 가능 여부 대조는
 * 소비처가 `useProjects()` 목록과 맞춰 수행한다(E1) — 저장 시점에는 기록자
 * (`useTrackActiveProject`)의 `isKnownProject` 가드가 같은 일을 한다.
 *
 * ⚠️ 이 목록은 **프로젝트 스위처의 정렬 축 전용**이다. 사이드바 "최근 항목"은
 * 최근 본 **이슈**이며 별도 스토어를 쓴다 (ADR §D1).
 */
export const useRecentProjects = create<RecentProjectsStore>((set, get) => ({
  recentProjectKeys: readStoredRecentProjects(),
  pushRecentProject: (key: string): void => {
    // 빈 문자열은 무시한다 — 흘러가면 스위처에 이름 없는 항목이 뜬다
    if (key.length === 0) return

    const current = get().recentProjectKeys
    // 이미 맨 앞이면 순서가 바뀌지 않으므로 write도 리렌더도 불필요하다(E5)
    if (current[0] === key) return

    const next = [key, ...current.filter((existing) => existing !== key)].slice(
      0,
      MAX_RECENT_PROJECTS,
    )
    writeStoredRecentProjects(next)
    set({ recentProjectKeys: next })
  },
}))
