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
    },
  },
  {
    // shadcn/ui 컴포넌트는 variants/hooks를 같은 파일에서 export하는 공식 패턴 — fast-refresh 경고 비활성화
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
)
