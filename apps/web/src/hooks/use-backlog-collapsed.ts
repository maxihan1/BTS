// 백로그 섹션 접힘 상태를 프로젝트별로 localStorage에 영속하는 훅 — zustand 공유 스토어 (FR-UX-13 F15 Task 5)
import { useCallback, useMemo } from 'react'
import { create } from 'zustand'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 · 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 접힌 섹션이 하나도 없는 상태(기본값 — 전부 펼침).
 *
 * 모듈 전역 동결 배열을 재사용해 참조를 고정한다. 매번 새 배열을 만들면
 * `useMemo` 결과가 렌더마다 바뀌어 소비 컴포넌트가 불필요하게 리렌더된다.
 */
const NONE_COLLAPSED: readonly string[] = Object.freeze([])

/**
 * 프로젝트별 접힘 목록을 저장하는 localStorage 키를 만든다.
 *
 * **키를 프로젝트마다 분리하는 이유.** 섹션 id 가 `sprint-{sprintId}` 라 프로젝트가
 * 달라지면 의미가 완전히 달라진다. 한 키에 몰아 담으면 A 프로젝트에서 접은
 * 스프린트 id 가 B 프로젝트의 다른 스프린트를 접는 오작동이 나고, 프로젝트를
 * 지워도 그 흔적이 남는다. 키가 분리돼 있으면 프로젝트별로 독립 수명을 갖는다.
 *
 * ⚠️ 저장값은 인증 토큰이 아니라 UI 선호값(어느 섹션을 접어 뒀는지)뿐이다.
 * 절대 규칙 §1.18(토큰 localStorage 저장 금지)은 인증 토큰이 대상이며,
 * 이 훅은 토큰·개인정보(PII)를 저장하지 않는다(`use-sidebar-collapsed.ts` 선례).
 *
 * @param projectKey 프로젝트 키(예: `PROJ`)
 */
export function backlogCollapsedStorageKey(projectKey: string): string {
  return `bts.backlog.collapsed.${projectKey}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 스토리지 헬퍼 — 전부 fail-safe (E6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알 수 없는 값이 문자열 배열인지 판정한다.
 *
 * 원소가 하나라도 문자열이 아니면 **전체를 거부한다** — 일부만 골라 쓰면 수동 변조된
 * 저장값의 절반이 살아남아 접힘 상태가 설명 불가능해진다(`use-project-tree-expanded.ts` 선례).
 */
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/**
 * localStorage에서 접힌 섹션 id 목록을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·시크릿 모드/쿠키 차단 등 접근 예외·깨진 JSON·배열이 아닌 값을
 * 모두 **전부 펼침**으로 폴백한다(E6). 화면에 오류를 띄우지 않는다.
 *
 * **저장돼 있으나 지금은 없는 `sprint-{id}` 는 걸러내지 않는다(E7).** 어떤 스프린트가
 * 실재하는지는 이 훅이 알 수 없고, 남아 있어도 대응하는 섹션이 없어 무해하다.
 *
 * @param projectKey 프로젝트 키
 * @returns 접힌 섹션 id 목록, 또는 미설정·손상·접근 불가 시 빈 목록
 */
function readStoredCollapsed(projectKey: string): readonly string[] {
  if (typeof window === 'undefined') return NONE_COLLAPSED

  try {
    const raw = window.localStorage.getItem(backlogCollapsedStorageKey(projectKey))
    if (raw === null) return NONE_COLLAPSED
    const parsed: unknown = JSON.parse(raw)
    return isStringArray(parsed) ? parsed : NONE_COLLAPSED
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 전부 펼침으로 폴백(E6)
    return NONE_COLLAPSED
  }
}

/**
 * localStorage에 접힌 섹션 id 목록을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 접힘 상태는 정상 유지된다(영속만 생략 — E6).
 *
 * @param projectKey 프로젝트 키
 * @param sectionIds 저장할 접힌 섹션 id 목록
 */
function writeStoredCollapsed(projectKey: string, sectionIds: readonly string[]): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(backlogCollapsedStorageKey(projectKey), JSON.stringify(sectionIds))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨(E6)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 접힘 상태 zustand 스토어 형태 */
export interface BacklogCollapsedStore {
  /** projectKey → 접힌 섹션 id 목록. 아직 한 번도 토글하지 않은 프로젝트는 키가 없다 */
  byProject: Record<string, readonly string[]>
  /** 섹션 접힘을 반전하고 localStorage에 동기화한다 */
  toggle: (projectKey: string, sectionId: string) => void
}

/** useBacklogCollapsed 반환 타입 */
export interface BacklogCollapsedResult {
  /**
   * 섹션이 접혀 있는지 확인한다.
   * @param sectionId droppable id 와 같은 문자열 — `backlog` 또는 `sprint-{sprintId}`
   */
  isCollapsed: (sectionId: string) => boolean
  /**
   * 섹션 접힘/펼침을 반전한다.
   * @param sectionId droppable id 와 같은 문자열 — `backlog` 또는 `sprint-{sprintId}`
   */
  toggle: (sectionId: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 스토어 · 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 섹션 접힘 상태의 zustand 공유 스토어.
 *
 * 백로그 화면은 섹션마다 컴포넌트가 따로 있어(`BacklogColumn`·`SprintColumn`) 각자
 * 훅을 호출한다. 컴포넌트-로컬 `useState` 였다면 인스턴스가 서로 격리돼 한 섹션의
 * 토글이 다른 섹션에 반영되지 않는다(`use-sidebar-collapsed.ts` 가 같은 이유로 zustand).
 *
 * ⚠️ 모듈 전역 싱글턴이라 테스트에서는
 * `useBacklogCollapsedStore.setState({ byProject: {} })` 리셋이 필수다.
 */
export const useBacklogCollapsedStore = create<BacklogCollapsedStore>((set, get) => ({
  byProject: {},
  toggle: (projectKey: string, sectionId: string): void => {
    // 아직 스토어에 없는 프로젝트는 저장값을 기준으로 반전해야 다른 섹션의 접힘이 지워지지 않는다
    const current = get().byProject[projectKey] ?? readStoredCollapsed(projectKey)
    const next = current.includes(sectionId)
      ? current.filter((id) => id !== sectionId)
      : [...current, sectionId]

    writeStoredCollapsed(projectKey, next)
    set((state) => ({ byProject: { ...state.byProject, [projectKey]: next } }))
  },
}))

/**
 * 백로그 섹션 접힘 상태를 프로젝트별로 localStorage에 영속하는 훅.
 *
 * - 기본값: **전부 펼침**. 저장값이 없거나 손상됐거나 스토리지가 막혀 있어도 같다(E6)
 * - 저장 키: {@link backlogCollapsedStorageKey} (`bts.backlog.collapsed.{projectKey}`)
 * - 저장 값: 접힌 섹션 id 배열 — droppable id 와 같은 문자열
 *
 * @param projectKey 현재 백로그가 속한 프로젝트 키
 */
export function useBacklogCollapsed(projectKey: string): BacklogCollapsedResult {
  const stored = useBacklogCollapsedStore((state) => state.byProject[projectKey])
  const toggleSection = useBacklogCollapsedStore((state) => state.toggle)

  // 스토어에 없는 프로젝트만 저장값을 읽는다. 첫 렌더에서 동기적으로 읽어야
  // 새로고침 직후 「펼침 → 접힘」으로 깜빡이지 않는다.
  const collapsedIds = useMemo(
    () => stored ?? readStoredCollapsed(projectKey),
    [stored, projectKey],
  )

  const isCollapsed = useCallback(
    (sectionId: string) => collapsedIds.includes(sectionId),
    [collapsedIds],
  )

  const toggle = useCallback(
    (sectionId: string) => {
      toggleSection(projectKey, sectionId)
    },
    [toggleSection, projectKey],
  )

  return { isCollapsed, toggle }
}
