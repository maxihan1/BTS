# 워크플로우 스킴 프론트↔백엔드 계약 파손 봉합

> slug: workflow-scheme-contract-align
> type: **ui (수동 판정)** — classify 오분류. 아래 §분류 판정 참조
> agent: frontend-engineer (주). B안 채택 시 backend-engineer 뷰 레이어 task 추가 (학습 2026-05-22 「옵션 C 패턴」)
> primary_bc: project-workflow
> 생성: 2026-07-27

## Brief

### 사용자 원문

TODOS.md 「project-workflow — 워크플로우 스킴 프론트↔백엔드 계약 파손」(PR #314 plan-eng-review
outside voice 발견, **★차단 사안**) 을 착수한다. 이 부채가 남는 한 스킴 기능의 어떤 PR 도 프론트
테스트로 "회귀 없음" 을 증명할 수 없다 — 초록은 MSW 가 MSW 와 맞는다는 뜻이다.

### 착수 전 실측 (2026-07-27, 이 세션)

TODOS.md 의 지시("추측하지 말고 조립 부팅 실측부터 — 화면이 실서버에서 동작한 적이 없는 것인지,
내가 못 본 변환 계층이 있는 것인지가 먼저 확정돼야 한다")에 따라 **코드 실측을 선행**했다.

**변환 계층 부재 확정.**

| 검사 | 명령 | 결과 |
|---|---|---|
| 전역 JSON 필드명 변환 | `grep -rn "PropertyNamingStrategy\|property-naming-strategy" backend` | **0건** |
| 필드별 별칭 | `grep -rn "JsonProperty" backend/modules/project-workflow/src/main` | **0건** |
| 경계 정규화 | `workflow-schemes.types.ts` `.transform()` | **1건** (`assignableSchemeResponseSchema` 만) |

⇒ 못 본 변환 계층은 없다. `assignable` 1개만 #314 가 D3=1A 결정으로 정규화해 뒀고 나머지는 무방비다.

**★TODOS.md 대조표가 실제와 다르다 — 3종이 아니라 응답 7종 + 요청 1종.**
메모리 [[spec-stated-count-becomes-blindfold]] 재발. 기록된 개수·상대 DTO 를 그대로 물려받지 말 것.

#### 응답(response) 대조 — 전수

| # | endpoint | 백엔드 실제 반환형 | 프론트 Zod | 불일치 |
|---|---|---|---|---|
| R1 | `GET /api/v1/workflow-schemes` | `WorkflowSchemeDetailResponse` (`WorkflowSchemeDto.kt:116`) | `schemeResponseSchema` (`:20`) | `schemeKey`(bk=`key`) · `isStandard`(bk=`isDefault`) · `description` non-null vs `String?` |
| R2 | `GET /api/v1/workflow-schemes/{key}` | 같음 + `mappings: MappingResponseDetail[]` (`:69`) | `schemeDetailResponseSchema` (`:43`) | R1 3건 + `mappings[].isDefault` **필드 부재** |
| R3 | `POST /api/v1/workflow-schemes` | **`WorkflowSchemeResponse`** (`:213`) | `schemeResponseSchema` | R1 3건 + `usedByProjectsCount`·`mappingsCount` **둘 다 부재** = 5건 |
| R4 | `PUT /api/v1/workflow-schemes/{key}` | **`WorkflowSchemeResponse`** | `schemeResponseSchema` | R3 과 동일 5건 |
| R5 | `GET /api/v1/projects/{k}/workflow-scheme` | `SchemeResponse{id,key,name,description,isDefault}` (`ProjectWorkflowSchemeController.kt:198`) | `assignmentResponseSchema{projectKey,schemeKey,schemeName}` (`:48`) | **교집합 0** |
| R6 | `PUT /api/v1/projects/{k}/workflow-scheme` | **`AssignmentResponse{projectId,workflowSchemeId,assignedAt,assignedBy}`** (`:182`) | 같은 `assignmentResponseSchema` | **교집합 0** (TODOS.md 가 이 DTO 를 `SchemeResponse` 로 오지목) |
| R7 | `POST /{key}/mappings` | **`MappingResponse{id,schemeId,issueTypeId,workflowId,createdAt}`** (`WorkflowSchemeDto.kt:32`) | `mappingResponseSchema` (`:31`) | **`id` 만 겹침** — 5/6 부재 (TODOS.md 는 `isDefault` 1건만 기록) |
| ✅ | `GET /api/v1/projects/{k}/assignable-workflow-schemes` | `SchemeResponse` | `assignableSchemeResponseSchema` **`.transform()` 보유** | **정상** |
| — | `DELETE` 2건 | 204 no content | 파싱 없음 | 무해 |

⇒ **응답 파싱을 수행하는 8 endpoint 중 7 파손.** `z.object()` 는 선언 키 부재 시 throw 하므로
7 경로 전부 `ZodError` 로 화면이 깨진다.

#### 요청(request) 대조 — 신규 발견, TODOS.md 미기록

`apiPost`/`apiFetch` 는 raw body 가 아니면 `JSON.stringify(body)` 로 그대로 보낸다(`client.ts:90,141`).

| # | endpoint | 프론트가 보내는 것 | 백엔드가 요구하는 것 | 판정 |
|---|---|---|---|---|
| Q1 | `POST /api/v1/workflow-schemes` | `CreateSchemeInput{schemeKey,name,description?}` (`:101`) | `CreateWorkflowSchemeRequest{key,name,description?}` (`WorkflowSchemeDto.kt:183`) | **`key` 부재 → Kotlin non-null 역직렬화 실패 → 400** |
| Q2 | `PUT /api/v1/workflow-schemes/{key}` | `UpdateSchemeInput{name?,description?}` (`:108`) | `UpdateWorkflowSchemeRequest{name(non-null),description?}` (`:197`) | `name` 생략 시 400 (nullability 비대칭, 화면이 항상 보내면 잠복) |
| Q3 | `POST /{key}/mappings` | `AddMappingInput{issueTypeKey,workflowKey}` | `MappingRequestDto{issueTypeKey?,workflowKey}` | ✅ 일치 |
| Q4 | `PUT /projects/{k}/workflow-scheme` | `AssignSchemeInput{schemeKey}` | `AssignSchemeRequest{schemeKey}` | ✅ 일치 |

**★백엔드 자체도 어휘가 갈린다** — `CreateWorkflowSchemeRequest` 는 `key`, `AssignSchemeRequest` 는
`schemeKey`. 「백엔드가 정본」이라는 전제가 성립하지 않는다. 정본을 먼저 정하는 것이 spec 의 첫 일이다.

### 근본 원인 — 개별 실수 7개가 아니다

**프론트 Zod 스키마 3장을 각각 서로 다른 두 개의 백엔드 DTO 에 재사용하고 있다.**

| Zod 스키마 1장 | 이 응답에도 | 저 응답에도 |
|---|---|---|
| `schemeResponseSchema` | `WorkflowSchemeDetailResponse` (카운트 보유) | `WorkflowSchemeResponse` (카운트 **없음**) |
| `assignmentResponseSchema` | `SchemeResponse` (스킴 정보) | `AssignmentResponse` (배정 이력) |
| `mappingResponseSchema` | `MappingResponseDetail` (키·이름) | `MappingResponse` (내부 PK) |

⇒ 스키마를 고치는 문제가 아니라 **endpoint 수만큼 스키마를 분리**하는 문제다.

### 왜 아무도 몰랐나

프론트 테스트 전부가 MSW 픽스처(`scheme-fixtures.ts`)와 대조한다. 픽스처가 프론트 어휘로 작성돼
있어 초록이다. 메모리 [[msw-derived-behavior-shared-store-e2e]]·학습 2026-06-25(「MSW lexical 비교로
가짜그린」)과 동질 — **세 겹(단위·MSW·E2E)이 다 통과하는데 실서버에서 깨진다.**

### 미확정 — spec 이 답해야 할 것

1. **정본 어휘.** `key`/`isDefault`(백엔드 다수) vs `schemeKey`/`isStandard`(프론트 + 백엔드 `AssignSchemeRequest`)
2. **화면이 실서버에서 동작한 적이 있는가.** 라우트 4개(`admin.workflow-schemes{,.new,.$schemeKey}` ·
   `projects.$projectKey.settings.workflow-scheme`)는 실재한다. 7/7 파손이면 **동작한 적이 없다** =
   부채가 아니라 기능 부재에 가깝다. 학습 2026-07-17 「파일 존재 ≠ 기능 존재」의 프론트 판본.
   ⇒ **조립 부팅(dev postgres 5433 + `:app:test` 또는 `ProdAssemblyHttpTestBase`)으로 실응답 확정 필요**
3. **`mappings[].isDefault` 의 출처.** 백엔드 미보유. `issueTypeKey === null` 에서 파생 가능한지
   (백엔드 KDoc `MappingResponseDetail:61` "default mapping 은 issueTypeKey/issueTypeName 이 null")
4. **R3/R4 카운트 부재를 화면이 실제로 쓰는가.** 안 쓰면 스키마 분리로 끝. 쓰면 refetch 필요

### 처방 후보 (spec 에서 결정)

| 안 | 내용 | 백엔드 변경 | 폭발 반경 | 선례 |
|---|---|---|---|---|
| A | 프론트가 경계에서 `.transform()` 정규화 + endpoint 별 스키마 분리 | 0 | apps/web (`schemeKey`/`isStandard` 34 파일 중 어휘 사용분) | **#314 D3=1A 가 `assignable` 에 이미 적용** |
| B | 백엔드 응답 DTO 를 프론트 어휘로 통일 | 3 DTO | project-workflow + OpenAPI + 백엔드 테스트. `assignable` `.transform()` 제거(=#314 되돌림) | 학습 2026-05-22 「same BC 뷰 레이어는 프론트 PR 안에서 가능(옵션 C)」 |
| C | 프론트가 백엔드 어휘(`key`/`isDefault`)를 그대로 채택 | 0 | apps/web 광범위 + `assignable` `.transform()` 제거 | — |

※ **어느 안이든 프론트 스키마 분리는 필수** — R6(`AssignmentResponse`)·R7(`MappingResponse`)은
어휘 문제가 아니라 **의미가 다른 DTO** 라 백엔드를 바꿔도 스키마를 나눠야 한다. 그래서 분류를 `ui` 로 판정했다.

### 분류 판정 (classify 오분류 → 수동 override)

`classify-task.ts` 가 같은 작업의 제목 3종에 **서로 다른 3개 타입**을 냈다.

| 제목 | 결과 | 원인 |
|---|---|---|
| "…Zod **스키마**…" | `migration` / db-engineer | `classify-task.ts:45` 가 `'schema','스키마'` 를 무조건 migration 으로 보냄. **Zod 스키마는 DB 무관** |
| "…**회귀 테스트**" | `qa` / qa-engineer | qa 키워드 우선 |
| "…project-workflow 뷰 레이어" | `backend` / backend-engineer | backend 키워드 |

⇒ **수동 판정 `type=ui` / `agent=frontend-engineer` / `primary_bc=project-workflow`.**
근거 — 3 처방안 전부 프론트 변경 필수, 백엔드는 B 안에서만 추가되며 그때도 same-BC 뷰 레이어.
**공유 `.bts-cache/classify.json` 은 덮어쓰지 않았다** (동시 세션 FR-CO-02 소유,
메모리 [[bts-cache-multisession-collision]] — 이 함정이 실제 발동해 `--cache` 를 쓰면
FR-CO-02 분류를 받았을 상황이었다). **plan 이 진실출처.**

### 후속 등재 대상 (이 PR 범위 밖, TODOS.md 등재)

- **`classify-task.ts` 키워드 충돌** — `'스키마'` 가 Zod/GraphQL/JSON schema 작업을 전부 migration 으로
  오분류한다. TODOS.md 기존 항목 「`bts-review-plan` 분기 표에 `type=backend` 가 없다」의 형제
  (하드코딩 목록 결함). 판별식 필요 — 개별 단어 제거로 끝내지 말 것.

## 도메인 정리

- **BC**. project-workflow (단일) — cross-BC 0
- **영향 엔티티**. `WorkflowScheme` · `SchemeIssueTypeMapping` · `ProjectWorkflowSchemeAssignment`
  (전부 기존, 신설 0). 변경은 **뷰 레이어(DTO) 한정** — 도메인 모델 무변경
- **기존 결정 충돌**. 없음. `assignableSchemeResponseSchema` 의 `.transform()`(#314 D3=1A)은
  D1 적용 후 불필요해져 제거하나, 이는 그 결정의 목적(프론트 어휘 일관성)을 **더 강하게 달성**하는 방향이므로 무효화가 아니다
- **관련 ADR**. [docs/decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md](../decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md) (신설)

### 유비쿼터스 언어 — 확정

**정본 = `key` + `isStandard`** (Maxi 결정 D1 = B안).

| 개념 | 한국어 정본 | 코드 정본 | 현재 상태 |
|---|---|---|---|
| 워크플로우 스킴 | 워크플로우 스킴 | `key` | 백엔드 ✅ / 프론트 `schemeKey` ❌ |
| 표준 스킴 | 표준 스킴 | `isStandard` | 백엔드 `isDefault` ❌ / 프론트 ✅ |
| 기본 매핑 | 기본 매핑 | `isDefault` | 양쪽 ✅ (개명 대상 아님) |

근거 — 형제 개념 2종이 이미 이 규약으로 **양쪽 일치**한다.
`IssueTypeResponse.kt:27` = `key`+`isStandard` · Resolution 백엔드 ↔ `resolutions.ts:19,27` 일치.
**워크플로우 스킴만 예외**이며 백엔드·프론트가 각각 절반씩 이탈한 상태였다.

**★`isDefault` 한 이름이 서로 다른 두 개념을 가리키고 있었다.** 「표준 스킴」(시스템 4개, 보호 대상)과
「기본 매핑」(스킴당 1개, 이슈타입 미지정 시 대체값)이다. DB 주석조차
`V201__workflow_schemes.sql:37` 이 `is_default` 를 *"true = 시스템 표준 스킴"* 이라 설명해
**컬럼명과 주석이 이미 어긋나 있었다.** 권한 스킴의 「기본 스킴」(V013 fallback)과도 이름이 겹친다.
스킴 쪽을 `isStandard` 로 옮기면 남은 `isDefault` 는 진짜 "기본"이라 이름이 정확해진다.

### 결정 3건 (Maxi)

| # | 결정 | 요지 |
|---|---|---|
| D1 | **B안 — 양쪽이 규약으로 수렴** | 백엔드 `isDefault`→`isStandard`, 프론트 `schemeKey`→`key`. 변환 계층 0 |
| D2 | **뷰 레이어 한정** | DTO 3파일 13참조만. 도메인 28참조·DB 컬럼은 이연 (마이그레이션 0) |
| D3 | **문서 3종 전부 갱신** | ADR 신설 + glossary 3항목 + domain 노트 구조 정정 |

### 코드로 답한 질문 (Maxi 판정 불요)

- **`mappings[].isDefault` 파생 가능한가 → 불가.** `WorkflowSchemeApplicationService.kt:502` 의
  `mapping.issueTypeId?.let { issueTypeRefMap[it] }` 때문에 `issueTypeRef` 가 null 인 경우가 둘이다 —
  ① 진짜 기본 매핑 ② cross-BC 조회 실패. `issueTypeKey === null` 로 파생하면 조회 실패한 실제 매핑이
  **★ 기본 매핑으로 오표시 + 정렬 최상단**으로 올라간다(`MappingTable.tsx:122,130,134,140,331`이 실소비).
  ⇒ 백엔드가 `from()` 안에서 `mapping.issueTypeId == null` 로 명시 산출 (ADR D3)
- **소비자 전수 → `apps/web` 밖 0건.** 매치 전부 project-workflow 자체 테스트. 정적 OpenAPI 스펙 0건

### 문서 갭 (Maxi 승인 완료, 반영 대상)

- **glossary.md** — 「권한 스킴」은 등재됐으나 **「워크플로우 스킴」·「표준 스킴」·「기본 매핑」 3종 미등재**.
  TODOS.md 기존 항목 「핵심 엔티티 3종이 glossary 미등재」의 추가 사례
- **domain/project-workflow.md** — 핵심 엔티티에 `WorkflowScheme`·`SchemeIssueTypeMapping` 누락.
  "WorkflowAssignment (프로젝트 → 워크플로우 바인딩)"이라 적혀 있으나 실제는
  **프로젝트 → 스킴 → (이슈타입별) 워크플로우**로 한 겹 더 깊다. BC 노트가 실제 모델보다 얕다
  ※ 둘 다 Obsidian(저장소 밖) 수동 영역 — PR diff 에 포함되지 않음

### grill-with-docs 편차

`grill-with-docs` 의 `CONTEXT.md` / `docs/adr/` 규약은 BTS 에 없다. BTS 는 `Maxi_wiki/BTS/glossary.md` +
`domain/<bc>.md` + `docs/decisions/` 를 쓰므로 그 위치에 반영했다(bts-domain SKILL.md §Step 3 매핑 표).
질문은 3건으로 수렴 — 나머지는 코드 탐색으로 답해 Maxi 부담을 줄였다(스킬 지시 "코드로 답할 수 있으면 코드로").

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
