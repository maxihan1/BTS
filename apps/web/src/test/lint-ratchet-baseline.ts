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
  // ⚠️ 2026-09-04 (부채 177) 신규 등록 237줄. **이 PR 의 부채가 아니다.**
  //   #453(에디터 마감 4건)이 이 컴포넌트를 141줄 키워 200줄을 넘겼는데 래칫 등록을 빠뜨렸다.
  //   #453 은 `gh pr checks` 가 "no checks reported" 인 채로 머지돼 R4 가 한 번도 돌지 않았다.
  //   부채 177 이 main 을 병합하며 처음 드러났고, 여기서 막히면 머지가 서므로 등록만 해 둔다.
  //   ★상환은 #453 쪽 몫이다 — 함수를 쪼개는 것이 정처방이고, 그 리팩터를 이 PR 이 하면
  //     보드 설정 화면의 리뷰 범위를 남의 컴포넌트로 넓히게 된다.
  "src/components/issue/AttachmentPreviewModal.tsx::Function 'AttachmentPreviewModal'": 237,
  // ★두 PR 이 같은 함수를 서로 다른 근거로 줄여 머지에서 만났다. 어느 쪽 값도 아니고 **실측**이다.
  //   - 부채 177 이 상세 보기 구성 읽기를 배선하며 317 → 333 (머지 블로커였다).
  //     baseline 을 올리는 대신 하단 액션 두 개(클론·삭제)를 `IssueMetaActions` 로 순수 추출 → 298.
  //   - #462 가 보고자 행을 `IssueReporterRow` 로 빼며 → 314.
  //   두 추출이 함께 적용된 머지 결과가 295 였고, 그 뒤 리뷰 C1 이 지목한 reporter 해석 경로를
  //   `IssueReporterRow` 와 맞추며 **296** 이 됐다(같은 화면에 UUID·이름 두 모양이 나오던 자리다).
  //   어느 PR 의 값도 아니고 매번 **실측**이다 — 양쪽 값을 그대로 고르면 둘 다 틀린다.
  //   여전히 200 위라 줄을 지우지 않는다. 200 밑으로 내리는 것은 #453 몫이라는 위 문단 그대로다.
  "src/components/issue/IssueMetaPanel.tsx::Function 'IssueMetaPanel'": 296,
  "src/components/issue/mention/use-mention-autocomplete.ts::Function 'useMentionAutocomplete'": 272,
  "src/components/issues/BulkTransitionDialog.tsx::Function 'BulkTransitionDialog'": 212,
  "src/components/issues/MoveIssueDialog.tsx::Function 'MoveIssueDialog'": 311,
  "src/components/issues/NodeMappingSection.tsx::Function 'NodeMappingSection'": 232,
  "src/components/search/ExportDialog.tsx::Function 'ExportForm'": 334,
  "src/routes/dashboards.$dashboardId.tsx::Function 'DashboardDetailPage'": 332,
  // J1 표시 방식 토글이 조건 렌더 1줄을 더했다(1011 → 1012). 함께 들어온 40줄 중 39줄은
  // `IssueDetailPresentationMenu` 로 떼어내 갚았고, 남은 한 줄은 「이 기능이 존재한다」는
  // 최소 렌더라 0줄로 만들려면 기능을 지우는 수밖에 없다.
  // ★2026-09-07 (J25~J27 독립 스크롤) 1003 → 944. **줄어든 값이라 승인이 아니라 상환이다.**
  //   본문/메타가 각자 스크롤하게 되면서 머리 영역이 두 스크롤 컨테이너의 바깥 형제가 됐고,
  //   그 층을 `IssueDetailHeader` 로 떼어냈다(구조가 달라진 자리라 파일도 갈랐다).
  //   리뷰 반영(lg 게이팅 · 거짓 주석 교정 · aria-label 경고)으로 929 → 944 로 되올랐다.
  //   전부 주석과 클래스이고 로직 증가는 0 이다. 착수 시점 1003 대비로는 여전히 상환이다.
  "src/routes/issues.$key.tsx::Function 'IssueDetailPage'": 944,
  "src/routes/issues.index.tsx::Function 'IssueListPage'": 331,
  // ★2026-08-24 367 → 372. **의도적으로 올린 항목**이다(위 289 선례와 같은 성격).
  //   보드 화면에만 `<h1>` 이 없어 헤더에 즐겨찾기 별 아이콘 하나만 떠 있었고 문서당 h1 이 0개였다
  //   (형제 뷰 `백로그`·`타임라인` 은 같은 자리에 h1 을 갖는다). 제목을 넣는 최소 형태가
  //   래퍼 `<div>` + `<h1>` + 사유 주석 = 5줄이다.
  //   ⚠️ 함수를 쪼개 상환하는 쪽을 택하지 않았다 — `projectFavoriteHeader` 는 `projectKey`·
  //      `canCreate`·`createIssueOpen`/`setCreateIssueOpen`·`queryClient`·`currentBoardId`·
  //      `navigate` 7개를 닫아 잡고 있어 추출하면 props 7개짜리 컴포넌트가 되고, 이 PR 의
  //      범위(레이아웃 결함 수정)를 넘는 리팩터가 된다. 게이트 2 요약에 이탈로 싣는다.
  //   상환 후보로는 이 항목이 그대로 남는다 — 부채 매핑에 함께 읽을 것.
  //   ★2026-08-31 (FR-BD-01-2) 372 → 393. **의도적으로 올린 항목**이다(위 두 선례와 같은 성격).
  //   보드 `⋯` 관리 메뉴(이름 변경·삭제)를 헤더 행에 다는 최소 형태가 21줄이다 — 메뉴 본체와
  //   두 다이얼로그는 이미 이 함수 **밖**(`BoardActionsMenu`·`RenameBoardDialog`)에 있고,
  //   여기 남은 것은 렌더 조건 + props 6개 + 삭제 후 이동(E2) 콜백뿐이다.
  //   ⚠️ 갱신 전에 래칫이 실제로 무는지 red 를 한 번 봤다(393 을 요구하는 실패 출력).
  //      숫자만 맞추면 그 래칫은 그 뒤로 아무것도 지키지 않는다.
  //   상환 후보로 이 항목은 그대로 남는다 — 위 2026-08-24 주석의 `projectFavoriteHeader`
  //   추출 논의와 함께 읽을 것.
  // 2026-09-02 FR-BD-04 D6(PR ③) — 393 → 422. 스크럼 보드 화면이 붙으면서 라우트가 그만큼 커졌다.
  // 먼저 쪼갤 것을 쪼갰다 — 활성 스프린트 표기는 `ActiveSprintSummary`, 빈 상태와 그 판정은
  // `ScrumSprintEmptyState` 로 나갔다(434 에서 11줄 회수). 남은 증가분은 라우트가 지는
  // 오케스트레이션(활성 스프린트 축 · 빈 상태 분기 3곳)이라 더 빼면 인위적이다.
  // 2026-09-02 FR-BD-04 PR ⑥ — 422 → 426. **의도적으로 올린 항목**이다(위 선례들과 같은 성격).
  // 뷰 전환 nav 가 보드 스코프를 잃던 결함(편차 X7 부분 해소)을 닫는 최소 형태가 4줄이다 —
  // `viewNavLinks` 호출 1 + 주석 1 + 빈 상태 CTA 에 `boardId` 를 넘기는 prop·가드 2.
  // 먼저 쪼갤 것을 쪼갰다 — 링크 조립 `useMemo` 와 그 KDoc 은 컴포넌트 **밖**
  // `useBoardViewNavLinks` 로 나갔다(446 에서 20줄 회수). 남은 4줄은 라우트가 지는 배선이라
  // 더 빼면 인위적이다.
  // ⚠️ 갱신 전에 래칫이 실제로 무는지 red 를 **두 번** 봤다(446 요구 → 추출 후 426 요구).
  //    숫자만 맞추면 그 래칫은 그 뒤로 아무것도 지키지 않는다.
  // 상환 후보로 이 항목은 그대로 남는다 — 위 `projectFavoriteHeader` 추출 논의와 함께 읽을 것.
  // 426 → 420 (Jira 패리티 J5) — 뷰 전환 nav 를 셸(`ProjectViewChrome`)로 옮기며 6줄이 빠졌다.
  // ⚠️ 2026-09-04 (부채 177) 420 → 421. **의도적으로 올렸다.**
  //   `BoardActionsMenu` 에 `canConfigure` prop 을 넘기는 JSX 한 줄이다 — 보드 설정 진입
  //   메뉴 항목의 권한 게이팅이고, 그 한 줄 없이는 기능이 성립하지 않는다.
  //   ★설명 주석 2줄은 걷어내 증가를 최소로 줄였다(423 → 421). 남은 +1 은 prop 그 자체다.
  //   #367 이 「래칫 장부를 낮추라는 지시를 안 지켜 재성장이 사전 승인됐다」로 남긴 사고를
  //   반복하지 않으려고, 이 이탈을 게이트 2 요약에 그대로 싣는다.
  "src/routes/projects.$projectKey.board.tsx::Function 'BoardPage'": 421,
  "src/routes/settings.account-links.tsx::Function 'AccountLinksSettingsPage'": 257,
}
