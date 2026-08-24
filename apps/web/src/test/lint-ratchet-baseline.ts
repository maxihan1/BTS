// R4 줄수 래칫의 동결 베이스라인 — 200줄을 넘는 비-테스트 함수의 현재 상태를 얼린다.
//
// 키 = `<apps/web 기준 상대경로>::<ESLint 서술자>`. 값 = raw 줄수(빈 줄·주석 포함).
// ★이 표는 실측과 **정확히 일치**해야 한다 (부채 매핑 22 · 2026-08-15 단조 → 엄격 일치).
//   늘린 것뿐 아니라 **줄이고 여기를 안 낮춘 것도 red** 이고, 200줄 밑으로 내려가 스캔에서
//   사라졌는데 줄을 안 지운 것(유령 키)도 red 다. 판정은 `lint-ratchet.test.ts` 의 drift 단언.
// ★새 항목을 여기 추가하는 것은 「200줄 넘는 컴포넌트를 하나 더 승인한다」는 뜻이다. 리뷰에서 그렇게 읽어라.
//
// ★건수를 여기 적지 않는다. 적는 순간 그것이 두 번째 목록이 되어 갈라진다 — 세고 싶으면
//   판별식을 돌려라. 아래 줄들은 손으로 적은 것이 아니라 `lint-ratchet.test.ts` 의 실패 출력을
//   그대로 옮긴 것이다. 갱신할 때도 같은 방법을 쓴다 — 테스트를 돌려 나온 줄을 붙여 넣는다.
export const OVERSIZED_FUNCTION_BASELINE: Readonly<Record<string, number>> = {
  "src/components/auth/MfaSettings.tsx::Function 'MfaSettings'": 218,
  "src/components/automation/AutomationRuleFormDialog.tsx::Function 'FormBody'": 230,
  "src/components/custom-fields/CustomFieldFormDialog.tsx::Function 'FormBody'": 225,
  // ★2026-08-14 (부채 매핑 16) 288 → 289. **의도적으로 올린 유일한 항목**이다.
  //   진행 중 신호를 부모에게 보고해야 하는데, 본문에 남길 수 있는 최소가 훅 호출 1줄이다
  //   (효과 본문·판정식·KDoc 은 전부 컴포넌트 밖 `useBusySignal`·`isWizardBusy` 로 뺐고,
  //    props 구조분해도 1줄로 되돌렸다 — 그렇게 296 에서 289 까지 내렸다).
  //   ⚠️ 이 PR 의 계획은 「baseline 무변경」을 완료 기준으로 적었다. 이 1줄이 그 이탈이며
  //      게이트 2 요약에 그대로 싣는다. 이탈이 겨눈 위험(항목 8·22 가 줄이려는 두 함수)은
  //      건드리지 않았다 — `IssueCreateForm`(227)·`IssueMetaPanel`(317)은 소스 0줄 변경이다.
  //   ★2026-08-15 (#385) 후행 사실 — 위 두 함수 중 `IssueCreateForm` 은 **상환돼 이 표에서
  //      사라졌다**(227 → 127). `IssueMetaPanel`(317)만 남아 있다. 위 문단을 그대로 읽으면
  //      없는 줄을 찾게 되므로 여기 적어 둔다.
  'src/components/import/mapping/ImportMappingWizard.tsx::Arrow function': 289,
  "src/components/issue-templates/IssueTemplateFormDialog.tsx::Function 'FormBody'": 259,
  "src/components/issue/IssueDescription.tsx::Function 'EditMode'": 337,
  "src/components/issue/IssueMetaPanel.tsx::Function 'IssueMetaPanel'": 317,
  "src/components/issue/mention/use-mention-autocomplete.ts::Function 'useMentionAutocomplete'": 272,
  "src/components/issues/BulkTransitionDialog.tsx::Function 'BulkTransitionDialog'": 212,
  "src/components/issues/MoveIssueDialog.tsx::Function 'MoveIssueDialog'": 311,
  "src/components/issues/NodeMappingSection.tsx::Function 'NodeMappingSection'": 232,
  "src/components/search/ExportDialog.tsx::Function 'ExportForm'": 334,
  "src/routes/dashboards.$dashboardId.tsx::Function 'DashboardDetailPage'": 332,
  "src/routes/issues.$key.tsx::Function 'IssueDetailPage'": 1013,
  "src/routes/issues.index.tsx::Function 'IssueListPage'": 331,
  "src/routes/projects.$projectKey.board.tsx::Function 'BoardPage'": 367,
  "src/routes/settings.account-links.tsx::Function 'AccountLinksSettingsPage'": 257,
}
