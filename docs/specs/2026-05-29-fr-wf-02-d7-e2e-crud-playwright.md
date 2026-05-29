<!-- FR-WF-02 D7 워크플로우 스킴 Playwright E2E — spec (시나리오/FR/엣지/측정 + Brainstorming Check) -->

# FR-WF-02 D7 — 워크플로우 스킴 Playwright E2E — 스펙

> slug. fr-wf-02-d7-e2e-crud-playwright · type. qa · BC. project-workflow · 2026-05-29
> 범위 결정 (사용자 5건 → D6 spec S1~S10 매핑). 5 시나리오 = (1) CRUD = S1+S2+S3, (2) 매핑 편집 = S4+S5+S6, (3) 표준 보호 = S7, (4) 사용 중 삭제 차단 모달 = S8, (5) 프로젝트 할당 = S9+S10.
> 모드. **MSW-based** (playwright.config = `pnpm dev` 위, scheme-handlers 9개 + scheme-fixtures 사전 준비). 백엔드 기동 무관.
> 선행. PR #31 ([ui] FR-WF-02 D6) 머지 — UI 컴포넌트 + MSW handlers/fixtures 사용 가능.

## 사용자 시나리오 (Given-When-Then)

D6 spec S1~S10 을 E2E 행위 검증 단위로 5 시나리오 (`spec` 파일 단위) 로 묶음.

### E2E-1. 스킴 CRUD happy path (D6 S1+S2+S3 매핑, + PUT)

- **Given.** alice 로그인 + admin 화면 진입.
- **When 1 (S1 목록).** `/admin/workflow-schemes` 접근 → 좌 사이드바에 표준 4 + 커스텀 2 = 6 스킴 트리 표시 (scheme-fixtures 기준). 각 항목의 매핑 수 + 사용 프로젝트 수 카운트 노출.
- **Then 1.** `nav[aria-label="워크플로우 스킴 목록"]` 가 visible. "표준" 그룹 4개 / "커스텀" 그룹 2개 카운트 가시 (`getByText('표준')` + `getByText('커스텀')`).
- **When 2 (S3 생성).** "+ 새 스킴" 버튼 클릭 → `/admin/workflow-schemes/new` 진입 → key/name/description 입력 후 저장.
- **Then 2.** 201 응답 → 새 스킴 상세 `/admin/workflow-schemes/{key}` 로 navigate. 좌 사이드바 트리 카운트 +1 (5 → 6, "커스텀 (3)" 표시).
- **When 3 (S2 상세 진입).** 다른 기존 스킴 좌 네비 클릭 → 그 스킴 상세 진입.
- **Then 3.** 중앙 패널에 매핑 테이블 + 우 메타패널에 표준 여부/사용 프로젝트/생성·수정/액션 표시. 라우트 `/admin/workflow-schemes/{schemeKey}` 일치.
- **When 4 (PUT 수정).** 메타패널 또는 인라인 편집으로 name 변경 후 저장.
- **Then 4.** 200 응답 → 메타패널 및 좌 네비 트리에 새 name 반영.

### E2E-2. 매핑 편집 (D6 S4+S5+S6 매핑)

- **Given.** alice 로그인 + 커스텀 스킴 상세 진입.
- **When 1 (S4 매핑 추가, 낙관적).** "+ 매핑 추가" 버튼 → 인라인 행 append → 이슈 타입 select + 워크플로우 select + "추가" 클릭.
- **Then 1.** POST 응답 전 UI 즉시 반영 (낙관적). 200 응답 후 매핑 list 최종 갱신. 토스트/role=status 정상 동작.
- **When 2 (S5 매핑 삭제).** 매핑 행의 "삭제" 버튼 → 확인 모달 (`aria-label="매핑 삭제 확인"`) → 확인.
- **Then 2.** 204 응답 → 행 제거. 우 메타패널의 "매핑 수" 카운트 -1.
- **When 3 (S6 default mapping 설정).** "+ 매핑 추가" → 이슈 타입 select 에서 "기본값 (모든 이슈 타입)" 옵션 선택 → 워크플로우 select → 추가.
- **Then 3.** 매핑 list 에 default 행 추가 (별도 highlight). frontend sentinel `__default__` 가 POST body 의 `issueTypeKey: null` 로 변환된 흐름이 MSW 에서 정상 매칭.

### E2E-3. 표준 스킴 보호 (D6 S7 매핑)

- **Given.** alice 로그인 + 표준 스킴 (`isDefault === true` — fixture `software-default` 등) 상세 진입.
- **When 1.** 우 메타패널 영역 확인.
- **Then 1.** "삭제" 액션 버튼 disabled + tooltip "표준 스킴은 삭제 불가" (또는 aria-describedby) 가시. key / "표준 여부" 필드 readonly 마킹 (input disabled / role 검증).
- **When 2.** name / description 인라인 편집 시도 → 저장.
- **Then 2.** 200 응답 (D11 결정 — name/description 편집 자유). 변경 정상 반영.

### E2E-4. 사용 중 스킴 삭제 차단 모달 (D6 S8 매핑)

- **Given.** alice 로그인 + 사용 중 커스텀 스킴 (fixture 기준 `usedByProjects` 1+) 상세 진입.
- **When.** 메타패널 "삭제" 액션 → 확인 모달 → 최종 삭제 클릭 → 백엔드 409 SCHEME_IN_USE 응답.
- **Then.** SchemeInUseModal 노출 (modal role=dialog). `usedByProjects` list 표시. 각 프로젝트 link 클릭 → 그 프로젝트의 스킴 할당 화면 `/projects/{key}/settings/workflow-scheme` 로 navigate (변경 후 재시도 동선).
- **참고.** 체크포인트 C4 — SchemeInUseModal usedByProjects link 가 백엔드/프론트 모두 빈 list 반환 가능. MSW fixture 가 비어있으면 link 클릭 시나리오는 스킵 또는 fixture 보강 필요.

### E2E-5. 프로젝트 스킴 할당 (D6 S9+S10 매핑)

- **Given.** alice 로그인.
- **When 1 (S9 첫 할당).** 프로젝트 (예. ATLAS — fixture 기준) 의 `/projects/ATLAS/settings/workflow-scheme` 접근. 현재 적용 스킴 표시 (또는 S10 의 미할당 안내 카드) → 변경 select → "스킴 지정" 또는 "스킴 변경" CTA 클릭.
- **Then 1.** PUT UPSERT 응답 → 현재 적용 스킴이 새 스킴으로 갱신 표시.
- **When 2 (S10 자동 할당 안내).** 미할당 프로젝트 (fixture 기준 또는 별도 fixture seed) 의 같은 라우트 진입.
- **Then 2.** GET 404 → "현재 적용된 스킴 없음. 첫 이슈 전이 시 software-scheme 자동 할당" 안내 카드 표시 + 변경 select 정상 사용 가능.

## 기능 요구사항 (FR)

- **FR1. E2E spec 파일 5개 신규.** `e2e/workflow-scheme-crud.spec.ts`, `e2e/workflow-scheme-mappings.spec.ts`, `e2e/workflow-scheme-standard-protect.spec.ts`, `e2e/workflow-scheme-in-use-modal.spec.ts`, `e2e/workflow-scheme-assignment.spec.ts`. 각 파일 1~4 test() 블록.
- **FR2. 공통 fixture 1개.** `e2e/fixtures/workflow-scheme-fixtures.ts` — `loginAsAdmin(page)` (alice 로 충분, dev seed) + `navigateToSchemeList(page)` + `navigateToSchemeDetail(page, schemeKey)` + `navigateToProjectAssignment(page, projectKey)` + (선택) `i18nLabels` 재노출 — 셀렉터 정본 참조 패턴 (PR #22 §F4 학습 정신).
- **FR3. 셀렉터 정본화 정책 (G-BLOCKER-1 게이트 1 결정 후 확정).** 옵션 (A) D7 작업에서 i18n 추출 동반 + E2E 가 정본 참조 / 옵션 (B) hardcoded literal + 후속 i18n migration PR / 옵션 (C) "셀렉터 영역만 const export" 부분 추출. Maxi 결정.
- **FR4. MSW handler 정합 검증.** scheme-handlers 가 spec 4.1~4.4 endpoint 9건 모두 cover. 누락 발견 시 본 PR scope 외 chore PR 위임 또는 본 PR inline 보강 (Maxi 결정).
- **FR5. 비-회귀 cross check.** D7 추가 후 `pnpm test:e2e` 전체 (기존 + 신규) green. 기존 E2E (login-*, workflow.spec.ts, issue-*, smoke.spec.ts) 영향 0 확인.

## 비기능 요구사항 (NFR)

- **시간 임계.** 각 시나리오 < 30s. 전체 D7 5 spec 추가 후 `pnpm test:e2e` 전체 duration 증가 < 2분 (현재 baseline 측정 후 비교).
- **격리.** 각 test 가 독립 실행 가능. MSW handler stateful (예. create 후 list 재조회) 의존 시 `beforeEach` 또는 fixture reset 명시.
- **접근성.** 셀렉터 우선순위 — `getByRole` > `getByLabel` > `getByText` > class. role=dialog / aria-label / aria-describedby 활용. WCAG AA 키보드 탐색 검증은 본 PR scope 외 (D7 후속 후보).
- **로깅.** test 본문 `console.*` 금지 (eslint no-console 준수). `--debug` flag 의존 0.
- **MSW dev mock 의존성.** Playwright webServer 가 `pnpm dev` 호출 → MSW worker 활성화 검증 (`apps/web/src/main.tsx` 의 `import.meta.env.DEV` 분기). MSW worker 부재 시 모든 test fail (사전 검증 1단계 권장).

## API 의존성 (정본 — D6 spec 4.1~4.4, MSW 매칭)

| 메서드 | 경로 | scheme-handlers 매칭 | E2E 시나리오 사용 |
|---|---|---|---|
| GET | `/api/v1/workflow-schemes` | ✅ | E2E-1 (S1 목록) |
| GET | `/api/v1/workflow-schemes/:schemeKey` | ✅ | E2E-1 (S2 상세), E2E-3 (S7) |
| POST | `/api/v1/workflow-schemes` | ✅ | E2E-1 (S3 생성) |
| PUT | `/api/v1/workflow-schemes/:schemeKey` | ✅ | E2E-1 (S4 PUT), E2E-3 (S7 name 편집) |
| DELETE | `/api/v1/workflow-schemes/:schemeKey` | ✅ | E2E-3 (S7 차단), E2E-4 (S8 모달) |
| POST | `/api/v1/workflow-schemes/:schemeKey/mappings` | ✅ | E2E-2 (S4, S6) |
| DELETE | `/api/v1/workflow-schemes/:schemeKey/mappings/:mappingId` | ✅ | E2E-2 (S5) |
| GET | `/api/v1/projects/:projectKey/workflow-scheme` | ✅ | E2E-5 (S9, S10) |
| PUT | `/api/v1/projects/:projectKey/workflow-scheme` | ✅ | E2E-5 (S9) |
| GET | `/api/v1/issue-types` | ❓ scheme-handlers 외 위치 확인 필요 | E2E-2 (S4 매핑 추가 시 이슈 타입 select) |

→ FR4 — `/issue-types` handler 존재 확인 (mocks/handlers.ts 또는 별 파일). 누락 시 본 PR inline 보강 또는 chore PR.

## 데이터 모델 변경

**없음.** E2E test 추가만. MSW fixture 보강 (필요 시) 만 가능.

## 엣지 케이스

1. **MSW worker 미활성.** Playwright webServer 가 `pnpm dev` 기동했지만 MSW 가 active 안 됐을 때 → 모든 test fail. 첫 test 의 `beforeAll` 에서 MSW 응답 확인 1단계 권장 (예. `GET /api/v1/workflow-schemes` 응답 200 확인).
2. **fixture 데이터 변경.** scheme-fixtures 의 표준/커스텀 카운트 가 시나리오의 기대값과 불일치 가능 (예. fixture 가 표준 4 + 커스텀 2 → 시나리오 기대값 동일 보장). 미스매치 시 fixture 갱신.
3. **i18n 추출 후 셀렉터 drift.** FR3 옵션 (A) 채택 시 i18n 추출이 components 의 hardcoded literal 을 import 로 대체 — E2E 가 i18n 정본 참조 시 자동 sync. 옵션 (B) 채택 시 i18n migration PR 후 E2E 셀렉터도 같이 마이그레이션 필요 (후속 PR 부담).
4. **flaky timing.** 낙관적 업데이트의 toast 가 즉시 fade 가능 — `waitForResponse` 또는 `expect(page.locator).toBeVisible({ timeout: 5000 })` 명시.
5. **dev seed 사용자 alice.** PR #11 의 loginAsAlice 패턴 그대로 사용. 권한 (admin) 분기 미적용 (FR-PM-04 후속).
6. **404 unknown schemeKey.** D7 cover 안 함 (D6 §EC 가 unit test 위임). 본 PR scope 외.
7. **403 SCHEME_STANDARD_FIELD_LOCKED.** UI 가 read-only 라 race 시 발생 — D7 cover 안 함 (단위 테스트 위임).
8. **409 MAPPING_DUPLICATE / MAPPING_DEFAULT_DUPLICATE 토스트.** E2E-2 의 happy path 만 cover. 409 분기는 본 PR scope 외 (D6 단위 테스트 위임).
9. **모바일 viewport.** D7 cover 안 함 (D6 FR12 의 모바일 대응 자체가 후속).
10. **WCAG 키보드 탐색.** D7 cover 안 함 (NFR 위임, D7 후속 후보).

## 제약 조건

- **BC 격리.** project-workflow frontend 영역 + e2e 디렉토리. 다른 BC 코드 변경 0. backend 변경 0.
- **i18n 추출 정책 (FR3 옵션 결정 의존).**
  - 옵션 (A). i18n/ko.ts 에 `workflowSchemeStrings`, `workflowSchemeSidebarStrings`, `workflowSchemeMappingsStrings`, `projectAssignmentStrings` 신규 export + 4 routes + 3+ components 가 import 로 대체. 본 PR scope 확장 (task 3~5 추가).
  - 옵션 (B). E2E 가 hardcoded korean string literal 직접 사용. 후속 i18n migration PR 부담. PR #22 §F4 학습 정신과 어긋남.
  - 옵션 (C). E2E 셀렉터 영역만 const export (예. `WORKFLOW_SCHEME_LABELS = { sidebarNav: '워크플로우 스킴 목록', ... }` 신규 file). 추출 범위 최소. i18n 통합은 후속.
- **셀렉터 우선순위.** `getByRole` > `getByLabel` > `getByText` > class. PR #32 패턴 일관.
- **fixture 분리.** D6 와 E2E 의 fixture 가 일치하도록 `apps/web/src/mocks/scheme-fixtures.ts` 정본 참조 (E2E 가 별도 fixture 만들지 말 것).
- **MSW handler 통합 (PR #26 C1 learning).** `mocks/handlers.ts` 에 `schemeHandlers` spread 가 이미 적용됨 (PR #31). 본 PR 영역 0.
- **TDD red→green→refactor 강제** (BTS 핵심 패턴). 각 E2E spec 파일 작성 시 (a) 먼저 fail 하는 test 작성 (`test:` commit) → (b) 가장 작은 변경으로 통과 (`feat:` 또는 `qa:` commit). 본 task 의 경우 UI 가 이미 존재 → "RED 자연 발생" 가설 (test 작성하면 일부 통과, 일부 fail). plan §Task 본문에 "TDD 변형 — UI 사전 존재" 명시 (learnings 2026-05-28 Flyway recursive 사례 패턴).

## 측정 가능한 완료 기준

- [ ] `pnpm test:e2e` 신규 5 spec + 기존 10 spec 모두 green
- [ ] `pnpm verify` (lint + typecheck + test + build) green — 기존 단위/통합 회귀 0
- [ ] D7 5 시나리오 (E2E-1 ~ E2E-5) 본 spec §사용자 시나리오 의 모든 Given/When/Then assert 매칭
- [ ] FR3 옵션 결정 후 셀렉터 정본화 일관 적용 (옵션 (A) → i18n 정본 참조, (B) → hardcoded 일관, (C) → const file 일관)
- [ ] `apps/web/playwright.config.ts` 변경 0 (기존 `pnpm dev` webServer 그대로)
- [ ] `e2e/fixtures/workflow-scheme-fixtures.ts` 신규 1 파일
- [ ] FR-WF-02 §2.2 D7 `[ ] → [x]` 마킹 (`docs/plan/product/project-workflow.md`)
- [ ] §BC 완료 게이트 (`§2 (FR-WF 2개) 모두 [x]`) 가 D7 완료 후 활성 — 본 PR 머지 후 검증

## Brainstorming Check

controller inline brainstorming (D6 spec line 156 패턴 따름 — brainstorming 풀 호출 생략, 발견된 gap 만 보고).

### 🚨 BLOCKER (Maxi 게이트 1 결정 필요)

- **G-BLOCKER-1. 셀렉터 정본화 정책 결정 필요 (FR3).** workflow-scheme UI 컴포넌트 4 routes + 3+ components 의 한국어 strings 모두 hardcoded. PR #22 §F4 학습은 E2E 가 i18n 정본 참조 권장 — 이를 D7 에서 적용하려면 i18n 추출이 선행돼야. 옵션 3안 위 §FR3 + §제약 조건 명시. 본 spec 작성 시 옵션 (B) (hardcoded) 가정으로 시나리오 본문 작성 — Maxi 결정에 따라 plan 단계에서 추출/마이그레이션 task 추가 또는 그대로 hardcoded 진행.

### 그 외 gap (보강 사항, BLOCKER 아님)

- **G1 (확인 필요).** `/api/v1/issue-types` MSW handler 위치. scheme-handlers 외 별 파일 추정. 누락 시 본 PR inline 보강 task 추가. plan §Task 단계에서 확인.
- **G2 (보강).** E2E-1 의 "표준 4 + 커스텀 2 = 6 스킴" 가정이 scheme-fixtures 의 현재 상태와 일치 확인. fixture 변경 시 시나리오 카운트 갱신 의존.
- **G3 (보강).** SchemeInUseModal 의 `usedByProjects` link 클릭 후 navigate (E2E-4) 가 backend/frontend 모두 빈 list 반환 가능 (체크포인트 C4). MSW fixture 의 `usedByProjects` 가 비어있으면 link 클릭 시나리오는 스킵. fixture 검증 task 추가 후보.
- **G4 (보강).** TDD red→green 패턴 변형. UI 가 이미 존재하므로 "RED 자연 발생" — test 가 일부 통과 (S1 목록 등) + 일부 fail (S6 sentinel 매칭 등) 가능. learnings 2026-05-28 (Flyway recursive 사례) 의 "명시성 강화 변경 패턴" 정신과 동등 — plan §Task 본문에 "TDD 변형 사유 — UI 사전 존재로 일부 시나리오 RED 자연 발생 안 함, 본질은 행위 검증 신규" 명시 권장.
- **G5 (확인 필요).** PR #11 의 loginAsAlice 가 admin 권한 가정 또는 가드 0 인지 확인. AlwaysAllow stub (`@Profile("!prod")`) 단계라 권한 분기 없음 추정 — D6 spec §FR11 일관.
- **G6 (보강).** `pnpm test:e2e` 전체 duration baseline 측정 (PR #32 머지 후 현재). D7 5 spec 추가 후 < +2분 증가 확인.

### ✅ Maxi 결정 (2026-05-29)

**옵션 (C) 채택 — 셀렉터 영역만 const file 추출**.

- 신규 file. `apps/web/src/i18n/workflow-scheme-labels.ts` — E2E 셀렉터가 의존하는 라벨/텍스트만 const export. 예. `sidebarNav: '워크플로우 스킴 목록'`, `addSchemeButton: '+ 새 스킴'`, `addMappingButton: '+ 매핑 추가'`, `inUseModalLabel: 'SchemeInUseModal 모달'` 등.
- 기존 components 의 hardcoded literal 중 E2E 가 셀렉터로 참조하는 라벨만 const import 로 대체 (최소 영역). 그 외 텍스트 (placeholder, 에러 메시지 등) 는 hardcoded 그대로 둠 — 전면 i18n migration 은 별 후속 PR scope.
- E2E 가 import 후 정본 참조 — 라벨 변경 시 자동 sync. PR #22 §F4 정신 부분 적용 (selector 영역 한정).

### Final Status

✅ 통과 (1 iteration, BLOCKER 1건 옵션 (C) 채택, gap 6건 plan 단계 흡수). 게이트 1 에서 plan 일괄 검토.
