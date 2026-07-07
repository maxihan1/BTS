// whoami 사용자 환경설정(theme/locale)을 <html>에 반영하는 전역 Provider — FR-PF-01 Task-6
import { useEffect, type JSX, type ReactNode } from 'react'
import { useAuthUser } from '@/auth/authStore'
import { applyTheme, isTheme, writeStoredTheme } from '@/lib/theme'
import type { Theme } from '@/lib/theme'

/** user.theme 필드가 없거나(`undefined`) 알 수 없는 값일 때 사용할 기본 테마 */
const DEFAULT_THEME: Theme = 'system'

/** user.locale 필드가 없을 때(`undefined`) 사용할 기본 언어 */
const DEFAULT_LOCALE = 'ko'

/** {@link PreferencesProvider} Props */
export interface PreferencesProviderProps {
  /** 하위 트리 — Provider는 자체 UI 없이 그대로 통과시킨다 */
  readonly children: ReactNode
}

/**
 * 로그인 사용자의 theme/locale 환경설정을 전역(`<html>`)에 반영하는 Provider.
 *
 * - theme. {@link applyTheme}으로 `<html>`에 `.dark` 클래스를 토글한다. 'system'이면
 *   OS `prefers-color-scheme` 변경 이벤트를 구독해 실시간으로 추종하고, theme이
 *   system이 아니게 되거나 언마운트되면 구독을 해제한다. 적용한 값은 {@link writeStoredTheme}로
 *   `bts.theme` localStorage에도 미러링해 다음 로드 시 FOUC 방지 인라인 스크립트
 *   (`index.html`)가 재사용한다.
 * - locale. `<html lang>` 속성에 반영한다(UI 문자열 번역 자체는 이번 범위 밖).
 * - `user`가 없거나(미인증) 필드가 `undefined`(whoami 스키마가 `.optional()`)이면
 *   각각 {@link DEFAULT_THEME}/{@link DEFAULT_LOCALE}로 폴백한다.
 *
 * @param props children — 하위 트리
 * @returns children을 그대로 감싸는 Fragment
 */
export function PreferencesProvider({ children }: PreferencesProviderProps): JSX.Element {
  const user = useAuthUser()
  const themeValue = user?.theme
  const theme: Theme = themeValue !== undefined && isTheme(themeValue) ? themeValue : DEFAULT_THEME
  const locale = user?.locale ?? DEFAULT_LOCALE

  useEffect(() => {
    applyTheme(theme)
    writeStoredTheme(theme)
    if (theme !== 'system') return undefined

    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const handleChange = () => applyTheme(theme)
    media.addEventListener('change', handleChange)
    return () => media.removeEventListener('change', handleChange)
  }, [theme])

  useEffect(() => {
    document.documentElement.lang = locale
  }, [locale])

  return <>{children}</>
}
