# FR-UX-06 Phase 5 PR19 — 이슈 상세 탭화 + IssueMetaPanel 분해

> slug: fr-ux-06-pr19-issue-detail-tabs
> type: ui (classify가 backend로 오분류 → 실측 정정, FR-UX-06 UI 시리즈 선례 PR9/PR10/PR12 동일 함정)
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트)
> 생성: 2026-07-24

## Brief

FR-UX-06(BTS UI/UX를 Jira Cloud 2025 방식으로 전면 개편) Phase 5(화면) 세 번째 PR.
이슈 상세 화면을 Radix Tabs로 탭화하고, 거대해진 `IssueMetaPanel`(~1204줄)을 분해한다.
split view(목록+상세 2분할)도 이 PR과 함께 검토 (PR18에서 의도적으로 이연).

### 착수 전 필독 (메모리)
- `frontend-nav-aria-label-e2e-contract` — **규칙: 라우트 변경=nav+Link, 같은 라우트 패널 전환=Radix Tabs.**
  이슈 상세 활동 탭은 탭 0개·라우팅 아님 → **여기선 Tabs가 정답**.
- `playwright-getbyrole-exact-strict-mode` — 단일단어 라벨(저장/삭제/취소/확인/추가) substring 매칭 위험 → `exact:true`.
- `e2e-playwright-filter-arg-drop` — 특정 spec만 돌리려면 `apps/web/node_modules/.bin/playwright` 직접 호출. CI에 e2e 잡 없음 → 로컬 e2e 필수.

## 도메인 정리

- **BC**: issue-tracking (프론트 뷰). 순수 UI 재구조화, 백엔드/도메인 모델 영향 0.
- **새 용어**: 없음. 유비쿼터스 언어 변경 없음(탭/패널은 UI 표현일 뿐 도메인 개념 아님).
- **기존 결정 충돌**: 없음. 오히려 **방향이 기존 결정으로 이미 확정됨** — right-size 도메인 단계(신규 grill-with-docs 불필요, PR18 선례 "domain 용어/ADR 0"과 동일).
- **지배 결정 (이 작업을 지시하는 정본)**:
  - ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` **§D4** — "라우트 변경=nav+Link / 같은 라우트 패널 전환=Radix Tabs. **이슈 상세 활동 탭은 Radix Tabs가 정답**(탭 0개라 깨질 어서션 없음·라우팅 아님)."
  - 디자인 스펙 `docs/design/fr-ux-06-jira-redesign.md` **§3.3 이슈 상세 2컬럼** — `grid-template-columns: minmax(0,1fr) 340px`(현재 280px→340px). 활동 영역(댓글/히스토리/작업로그/연결)을 **탭으로 접는다**(현재는 2단 그리드 바깥 세로 무한 적층). §282-283 반응형: ≥1024px 2컬럼 / <1024px 1컬럼(메타패널 본문 아래). row246 `tabs` 프리미티브=이슈 상세 활동(PR19). §302 이슈 상세 활동=Radix Tabs. line140 `IssueMetaPanel` 분해 −500 LOC 예상.
- **관련 ADR**: 신규 생성 없음(§D4가 이미 커버). 관련 = D4 + 디자인 스펙 §3.3.
- **⚠️ 스펙 단계 확인 필요(도메인 아님)**: 디자인 스펙이 활동 탭에 "댓글"을 열거하나, 메모리 `comment-backend-is-import-byproduct-read-only`상 CommentController는 GET만(쓰기 REST 미노출). 현재 이슈 상세에 댓글 UI가 실재하는지 실측 후 탭 구성 확정 → /bts-spec에서 판정.

## 스펙

전체 스펙. [docs/specs/2026-07-24-fr-ux-06-pr19-issue-detail-tabs.md](../specs/2026-07-24-fr-ux-06-pr19-issue-detail-tabs.md)

**범위 (Maxi 게이트 2026-07-24)**
- IN: ①활동 3탭(작업로그[기본]/연결/이력) Radix Tabs화 ②그리드 우측 280→340px ③`IssueMetaPanel`(1204줄) 서브패널 분해(DOM/testid 보존, −500 LOC급).
- OUT 이연: split view → 후속 PR20 · 댓글 탭 → 댓글 FR 도입 시(디자인 스펙 §3.3 deviation 주석 갱신).

핵심 시나리오.
- 이슈 상세 활동 영역이 세로 무한 적층 → 3탭으로 접힘, 기본=작업로그.
- 탭 전환은 같은 라우트 패널 전환(ADR §D4) → URL 미변경·로컬 상태.
- `IssueMetaPanel`은 우측 컬럼 유지, 내부만 서브패널 파일로 분해(순수 구조 리팩터).

## Brainstorming Check

✅ 통과 (자체 sanity check, blocking gap 0). impl-plan 확인 3건:
- G1 `ui/tabs` 첫 소비자(계약 확정) · G2 IssueMetaPanel 3 테스트파일 green 보존 + 서브패널 신규테스트 · G3 Radix Tabs 지연마운트(연결/이력 탭 활성 시 fetch, e2e 탭클릭 선행).

## Plan (← /bts-plan 채움)

## Plan

> 파일 겹침 규칙: Task 2·3은 같은 `IssueMetaPanel.tsx`를 편집 → 직렬(2 depends-on 없음이어도 자동 직렬, 명시). Task 1은 route+신규파일(분해와 파일 무겹침) → 병렬. Task 4(e2e)는 Task 1 탭 구현 후.

### Task 1. 활동 3탭 Radix Tabs화 + 그리드 340px

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueActivityTabs.tsx`, `apps/web/src/components/issue/__tests__/IssueActivityTabs.test.tsx`, `apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/__tests__/issues.$key.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: []

**RED**: `IssueActivityTabs.test.tsx` — (a) `role="tablist"` + 탭 3개(작업로그/연결/이력) 렌더, (b) 기본 활성=작업로그(`WorklogSection` 콘텐츠 보임), (c) 연결 탭 클릭 시 `IssueLinksPanel`·`LinkGraph` 보이고 작업로그 숨음, (d) 이력 탭 클릭 시 `IssueChangelog` 보임, (e) 에픽 타입일 때만 연결 탭에 `EpicChildrenSection`. 실패(컴포넌트 없음).
**GREEN**: `IssueActivityTabs` 신설 — `ui/tabs` **uncontrolled `defaultValue`**(E1 리뷰: URL 동기화 불요 → `useState` 불필요, 최소 구현) 소비. 기본 탭 값=**이력**(`defaultValue="history"`, D1 Maxi 게이트 결정 2026-07-24 — 변경 이력은 생성 이벤트가 항상 있어 빈 첫인상 회피). RED 테스트 (b) "기본 활성=작업로그"도 **이력**으로 갱신. 3 `TabsContent`(worklog/links/history), 비활성 패널 기본 언마운트(G3 lazy). props로 `issueKey·canUpdate·issue`(에픽/부모/epic·showEpicSection 판정) 전달. route: 그리드 아래 5개 적층 섹션(`WorklogSection`·`IssueLinksPanel`·`EpicChildrenSection`·`LinkGraph`·`IssueChangelog`)을 `<IssueActivityTabs.../>` 1개로 대체. 그리드 `lg:grid-cols-[1fr_280px]`→`[1fr_340px]`. i18n 탭 라벨 3종. **E2: `issues.$key.test.tsx`의 LinkGraph/Changelog eager 렌더 기대 어서션을 탭 활성 후 검증으로 갱신(lazy-mount 정합).**
**REFACTOR**: 탭 value 상수화(`ACTIVITY_TABS`), props 타입 정리, L1 한글 헤더 주석.
**검증**: `cd apps/web && node_modules/.bin/vitest run IssueActivityTabs issues.\$key`

### Task 2. IssueMetaPanel 분해 A — 필드 셀렉터/에디터 추출

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/meta/IssuePrioritySelect.tsx`, `apps/web/src/components/issue/meta/IssueImpactSelect.tsx`, `apps/web/src/components/issue/meta/IssueTypeSelect.tsx`, `apps/web/src/components/issue/meta/IssueEnvironmentEdit.tsx`, `apps/web/src/components/issue/meta/IssueLabelsEdit.tsx`, `apps/web/src/components/issue/meta/IssueCustomFieldsEdit.tsx`, `apps/web/src/components/issue/meta/__tests__/*.test.tsx`]
- depends-on: []

**RED**: 추출 대상별 신규 단위테스트(meta/__tests__): 각 컴포넌트가 독립 마운트로 현 동작 재현(우선순위 5택 select·영향도 3택·유형 아이콘·환경 편집 저장·라벨 칩 추가/삭제·커스텀필드 저장). 실패(파일 없음).
**GREEN**: `IssueMetaPanel.tsx`의 인라인 정의(`IssuePrioritySelect`449·`IssueImpactSelect`492·`IssueTypeSelect`732·`IssueEnvironmentEdit`542·`IssueLabelsEdit`+`LabelChip`606·`IssueCustomFieldsEdit`+`isRequiredFieldEmpty`1041)를 `meta/` 파일로 이동·`export`. `IssueMetaPanel`은 이 컴포넌트들을 import해 **동일 위치·동일 props·동일 data-testid로 렌더**(조합 diff 0).
**REFACTOR**: 각 파일 L1 한글 주석, 공유 헬퍼(`isFieldHidden`/`isFieldDisabled`) 노출 경로 정리.
**검증**: 기존 `IssueMetaPanel.test.tsx`·`__tests__/IssueMetaPanel.test.tsx`·`.fieldperm.test.tsx` **green 유지**(회귀 가드) + 신규 meta 테스트 green. `node_modules/.bin/vitest run IssueMetaPanel meta/`

### Task 3. IssueMetaPanel 분해 B — 담당자·전이 추출 + 조합 확정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/meta/IssueAssigneeSelect.tsx`, `apps/web/src/components/issue/meta/AssigneeUserList.tsx`, `apps/web/src/components/issue/meta/IssueStateTransition.tsx`, `apps/web/src/components/issue/meta/__tests__/*.test.tsx`]
- depends-on: [2]   # 같은 IssueMetaPanel.tsx 편집 → 직렬

**RED**: `IssueAssigneeSelect`(검색·선택·해제)·`AssigneeUserList`·`IssueStateTransition`(전이 select·disabled·unavailableReason terminal/no-workflow) 신규 단위테스트. 실패(파일 없음).
**GREEN**: 인라인 정의(`IssueAssigneeSelect`784·`AssigneeUserList`880·`IssueStateTransition`920) `meta/` 이동·`export`, `IssueMetaPanel`에서 import. 남은 main 파일=순수 조합 + 헬퍼.
**REFACTOR**: L1 주석. `IssueMetaPanel.tsx` 라인수 유의미 감소 확인(1204 → ~700 목표, −500 LOC급).
**검증**: 기존 3 테스트파일 green 유지 + 신규 green. `node_modules/.bin/vitest run IssueMetaPanel meta/` + `IssueMetaPanel.tsx` wc -l 대조.

### Task 4. E2E 탭화 봉합 (회귀 방지)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/worklog.spec.ts`, `apps/web/e2e/issue-links.spec.ts`, `apps/web/e2e/issue-ui-regression.spec.ts`, `apps/web/e2e/*.spec.ts (탭 뒤 콘텐츠 의존 spec 실측 후)`]
- depends-on: [1]   # 탭 구현 후에만 의미

**RED/식별**: 전수 e2e 실행 → 탭화로 숨은 작업로그/연결/이력 콘텐츠에 의존하던 실패 spec 식별(`e2e-playwright-filter-arg-drop` 바이너리 직접호출·baseline 대조로 PR 회귀 vs 사전존재 구분).
**GREEN**: 실패 spec에 활동 탭 활성 클릭(`getByRole('tab', { name: '연결'|'이력', exact: true }).click()`) 선행 추가. 셀렉터 verbatim 보존. `playwright-getbyrole-exact-strict-mode` 준수(exact).
**검증**: 이슈 상세 관련 e2e 전수 green(로컬, CI e2e 잡 없음 → 필수).

## Plan 메타

- task 수: 4
- 예상 wave: 2 (wave1: T1∥T2 / wave2: T3(←2)∥T4(←1))
- TDD 강제: yes (분해 T2·T3은 "기존 테스트 green 유지 + 신규 서브컴포넌트 테스트"가 RED→GREEN 가드)
- 추가 검증: typecheck·eslint·vitest 전수·playwright(qa) · FR 129 불변 · IssueMetaPanel.tsx wc 대조

## 리뷰 결과

### plan-design-review + eng self-review (2026-07-24, right-size·목업 스킵)

**BLOCKER: 없음.** 방향은 ADR §D4 + 디자인 스펙 §3.3/§302로 잠김.

**디자인**
- ✅ 탭 시각/반응형은 디자인 스펙 §3.3(340px 2컬럼·§282-283 <1024px 1컬럼)·row246·§302로 규정. Radix Tabs a11y 기본.
- ⚠️ **D1 (Maxi 취향 결정 → 게이트 1)**: 기본 활성 탭. **작업로그**는 시간추적 안 한 이슈에서 자주 비어 첫인상이 빈 화면일 수 있음. **이력**(변경 이력)은 최소 "생성" 이벤트가 항상 있어 항상 콘텐츠 존재. Jira 기본탭(댓글)이 없으니 이력이 근접 대체. → 기본탭 후보: 작업로그 vs 이력.

**엔지니어링**
- ✅ 태스크 분해/직렬화 정합: T2·T3 동일 `IssueMetaPanel.tsx` → depends-on [2] 직렬. T1 독립·T4←T1. 2 wave.
- ✅ **E1 반영**: `ui/tabs` 첫 소비자 사용 계약 — controlled+defaultValue 모순 → **uncontrolled `defaultValue`** 확정(URL 동기화 불요, useState 제거).
- ✅ **E2 반영**: G3 lazy-mount로 비활성 탭(연결/이력) 콘텐츠가 초기 미렌더 → `issues.$key.test.tsx`의 eager 렌더 기대 어서션을 탭 활성 후 검증으로 갱신(Task 1 files 포함).
- ✅ **E3 확인**: 분해 TDD 순서 — 추출 서브컴포넌트 신규 테스트를 먼저(RED: 파일 없음)·추출 후 GREEN. 기존 `IssueMetaPanel` 3 테스트파일=회귀 가드(green 유지). bts-impl `test:`→`feat:` 순서 준수.

### plan-eng-review 요약
- ✅ 통과: FR 129 불변(순수 UI·D-step) · BC 격리(issue-tracking 프론트 단일) · 완제품 기준.
- BLOCKER: 없음.
