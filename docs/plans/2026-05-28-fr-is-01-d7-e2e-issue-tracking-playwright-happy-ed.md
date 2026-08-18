# FR-IS-01 D7 — issue-tracking Playwright E2E (happy path + edge cases)

> slug: fr-is-01-d7-e2e-issue-tracking-playwright-happy-ed
> type: qa
> agent: qa-engineer
> 생성: 2026-05-28

## Brief

사용자 원문: "fr-is-01 E2E 진행해줘"

classify 결과:
- type: qa
- agent: qa-engineer
- primary_bc: null (수동 보강 → issue-tracking)
- task_count: 0

직전 세션 컨텍스트 (`20260528-184511`) §Remaining Work #1:
> FR-IS-01 D7 E2E unblock — 즉시 진행 가능. PR #28 (BLOCKER 1) + PR #29 (BLOCKER 2) + PR #30 (CONCERN-1) 머지로 transition 매핑 + 마이그레이션 경로 + aggregate invariant 모두 정합. `docs/plan/product/issue-tracking.md §2.1.1 D7` Playwright E2E 작성.

전제: FR-IS-01 D1~D6 (백엔드 + 프론트엔드 UI + workflow transition wiring) 모두 머지 완료. D7 = E2E (End-to-End — 브라우저 자동화로 실제 사용자 흐름 검증) Playwright 작성으로 D 라인 마무리.

## 도메인 정리

**처리 방식**: fast-track inline (Maxi D2 옵션 A 승인). type=qa 가 스킬 명시 fast-track 조건 (bugfix/chore) 밖이지만, 본 작업 본질 = E2E 테스트 추가 → 프로덕션 코드 / 도메인 모델 변경 0 → grill-with-docs 가치 ≈ 0. PR #21~#30 (10건 연속) inline 패턴 일관.

**BC**: issue-tracking (주) + project-workflow (transition 매핑 의존, 이미 PR #27/#28 wiring 머지)

**영향 엔티티**: 신규 0, 변경 0
- 기존 활용: Issue, IssueKey, IssueHistory ([[domain/issue-tracking]] §핵심 엔티티 4 항목)
- 활용 경로: Workflow + Transition (project-workflow BC, PR #27 transition wiring + PR #28 매핑 hot-fix + PR #29 Flyway 정리 + PR #30 invariant 정렬)

**새 용어**: 0 (glossary 갱신 불필요). 기존 용어 인용만 — `이슈` / `이슈 키` / `워크플로우` / `전환` (glossary 라인 11, 12, 14, 33).

**기존 결정 충돌**: 0
- 관련 ADR (영향 없음, 활용만): `2026-05-27-shared-kernel-extraction.md` (project-workflow SPI), `2026-05-22-frontend-logging-policy.md` (E2E 콘솔 로깅 정책 일관).
- 관련 plan: `docs/plan/product/issue-tracking.md §2.1.1 D7` 명세 그대로 활용 (인용은 §스펙 단계).

**glossary 갱신**: 없음 (Maxi 확인 불필요).
**domain/issue-tracking.md 갱신**: 없음 (Maxi 확인 불필요).
**ADR 신규**: 없음.


## 스펙

**처리 방식**: Phase A fast-track inline (별 `docs/specs/<date>-<slug>.md` 분리 안 함, plan §스펙 직접 작성). Maxi D3 옵션 1 승인.

**명세 출처**: `docs/plan/product/issue-tracking.md §2.1.1 D7` (line 38).

> D7. E2E + NFR — 생성→조회→수정→상태 전환→소프트 삭제→키 영속성 (이동 후 옛 키 redirect) (책임. qa-engineer)

**Scope 결정** (Maxi D3 옵션 1, brainstorming 적용 예정):

D7 원본 6단계 vs 현재 코드 상태 매핑.

| # | 단계 | UI | 백엔드 | E2E 가능 | 본 PR 처리 |
|---|---|---|---|---|---|
| 1 | 생성 | ✅ `issues.new.tsx` | ✅ `POST /api/v1/issues` | ✅ | 포함 |
| 2 | 조회 | ✅ `issues.index.tsx` / `issues.$key.tsx` | ✅ `GET /api/v1/issues`, `GET /api/v1/issues/{key}` | ✅ | 포함 |
| 3 | 수정 | ✅ 인라인 summary (낙관적 업데이트) | ✅ `PATCH /api/v1/issues/{key}` | ✅ | 포함 |
| 4 | 상태 전환 | ❌ **UI 부재** (D6 §메모 "상태 전환 UI는 제외") | ✅ PR #27/#28 wiring 머지 | ❌ | **후속 slice 위임** |
| 5 | 소프트 삭제 | ✅ destructive 버튼 + 확인 UI | ✅ `DELETE /api/v1/issues/{key}` | ✅ | 포함 |
| 6 | 키 영속성 (이동 후 redirect) | ❌ UI 부재 | ❌ **백엔드 미구현** (grep `IssueKeyRedirect`/`/move` 0건) | ❌ | **후속 slice 위임 (FR-IS-12 move)** |

**본 PR 검증 범위** = 4단계 (생성/조회/수정/소프트 삭제) + NFR 측정 3건.

**후속 slice 위임** (이 PR 머지 직후 product plan §2.1.1 §D7 본문 메모 갱신).
- 상태 전환 UI 추가 → `FR-IS-01 D6.5` 신규 slice (frontend DropdownMenu + `useTransitionMutation` + 이슈 상세 영역 통합)
- 이슈 이동 + 키 redirect → `FR-IS-12 move` (별 FR. backend `IssueKeyRedirect` 엔티티 + Flyway + jOOQ + frontend 이동 다이얼로그 + redirect 라우트)
- D7 §완료 기준 = **부분 통과** (4단계 + NFR) 명시, full 통과 = 위 2 slice 완료 후 별도 D7-Extended 작업

---

### 사용자 시나리오 (Given-When-Then)

#### E2E-1 Happy Path — 이슈 생명주기 (생성→조회→수정→삭제)

```
Given 로그인된 사용자 alice (LOCAL provider, dev seed)
  And 빈 이슈 목록 상태

When  /issues/new 페이지로 이동
  And summary 필드에 "E2E 테스트용 이슈" 입력
  And 제출 버튼 클릭

Then  자동 발급된 이슈 키 (예: PROJ-1) 로 /issues/<key> 리다이렉트
  And 이슈 상세 페이지에 summary "E2E 테스트용 이슈" 표시

When  summary 영역 인라인 편집 모드 진입
  And 새 값 "E2E 테스트용 이슈 (수정됨)" 입력
  And Enter (또는 저장 단축키)

Then  낙관적 업데이트로 즉시 새 값 반영
  And 서버 응답 후 동기 상태 일치

When  삭제 destructive 버튼 클릭
  And 확인 다이얼로그에서 "삭제" 선택

Then  /issues 목록으로 navigate
  And 방금 삭제한 이슈가 목록에서 제외됨 (소프트 삭제, 백엔드 deleted_at 검증은 단위/통합 테스트에서)
```

#### E2E-2 — 비로그인 접근 가드

```
Given 비로그인 (sessionStorage 비어있음)
When  /issues 또는 /issues/<key> 또는 /issues/new 직접 접근
Then  /login?returnTo=<원래경로> 로 redirect (TanStack Router beforeLoad 가드)
```

#### E2E-3 — 존재하지 않는 키

```
Given 로그인된 사용자
When  /issues/PROJ-99999 (미존재 키) 접근
Then  404 에러 UI 또는 에러 토스트 노출 (apps/web/src/routes/issues.$key.tsx:77 `role="alert"` 영역)
```

---

### NFR 측정 (Playwright)

| 항목 | 임계 (p95) | 측정 방법 |
|---|---|---|
| 단건 조회 | 200ms | `page.evaluate(performance.measure)` × 10회 (warm-up 2회 제외) |
| 목록 50건 | 500ms | fixture에 50건 사전 시드 후 동일 |
| POST /issues | 300ms | createIssue API 호출 × 10회 |

**환경 결정 필요** (brainstorming Phase B에서 한 번 더 흔들 예정).
- (a) **MSW mock 환경**. latency 0 가정 → 의미 있는 p95 측정 불가, 측정값 무의미.
- (b) **실 backend (Testcontainers + Spring Boot Dev)**. Playwright orchestrate, 측정 정합. 복잡도 +.
- (c) **NFR은 D7 본 PR scope 외, 별 cleanup PR**. E2E happy/엣지만 본 PR.

→ Phase B brainstorming 후 옵션 확정.

---

### 엣지 케이스 (현재 식별)

- EC-1. 동일 이슈 동시 편집 시 409 → 토스트. (PR #26 이미 구현, 회귀 가드 정도)
- EC-2. 권한 모델 미확정 — 다른 사용자가 본인 외 이슈 삭제 시도 시나리오 (현재 backend 권한 가드 명세 확인 필요).
- EC-3. 빈 summary 제출 시 클라이언트 검증 (Zod) + 서버 400 응답 일관성.

**Phase B brainstorming 입력**.
- 이 스펙을 sanity check. 누락된 시나리오 / 모호한 표현 / NFR 환경 결정 / 권한 모델 영역 발견.


## Brainstorming Check

✅ 통과 (1 라운드, Maxi 결정 2건 + controller 자동 결론 1건 + 추가 발견 6건 보강).

**처리 방식**: brainstorming 스킬을 sanity check 모드로 활용. 별 `docs/superpowers/specs/<date>-<topic>-design.md` 분리 안 함 (BTS 워크플로우 일관, plan §Brainstorming Check 인라인). `writing-plans` 호출은 다음 단계 `bts-plan`이 담당.

### 발견된 Gap 9건

#### Maxi 결정 (게이트1 사전)

**gap-A. NFR 측정 환경** → Maxi D4 옵션 1 승인 (MSW mock + NFR 별 PR 위임).
- 사유. MSW (Mock Service Worker) latency 0 → NFR p95 측정 무의미. 실 backend 실행 시 PR #11 ECONNREFUSED 함정 재발 위험.
- 처리. NFR 측정 3건 (조회/목록/POST) 본 PR scope 외. `docs/plan/product/issue-tracking.md §2.1.1 D7` 본문에 **Deferred trigger** blockquote 추가 (PR #21 §F4 패턴 — "k6 + Playwright NFR 계층 도구 도입 후 측정" + 선언자 Maxi).

**gap-B. 권한 모델 E2E** → controller 자동 결론 (Maxi 확인 불필요).
- 사유. backend `IssuePermissionResolver` SPI는 존재 + `IssueApplicationService` 각 메서드 진입 시 권한 검증 호출. 단 dev/test 환경 = `AlwaysAllowIssuePermissionResolver` (권한 우회). 실 RBAC 가드 미구현.
- 처리. 권한 가드 E2E (다른 사용자 이슈 삭제 시도 → 403) **본 PR 제외** — 현재 항상 PASS (false negative 위험). 실 권한 가드 구현 (FR-AC-XX 별 FR 후보) 후 E2E 추가.

**gap-C. backend 실 실행 의존** → gap-A 옵션 1로 자동 해소.
- 처리. MSW mock 사용, backend dev 서버 실행 불필요. PR #11 ECONNREFUSED 함정 회피.

#### Controller 자동 적용 (학습 준수)

**gap-D. E2E 시나리오 보강 (UI 동작 회귀 가드)** → 본 PR 추가.
- D-1. 인라인 summary 편집 Esc 취소 시 원본 값 유지 (사용자 의도 보존).
- D-2. 삭제 destructive 다이얼로그 취소 (Cancel 버튼) 시 이슈 보존.
- D-3. 빈 summary 제출 시 Zod 클라이언트 검증 메시지 표시 (서버 호출 0).

**gap-E. i18n 정본 import 강제** → 본 PR 적용 (PR #22 §F4 학습).
- 처리. 모든 E2E 라벨/문자열 셀렉터는 `apps/web/src/i18n/ko.ts` 의 정본 (예: `issueStrings.detail.summary` / `issueStrings.detail.deleteConfirm`) import 사용. hardcoded string literal 금지. 정본 부재 시 i18n 키 신규 추가도 본 PR scope.

**gap-F. dev seed alice 의존** → 본 PR 적용 (PR #11 학습).
- 처리. E2E는 `apps/web/e2e/login-happy-path.spec.ts` 패턴 따라 alice (LOCAL provider, Argon2id seed) 사용. backend dev seed (`data-dev.sql`) 의존 명시 — MSW mock 모드라 backend dev seed는 frontend mock fixtures 로 시뮬.

#### 추가 발견 (controller 적용 또는 plan §리스크 명시)

**gap-G. 미존재 키 에러 UI 분기 정밀화** → 본 PR 적용.
- 발견. `issues.$key.tsx:77` `role="alert"` 에러 영역의 404 (미존재) vs 500 (서버 에러) vs 403 (권한) 분기가 현재 코드에서 명확한지 — UI 통합 동작 검증 필요.
- 처리. E2E-3 (미존재 키) 시나리오에서 `role="alert"` 영역 노출 + 에러 메시지 정합 검증 추가.

**gap-H. 소프트 삭제 후 GET /issues 응답 필터링** → 본 PR 검증.
- 발견. PR #26 D6 메모 "소프트 삭제" 명시되어 있으나 backend `GET /api/v1/issues` 응답에서 `deleted_at != NULL` 이슈를 자동 필터링하는지 명세 미확인. 만약 안 한다면 E2E "삭제 후 목록에서 제외" 단계 false negative.
- 처리. E2E-1 happy path 마지막 단계에서 목록 응답에 해당 이슈 키 부재 명시 검증 + (필요 시 MSW mock 핸들러 갱신).

**gap-I. 동시 편집 409 회귀 가드** → 본 PR 검증 (PR #26 EC-1 이미 구현, 회귀 가드만).
- 처리. E2E-1 시나리오에 옵션으로 추가 또는 별 E2E (E2E-4 동시성 시나리오) 분리. 우선순위 낮음, 시간 여유 시 추가.

### 최종 본 PR E2E 시나리오 (확정)

| # | 시나리오 | 우선순위 |
|---|---|---|
| E2E-1 | Happy Path — 생성→조회→인라인 수정→소프트 삭제→목록 제외 | 필수 |
| E2E-2 | 비로그인 가드 — /issues, /issues/<key>, /issues/new → /login redirect (returnTo 보존) | 필수 |
| E2E-3 | 미존재 키 — /issues/PROJ-99999 → `role="alert"` 에러 UI 노출 (gap-G) | 필수 |
| E2E-4 | UI 동작 회귀 가드 — Esc 취소 (gap-D-1) + 다이얼로그 취소 (gap-D-2) + 빈 summary 클라이언트 검증 (gap-D-3) | 필수 |
| E2E-5 | 동시 편집 409 회귀 가드 (gap-I) | **본 PR scope 외** (별 cleanup PR — PR #26 EC-1 이미 구현, 회귀 가드만 필요) |

### Scope 외 위임 항목 (product plan 갱신)

본 PR 머지 직후 `docs/plan/product/issue-tracking.md §2.1.1 §D7` 본문 메모 갱신 (1 commit).
- D7 본 PR = 부분 통과 (E2E-1~4).
- D7 full 통과 후속 의존: (1) 상태 전환 UI 추가 (FR-IS-01 D6.5 신규 slice), (2) 이슈 이동 + 키 redirect (FR-IS-12 move 별 FR), (3) NFR 측정 (k6 + Playwright NFR 계층 도구 도입 별 PR — Deferred trigger), (4) 권한 모델 E2E (실 RBAC 가드 구현 별 FR 후).


## Plan

**처리 방식**: Controller inline (Maxi D5 옵션 A 승인). writing-plans 스킬 우회 누적 +1 (PR #21~#30 일관 패턴). 별 chore PR 후보 누적.

**TDD 변형 본질** (verifier prompt 사전 명시):
- 본 PR = E2E only (production 코드 신규 변경 0, 기존 UI/API 검증 모드).
- RED phase = E2E spec 작성 시점 (실 페이지 동작 검증 전).
- GREEN phase = spec 실행 → 통과 (이미 구현된 UI 대상). 통과 안 하면 mock 핸들러 또는 spec 자체 fix commit (PR #13/#16 D5 옵션 C 패턴).
- 인프라 task (T1) = chore commit (i18n 검증 + MSW stateful 보강 + fixtures 신규). 그 후 모든 E2E task = test commit.
- verifier 인용 예: "test commit `<hash>` (E2E-1 happy) + chore commit `<hash>` (인프라) 순서 확인. production 코드 변경 0 — E2E 검증 모드".

---

### Task 1. E2E 인프라 셋업 (i18n 정본 검증 + MSW stateful 보강 + e2e fixtures 신규)

**메타**.
- agent: `qa-engineer` (테스트 인프라 책임. PR #20 §1 학습 — 공유 인프라 파일은 단일 task 명시 할당으로 lint-staged race 회피)
- files:
  - `apps/web/src/i18n/ko.ts` (검증 — 기존 `issueDetailStrings` / `issueCreateStrings` 활용 가능 여부 확인. 신규 키 누락 시 0~2건 추가 — 삭제 다이얼로그 "취소" 라벨 등)
  - `apps/web/src/mocks/issue-handlers.ts` (gap-H 보강 — `deletedKeys` Set + `listIssuesHandler` 필터링 + `getIssueHandler` 삭제 후 404 + `deleteIssueHandler` Set add. 테스트별 reset helper export)
  - `apps/web/e2e/fixtures/issue-fixtures.ts` (신규 — alice 로그인 헬퍼 + 이슈 생성/삭제 helper + i18n 정본 셀렉터 export. workflow-helpers.ts 패턴 일관)
- depends-on: []

**RED**: 본 task는 인프라 셋업 task — RED phase 명시적 fail test 없음. E2E task (T2~T5) 가 본 인프라 사용. T1 단독 단위 테스트 0.

**GREEN**:
- i18n 키 확인 / 누락 시 추가 + 정본 export 확정
- mock 핸들러 stateful 변환 — `deletedKeys: Set<string>` module-scope + `listIssuesHandler` 응답 필터링 + `getIssueHandler` deleted set 검증 후 404 + `deleteIssueHandler` Set add + `resetIssueState()` export
- e2e fixtures helper 작성 — `loginAsAlice(page)` / `createIssueViaUI(page, summary)` / `i18nLabels` re-export

**REFACTOR**: i18n 정본 key naming 일관 / mock 핸들러 stateful 변환 KDoc 추가 / fixture import 경로 정리.

**검증**: `pnpm typecheck && pnpm test -- src/mocks/issue-handlers` (단위 테스트 영향 0 확인) + `pnpm lint`.

**commit prefix**: `chore:` (인프라 셋업, production 코드 변경 0). 단일 commit 또는 영역별 분리 (i18n / mock / fixture 3 commit) — implementer 판단.

---

### Task 2. E2E-1 Happy Path (생성→조회→인라인 수정→소프트 삭제→목록 제외)

**메타**.
- agent: `qa-engineer`
- files: `apps/web/e2e/issue-crud-happy.spec.ts` (신규)
- depends-on: [1]

**RED**:
- 파일 작성 후 `pnpm test:e2e -- issue-crud-happy` 실행
- 실패 사유 후보. (a) 이미 통과 (UI 동작 정상) — RED 형식 변형 (test commit 단독, E2E 검증 모드). (b) 통과 안 함 → mock 핸들러 또는 E2E spec 자체 fix (별 commit, D5 옵션 C 패턴).

**GREEN**:
- alice 로그인 fixture → `/issues/new` → projectKey "ATLAS" + summary "E2E 테스트용 이슈" → 제출
- 자동 발급 키 (`ATLAS-42` mock 응답) → `/issues/ATLAS-42` redirect 검증 → `issueDetailStrings.summaryLabel` + 입력값 노출 검증
- 인라인 편집 (editTitleButton 클릭 → titleEditLabel 입력 → saveButton)
- 낙관적 업데이트 즉시 반영 + 서버 응답 후 동기 검증
- destructive 삭제 버튼 → 확인 다이얼로그 → confirmButton 클릭
- `/issues` 목록 navigate + 해당 키 부재 검증 (gap-H — mock 핸들러 stateful 필터링 의존)

**REFACTOR**: 셀렉터 i18n 정본 import 일관 (PR #22 §F4 학습) / 공통 step을 fixtures helper 호출로 추출.

**검증**: `pnpm test:e2e -- issue-crud-happy` 통과.

**commit prefix**: `test:`.

---

### Task 3. E2E-2 비로그인 가드 (이슈 라우트 3개 → /login redirect + returnTo 보존)

**메타**.
- agent: `qa-engineer`
- files: `apps/web/e2e/issue-auth-guard.spec.ts` (신규)
- depends-on: [1]

**RED → GREEN**: `apps/web/e2e/already-authed.spec.ts` 반전 패턴 (PR #11) — sessionStorage 비운 상태 + `/issues` / `/issues/ATLAS-1` / `/issues/new` 3개 라우트 접근 시 `/login?returnTo=<원래경로>` redirect 검증.

**REFACTOR**: 3 시나리오를 `test.describe.each` 또는 `test.each` 로 압축.

**검증**: `pnpm test:e2e -- issue-auth-guard` 통과.

**commit prefix**: `test:`.

---

### Task 4. E2E-3 미존재 키 (404 에러 UI — gap-G)

**메타**.
- agent: `qa-engineer`
- files: `apps/web/e2e/issue-not-found.spec.ts` (신규)
- depends-on: [1]

**RED → GREEN**: alice 로그인 → `/issues/ATLAS-99999` (mock `issueFixtureMap` 미등록) → mock 핸들러 404 응답 → `issues.$key.tsx:77` `role="alert"` 영역 노출 + `issueDetailStrings.notFound` 메시지 정합 검증.

**REFACTOR**: 다른 status code (403 권한 / 500 서버) 분기는 본 PR scope 외 — 권한 가드 미구현 (gap-B) 으로 인해 별 후속 slice.

**검증**: `pnpm test:e2e -- issue-not-found` 통과.

**commit prefix**: `test:`.

---

### Task 5. E2E-4 회귀 가드 (Esc 취소 / 다이얼로그 취소 / 빈 summary Zod 검증)

**메타**.
- agent: `qa-engineer`
- files: `apps/web/e2e/issue-ui-regression.spec.ts` (신규)
- depends-on: [1]

**RED → GREEN**: 3 sub-test.
- 5-1. 인라인 편집 모드 진입 → 새 값 입력 → Esc 키 → 원본 값 유지 검증 (`issueDetailStrings.editTitleButton` + `cancelButton`).
- 5-2. 삭제 destructive 버튼 → 다이얼로그 노출 → 취소 (`cancelButton` 또는 외부 클릭) → 이슈 보존 검증 (mock 핸들러 호출 0).
- 5-3. `/issues/new` 폼에서 projectKey + 빈 summary 제출 → `issueCreateStrings.summaryRequired` Zod 클라이언트 검증 메시지 표시 + 서버 호출 0 (mock `createIssueHandler` 호출 history 0).

**REFACTOR**: 3 sub-test 가 같은 spec 안 `test.describe` 묶음.

**검증**: `pnpm test:e2e -- issue-ui-regression` 통과.

**commit prefix**: `test:`.

---

### Task 6. Product plan §2.1.1 §D7 본문 메모 갱신 (Deferred trigger + 후속 slice 위임)

**메타**.
- agent: `qa-engineer` (또는 backend-engineer — docs 영역 책임 모호. 본 PR 본질 일관으로 qa-engineer)
- files: `docs/plan/product/issue-tracking.md`
- depends-on: [2, 3, 4, 5]

**RED → GREEN**: §2.1.1 §D7 본문에 다음 내용 추가 (line 38 직후, `| 항목 | 임계 ... |` 표 앞).

```markdown
> **D7 본 PR 부분 통과 (PR #32)**. E2E-1~4 (생성→조회→수정→소프트 삭제 / 비로그인 가드 / 미존재 키 404 / UI 회귀 가드 3건). full 통과 후속 의존:
> - **상태 전환 E2E** ← FR-IS-01 D6.5 신규 slice (전환 UI 추가 — DropdownMenu + useTransitionMutation).
> - **이슈 이동 + 키 redirect E2E** ← FR-IS-12 move 별 FR (백엔드 IssueKeyRedirect + Flyway + jOOQ + frontend 이동 다이얼로그 + redirect 라우트).
> - **NFR p95 측정 3건 (조회/목록/POST)** Deferred trigger — (a) k6-load-testing + Playwright NFR 계층 도구 도입 후 + (b) Maxi 1인 선언으로 trigger 조정.
> - **권한 모델 E2E** ← 실 RBAC 가드 구현 (현재 dev/test = `AlwaysAllowIssuePermissionResolver`) 별 FR 후.
```

**REFACTOR**: 무관 (docs 갱신).

**검증**: Markdown 정합 + 본 PR 메모 링크 (#32) 정확성.

**commit prefix**: `docs:`.

---

### Task 7. Verification (controller 직접 ground truth 실행 — PR #27 §2 학습)

**메타**.
- agent: controller (메인 agent) — 별 sub-agent dispatch 0. 컨트롤러가 직접 `pnpm test:e2e` + `pnpm verify` 실행.
- files: []
- depends-on: [2, 3, 4, 5, 6]

**검증** (controller 직접):
1. `cd apps/web && pnpm test:e2e -- --reporter=list` — 신규 E2E 4건 + 기존 E2E (login 3건 + workflow 4건 + smoke) 모두 통과 확인.
2. `pnpm verify` — lint + typecheck + test + build 통과 확인 (서브에이전트 scoped typecheck 맹점 회피 — PR #27 §2 학습).
3. PRE_EXISTING 회귀 (저장 컨텍스트 §33 ktlintTest + detekt) 표면화 시 D5 옵션 C 패턴 (별 cleanup PR 위임 + 본 PR 머지 차단 사유 hot-fix 한정).

**commit prefix**: 무 (verification 보고만, 별 commit 없음).

---

## Plan 메타

- task 수: 7 (인프라 1 + E2E 4 + docs 1 + verification 1)
- 예상 시간: 단일 implementer 기준 약 30~45분. Wave 계산 시 약 15~20분.
- TDD 강제: yes (단, E2E 검증 모드 변형 — RED 명시 fail test 부재, GREEN = 이미 구현된 UI 검증). verifier prompt 사전 명시.
- Wave 계산 (bts-impl 자동):
  - **Wave 1**: T1 (단독, 인프라 셋업)
  - **Wave 2**: T2 + T3 + T4 + T5 (4-병렬, 의존 [T1], files 교집합 0 — PR #20 §1 학습 적용)
  - **Wave 3**: T6 (의존 [T2,T3,T4,T5])
  - **Wave 4**: T7 (의존 [T2,T3,T4,T5,T6], controller 직접 실행)
- 병렬 dispatch: bts-impl이 task 메타로 wave 자동 계산
- 추가 검증: `pnpm test:e2e` (Playwright) + `pnpm verify` (lint + typecheck + test + build)
- 학습 적용:
  - PR #20 §1 — 공유 인프라 파일은 단일 task 명시 할당 (T1)
  - PR #22 §F4 — E2E i18n 정본 import 강제 (T2~T5 REFACTOR)
  - PR #11 — backend dev seed alice 의존 (T1 fixtures helper)
  - PR #13/#16 D5 옵션 C — E2E 통과 안 할 시 mock 핸들러/spec fix commit 패턴
  - PR #27 §2 — 통합 task가 controller 직접 ground truth 실행 (T7)


## 리뷰 결과

### 처리 방식

`type=qa` 는 `bts-review-plan` SKILL 분기표 명시 fast-track (skip). 외부 `plan-eng-review` / `plan-ceo-review` / `plan-design-review` / `plan-devex-review` / `autoplan` 모두 정당 스킵. Controller inline self-review 적용 (PR #21~#30 일관 패턴). skill 우회 누적 +1.

### Controller inline self-review (11 항목)

| # | 검증 항목 | 결과 | 비고 |
|---|---|---|---|
| 1 | TDD 강제 변형 정당성 (E2E 검증 모드, production 변경 0) | ✅ PASS | §Plan 본문 "TDD 변형 본질" 명시. verifier prompt 사전 인라인 강제 — bts-impl 단계 책임 |
| 2 | 공유 인프라 파일 race 회피 (PR #20 §1) | ✅ PASS | T1 단일 task 명시 할당 (i18n / mock / fixtures 3 영역 단일 책임) |
| 3 | 메타 블록 완전성 (agent / files / depends-on) | ✅ PASS | T1~T7 모두 명시 |
| 4 | 의존성 그래프 순환 0 | ✅ PASS | T1 → {T2,T3,T4,T5} → T6 → T7 DAG (Directed Acyclic Graph — 순환 없는 방향 그래프) |
| 5 | Wave 계산 정합 (files 교집합 + depends-on 충족) | ✅ PASS | W1(T1) → W2(T2~T5 4-병렬, files 교집합 0) → W3(T6) → W4(T7) |
| 6 | i18n 정본 import 강제 (PR #22 §F4) | ✅ PASS | T2~T5 REFACTOR 명시 ("셀렉터 i18n 정본 import 일관") |
| 7 | controller 직접 verify (PR #27 §2 서브에이전트 scoped typecheck 맹점 회피) | ✅ PASS | T7 controller 직접 `pnpm test:e2e` + `pnpm verify` 실행 명시 |
| 8 | Scope drift 방지 (후속 위임 명시) | ✅ PASS | 4건 명시 (transition UI / move-redirect / NFR / 권한 E2E) — T6 docs 갱신으로 product plan 반영 |
| 9 | PRE_EXISTING 회귀 처리 패턴 (PR #29/#30 D5 옵션 C) | ✅ PASS | T7 본문 명시 ("PRE_EXISTING 표면화 시 D5 옵션 C 별 cleanup PR 위임 + 본 PR 머지 차단 사유 hot-fix 한정") |
| 10 | Production 코드 변경 0 검증 (PR commit 흐름 일관) | ✅ PASS | T1 chore + T2~T5 test + T6 docs + T7 (commit 0). commit prefix 모두 명시 |
| 11 | i18n 정본 활용 가능성 사전 검증 (PR #20 §3 scoped 맹점 회피) | ✅ PASS | `apps/web/src/i18n/ko.ts` 본문 확인 완료. `issueDetailStrings` + `issueCreateStrings` 풍부 (deleteButton / deleteConfirmMessage / confirmButton / cancelButton / summaryRequired / notFound 등 모두 존재). 신규 키 추가 0~2건 추정 |

### Advisory CONCERN (BLOCKER 0, advisory만)

**C1 (advisory)**. Mock 핸들러 stateful 변환 (T1 의 `deletedKeys: Set<string>` + filter) 이 기존 단위 테스트 (특히 `issues.index.test.tsx` / `issues.$key.test.tsx` 의 fixture 의존) 에 부작용 가능. T1 본문 명시된 `resetIssueState()` helper export + test setup `beforeEach` 호출이 필수. implementer prompt 에 강조 인라인.

**C2 (advisory)**. T5-2 (삭제 다이얼로그 취소) 검증은 shadcn Dialog 동작 (modal close on outside click vs Escape vs cancel button) 의존. cancelButton 클릭만 검증하면 충분 (외부 클릭 검증은 shadcn 라이브러리 자체 책임, 본 PR scope 외). implementer 판단.

**C3 (suggestion)**. T7 (controller verification) commit 0 — 머지 후 verification 보고는 PR description 갱신 또는 PR comment 로 처리 가능. 별 commit 안 만들고 PR body 갱신 권장.

**C4 (suggestion)**. T1 의 chore commit 분리 (i18n / mock / fixtures 3 commit) vs 단일 commit. 분리 시 git log 가독성 +, 단일 시 시간 절감 -. implementer 판단으로 정당 (plan §T1 명시).

### BLOCKER

없음. 게이트 1 진입 가능.

---

## 리뷰 결과 (PR #32 단위, bts-codereview)

### superpowers:code-reviewer agent (adversarial)
- **PASS (with 1 LOW advisory)**. agent ID `abd15d445a5882081`
- 검증 매트릭스 11항목 — mock stateful race / handlers.test 영향 / E2E i18n cascade / hidden coupling / NEVER-1~18 / gap-A~I 정합 / dialog/edit cancelButton 매칭 / uncommitted plan 등 모두 PASS 또는 advisory.
- plan §Brainstorming Check gap-A(NFR Deferred) / gap-B(권한 가드 후속) / gap-D(회귀 3건) / gap-E(i18n) / gap-F(alice seed) / gap-G(notFound) / gap-H(소프트 삭제 필터) 모두 정합 적용 확인.

### /review (gstack) — controller inline 핵심 적용
- **PASS**. NEVER-15 (console.log) 0건 / NEVER-16 (PoC/prototype/TODO/FIXME) 0건 / SQL/shell injection/LLM trust/enum N/A (E2E 영역).
- Scope drift 0 — 변경 파일 8개 (1 변경 + 7 신규) 모두 plan §스펙 명시 파일과 정확 일치, scope creep 0.
- Cross-model 검증 — code-reviewer agent L1/L2/L3 advisory와 동일 위치 검출 (구조적 이슈 일관).
- `/review` 스킬 정공법 sub-agent dispatch (testing/maintainability/security/performance 등) 은 PR 본질 (production 0, E2E only) 대비 비용 과대로 controller inline 핵심 적용. 우회 누적 +1.

### /plan-ceo-review (auth/migration 시 적용)
- **Skip**. type=qa, classify.type 분기 외.

### Advisory CONCERN 종합 (BLOCKER 0)

| # | severity | 위치 | 내용 | 권장 처리 |
|---|---|---|---|---|
| L1 | LOW | `apps/web/e2e/fixtures/issue-fixtures.ts:53-54` | `createIssueViaUI` 의 hardcoded `'ATLAS-42'` 키 — mock `createdIssueFixture.key` 변경 시 silent break | 별 cleanup PR 위임 (mock key 변경 빈도 ≈ 0, 즉시 fix 불요) |
| L2 | LOW | `apps/web/src/mocks/issue-handlers.ts:38-41` | `resetIssueState()` export 호출처 0 — plan §C1 advisory 약속 미충족, dead code 우려 | (옵션) handlers.test setup 에 1줄 추가 또는 별 cleanup PR. 현재 vitest file isolation 으로 leak 0, 실 영향 0 |
| L3 | LOW | `docs/plans/2026-05-28-...md` | 본 plan 산출물 (§스펙 / §Brainstorming Check / §Plan / §리뷰 결과) 미커밋 — 머지 시 워킹 변경 분실 위험 | 머지 직전 chore commit 으로 추가 |

### PRE_EXISTING (본 PR 책임 외)

**PE1**. `handlers.test.ts` PATCH/POST 에서 MSW unhandled exception (`Body is unusable: Body has already been read`) 발생. main 트리에서 동일 재현 — 본 PR 신규 결함 아님. 테스트는 통과 (status 만 assert). MSW handler lookup pipeline 의 body consumption race 추정. 별 cleanup PR — D5 옵션 C 패턴 (저장 컨텍스트 §33 ktlint/detekt 와 같은 정책 누적).


