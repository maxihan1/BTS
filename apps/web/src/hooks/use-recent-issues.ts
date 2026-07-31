// 최근 본 이슈 MRU 목록 localStorage 영속 훅 — zustand 공유 스토어 (FR-UX-08 PR-B Task 1, ADR D3/D4)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 최근 본 이슈 목록을 저장하는 localStorage 키.
 *
 * ⚠️ **이 키에는 이슈 키 문자열만 저장한다. 제목·본문·담당자 등 업무 내용을 저장하지 않는다**
 * (ADR §D4 · 스펙 NFR1). 근거는 둘이다.
 *
 * 1. **선례 부재.** 실측한 기존 localStorage 사용처 6곳은 전부 화면 설정값이다 —
 *    `bts.theme` · `bts.sidebar.collapsed` · `bts.active-project` · `issue-table-columns` ·
 *    `timeline-zoom` · 열 표시 설정. 업무 내용을 저장한 전례가 0건이다.
 * 2. **로그아웃이 이 저장소를 지우지 않는다.** `authStore.ts` 의 `clearSession()` 은
 *    `sessionStorage` 만 정리한다. 제목을 저장하면 **같은 브라우저를 쓰는 다음 사용자가
 *    이전 사용자의 이슈 제목을 읽는다.**
 *
 * 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증 토큰을 대상으로 하므로 이 훅을
 * 직접 강제하지는 않는다. 그러나 §1.18 의 취지와 위 선례 부재를 함께 보면 **키만 저장**이
 * 이 코드베이스의 일관된 답이다.
 *
 * 사용자 스코프가 없어 로그아웃 후에도 키는 남지만, 소비처가 제목을 조회할 때
 * 403/404 로 떨어져 자동 탈락한다(스펙 E2 · L1).
 */
export const RECENT_ISSUES_STORAGE_KEY = 'bts.recent-issues' as const

/**
 * 최근 목록의 상한 (ADR §D3 — Maxi 확정).
 *
 * 초과 시 가장 오래된 항목부터 축출한다(LRU). 사이드바 세로 공간과 "최근"의 의미
 * (전체 목록의 대체가 아님) 사이에서 정해진 값이며, 마운트 시 조회량 상한이기도 하다
 * (스펙 NFR5 — 최대 5건).
 */
export const MAX_RECENT_ISSUES = 5

/** localStorage 미설정·읽기 실패 시 기본값 */
const DEFAULT_RECENT_ISSUES: readonly string[] = []

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알 수 없는 값이 문자열 배열인지 판정한다.
 *
 * **원소가 하나라도 문자열이 아니면 전체를 거부한다** — 일부만 골라 쓰면 수동 변조된
 * 저장값의 절반이 살아남아 "왜 이 이슈가 최근 목록에 있지"를 추적할 수 없게 된다.
 */
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/**
 * localStorage에서 최근 이슈 목록을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 스토리지 접근 예외·잘못된 JSON·
 * 배열이 아닌 값·원소가 문자열이 아닌 배열을 모두 빈 목록으로 폴백한다(fail-safe).
 *
 * **중복 제거가 상한 적용보다 먼저**여야 상한이 정확히 채워진다 — push 경로에만 중복
 * 제거를 두고 복원 경로에 두지 않으면 수동 변조·과거 버전 잔재가 사이드바에 같은 이슈를
 * 두 번 렌더하고 React `key` 중복 경고를 낸다(FR-UX-08 PR-A 코드리뷰 CR2 와 동형).
 *
 * @returns 저장된 이슈 키 목록(MRU 순, 중복 제거 + 상한 적용), 또는 폴백 시 빈 목록
 */
function readStoredRecentIssues(): string[] {
  if (typeof window === 'undefined') return [...DEFAULT_RECENT_ISSUES]

  try {
    const raw = window.localStorage.getItem(RECENT_ISSUES_STORAGE_KEY)
    if (raw === null) return [...DEFAULT_RECENT_ISSUES]
    const parsed: unknown = JSON.parse(raw)
    if (!isStringArray(parsed)) return [...DEFAULT_RECENT_ISSUES]
    return [...new Set(parsed)].slice(0, MAX_RECENT_ISSUES)
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 빈 목록으로 폴백
    return [...DEFAULT_RECENT_ISSUES]
  }
}

/**
 * localStorage에 최근 이슈 목록을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 상태는 정상 유지된다(영속만 생략).
 *
 * @param keys 저장할 이슈 키 목록 (MRU 순)
 */
function writeStoredRecentIssues(keys: readonly string[]): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(RECENT_ISSUES_STORAGE_KEY, JSON.stringify(keys))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useRecentIssues 스토어 형태 */
export interface RecentIssuesStore {
  /** 최근 본 이슈 키 목록 — MRU 순(맨 앞이 가장 최근), 최대 {@link MAX_RECENT_ISSUES}개 */
  recentIssueKeys: string[]
  /** 이슈 키를 최근 목록 맨 앞에 기록한다 (중복 제거 + 상한 축출 + localStorage 동기화) */
  pushRecentIssue: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 최근 본 이슈 MRU 목록을 localStorage에 영속하는 zustand 스토어.
 *
 * 기록자(`routes/issues.$key.tsx`)와 소비처(사이드바 "최근 항목")가 각자 이 훅을 호출해도
 * zustand 스토어는 모듈 전역 단일 인스턴스라 상태가 공유된다.
 *
 * - 초기값: 모듈 로드 시 {@link RECENT_ISSUES_STORAGE_KEY} 1회 파싱
 *   (미설정·파싱 실패·SSR 환경 → 빈 목록)
 * - `pushRecentIssue(key)`: 맨 앞으로 이동(이미 있으면 중복 제거) + 상한 축출 +
 *   localStorage 동시 갱신(fail-safe). **이미 맨 앞이면 아무것도 하지 않는다** —
 *   불필요한 write와 리렌더를 막는다(스펙 E5)
 *
 * 이 스토어는 "어디를 최근에 봤나"를 **저장만** 한다. 접근 가능 여부 대조는 소비처가
 * 제목 조회 결과(403/404 → 숨김)로 수행한다(스펙 E2) — 프로젝트 쪽
 * `useRecentProjects` 가 `isKnownProject` 가드에 의존하는 것과 달리, 이슈는 목록 API 가
 * 없으므로 **조회 실패가 곧 가드**다.
 *
 * ⚠️ 기록 지점은 `routes/issues.$key.tsx` **한 곳뿐**이다. 생산 지점을 늘리면 가드가
 * 한쪽에만 붙는 결함(FR-UX-07 코드리뷰 CR3)이 재발한다.
 */
export const useRecentIssues = create<RecentIssuesStore>((set, get) => ({
  recentIssueKeys: readStoredRecentIssues(),
  pushRecentIssue: (key: string): void => {
    // 빈 문자열은 무시한다 — 흘러가면 사이드바에 이름 없는 항목이 뜬다
    if (key.length === 0) return

    const current = get().recentIssueKeys
    // 이미 맨 앞이면 순서가 바뀌지 않으므로 write도 리렌더도 불필요하다(E5)
    if (current[0] === key) return

    const next = [key, ...current.filter((existing) => existing !== key)].slice(
      0,
      MAX_RECENT_ISSUES,
    )
    writeStoredRecentIssues(next)
    set({ recentIssueKeys: next })
  },
}))
