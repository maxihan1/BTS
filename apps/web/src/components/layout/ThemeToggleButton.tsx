// 상단바 해/달 테마 전환 버튼 — 누르면 라이트 ↔ 다크, 환경설정 theme 으로 저장 (FR-PF-01)
import type { JSX } from 'react'
import { Moon, Sun } from 'lucide-react'
import { toast } from 'sonner'
import { useAuthUser } from '@/auth/authStore'
import { usePreferencesMutation } from '@/api/preferences'
import { applyTheme, isTheme } from '@/lib/theme'
import { navLabels } from '@/i18n/nav-labels'
import { Button } from '@/components/ui/button'

/**
 * 지금 보이는 테마(라이트 · 다크)의 아이콘을 보이고, 누르면 반대쪽으로 저장한다.
 * `system` 이면 OS 가 정한 쪽을 지금 테마로 보고 반대쪽을 명시값으로 저장한다 — `system` 복귀는 환경 설정에서.
 *
 * 저장 뒤 whoami 재조회 → `PreferencesProvider` 가 적용하는 경로는 그대로 두고,
 * 누른 즉시 바뀌어 보이도록 `applyTheme` 을 먼저 한 번 부른다. 저장이 실패하면 원래 테마로 되돌린다.
 */
export function ThemeToggleButton(): JSX.Element {
  const user = useAuthUser()
  const mutation = usePreferencesMutation()
  const theme = user?.theme !== undefined && isTheme(user.theme) ? user.theme : 'system'
  // system 이면 `PreferencesProvider` 가 `<html>` 에 실제로 건 `.dark` 를 읽는다 — matchMedia 를 다시 부르지 않는다.
  // ponytail: system 모드에서 OS 테마가 바뀌어도 아이콘은 다음 렌더까지 그대로다. 문제되면 matchMedia 구독
  const isDark = theme === 'system' ? document.documentElement.classList.contains('dark') : theme === 'dark'

  function handleClick(): void {
    const next = isDark ? 'light' : 'dark'
    applyTheme(next)
    mutation.mutate({ theme: next }, {
      onError: () => {
        applyTheme(theme)
        toast.error(navLabels.themeSaveError)
      },
    })
  }

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      className="rounded-md hover:bg-accent"
      aria-label={isDark ? navLabels.themeToLight : navLabels.themeToDark}
      disabled={mutation.isPending}
      onClick={handleClick}
    >
      {isDark ? <Moon className="size-4" /> : <Sun className="size-4" />}
    </Button>
  )
}
