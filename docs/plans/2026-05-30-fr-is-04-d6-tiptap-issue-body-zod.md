# FR-IS-04 D6 프론트 — TipTap issue-body 에디터 + 우선순위/라벨/환경/영향도 셀렉터/칩 + Zod 스키마 동기

> slug: fr-is-04-d6-tiptap-issue-body-zod
> type: ui
> agent: frontend-engineer
> 생성: 2026-05-30
> 분류 정정: classify migration/db-engineer 오판 → Maxi 확정 ui/frontend-engineer

## Brief

FR-IS-04(이슈 본문 Markdown + 우선순위/라벨/환경/영향도)의 프론트엔드 짝(D6). FR 분할 3PR(백엔드 D1~D5 / 프론트 D6 / E2E D7) 중 2/3.

**백엔드는 PR #43(squash be8780e) 머지 완료.** 이번 PR은 그 백엔드 계약을 프론트에 노출:
- TipTap issue-body 에디터 (Markdown 직렬화, HTML 아님)
- 우선순위(1~5)/영향도(1~3) 셀렉터, 라벨 칩 입력, 환경 텍스트
- issueResponseSchema에 description/descriptionHtml/priority/priorityName/labels/environment/impact/impactName 추가
- UpdateIssueInput에 5필드 추가

**계약 주의(stored XSS 방지)**: 이슈 목록/표시는 raw `description`이 아니라 정화본 `descriptionHtml`을 써야 함. raw description은 편집 폼에서만 사용.

**선례 패턴**: PR #39 D6(타입 셀렉터)·PR #41(전환 UI)이 같은 화면(IssueMetaPanel / issues.$key) 작업. 계약갭(frontend-zod-backend-dto-contract-gap) 회피 위해 spec 단계에서 백엔드 DTO grep 검증 필수.

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: 없음 (프론트 전용). 백엔드 `Issue` Aggregate는 PR #43에서 이미 5필드(description/priority/labels/environment/impact) 보유.
- **새 용어**: 없음. description/priority/labels/environment/impact는 SDD 05 정본 용어 + 백엔드 구현 완료. 프론트는 한글 UI 라벨로 표시만.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: 없음 (백엔드 도메인 결정은 PR #43 D1~D5에서 완료. 프론트 신규 ADR 불필요).
- **게이트1 승인 필요 항목**: TipTap 라이브러리 신규 도입 (외부 의존성, DEVELOPMENT.md §외부 의존성. PR #43 OWASP Sanitizer 게이트1 승인 선례).

### 백엔드 계약 (grep 검증 완료 — frontend-zod-backend-dto-contract-gap 회피)

**응답 `GET /api/v1/issues/{key}` (IssueResponse.kt):**
| 필드 | 타입 | 비고 |
|---|---|---|
| `description` | `String?` | raw Markdown 원본. **편집 폼 전용**. |
| `descriptionHtml` | `String?` | 렌더+sanitize HTML. **단건 GET만 채워짐(목록=null)**. **표시 전용** (stored XSS 방지). |
| `priority` | `Int` | 1~5, 기본 3. |
| `priorityName` | `String` | 1=Highest, 2=High, 3=Medium, 4=Low, 5=Lowest. |
| `labels` | `List<String>` | 기본 []. |
| `environment` | `String?` | null 허용. |
| `impact` | `Int?` | 1~3, null 허용. |
| `impactName` | `String?` | 1=High, 2=Medium, 3=Low. impact=null이면 null. |

**요청 `PATCH /api/v1/issues/{key}` (UpdateIssueRequest.kt) — RFC 7396 merge-patch 3-state:**
| 필드 | 규칙 | 검증 |
|---|---|---|
| `description` | null=무변경, `""`=DB NULL 클리어, 값=설정 | max 65535자. summary @Pattern 복사 금지(빈문자열=클리어 sentinel). |
| `priority` | null=무변경 | 1~5, 범위 밖 400. |
| `labels` | null=무변경, `[]`=전체제거, 값=교체 | 각 50자, 최대 20개. |
| `environment` | null=무변경, `""`=클리어 | max 1000자. |
| `impact` | null=무변경 | 1~3, 범위 밖 400. |
| `expectedVersion` | 필수 | 낙관적 잠금. DB 버전과 다르면 409. |

### 프론트 현 상태

- Zod 스키마 위치: `apps/web/src/api/issues.ts` (`issueResponseSchema` / `UpdateIssueInput` 5필드 추가 대상).
- TipTap **미설치** — `@tiptap/*` 설치 + **Markdown 직렬화 확장** 필요 (HTML 아닌 Markdown 저장).
- 화면 파일: `IssueMetaPanel` / `issues.$key` (PR #39 D6 타입셀렉터·PR #41 전환UI와 동일 화면 — 패턴 재사용).

## 스펙

전체 스펙. [docs/specs/2026-05-30-fr-is-04-d6-tiptap-issue-body-zod.md](../specs/2026-05-30-fr-is-04-d6-tiptap-issue-body-zod.md)

**핵심 결정 (Maxi 확정)**: 본문 에디터 = **GitHub 스타일 Write/Preview 탭** (TipTap 미사용, 의존성 0개 추가). 백엔드 Markdown 정본 모델과 정합. Jira식 ADF/WYSIWYG는 백엔드 재설계 선행 필요 → 범위 밖.

핵심 시나리오 요약.
- 본문: Preview 탭=descriptionHtml(정화본) 렌더, Write 탭=raw Markdown textarea 편집 → 저장 시 PATCH {description}.
- 우선순위(1~5)/영향도(1~3) 셀렉터 = 선택 즉시 PATCH(typeChange 패턴). 환경/라벨 = 명시 저장 버튼.
- 모든 변경 expectedVersion 동반(OCC), 409 충돌 시 재조회+toast.

저장 인터랙션: 셀렉터=즉시 PATCH, 본문/환경/라벨=명시 저장. impact는 클리어 sentinel 부재 → 한번 설정 후 "미지정" 복귀 불가.

## Brainstorming Check

✅ 통과 (직접 sanity review 1회). gap 2건 발견·보강 — 메타필드 저장 인터랙션 모델 + 읽기값/편집 컨트롤 병기. Maxi 결정 필요 항목 없음.

## Plan

> 전부 frontend-engineer. 경로는 repo 루트 기준.

### Task 1. issues.ts Zod 계약 확장 (issueResponseSchema 8필드 + UpdateIssueInput 5필드)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED**:
- `issues.test.ts`에 테스트 추가 — `issueResponseSchema.parse`가 description/descriptionHtml/priority/priorityName/labels/environment/impact/impactName 필드를 파싱·검증(priority 1~5 int, impact 1~3 nullable, labels string[], descriptionHtml nullable). 신규 필드 미정의 → 실패.
- 백엔드 DTO와 1:1 대응 회귀가드 1건(필드 누락 시 실패).

**GREEN**:
- `issueResponseSchema`에 8필드 추가: `description: z.string().nullable()`, `descriptionHtml: z.string().nullable()`, `priority: z.number().int().min(1).max(5)`, `priorityName: z.string()`, `labels: z.array(z.string())`, `environment: z.string().nullable()`, `impact: z.number().int().min(1).max(3).nullable()`, `impactName: z.string().nullable()`.
- `UpdateIssueInput`에 5필드 추가(전부 optional): `description?: string`, `priority?: number`, `labels?: string[]`, `environment?: string`, `impact?: number`. KDoc에 merge-patch 3-state 시맨틱 명시.

**REFACTOR**:
- 필드 KDoc 정리 (descriptionHtml = 단건 GET만 채워짐, 목록 null 명시).

**검증**: `cd apps/web && pnpm test issues.test`

### Task 2. i18n 신규 문자열 (issueDetailStrings)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`]
- depends-on: []

**RED**:
- (i18n은 상수 객체) 본문/우선순위/영향도/환경/라벨 라벨·버튼·안내 키가 issueDetailStrings에 존재하는지 참조하는 타입체크 또는 간단 단위테스트. 키 부재 → 실패/타입에러.

**GREEN**:
- issueDetailStrings에 추가: `descriptionWriteTab`, `descriptionPreviewTab`, `descriptionEmpty`(본문 없음), `descriptionEditButton`, `priorityLabel`, `priorityNames`(Highest..Lowest 한글 매핑), `impactLabel`, `impactNames`, `impactUnset`(미지정), `environmentLabel`, `environmentPlaceholder`, `labelsLabel`, `labelAddPlaceholder`, `labelRemoveLabel`, `metaSaveButton`, 저장 실패 toast 등.

**REFACTOR**:
- 키 그룹 주석 정리.

**검증**: `cd apps/web && pnpm typecheck`

### Task 3. MSW issue PATCH stateful mutation 핸들러 (5필드 + descriptionHtml 재렌더)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/issue-fixtures.ts`, `apps/web/src/mocks/__tests__/issue-handlers.test.ts`]
- depends-on: [1]

**RED**:
- `issue-handlers.test.ts`에 테스트 — PATCH /issues/{key}에 description/priority/labels/environment/impact 전달 시 stateful 오버라이드에 영속되고 후속 GET이 갱신값+descriptionHtml(간단 렌더 모킹) 반환. 핸들러 미구현 → 실패.
- merge-patch 3-state: description "" → null, labels [] → 빈배열 반영 검증.

**GREEN**:
- issue-fixtures에 5필드 기본값 추가(priority 3, labels [], description null 등 백엔드 DEFAULT 정합).
- PATCH 핸들러가 5필드 merge-patch 반영 + stateful 영속(메모리 msw-mutation-stateful-refetch — setQueryData 위 가짜그린 방지). descriptionHtml은 단건 GET에서 description을 간단 `<p>` 래핑 등으로 모킹.

**REFACTOR**:
- 3-state 처리 헬퍼 추출.

**검증**: `cd apps/web && pnpm test issue-handlers`

### Task 4. 본문 Write/Preview 컴포넌트 (IssueDescription 신규)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/IssueDescription.test.tsx`]
- depends-on: [1, 2]

**RED**:
- `IssueDescription.test.tsx` — Preview 탭에서 descriptionHtml 렌더(dangerouslySetInnerHTML), description=null이면 descriptionEmpty placeholder. 편집 진입 → Write 탭 textarea에 raw description 표시. 저장 → onSave(rawMarkdown) 호출. 빈 입력 저장 → onSave("") (클리어). 컴포넌트 미존재 → 실패.

**GREEN**:
- `IssueDescription.tsx` 신규 — props: `descriptionHtml: string|null`, `description: string|null`, `onSave: (md: string) => void`, `isSaving: boolean`. Write/Preview 탭 토글 + textarea + 저장/취소 버튼. 표시는 descriptionHtml만(NFR1, raw 미표시).
- 파일 헤더 한글 주석. WCAG min-h-[44px], aria-label.

**REFACTOR**:
- 탭 상태/저장 핸들러 정리.

**검증**: `cd apps/web && pnpm test IssueDescription`

### Task 5. 메타패널 우선순위/영향도/환경/라벨 컨트롤 (IssueMetaPanel 확장)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/IssueMetaPanel.test.tsx`]
- depends-on: [1, 2]

**RED**:
- `IssueMetaPanel.test.tsx` — 우선순위 셀렉터(priorityName 표시 + 1~5 옵션, 변경 시 onPriorityChange 즉시 호출), 영향도 셀렉터(impactName/미지정 표시 + 1~3, 변경 시 onImpactChange; impact 설정 후 "미지정" 옵션 disabled — EC3), 환경(현재값 표시+편집+저장 onEnvironmentSave), 라벨 칩(현재 칩 표시 + 추가/삭제 + onLabelsSave, ≤50자/≤20개 클라검증). 컨트롤 부재 → 실패.

**GREEN**:
- IssueMetaPanel에 4섹션 추가. 셀렉터는 IssueTypeSelect/IssueStateTransition 패턴(즉시 콜백), 환경/라벨은 로컬상태+저장버튼. props 확장: `onPriorityChange`, `onImpactChange`, `onEnvironmentSave`, `onLabelsSave`, isSaving 류.
- 셀렉터 현재값은 issue props 파생(useState 초기화 금지 — 메모리 react-usestate-stale-key-prop).
- **(리뷰 CONCERN1) 환경/라벨 로컬 편집상태 stale 회피**: 환경/라벨은 편집 진입 시 issue props로 seed하고, 미편집 시 props 값을 표시한다(제목 편집 `handleEditStart`→`setEditSummary(issue.summary)` 패턴 미러). refetch로 props 변경 시 stale 로컬상태 잔존 금지.

**REFACTOR**:
- 라벨 칩 입력 서브컴포넌트 추출, 클라 정규화(공백/중복) 헬퍼.

**검증**: `cd apps/web && pnpm test IssueMetaPanel`

### Task 6. issues.$key 배선 (본문 컴포넌트 + 메타필드 mutation 5개)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [1, 4, 5]

**RED**:
- `issues.$key.test.tsx` — 자리표시자(279-282) 대신 IssueDescription 렌더, 본문 저장 → PATCH {description}; 우선순위/영향도 변경 → 즉시 PATCH; 환경/라벨 저장 → PATCH; 각 성공 시 캐시 무효화; 409 VERSION_CONFLICT → toast+재조회(typeChange 패턴). 배선 부재 → 실패.
- **(리뷰 CONCERN2) MSW stateful 반영 검증**: 본문/필드 저장 후 invalidateQueries refetch → 화면이 갱신값으로 반영되는지 통합 검증(setQueryData 위 가짜그린 방지, 메모리 msw-mutation-stateful-refetch). MSW stateful 핸들러(T3) 위에서 refetch 후에도 값 유지 확인.

**GREEN**:
- 자리표시자 교체 → `<IssueDescription>` 배선(onSave → descriptionMutation).
- 메타필드 mutation 5종을 typeChangeMutation 패턴으로 추가(각 updateIssue(key, {필드, expectedVersion}) + onSuccess setQueryData/invalidate + onError 409 처리). IssueMetaPanel에 핸들러 props 연결.

**REFACTOR**:
- mutation 5종 공통 onError 헬퍼 추출(중복 제거).

**검증**: `cd apps/web && pnpm test issues.\$key && pnpm typecheck && pnpm lint`

## Plan 메타

- task 수: 6
- 예상 wave: 3 — wave1[1,2] → wave2[3,4,5] → wave3[6]
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 병렬 dispatch: bts-impl이 depends-on + files 겹침으로 wave 계산. IssueMetaPanel.tsx(T5)·issues.$key.tsx(T6)·issues.ts(T1)는 단일 task 전속 → race 회피.
- 추가 검증: typecheck, lint, vitest 전체. Playwright E2E는 D7 별 PR(이 PR 범위 밖).
- 함정 주의: msw-mutation-stateful-refetch(T3), react-usestate-stale-key-prop(T5), frontend-zod-backend-dto-contract-gap(T1 — 계약 1:1), impact 클리어 불가(T5 EC3).

## 리뷰 결과

### 독립 plan 리뷰 (2026-05-30, ground-truth 대조)

> code-reviewer 에이전트 dispatch가 서버 과부하(529/ECONNRESET)로 3회 실패 → 메인에서 직접 백엔드 계약 파일(IssueResponse.kt / UpdateIssueRequest.kt / IssuePriority·Impact enum) 대조 검증. autoplan 4종은 스킵(메모리 bts-review-plan-autoplan-overkill — 기존 화면 패턴 답습, 새 시각디자인 없음).

**BLOCKER: 0건**

**통과 (ground-truth 확인):**
- ✅ 계약 1:1 — issueResponseSchema 8필드 + UpdateIssueInput 5필드가 IssueResponse.kt:53-60 / UpdateIssueRequest.kt 실제 필드명·타입·nullability·범위와 정합. priority non-null(기본3), impact nullable, descriptionHtml 단건만(IssueResponse.kt:107).
- ✅ impact 클리어 불가 — UpdateIssueRequest.kt:65-67 클리어 sentinel 부재 사실 확인. plan disabled 처리 정합.
- ✅ 정화 렌더 보안 — IssueResponse.kt:107 MarkdownRenderer.renderSafe 서버측 정화 확인. dangerouslySetInnerHTML 정당, NFR1(raw 미표시) 명시.
- ✅ wave 직렬화 — T1~T6 파일 겹침 0, [1,2]→[3,4,5]→[6] 정확.

**CONCERN: 3건**
- C1 (plan 반영 완료) — T5 환경/라벨 로컬 편집상태 stale 회피(편집 진입 시 props seed). T5 GREEN 보강.
- C2 (plan 반영 완료) — T6 RED에 MSW stateful refetch 후 화면 반영 통합검증 추가.
- C3 (Maxi 인지 — 게이트1) — impact 되돌리기 불가는 백엔드 sentinel 부재 제약. D6는 disabled로 정합하나, 영향도 클리어 지원은 후속 FR(백엔드 변경) 후보.
