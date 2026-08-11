// R4 줄수 래칫의 동결 베이스라인 — 200줄을 넘는 비-테스트 함수의 현재 상태를 얼린다.
//
// 키 = `<apps/web 기준 상대경로>::<ESLint 서술자>`. 값 = raw 줄수(빈 줄·주석 포함).
// ★줄인 뒤에는 이 숫자를 **함께 낮춰라.** 낮추지 않으면 그만큼 다시 늘릴 여지가 남는다.
// ★새 항목을 여기 추가하는 것은 「200줄 넘는 컴포넌트를 하나 더 승인한다」는 뜻이다. 리뷰에서 그렇게 읽어라.
//
// 아래 18건은 손으로 적은 것이 아니라 `lint-ratchet.test.ts` 의 실패 출력을 그대로 옮긴 것이다.
// 갱신할 때도 같은 방법을 쓴다 — 테스트를 돌려 나온 줄을 붙여 넣는다.
export const OVERSIZED_FUNCTION_BASELINE: Readonly<Record<string, number>> = {
  "src/components/auth/MfaSettings.tsx::Function 'MfaSettings'": 218,
  "src/components/automation/AutomationRuleFormDialog.tsx::Function 'FormBody'": 230,
  "src/components/custom-fields/CustomFieldFormDialog.tsx::Function 'FormBody'": 225,
  'src/components/import/mapping/ImportMappingWizard.tsx::Arrow function': 288,
  "src/components/issue-templates/IssueTemplateFormDialog.tsx::Function 'FormBody'": 259,
  "src/components/issue/IssueCreateForm.tsx::Function 'IssueCreateForm'": 227,
  "src/components/issue/IssueDescription.tsx::Function 'EditMode'": 337,
  "src/components/issue/IssueMetaPanel.tsx::Function 'IssueMetaPanel'": 317,
  "src/components/issue/mention/use-mention-autocomplete.ts::Function 'useMentionAutocomplete'": 272,
  "src/components/issues/BulkTransitionDialog.tsx::Function 'BulkTransitionDialog'": 212,
  "src/components/issues/MoveIssueDialog.tsx::Function 'MoveIssueDialog'": 326,
  "src/components/issues/NodeMappingSection.tsx::Function 'NodeMappingSection'": 232,
  "src/components/search/ExportDialog.tsx::Function 'ExportForm'": 334,
  "src/routes/dashboards.$dashboardId.tsx::Function 'DashboardDetailPage'": 332,
  "src/routes/issues.$key.tsx::Function 'IssueDetailPage'": 1041,
  "src/routes/issues.index.tsx::Function 'IssueListPage'": 331,
  "src/routes/projects.$projectKey.board.tsx::Function 'BoardPage'": 367,
  "src/routes/settings.account-links.tsx::Function 'AccountLinksSettingsPage'": 257,
}
