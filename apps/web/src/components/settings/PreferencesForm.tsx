// 사용자 환경설정(테마/언어/날짜포맷) 편집 폼 — 선택 즉시 PATCH 저장 + 날짜포맷 라이브 프리뷰 (FR-PF-01 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useAuthUser } from '@/auth/authStore'
import { usePreferencesMutation, isLocale } from '@/api/preferences'
import type { Theme, Locale, PreferencesResponse } from '@/api/preferences'
import { isTheme } from '@/lib/theme'
import { isDatePreset, formatDateByPreset } from '@/lib/date-preferences'
import type { DatePreset } from '@/lib/date-preferences'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

/** 라이브 프리뷰 참조 날짜 — 스펙 §date_format 프리셋 표의 예시 날짜(2026-07-07)와 동일한 고정값 */
const PREVIEW_SAMPLE_DATE = '2026-07-07'

/** 언어 셀렉터 하단 안내 문구 — UI 문자열 번역은 후속 범위(스펙 S5) */
const LOCALE_NOTICE = '언어 설정은 저장되며, 화면 문구 번역은 후속 업데이트에서 제공됩니다.'

/** 저장 실패 시 노출하는 공통 에러 메시지 */
const SAVE_ERROR_MESSAGE = '환경설정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** whoami의 theme 필드에서 유효한 Theme를 읽는다 — 부재/알 수 없는 값이면 'system' */
function resolveInitialTheme(value: string | undefined): Theme {
  return value !== undefined && isTheme(value) ? value : 'system'
}

/** whoami의 locale 필드에서 유효한 Locale을 읽는다 — 부재/알 수 없는 값이면 'ko' */
function resolveInitialLocale(value: string | undefined): Locale {
  return value !== undefined && isLocale(value) ? value : 'ko'
}

/** whoami의 dateFormat 필드에서 유효한 DatePreset을 읽는다 — 부재/알 수 없는 값이면 'iso' */
function resolveInitialDateFormat(value: string | undefined): DatePreset {
  return value !== undefined && isDatePreset(value) ? value : 'iso'
}

/**
 * 사용자 환경설정 편집 폼.
 *
 * 초기값은 whoami(`useAuthUser()`)에서 읽는다. 각 셀렉터는 선택 즉시 PATCH를 저장하며
 * 성공 응답으로 세 필드를 모두 재동기화한다. 저장 성공 후 whoami를 재조회해 authStore를
 * 갱신한다(`usePreferencesMutation`).
 */
export function PreferencesForm(): JSX.Element {
  const user = useAuthUser()
  const mutation = usePreferencesMutation()

  const [theme, setTheme] = useState<Theme>(() => resolveInitialTheme(user?.theme))
  const [locale, setLocale] = useState<Locale>(() => resolveInitialLocale(user?.locale))
  const [dateFormat, setDateFormat] = useState<DatePreset>(() => resolveInitialDateFormat(user?.dateFormat))

  function syncFromResponse(updated: PreferencesResponse): void {
    setTheme(updated.theme)
    setLocale(updated.locale)
    setDateFormat(updated.dateFormat)
  }

  function handleThemeChange(value: string): void {
    const next = value as Theme
    setTheme(next)
    mutation.mutate({ theme: next }, { onSuccess: syncFromResponse })
  }

  function handleDateFormatChange(value: string): void {
    const next = value as DatePreset
    setDateFormat(next)
    mutation.mutate({ dateFormat: next }, { onSuccess: syncFromResponse })
  }

  function handleLocaleChange(value: string): void {
    const next = value as Locale
    setLocale(next)
    mutation.mutate({ locale: next }, { onSuccess: syncFromResponse })
  }

  return (
    <div className="space-y-6">
      {mutation.isError && (
        <div role="alert" aria-live="polite" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {SAVE_ERROR_MESSAGE}
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor="preferences-theme">테마</Label>
        <Select value={theme} onValueChange={handleThemeChange} disabled={mutation.isPending}>
          <SelectTrigger id="preferences-theme" aria-label="테마" className="w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="light">라이트</SelectItem>
            <SelectItem value="dark">다크</SelectItem>
            <SelectItem value="system">시스템 설정</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="preferences-date-format">날짜 표시 형식</Label>
        <Select value={dateFormat} onValueChange={handleDateFormatChange} disabled={mutation.isPending}>
          <SelectTrigger id="preferences-date-format" aria-label="날짜 표시 형식" className="w-64">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="iso">ISO (2026-07-07)</SelectItem>
            <SelectItem value="kr">한국식 (2026. 07. 07.)</SelectItem>
            <SelectItem value="us">미국식 (07/07/2026)</SelectItem>
            <SelectItem value="eu">유럽식 (07/07/2026)</SelectItem>
          </SelectContent>
        </Select>
        <p className="text-sm text-muted-foreground">
          미리보기: {formatDateByPreset(PREVIEW_SAMPLE_DATE, dateFormat)}
        </p>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="preferences-locale">언어</Label>
        <Select value={locale} onValueChange={handleLocaleChange} disabled={mutation.isPending}>
          <SelectTrigger id="preferences-locale" aria-label="언어" className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ko">한국어</SelectItem>
            <SelectItem value="en">English</SelectItem>
          </SelectContent>
        </Select>
        <p className="text-xs text-muted-foreground">{LOCALE_NOTICE}</p>
      </div>
    </div>
  )
}
