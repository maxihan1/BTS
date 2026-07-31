// 프로젝트 트리 펼침 집합 localStorage 영속 훅 — zustand 공유 스토어 (FR-UX-08 PR-A Task 2, ADR D2)
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 트리에서 펼쳐진 프로젝트 키 집합을 저장하는 localStorage 키.
 *
 * ⚠️ 이 키에 저장되는 값은 인증 토큰이 아니라 **UI 선호값**(어느 프로젝트 행을 펼쳐
 * 뒀는지)뿐이다. 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증 토큰을 대상으로
 * 하며, 이 훅은 토큰이나 개인정보(PII)를 저장하지 않으므로 §1.18과 무관하다
 * (`use-sidebar-collapsed.ts`·`use-active-project.ts` 선례).
 *
 * **영속 범위는 프로젝트 레벨이다.** 중첩그룹("리포트"·"프로젝트 설정")의 펼침은
 * `ProjectTree.tsx`의 `ProjectTreeRow` 로컬 `useState`라 이 스토어의 대상이 아니다
 * (스펙 L6).
 */
export const PROJECT_TREE_EXPANDED_STORAGE_KEY = 'bts.project-tree.expanded' as const

// ─────────────────────────────────────────────────────────────────────────────
// 직렬화 헬퍼 — 메모리는 Set, 저장은 string[]
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알 수 없는 값이 문자열 배열인지 판정한다.
 *
 * **원소가 하나라도 문자열이 아니면 전체를 거부한다** — 일부만 골라 쓰면 수동 변조된
 * 저장값의 절반이 살아남아 펼침 상태가 설명 불가능해진다.
 */
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/**
 * localStorage에서 펼침 집합을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 스토리지 접근 예외·잘못된 JSON·
 * 배열이 아닌 값·원소가 문자열이 아닌 배열을 모두 빈 집합으로 폴백한다(fail-safe).
 *
 * @returns 저장된 펼침 키 집합, 또는 폴백 시 빈 집합
 */
function readStoredExpandedKeys(): Set<string> {
  if (typeof window === 'undefined') return new Set<string>()

  try {
    const raw = window.localStorage.getItem(PROJECT_TREE_EXPANDED_STORAGE_KEY)
    if (raw === null) return new Set<string>()
    const parsed: unknown = JSON.parse(raw)
    if (!isStringArray(parsed)) return new Set<string>()
    return new Set(parsed)
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 빈 집합으로 폴백
    return new Set<string>()
  }
}

/**
 * localStorage에 펼침 집합을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 상태는 정상 유지된다(영속만 생략).
 *
 * @param keys 저장할 펼침 키 집합
 */
function writeStoredExpandedKeys(keys: ReadonlySet<string>): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(
      PROJECT_TREE_EXPANDED_STORAGE_KEY,
      JSON.stringify([...keys]),
    )
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useProjectTreeExpanded 스토어 형태 */
export interface ProjectTreeExpandedStore {
  /** 현재 펼쳐진 프로젝트 키 집합 */
  expandedKeys: Set<string>
  /** 사용자의 디스클로저 클릭 — 펼침/접힘을 반전하고 localStorage에 동기화한다 */
  toggle: (key: string) => void
  /** 활성 프로젝트 자동 펼침 — **더하기만** 한다. 다른 키를 제거하지 않는다 */
  expand: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 — zustand 공유 스토어
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 트리 펼침 집합을 localStorage에 영속하는 zustand 스토어.
 *
 * **★ FR-UX-06 PR12 의 FR5 를 정정한다 (ADR §D2).** 기존 `ProjectTree`는 라우트가
 * 바뀔 때마다 `setExpandedKeys(new Set([activeProjectKey]))`로 펼침 집합을 **통째로
 * 갈아엎었고**, JSDoc이 이를 *"이전 수동 펼침을 덮어쓴다"* · *"수동 펼침은 ephemeral
 * 이며 영속하지 않는다"* 로 의도된 동작으로 명시하고 있었다.
 *
 * 새 동작은 **더하기(`expand`)만** 한다 — 활성 프로젝트가 바뀌면 그 키를 집합에
 * 추가할 뿐 다른 키를 제거하지 않는다. 자동펼침의 원래 목적("현재 프로젝트가 보이게
 * 한다")은 100% 보존되고, "내가 접은 게 왜 다시 열리나"만 사라진다.
 *
 * - 초기값: 모듈 로드 시 {@link PROJECT_TREE_EXPANDED_STORAGE_KEY} 1회 파싱
 * - `toggle(key)`: 사용자 클릭 — 반전 + localStorage 동시 갱신
 * - `expand(key)`: 자동 펼침 — 더하기만. **이미 있으면 아무것도 하지 않는다**(E5)
 * - **상한 없음**(NFR7): 사용자가 스스로 접을 수 있고 그 접기가 이제 유지되므로
 *   프로그램이 대신 정리할 이유가 없다
 *
 * ⚠️ 테스트에서는 `useProjectTreeExpanded.setState({ expandedKeys: new Set() })`로
 * `beforeEach` 리셋이 **필수**다. 모듈 전역 싱글턴이라 상태가 테스트 간에 누출되고,
 * "나머지는 접힘" 계열 단언이 순서 의존이 되어 거짓 실패·거짓 통과가 둘 다 가능하다.
 */
export const useProjectTreeExpanded = create<ProjectTreeExpandedStore>((set, get) => ({
  expandedKeys: readStoredExpandedKeys(),
  toggle: (key: string): void => {
    if (key.length === 0) return

    const next = new Set(get().expandedKeys)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    writeStoredExpandedKeys(next)
    set({ expandedKeys: next })
  },
  expand: (key: string): void => {
    // 빈 문자열은 무시한다 — 집합에 들어가도 어떤 행과도 대응되지 않는다
    if (key.length === 0) return

    const current = get().expandedKeys
    // 이미 펼쳐져 있으면 상태가 바뀌지 않으므로 write도 리렌더도 불필요하다(E5)
    if (current.has(key)) return

    const next = new Set(current)
    next.add(key)
    writeStoredExpandedKeys(next)
    set({ expandedKeys: next })
  },
}))
