// 테마(light/dark/system) 해석·적용·localStorage 영속 로직 — FR-PF-01 Task-6 (FOUC 프리하이드레이션 지원)

/** 지원하는 테마 값 3종 — 스펙 §theme 지원 값(light/dark/system) */
export const THEMES = ['light', 'dark', 'system'] as const

/** {@link THEMES} 중 하나 — 사용자 환경설정 theme 값 */
export type Theme = (typeof THEMES)[number]

/**
 * theme 값을 저장하는 localStorage 키.
 *
 * ⚠️ 이 키는 인증 토큰이 아니라 비민감 테마 enum('light'|'dark'|'system')만 저장하므로
 * 절대 규칙 §1.18(토큰 localStorage 금지)·authStore의 sessionStorage 정책(`bts.auth`)과
 * 무관하다. 목적은 FOUC(Flash Of Unstyled Content — 첫 페인트 순간 잘못된 테마가 잠깐
 * 보였다 바뀌는 깜빡임) 방지다. `index.html`의 인라인 스크립트가 React가 마운트되기
 * 전, 첫 페인트 이전에 이 값을 읽어 `.dark` 클래스를 미리 적용한다 — next-themes 등
 * 표준 라이브러리가 쓰는 것과 동일한 localStorage 프리하이드레이션 패턴이다.
 */
const THEME_STORAGE_KEY = 'bts.theme'

/**
 * 값이 지원하는 {@link Theme}인지 판별하는 타입 가드.
 * whoami 등 외부에서 온 느슨한 string 값(백엔드 view-layer 필드는 plain String)이나
 * localStorage에서 읽은 값을 안전하게 좁힐 때 사용한다.
 *
 * @param value 검사할 문자열
 * @returns Theme 여부
 */
export function isTheme(value: string): value is Theme {
  return (THEMES as readonly string[]).includes(value)
}

/**
 * theme 값을 실제로 적용할 'light' 또는 'dark'로 해석한다.
 * 'system'이면 OS의 다크 모드 선호(`prefers-color-scheme`)를 그대로 따른다.
 *
 * @param theme 사용자 환경설정 theme 값('light'|'dark'|'system')
 * @returns 'light' 또는 'dark'
 */
export function resolveTheme(theme: Theme): 'light' | 'dark' {
  if (theme !== 'system') return theme
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/**
 * 해석된 테마를 `<html>`(documentElement)의 `.dark` 클래스로 반영한다.
 * `index.css`의 `@custom-variant dark (&:is(.dark *))` 정의를 활용해 전역 다크 모드를 적용한다.
 *
 * @param theme 사용자 환경설정 theme 값('light'|'dark'|'system')
 */
export function applyTheme(theme: Theme): void {
  const resolved = resolveTheme(theme)
  document.documentElement.classList.toggle('dark', resolved === 'dark')
}

/**
 * localStorage에 저장된 theme 값을 읽는다({@link THEME_STORAGE_KEY} 참고).
 *
 * @returns 저장된 유효한 Theme 값, 없거나 유효하지 않으면 null
 */
export function readStoredTheme(): Theme | null {
  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  return stored !== null && isTheme(stored) ? stored : null
}

/**
 * theme 값을 localStorage에 저장한다({@link THEME_STORAGE_KEY} 참고).
 *
 * @param theme 저장할 Theme 값
 */
export function writeStoredTheme(theme: Theme): void {
  localStorage.setItem(THEME_STORAGE_KEY, theme)
}
