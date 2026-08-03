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
      // ─────────────────────────────────────────────────────────────────────
      // FR-UX-06 PR22 락 2종 — 흡수한 결과를 되돌리지 못하게 봉인한다.
      //
      // 왜 내장 no-restricted-syntax 인가. eslint-plugin-react 가 설치돼 있지 않고
      // 이 PR의 제약이 "신규 의존성 0"이라, AST 셀렉터를 직접 쓰는 내장 룰만 사용한다.
      // PR8의 Dialog 락(no-restricted-imports)과 같은 구조다.
      //
      // 예외 판정 기준 (아래 override 목록). Button 프리미티브는 기본 클래스에
      // `inline-flex items-center justify-center` 를 강제하므로, **좌측 정렬을 요구하거나
      // role 을 직접 지정한** 원시 button 은 흡수 대상이 아니다. 판정식은 하나다.
      //     OUT ⟺ role="…" 보유  OR  text-left 보유  OR  justify-start 보유
      // 이 판정식은 완료된 배치1에 역적용해 IN=0/OUT=11 로 수동 분류를 100% 재현하는 것으로
      // 정확성을 확인했다. 각 예외 발생 위에는 `// PR22 OUT — P4|P5|P6 <사유>` 주석이 있고,
      // src/components/__tests__/button-primitive-usage.test.ts 가 그 주석의 실재를
      // **목록 전수 비교**로 강제한다(개수 가드가 아니다). 예외를 늘리려면 주석과
      // EXPECTED_OUT 을 함께 갱신해야 한다.
      //
      // AST 기반이므로 **주석 속 `<button>` 리터럴은 위반으로 잡히지 않는다** —
      // routes/issues.index.tsx 의 JSDoc 예시가 그 사례다.
      // ─────────────────────────────────────────────────────────────────────
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXOpeningElement[name.name='button']",
          message:
            '원시 <button> 대신 @/components/ui/button 의 <Button>을 쓰세요 (FR-UX-06 PR22). ' +
            'role 지정·좌측 정렬 옵션 행·전체 클릭 영역은 eslint.config.js overrides 예외로 등재하고 ' +
            '해당 발생 위에 "// PR22 OUT — P4|P5|P6 <사유>" 주석을 남기세요.',
        },
        {
          selector: "JSXAttribute[name.name='className'] Literal[value=/animate-pulse/]",
          message:
            '인라인 스켈레톤 대신 @/components/ui/skeleton 의 <Skeleton>을 쓰세요 (디자인 스펙 §7 — 인라인 재정의 금지).',
        },
      ],
    },
  },
  {
    // FR-UX-06 PR22 — 원시 <button> 을 남기기로 판정한 파일 전수.
    // **아래 목록이 정본이고 개수는 세지 않는다.** 개수 리터럴은 목록이 늘어나는 순간
    // 거짓이 되는데 아무도 안 고쳐 조용히 오정보로 남는다(교훈
    // `orchestrator-instruction-counts-are-blindfolds` — 실제로 `19파일 / 20발생` 이
    // 낡은 채 방치돼 있었다). button-primitive-usage.test.ts:118 도 같은 이유로
    // "여기 적힌 숫자를 신뢰 근거로 쓰지 않는다" 고 선언한다.
    // 목록은 위 판정식으로 기계 도출했고, 각 발생 위의 `// PR22 OUT — P4|P5|P6` 주석과
    // 1:1 대응하며 button-primitive-usage.test.ts 가 그 대응을 **전수 비교**로 강제한다.
    files: [
      // ── P4 role="tab" (탭 시맨틱 직접 지정) ──
      'src/components/issue/IssueDescription.tsx',
      'src/routes/inbox.tsx',
      'src/routes/projects.$projectKey.sprints.$sprintId.burndown.tsx',
      // ── P5 옵션/후보 행 (w-full text-left 콤보박스·카탈로그) ──
      'src/components/admin/AddMemberDialog.tsx',
      'src/components/admin/AuditLogFilters.tsx',
      'src/components/admin/WorkflowSchemeSidebar.tsx',
      'src/components/component/ComponentLeadSelect.tsx',
      'src/components/dashboard/DashboardForm.tsx',
      'src/components/dashboard/GadgetCatalogModal.tsx',
      'src/components/filters/FilterBar.tsx',
      'src/components/global-permissions/GlobalPermissionFormDialog.tsx',
      'src/components/issue/meta/AssigneeUserList.tsx',
      'src/components/ooo/OooModal.tsx',
      'src/components/project/ProjectLeadSelect.tsx',
      // ── P6 전체 클릭 영역 (행/카드 전체가 버튼) ──
      'src/components/automation/RuleExecutionTraceRow.tsx',
      'src/components/dashboard/DashboardTile.tsx',
      'src/components/issue/IssueChangelog.tsx',
      'src/components/layout/ProjectTree.tsx',
      'src/features/calendar/MonthGrid.tsx',
      'src/routes/issues.$key.tsx',
    ],
    rules: {
      // 같은 파일의 animate-pulse 락은 살려 둔다 — button 예외가 스켈레톤 예외를 겸하면
      // 예외 범위가 조용히 넓어진다. button 셀렉터만 빼고 두 번째 셀렉터는 유지한다.
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='className'] Literal[value=/animate-pulse/]",
          message:
            '인라인 스켈레톤 대신 @/components/ui/skeleton 의 <Skeleton>을 쓰세요 (디자인 스펙 §7 — 인라인 재정의 금지).',
        },
      ],
    },
  },
  {
    // 테스트 픽스처는 원시 <button>·animate-pulse 로 프리미티브를 흉내 내 검증한다.
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}', 'src/mocks/**/*.{ts,tsx}'],
    rules: { 'no-restricted-syntax': 'off' },
  },
  {
    // shadcn/ui 컴포넌트는 variants/hooks를 같은 파일에서 export하는 공식 패턴 — fast-refresh 경고 비활성화.
    // + 래퍼 레이어라 radix primitive 직접 import가 정당하므로 no-restricted-imports도 해제한다.
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
      'no-restricted-imports': 'off',
      // 프리미티브 레이어 자체가 원시 <button>/animate-pulse 의 **유일한 정의처**다
      // (button.tsx 의 Comp = asChild ? Slot.Root : 'button', skeleton.tsx 의 animate-pulse).
      'no-restricted-syntax': 'off',
    },
  },
)
