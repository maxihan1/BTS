// ESLint flat config — TypeScript-ESLint + React Hooks + React Refresh
import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

export default tseslint.config(
  { ignores: ['dist'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // D1-a. 'console.log' 등은 NEVER-15 (DEVELOPMENT.md §1 절대 규칙 #15) 위반.
      // warn/error 만 허용 (디버깅 + error boundary). ADR. docs/decisions/2026-05-22-frontend-logging-policy.md
      'no-console': ['error', { allow: ['warn', 'error'] }],
      // FR-UX-06 Phase 2 — radix Dialog primitive 직접 import 금지. 모든 소비자는 공용
      // @/components/ui/dialog compound 래퍼를 쓴다(흡수한 결과를 되돌리지 못하게 봉인).
      // 예외 = 래퍼가 사는 src/components/ui/** (아래 override에서 off). AlertDialog 등 다른
      // primitive는 대상 아님(importNames가 'Dialog' 정확 일치만 차단).
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'radix-ui',
              importNames: ['Dialog'],
              message:
                'radix Dialog를 직접 import하지 마세요. @/components/ui/dialog 래퍼를 사용하세요 (FR-UX-06 Phase 2).',
            },
          ],
        },
      ],
    },
  },
  {
    // shadcn/ui 컴포넌트는 variants/hooks를 같은 파일에서 export하는 공식 패턴 — fast-refresh 경고 비활성화.
    // + 래퍼 레이어라 radix primitive 직접 import가 정당하므로 no-restricted-imports도 해제한다.
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
      'no-restricted-imports': 'off',
    },
  },
)
