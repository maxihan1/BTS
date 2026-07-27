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

## 스펙

전체 스펙. [docs/specs/2026-07-27-workflow-scheme-contract-align.md](../specs/2026-07-27-workflow-scheme-contract-align.md)

**핵심 3줄.**
- 응답 8 endpoint 중 7 + 요청 1 이 어긋나 스킴 관리·배정 화면이 실서버에서 동작할 수 없다 (FR 불변 131, FR-WF-02 스펙 준수 복원)
- 정본 어휘 `key`+`isStandard` 로 양쪽 수렴 + Zod 를 형태별로 분리 + `mappings[].isDefault` 는 백엔드가 명시 산출
- MSW 픽스처를 백엔드 형태로 바꿔 프론트 초록이 처음으로 실제 계약을 증명하게 한다

**시나리오 6건 (S1~S6).** S1·S5 는 화면 자체가 에러. **S3·S4·S5 는 「서버는 성공했는데 화면이 실패로
표시하고 롤백」** — 사용자가 실패로 인식하나 서버에는 반영돼 있다. S6 은 신규 봉인 시나리오.

**FR C1~C9 / NFR N1~N6 / EC-1~EC-12 / 완료기준 A1~A11.**

**spec 단계 실측 판정 8건** (D-Q1~D-Q8). 재조사 불요.

| 판정 | 결론 |
|---|---|
| D-Q1 변환 계층 | 부재 3축 확정. **★백엔드 테스트 34건(skipped 0)이 `$.data.key` 를 단정하고 `schemeKey`·`mappings[].isDefault` 단정은 0건** — 두 진영 테스트가 서로 다른 필드명을 단정하며 둘 다 초록 |
| D-Q2 create/update 카운트 | 화면 미사용 (반환값 무시 + invalidate) → 스키마 분리로 끝 |
| D-Q3 `name` nullability | 잠복. 유일 호출부가 항상 둘 다 보냄 → **비용 0 으로 타입 봉인, 범위 포함(C8)** |
| D-Q4 PUT 배정 응답 | 미사용이나 파싱 실패가 서버 성공을 화면 실패로 만든다. GET·PUT 이 캐시 키 공유 → 캐시 타입은 GET 기준 |
| D-Q5 봉인 위치 | 2축 (픽스처 백엔드형태 + 스냅샷 대조). **남은 구멍까지 기록** |
| D-Q6 컴파일 가능성 | **DTO 만 개명해도 컴파일된다** — `src/main` 에서 DTO `isDefault` 를 읽는 코드 0건 ⇒ ADR D2 실현 가능 |
| D-Q7 에러 경로 | **★비-2xx 는 Zod 를 우회한다**(`client.ts:128-131`) ⇒ 403/404/409 초록은 계약 증거가 아니다. #314 의 403 회귀 테스트도 이 PR 의 증거로 쓸 수 없다 |
| D-Q8 낙관적 업데이트 | **★구현 블로커.** 3지점이 캐시에 직접 객체를 쓰며 그중 배정 훅의 `projectKey` 는 새 캐시 타입에 존재하지 않는다 → C9 로 규칙화 |

## Brainstorming Check

✅ **통과 (2회 iteration — gap 10건 발견 후 전량 보강).**

`superpowers:brainstorming` 전체 흐름은 Phase B 규약("재작성 금지, gap 만 보고")과 맞지 않아
**체크리스트 7번 Spec Self-Review 절만** 적용했다(#314 선례). 상세 표는 스펙 §Brainstorming Check.

**❓ Brainstorming 발견 — 중요 3건.**
1. **낙관적 업데이트 충돌(gap 4)** — 구현 블로커. plan task 분해에서 훅 3지점을 **명시 task** 로 잡아야 한다
2. **에러 경로가 Zod 우회(gap 2)** — "403 테스트 초록"을 회귀 없음의 근거로 쓰면 안 된다. codereview 단계에 인계
3. **A10 을 MSW 로 하면 무의미(gap 7)** — 계약 증거는 A9(조립 부팅), A10 은 시각 회귀 전용

**내부 불일치 1건 교정(gap 8)** — 스펙 본문이 "실서버와 통신한 적 없다"를 단정형으로 썼으나 ADR
잔여위험 1 은 미관측이라 했다. 본문에 주장 강도 구분 박스를 넣고 A9-② (수정 전 실패 재현)로
관측 승격 경로를 만들었다. **#314 가 다친 지점("이미 동작 중"을 관측 없이 근거로 씀)의 역방향.**

## Plan

**Goal.** 워크플로우 스킴의 프론트 Zod ↔ 백엔드 DTO 계약을 정본 어휘(`key`+`isStandard`)로 정렬해
스킴 관리·배정 화면이 실서버에서 동작하게 하고, 다시 어긋나면 깨지는 봉인을 남긴다.

**Architecture.** 계약을 **하나의 정본 파일**(백엔드가 생성하는 스냅샷 JSON)로 만들고 양쪽이 그것을
대조한다. 백엔드는 뷰 레이어 DTO 3파일만 바꾸고(도메인·DB 불변), 프론트는 Zod 를 응답 형태별로
분리하며 MSW 픽스처는 **자체 인터페이스 선언을 버리고 Zod 추론 타입을 참조**해 drift 근원을 없앤다.

**Tech Stack.** Kotlin/Spring MockMvc · Jackson · Zod v4 · TanStack Query v5 · MSW v2 · vitest · Playwright

### 추가 실측 (plan 단계에서 확정)

- **`WorkflowSchemeController.kt` 의 `isDefault` 1참조는 KDoc 주석(`:148`)이다.** 코드 참조 0건.
  ⇒ 백엔드 코드 변경은 **2파일 12참조** + KDoc 1줄. (메모리 [[global-advice-instance-token-leak-done]] 의
  "넓은 grep 이 KDoc 포함" 함정 — 개수를 그대로 쓰지 않았다)
- **★계약이 3곳에 중복 선언돼 있다.** 백엔드 DTO · 프론트 Zod · **`scheme-fixtures.ts` 가 자체
  `interface` 4개**(`SchemeSummaryResponse`·`SchemeMappingResponse`·`SchemeDetailResponse`·`AssignmentResponse`,
  `:4-32`)를 프론트 어휘로 손수 선언한다. 이름이 Zod 추론 타입과 **충돌**하기까지 한다.
  ⇒ 픽스처가 정본을 참조하게 바꾸는 것이 학습 2026-05-23 「fixture 옵션 B 패턴 — drift 본질 차단」이다.
- **`SchemeIssueTypeMapping`**(`:29-34`) = `{id: Long?, schemeId: WorkflowSchemeId, issueTypeId: IssueTypeId?, workflowId: UUID, createdAt: Instant}`
- **`WorkflowSchemeControllerTest.kt:739-740` 이 `MappingResponseDetail` 을 직접 생성**한다.
  필드 추가 시 이 헬퍼 동반 수정 필수(기본값 주지 않는다 — 명시 강제).

---

### Task 1. 계약 스냅샷 기전 신설 — **조립 컨텍스트에서 생성** (리뷰 발견 1 반영)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/contract/WorkflowSchemeContractSnapshotTest.kt`, `docs/contracts/workflow-schemes.snapshot.json`]
- depends-on: []

> 🔴 **리뷰 발견 1 (P1) 반영 — 슬라이스가 아니라 조립에서 만든다.**
> `WorkflowSchemeControllerTest` 는 `:80 @EnableWebMvc` + `:108 ObjectMapper().registerKotlinModule()`
> (JavaTimeModule 부재)이고, 이번에 다루는 `AssignmentResponse` 가 `assignedAt: Instant` ·
> `projectId`/`assignedBy: UUID` 를 갖는다. 메모리 [[enablewebmvc-slice-localdate-array-serialization]] 이
> 이 저장소에서 이 설정으로 `LocalDate` 가 배열로 나간 사고를 기록한다.
> 슬라이스에서 만들면 **봉인이 틀린 계약을 박제**한다.
> ⇒ 위치를 `:app` 테스트로, 부팅 기반을 `ProdAssemblyHttpTestBase`(또는 `:app:test` 의 기존 조립 베이스)로 둔다.
> **선행 조건.** dev postgres 5433 가동(`docker ps --filter name=bts-postgres-dev`).
> 기존 조립 테스트의 컨텍스트 설정을 발명하지 말고 복사할 것.

**★실현 가능성 실측 완료 (2026-07-27, impl 착수 직전).** 조립에서 스킴 endpoint 를 호출하려면
#314 가 붙인 `MANAGE_SCHEME`/Global 게이트를 통과하는 **실제 인증**이 필요하고, prod 프로파일이라
`AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)가 꺼져 있어 **실 권한 데이터**가 필요하다.
그 조리법이 이미 있다 — **`ProjectCreatePermissionProdBootTest.kt` 를 복사한다.**

| 필요한 것 | 기존 헬퍼 | 위치 |
|---|---|---|
| 실 Tomcat + prod 프로파일 + JWT 키 | `ProdAssemblyHttpTestBase` 상속 | `app/src/test/.../ProdAssemblyHttpTestBase.kt` |
| 사용자 시드 | `seedUser(id, username)` — `INSERT INTO users (id, username)` | `ProjectCreatePermissionProdBootTest.kt:166-171` |
| 토큰 | `seedPat(userId, rawToken)` — `token_hash` = SHA-256(raw) | `:173-174` |
| SYSTEM_ADMIN | `seedSystemAdmin(userId)` | `:96` |
| 전역 권한 grant | `seedGrant(userId)` | `:89` |

> ⚠️ **★PAT Bearer 를 써야 하는 이유 (`:50-51` 이 명시).** `Authorization: Bearer pat_…` 는
> ① 중앙 `SecurityConfig` 의 `patBearerMatcher` 로 **CSRF-ignore** 되고
> ② **비-Jwt principal 이라 `MfaEnrollmentGateFilter` 를 우회**한다.
> SYSTEM_ADMIN 은 MFA 미등록 시 그 게이트에서 **403** 이므로, JWT 로 하면 관리자 endpoint 를
> 조립에서 관측할 수 없다. 이 우회를 모르고 짜면 전 endpoint 가 403 으로 나와 "파손"으로 오판한다.
>
> ⚠️ **반대 함정도 있다.** 메모리 [[authenticated-error-path-token-leak-done]] 이
> *"PAT 측정 = 거짓음성(saveContext 미호출)"* 을 기록한다. 그것은 `/error` 경로 측정에 한정된 이야기이나,
> PAT 경로가 JWT 경로와 **다른 필터 조합**을 탄다는 사실 자체는 유효하다. 스냅샷은 **본문 형태**만
> 취하므로 영향 없으나, 이 스냅샷을 "인증 동작의 증거"로는 쓰지 말 것.

**추가 시드 필요분 (스킴 8 endpoint 용).** 위 4종 외에 ① 프로젝트 1개 + 그 프로젝트의 `PROJECT_ADMIN`
멤버십(=`ASSIGN_SCHEME`/Project 통과용) ② 표준 스킴은 `V201` 시드로 이미 존재 ③ 매핑추가·생성·수정은
테스트가 직접 만든다. #314 잔여위험 5 주의 — `MANAGE_WORKFLOW` 는 **기본 권한 스킴**의 `PROJECT_ADMIN`
에만 시드돼 있으므로 프로젝트를 기본 스킴에 두어야 배정 endpoint 가 통과한다.

> ⚠️ **★★두 번째 함정 — 베이스의 `rest`(TestRestTemplate)를 쓰면 안 된다**
> (`ProjectCreatePermissionProdBootTest.kt:67-70` 이 실측으로 기록).
> `:modules:app` 에 Apache HttpComponents 5 가 없어 `TestRestTemplate` 이 `HttpURLConnection` 으로
> 떨어지고, **본문 있는 요청이 401/403 을 받으면 본문을 못 읽는다.**
> T1 은 8 endpoint 의 **응답 본문**을 모으는 것이 목적이므로 이 함정에 정면으로 걸린다 —
> 시드가 하나라도 틀려 403 이 나면 본문이 비어 "파손"으로 오진한다.
> ⇒ **JDK 내장 `java.net.http.HttpClient` 로 원 응답을 직접 관측한다**
> (`GitWebhookInboundPermitAllTest` KDoc 이 같은 결론). `@LocalServerPort` 로 포트를 받고
> `@SpringBootTest`·`@ActiveProfiles`·`@DynamicPropertySource` 는 **자체 선언하지 않는다**
> (베이스만 상속 — 컨텍스트 캐시 키가 갈리면 9-BC prod 컨텍스트가 2회 부팅되고
> `@Scheduled` 워커가 2벌이 같은 pgmq 큐를 동시 폴링한다, 베이스 KDoc `:37-43`).
>
> **정리 규약.** `@BeforeEach cleanup()` + `@AfterEach cleanup()` — 공유 dev postgres(5433)에
> 잔여물을 남기지 않는다(선례 `:83-102`).

**왜 이것이 1번인가.** 이 파일이 이 PR 의 **유일한 계약 정본**이 된다. 프론트는 이것을 파싱해 검증하고,
백엔드는 이것이 stale 하면 실패한다. 양방향이 닫힌다.

> 🔧 **impl 정정 (2026-07-27) — 아래 RED/GREEN 은 리뷰 발견 1 이전 슬라이스 판본이었다.**
> 결정표의 「리뷰 발견1 = 1A(조립, 슬라이스 금지)」가 정본이므로 조립 판본으로 교체한다.
> 교체 전 문단은 `MockMvc`·`package com.bts.workflow.scheme.web`·`@WebMvcTest 설정 재사용`·
> `:modules:project-workflow:test` 를 지시해 **함정 1·2 와 정면 충돌**했다(메타 `files`·인용문·검증
> 블록은 이미 조립을 가리키고 있었다 — 문단만 갱신 누락).

**D-6 (impl 결정) — 스냅샷은 값이 아니라 「값의 종류」를 고정한다 (Maxi 확정).**
조립 응답에는 자동증가 `id`·실행 시각·랜덤 UUID·환경마다 다른 목록 길이가 섞여 있어 원본 값을 그대로
박제하면 **문자열 동등 비교가 매 실행 실패**한다(스냅샷 기전 자체가 성립 불가). 따라서 leaf 값을
타입별 표준값으로 치환한 뒤 비교한다.

| 원본 | 스냅샷 |
|---|---|
| 숫자 (id·카운트) | `0` |
| 불리언 | `true` |
| UUID 문자열 | `"00000000-0000-4000-8000-000000000000"` |
| ISO-8601 instant 문자열 | `"2026-01-01T00:00:00Z"` |
| 그 외 문자열 | `"string"` |
| `null` | `null` (그대로 — nullability 가 계약이다) |
| 배열 | 원소 정규화 후 **중복 제거 + 정렬** (행 수 비의존) |
| 객체 | 키 **사전순 정렬** |

**타입이 보존되므로 Task 2 의 Zod `.parse()` 설계는 그대로 유효하다** — 필드명 누락·오타·추가는
잡히고, 값 변동은 안 잡힌다(의도). `assignedAt` 이 숫자로 나가면 `"2026-01-01T00:00:00Z"` 가 아니라
`0` 으로 찍혀 **리뷰 발견 1 가드가 스냅샷 본문에서 직접 드러난다**. 추가로 원본 응답 단계에서
`assignedAt` 이 JSON **문자열**인지 단정하는 전용 테스트를 함께 둔다(grep 검증을 단정으로 승격).

**RED**. `WorkflowSchemeContractSnapshotTest.kt` 신설 — **`:modules:app` 조립 테스트**로,
`ProdAssemblyHttpTestBase` 를 **상속만** 하고 JDK `java.net.http.HttpClient` + PAT Bearer 로
8 endpoint 를 실제 HTTP 왕복시켜 응답 본문을 모은다(함정 1·2·3). 정규화 후
`docs/contracts/workflow-schemes.snapshot.json` 과 **문자열 동등** 비교한다.

```kotlin
// 워크플로우 스킴 8 endpoint 응답을 조립 컨텍스트에서 계약 스냅샷으로 고정해 프론트 Zod drift 를 차단
package com.bts.app.contract

class WorkflowSchemeContractSnapshotTest : ProdAssemblyHttpTestBase() {
    // @LocalServerPort port + @Autowired JdbcTemplate.
    // @SpringBootTest·@ActiveProfiles·@DynamicPropertySource 자체 선언 금지(베이스 KDoc :37-43).

    @Test
    fun `계약 스냅샷이 실제 조립 응답과 일치한다`() {
        val raw = collectRawResponses()          // 8회 실 HTTP. 상태코드 선단정(시드 오류를 파손으로 오진 차단)
        val pretty = prettyPrint(canonical(raw)) // D-6 정규화 + 키 정렬 + 배열 중복제거
        if (System.getProperty("contract.snapshot.update") == "true") { /* 파일 생성 */ }
        assertThat(SNAPSHOT_PATH).exists()       // 부재 = 실패 (RED)
        assertThat(SNAPSHOT_PATH.readText()).isEqualTo(pretty)
    }

    @Test
    fun `배정 응답의 assignedAt 이 ISO-8601 문자열이다 (리뷰 발견 1 회귀 가드)`() { /* 원본 노드 타입 단정 */ }
}
```

**스냅샷 경로 해석**. `Path.of("..")` 상대경로는 금지 — Gradle 테스트 CWD 가 `backend/modules/app`
이라 `..` 는 repo 루트가 아니다(리뷰가 Task 2 에 적용한 「repo 루트 기준」을 여기에도 적용).
CWD 에서 위로 올라가며 `docs/` 디렉토리 + `CLAUDE.md` 를 동시에 가진 디렉토리를 repo 루트로 판정한다.

**시드 (전부 실측 확인)**. 사용자 1 + PAT + `system_role_assignments`(SYSTEM_ADMIN — Global 축은
`isSystemAdmin` 단독 판정) + 프로젝트 `WFSNAP` + `PROJECT_ADMIN` 멤버십.
`project_permission_scheme` 매핑은 **넣지 않는다** — 없으면 `permission_schemes.is_default=TRUE` 로
폴백하고 그 기본 스킴이 `PROJECT_ADMIN → MANAGE_WORKFLOW` 를 이미 시드하고 있다(#314 잔여위험 5 해소).
정리 순서는 **배정 → 스킴 → 프로젝트 → 사용자** (배정이 `workflow_schemes` 를 `ON DELETE RESTRICT` 로
잡고 있어 역순이면 FK 위반).

**예상 실패 메시지**. `계약 스냅샷 부재` (파일이 아직 없다)

**GREEN**.
```bash
cd backend && ./gradlew :modules:app:test \
  --tests "*WorkflowSchemeContractSnapshotTest*" -Dcontract.snapshot.update=true
```
생성된 `docs/contracts/workflow-schemes.snapshot.json` 을 커밋한다. 이 시점 파일에는 **파손 상태**
(`key`·`isDefault`, `mappings[].isDefault` 부재, create/update 응답에 카운트 부재)가 그대로 담긴다 —
그것이 **A9-② 의 증거**다.

**REFACTOR**. 8 endpoint 호출 헬퍼를 `private fun` 으로 분리 + KDoc 에
"이 파일이 프론트 Zod 의 정본이다. 손으로 편집하지 말고 `-Dcontract.snapshot.update=true` 로 재생성한다" 명시.

**검증**.
```bash
docker ps --filter name=bts-postgres-dev --format '{{.Names}} {{.Status}}'   # 5433 가동 선확인
cd backend && ./gradlew :modules:app:test --tests "*WorkflowSchemeContractSnapshotTest*"
# XML 실측 — BUILD SUCCESSFUL 만 믿지 않는다 (메모리 gradle-batched-task-partial-test-run)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  modules/app/build/test-results/test/TEST-*ContractSnapshotTest.xml
# 발견 1 회귀 가드 — Instant 가 ISO 문자열로 담겼는지 눈으로 확인 (D-6 정규화 후 값)
grep -o '"assignedAt"[^,]*' ../docs/contracts/workflow-schemes.snapshot.json
# 기대. "2026-01-01T00:00:00Z" (정규화된 instant). 0 이나 배열이면 조립이 Instant 를 숫자/배열로
# 내보냈다는 뜻 → 중단하고 보고. 단정 판본은 `배정 응답의 assignedAt 이 ISO-8601 문자열이다` 테스트.
```

---

### Task 2. 프론트 스냅샷 파싱 테스트 → 현행 7종 파손 RED 실증 (A9-②)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/__tests__/workflow-schemes.contract.test.ts`]
- depends-on: [1]

**RED**. Task 1 이 커밋한 스냅샷을 **현행 Zod** 로 파싱한다. 7건이 실패해야 한다 — 그 실패가 파손의 증거다.

> ✅ **A9-② 증거 확보 (2026-07-27 실측)** — 스냅샷을 **현행** Zod 로 파싱한 결과 **파손 7/8 · 정합 1/8**.
> 임시 측정 테스트로 1회 실행 후 폐기했다(영구 테스트는 아래 코드블록 = Task 4 이후 초록이 되는 판본).
> 같은 실행에서 프론트 전체 **516 files / 8,045 tests 전건 통과** — 측정이 기존 스위트를 건드리지 않았다.
>
> | endpoint | 현행 스키마 | 실패 필드 |
> |---|---|---|
> | GET `/workflow-schemes` | `schemeResponseSchema` | `schemeKey`·`isStandard`·`description`(null 변형) |
> | GET `/workflow-schemes/{key}` | `schemeDetailResponseSchema` | `schemeKey`·`isStandard`·`mappings[].id`(too_small)·`mappings[].isDefault` |
> | POST `/workflow-schemes` | `schemeResponseSchema` | `schemeKey`·`isStandard`·`usedByProjectsCount`·`mappingsCount` |
> | PUT `/workflow-schemes/{key}` | `schemeResponseSchema` | 위 4종 + `description` |
> | POST `/workflow-schemes/{key}/mappings` | `mappingResponseSchema` | `id`(too_small)·`issueTypeKey`·`issueTypeName`·`workflowKey`·`workflowName`·`isDefault` |
> | GET `/projects/{k}/workflow-scheme` | `assignmentResponseSchema` | `projectKey`·`schemeKey`·`schemeName` |
> | PUT `/projects/{k}/workflow-scheme` | `assignmentResponseSchema` | `projectKey`·`schemeKey`·`schemeName` |
> | GET `/projects/{k}/assignable-workflow-schemes` | `assignableSchemeResponseSchema` | **없음 (정합)** |
>
> **★근본 원인이 실측으로 확정됐다.** `schemeResponseSchema` 가 **3 endpoint**(목록·생성·수정)에,
> `assignmentResponseSchema` 가 **2 endpoint**(배정 조회·배정)에 재사용되는데 각 짝의 백엔드 형태가
> 서로 다르다. 특히 배정 2종은 `SchemeResponse` ↔ `AssignmentResponse` 로 **필드가 하나도 안 겹친다**.
> `mappings[].id:too_small` 은 어휘 문제가 아니라 **정규화 id `0` 이 `z.number().int().positive()` 에
> 걸린 것**이므로 계약 파손이 아니다 — Task 4 에서 `nonnegative()` 로 완화하거나 스냅샷 표준값을
> 1 로 바꾼다(전자 권장. 백엔드 BIGSERIAL 은 1부터라 실제로 0 이 오지 않지만, 계약 테스트는 값이
> 아니라 형태를 봐야 하므로 값 제약을 계약 판정에 섞지 않는다).
> 유일 정합인 assignable 은 이미 `.transform()` 으로 `key`→`schemeKey` 정규화를 하고 있던 endpoint 다.

```ts
// 백엔드가 생성한 계약 스냅샷을 프론트 Zod 로 파싱해 계약 drift 를 차단하는 테스트
import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { z } from 'zod'
import {
  schemeListItemSchema, schemeDetailSchema, schemeMutationResultSchema,
  assignedSchemeSchema, assignmentRecordSchema, mappingCreatedSchema,
} from '../workflow-schemes.types'

const SNAPSHOT = resolve(__dirname, '../../../../../docs/contracts/workflow-schemes.snapshot.json')

const CASES: Array<[string, z.ZodTypeAny]> = [
  ['GET /api/v1/workflow-schemes', z.object({ data: z.array(schemeListItemSchema) }).strict()],
  ['GET /api/v1/workflow-schemes/{key}', z.object({ data: schemeDetailSchema }).strict()],
  ['POST /api/v1/workflow-schemes', z.object({ data: schemeMutationResultSchema }).strict()],
  ['PUT /api/v1/workflow-schemes/{key}', z.object({ data: schemeMutationResultSchema }).strict()],
  ['GET /api/v1/projects/{k}/workflow-scheme', z.object({ data: assignedSchemeSchema }).strict()],
  ['PUT /api/v1/projects/{k}/workflow-scheme', z.object({ data: assignmentRecordSchema }).strict()],
  ['POST /api/v1/workflow-schemes/{key}/mappings', z.object({ data: mappingCreatedSchema }).strict()],
  ['GET /api/v1/projects/{k}/assignable-workflow-schemes', z.object({ data: z.array(assignedSchemeSchema) }).strict()],
]

describe('워크플로우 스킴 계약 스냅샷', () => {
  it('스냅샷 파일이 존재한다', () => {
    // skip 금지 — 파일이 없으면 실패다 (vacuous 통과 차단)
    expect(existsSync(SNAPSHOT)).toBe(true)
  })

  const snapshot = existsSync(SNAPSHOT)
    ? (JSON.parse(readFileSync(SNAPSHOT, 'utf-8')) as Record<string, unknown>)
    : {}

  it.each(CASES)('%s 응답이 Zod 와 정합한다', (endpoint, schema) => {
    expect(Object.keys(snapshot)).toContain(endpoint)   // 항목 누락도 실패
    expect(() => schema.parse(snapshot[endpoint])).not.toThrow()
  })
})
```

**예상 실패 메시지**. `schemeListItemSchema` 등 신규 스키마 미존재 → import 에러. 스키마를 아직
만들지 않았으므로 **Task 4 이후에 초록**이 된다. 이 task 는 **`test:` 커밋으로 RED 를 남기는 것이 목적**이다.

**GREEN (이 task 범위)**. 없다. 의도된 RED 다. 실패 출력을 그대로 캡처해 PR 본문에 붙인다
(= 완료기준 A9-② 「수정 전 실패 재현」의 증거).

**REFACTOR**. `.strict()` 를 쓰는 이유를 주석으로 명시 — "백엔드가 필드를 **추가**해도 걸리게 하려면
strict 가 필요하다. 축2 가 삭제만 잡고 추가를 놓치는 구멍을 막는다"(spec §D-Q5).

**검증**.
```bash
cd apps/web && npx vitest run src/api/__tests__/workflow-schemes.contract.test.ts
# 기대. FAIL (신규 스키마 미존재). 출력 저장 → PR 본문
```

---

### Task 3. 백엔드 뷰 레이어 어휘 정렬 + `MappingResponseDetail.isDefault` 신설

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/dto/WorkflowSchemeDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeControllerTest.kt`, `docs/contracts/workflow-schemes.snapshot.json`]
- depends-on: [1]

**RED — EC-4 회귀 테스트가 먼저다.** `WorkflowSchemeControllerTest.kt` 에 추가.

```kotlin
@Test
fun `issueTypeId 가 있으나 cross-BC 조회 실패면 issueTypeKey 는 null 이지만 isDefault 는 false 다`() {
    val mapping = SchemeIssueTypeMapping(
        id = 1L,
        schemeId = WorkflowSchemeId(10L),
        issueTypeId = IssueTypeId(99L),          // 이슈타입이 실재한다
        workflowId = UUID.randomUUID(),
        createdAt = Instant.parse("2026-07-27T00:00:00Z"),
    )

    // issueTypeRef = null → cross-BC 조회 실패 상황 (기본 매핑이 아니다)
    val detail = MappingResponseDetail.from(mapping, issueTypeRef = null, workflow = someWorkflow())

    assertThat(detail.issueTypeKey).isNull()
    assertThat(detail.isDefault).isFalse()   // ★ 핵심 — issueTypeRef 가 아니라 issueTypeId 로 판정해야 통과
}

@Test
fun `issueTypeId 가 null 이면 isDefault 는 true 다`() {
    val mapping = SchemeIssueTypeMapping(
        id = 2L, schemeId = WorkflowSchemeId(10L), issueTypeId = null,
        workflowId = UUID.randomUUID(), createdAt = Instant.parse("2026-07-27T00:00:00Z"),
    )
    val detail = MappingResponseDetail.from(mapping, issueTypeRef = null, workflow = someWorkflow())
    assertThat(detail.isDefault).isTrue()
}
```

**예상 실패 메시지**. `No value passed for parameter 'isDefault'` 또는 `Unresolved reference: isDefault` (컴파일 실패)

**GREEN**. 3파일 편집.

```kotlin
// WorkflowSchemeDto.kt — MappingResponseDetail 에 필드 추가 (기본값 주지 않는다)
data class MappingResponseDetail(
    val id: Long,
    val issueTypeKey: String?,
    val issueTypeName: String?,
    val workflowKey: String,
    val workflowName: String,
    /** 기본 매핑 여부. issueTypeId 가 null 인 매핑이 기본 매핑이다. issueTypeRef 로 판정하면 cross-BC 조회 실패와 구분되지 않는다. */
    val isDefault: Boolean,
) {
    companion object {
        fun from(mapping: SchemeIssueTypeMapping, issueTypeRef: IssueTypeRef?, workflow: Workflow) =
            MappingResponseDetail(
                id = requireNotNull(mapping.id) { "SchemeIssueTypeMapping.id must not be null" },
                issueTypeKey = issueTypeRef?.key,
                issueTypeName = issueTypeRef?.name,
                workflowKey = workflow.key,
                workflowName = workflow.name,
                isDefault = mapping.issueTypeId == null,   // ★ 도메인 필드로 판정
            )
    }
}

// WorkflowSchemeDto.kt — WorkflowSchemeDetailResponse / WorkflowSchemeResponse
// `val isDefault: Boolean` → `val isStandard: Boolean`
// 두 companion from() 의 `isDefault = scheme.isDefault` → `isStandard = scheme.isDefault`
// (도메인은 isDefault 그대로 — ADR D2)

// ProjectWorkflowSchemeController.kt — 내부 SchemeResponse (`:198-204`)
// `val isDefault: Boolean` → `val isStandard: Boolean`
// `private fun WorkflowScheme.toResponse()` 의 `isDefault = isDefault` → `isStandard = isDefault`
```

동반 수정 — `WorkflowSchemeControllerTest.kt:739-740` 의 `MappingResponseDetail(...)` 헬퍼에
`isDefault = <해당 매핑의 issueTypeId == null>` 인자 추가. `WorkflowSchemeController.kt:148` KDoc 의
"표준 스킴(isDefault=true)" → "표준 스킴(`isStandard=true`, DB 컬럼 `is_default`)".

**REFACTOR**. 스냅샷 재생성 + 커밋.
```bash
cd backend && ./gradlew :modules:project-workflow:test \
  --tests "*WorkflowSchemeContractSnapshotTest*" -Dcontract.snapshot.update=true
```

**검증**.
```bash
cd backend && ./gradlew :modules:project-workflow:test
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  modules/project-workflow/build/test-results/test/TEST-*.xml
# 기준선 553 대비 실패 0 · skipped 0
cd backend && ./gradlew :modules:project-workflow:ktlintCheck :modules:project-workflow:detekt --rerun-tasks
```

---

### Task 4. 프론트 Zod 를 응답 형태별로 분리 + 요청 타입 정정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/workflow-schemes.types.ts`, `apps/web/src/api/workflow-schemes.ts`, `apps/web/src/api/__tests__/workflow-schemes.test.ts`]
- depends-on: [2, 3]

**RED**. Task 2 의 계약 테스트가 이미 RED 다. 여기서는 그것을 GREEN 으로 만든다
(추가 RED 를 새로 쓰지 않는다 — 같은 검증을 두 벌 만들지 않음).

**GREEN**. `workflow-schemes.types.ts` 를 아래 스키마로 재구성한다. `schemeResponseSchema` ·
`assignmentResponseSchema` · `mappingResponseSchema` 3장은 **삭제**한다.

> ⚠️ **선언 순서 (F4 반영 후 필수).** `const` 는 호이스팅되지 않으므로 아래 순서를 지킬 것 —
> `schemeCoreSchema` → `assignedSchemeSchema` → `schemeMutationResultSchema` →
> **`mappingDetailSchema`** → `schemeListItemSchema`(mappingDetailSchema 참조) →
> `schemeDetailSchema` → `mappingCreatedSchema` → `assignmentRecordSchema`.
> 아래 코드 블록은 개념 순서로 적혀 있으니 파일에는 이 순서로 배치한다(그대로 옮기면 TDZ 에러).

```ts
/** 스킴 공통 필드 — 목록·상세·배정조회·배정후보가 공유하는 최소 집합 */
const schemeCoreSchema = z.object({
  id: z.number().int().nullable(),
  key: z.string().min(1),
  name: z.string().min(1),
  description: z.string().nullable(),
  isStandard: z.boolean(),
})

/** GET /api/v1/projects/{k}/workflow-scheme · GET .../assignable-workflow-schemes — 둘 다 백엔드 SchemeResponse */
export const assignedSchemeSchema = schemeCoreSchema

/** POST · PUT /api/v1/workflow-schemes — 카운트 없음 (백엔드 WorkflowSchemeResponse) */
export const schemeMutationResultSchema = schemeCoreSchema.extend({
  id: z.number().int(),                 // 이 응답에서는 non-null
  createdAt: z.string(),
  updatedAt: z.string(),
})

/** GET /api/v1/workflow-schemes — 카운트 포함 (백엔드 WorkflowSchemeDetailResponse, mappings 는 빈 배열) */
export const schemeListItemSchema = schemeMutationResultSchema.extend({
  usedByProjectsCount: z.number().int().nonnegative(),
  mappingsCount: z.number().int().nonnegative(),
  // 리뷰 F4 반영 — z.unknown() 이면 목록 endpoint 에서 봉인이 무력해진다.
  // 목록은 빈 배열이지만 스키마는 실제 원소 형태를 요구한다(빈 배열은 그대로 통과).
  mappings: z.array(mappingDetailSchema),
})

/** 매핑 상세 — 상세 응답 안의 원소 (백엔드 MappingResponseDetail) */
export const mappingDetailSchema = z.object({
  id: z.number().int().positive(),
  issueTypeKey: z.string().min(1).nullable(),
  issueTypeName: z.string().nullable(),
  workflowKey: z.string().min(1),
  workflowName: z.string().min(1),
  isDefault: z.boolean(),
})

/** GET /api/v1/workflow-schemes/{key} */
export const schemeDetailSchema = schemeListItemSchema.extend({
  mappings: z.array(mappingDetailSchema),
})

/** POST /{key}/mappings — 백엔드 MappingResponse (내부 PK 형태, 상세와 다르다) */
export const mappingCreatedSchema = z.object({
  id: z.number().int().positive(),
  schemeId: z.number().int().positive(),
  issueTypeId: z.number().int().nullable(),
  workflowId: z.string().min(1),
  createdAt: z.string(),
})

/** PUT /api/v1/projects/{k}/workflow-scheme — 배정 이력. 파싱만 하고 소비하지 않는다 */
export const assignmentRecordSchema = z.object({
  projectId: z.string().min(1),
  workflowSchemeId: z.number().int().positive(),
  assignedAt: z.string(),
  assignedBy: z.string().min(1),
})
```

요청 타입 정정.
```ts
/** 스킴 생성 입력 — 백엔드 CreateWorkflowSchemeRequest{key,name,description?} 와 1:1 */
export interface CreateSchemeInput {
  key: string            // was schemeKey
  name: string
  description?: string
}

/** 스킴 수정 입력 — 백엔드 UpdateWorkflowSchemeRequest.name 은 non-null 이다 */
export interface UpdateSchemeInput {
  name: string           // was name?
  description?: string
}
```

`workflow-schemes.ts` 배선 — 각 함수의 스키마를 위 표대로 교체하고
`assignableSchemeResponseSchema` 의 `.transform()` 을 **삭제**(`assignedSchemeSchema` 사용).
`fetchProjectAssignment` 의 **404 → null 은 유지**(EC-1).

**REFACTOR**. 파일 상단에 "각 스키마 = endpoint 1개 또는 **형태가 동일한** endpoint 묶음" 주석 +
어느 백엔드 DTO 에 대응하는지 각 스키마 KDoc 에 명시.

**검증**.
```bash
cd apps/web && npx vitest run src/api/__tests__/workflow-schemes.contract.test.ts   # 기대. PASS (8/8)
cd apps/web && npx tsc --noEmit -p tsconfig.app.json                                 # 파이프 없이
```

---

### Task 5. MSW 픽스처·핸들러를 백엔드 형태로 + 자체 인터페이스 제거 (진짜 RED)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/scheme-fixtures.ts`, `apps/web/src/mocks/scheme-handlers.ts`, `apps/web/src/mocks/__tests__/scheme-handlers.test.ts`]
- depends-on: [4]

**RED**. 픽스처를 백엔드 형태로 바꾸면 이를 소비하는 기존 프론트 테스트가 **대량 실패**한다.
그 실패가 이 작업의 본체다.

**GREEN**.
1. `scheme-fixtures.ts` 의 자체 `interface` 4개(`:4-32`)를 **삭제**하고 Zod 추론 타입을 import 한다.
   ```ts
   import type { z } from 'zod'
   import { schemeListItemSchema, schemeDetailSchema, mappingDetailSchema, assignedSchemeSchema }
     from '@/api/workflow-schemes.types'

   type SchemeListItem = z.infer<typeof schemeListItemSchema>
   type SchemeDetail = z.infer<typeof schemeDetailSchema>
   type MappingDetail = z.infer<typeof mappingDetailSchema>
   type AssignedScheme = z.infer<typeof assignedSchemeSchema>
   ```
   ⇒ **drift 본질 차단** — 픽스처가 Zod 를 벗어나면 타입 에러다 (학습 2026-05-23 옵션 B 패턴).
2. `makeScheme` 등 helper 의 필드를 `schemeKey`→`key`, `isStandard` 유지, `description` nullable 로 조정.
   `id`·`createdAt`·`updatedAt` 을 추가한다(백엔드가 보내므로).
3. `scheme-handlers.ts` 의 각 핸들러 반환값을 endpoint 별 실제 형태로 맞춘다 — 특히
   **`POST /{key}/mappings` 는 `mappingCreatedSchema` 형태**(`schemeId`·`issueTypeId`·`workflowId`·`createdAt`),
   **`PUT /projects/{k}/workflow-scheme` 는 `assignmentRecordSchema` 형태**로 바꾼다. 지금 둘 다 프론트 형태다.

**REFACTOR**. 픽스처 파일 L1 주석을
`// 워크플로우 스킴 MSW fixture — 타입은 api/workflow-schemes.types.ts 의 Zod 추론을 참조한다(자체 선언 금지)` 로 갱신.

**검증**.
```bash
cd apps/web && npx vitest run src/mocks src/api                # 픽스처·핸들러 단위
cd apps/web && npx tsc --noEmit -p tsconfig.app.json
```

---

### Task 6. 낙관적 업데이트 3지점 정합 (FR C9)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-workflow-schemes.ts`, `apps/web/src/hooks/use-workflow-scheme-assignment.ts`, `apps/web/src/hooks/__tests__/use-workflow-schemes.test.tsx`, `apps/web/src/hooks/__tests__/use-workflow-scheme-assignment.test.tsx`]
- depends-on: [4]

**왜 별도 task 인가.** 세 지점이 캐시에 **객체를 직접 써 넣고** 그중 하나는 새 타입으로 만들 수 없다
(spec §D-Q8). 뭉개면 타입 에러로 막힌다.

**RED**. 훅 테스트에 낙관적 상태 단정을 추가한다.
```tsx
it('배정 낙관적 업데이트가 조회 응답 타입을 만족한다', async () => {
  // 낙관적 반영 직후 캐시 값이 assignedSchemeSchema 를 통과해야 한다
  const cached = queryClient.getQueryData(['projects', 'ATLAS', 'workflow-scheme'])
  expect(() => assignedSchemeSchema.parse(cached)).not.toThrow()
})
```
**예상 실패 메시지**. `Unrecognized key(s) in object: 'projectKey'` 또는 `Required: id, isStandard`

**GREEN**.
```ts
// use-workflow-scheme-assignment.ts:65-69 — projectKey 제거, 조회 응답 타입 준수
// projectKey 는 queryKey(ASSIGNMENT_KEYS.byProject) 에 이미 있어 값에서 빼도 정보 손실이 없다
const optimistic: AssignedScheme = {
  id: prevAssignment?.id ?? null,
  key: input.schemeKey,
  name: prevAssignment?.name ?? '',        // 서버 응답 대기 (onSettled invalidate 가 교체)
  description: prevAssignment?.description ?? null,
  isStandard: prevAssignment?.isStandard ?? false,
}

// use-workflow-schemes.ts:140 — 목록 낙관적 갱신
prevList.map((s) => (s.key === schemeKey ? { ...s, ...patch } : s))

// use-workflow-schemes.ts:220-227 — 낙관적 매핑은 캐시가 담는 detail 원소 타입을 쓴다
const optimisticMapping: MappingDetail = {
  id: -Date.now(),
  issueTypeKey: input.issueTypeKey,
  issueTypeName: input.issueTypeKey,
  workflowKey: input.workflowKey,
  workflowName: input.workflowKey,
  isDefault: input.issueTypeKey === null,   // input 에서는 모호하지 않다
}
```
`useMutation` 제네릭도 새 타입으로 교체 — `useMutation<SchemeMutationResult, …>` 등.

**REFACTOR**. 각 낙관적 블록에 `// 낙관적 객체는 이 캐시가 담는 타입(조회 응답 타입)을 만족해야 한다 (FR C9)` 주석.

**검증**.
```bash
cd apps/web && npx vitest run src/hooks
cd apps/web && npx tsc --noEmit -p tsconfig.app.json
```

---

### Task 7. 컴포넌트·라우트 어휘 참조 정정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/WorkflowSchemeSidebar.tsx`, `apps/web/src/components/admin/SchemeMetaPanel.tsx`, `apps/web/src/components/admin/MappingTable.tsx`, `apps/web/src/routes/admin.workflow-schemes.tsx`, `apps/web/src/routes/admin.workflow-schemes.new.tsx`, `apps/web/src/routes/admin.workflow-schemes.$schemeKey.tsx`, `apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx`, `apps/web/src/i18n/workflow-scheme-labels.ts`]
- depends-on: [4, 5, 6]

**RED**. Task 5 가 만든 대량 실패가 그대로 RED 다.

**GREEN**. `.schemeKey` → `.key` (응답 객체 프로퍼티 접근 21곳). **URL 경로 변수명·라우트 파일명·함수
인자명의 `schemeKey` 는 바꾸지 않는다** — 그것들은 응답 필드가 아니다. `SchemeMetaPanel.tsx:63` 의
`mutate({ name, description })` 는 `name` 이 이제 필수이므로 그대로 유효하다.
생성 폼(`admin.workflow-schemes.new.tsx`)이 `CreateSchemeInput` 에 `key` 를 넣도록 조정한다.

**REFACTOR**. 판별식 확인 — `grep -rn "\.schemeKey" apps/web/src | grep -v "__tests__"` 가 0 이어야 한다.
남으면 그 지점이 응답 필드인지 경로 변수인지 판정해 기록.

**검증**.
```bash
cd apps/web && npx vitest run                                   # 전체
cd apps/web && npx tsc --noEmit -p tsconfig.app.json
cd apps/web && npx eslint --max-warnings 0 src
```

---

### Task 8. 조립 부팅 실증(A9) + E2E 회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/workflow-scheme.spec.ts`, `docs/plans/2026-07-27-workflow-scheme-contract-align.md`]
- depends-on: [3, 4, 5, 6, 7]

**RED**. E2E 가 스킴 목록·상세·배정 시나리오에서 **작성자·표준 배지·★ 기본 매핑 표시**를 단정하도록
보강한다(현행 E2E 16건은 필드명을 단정하지 않아 이 회귀를 못 잡는다).

**GREEN**. A9 판정식 3개를 실행하고 결과를 plan 에 기록한다.

```bash
# ① 8 endpoint 실응답의 키 집합 ⊇ 프론트 Zod 요구 키 집합
docker ps --filter name=bts-postgres-dev --format '{{.Names}} {{.Status}}'   # 5433 가동 확인
cd backend && ./gradlew :app:test
# ② 수정 전 실패 재현 — Task 2 가 남긴 RED 출력을 증거로 인용 (재실행 불요)
# ③ springdoc 노출 확정
cd backend && ./gradlew :app:test --tests "*OpenApi*" || \
  echo "OpenApi 테스트 부재 — 조립 부팅 후 curl -s localhost:PORT/v3/api-docs | jq '.paths | keys' 로 확인"
```

**REFACTOR**. plan §Plan 메타 아래에 「A9 실증 결과」 표를 추가한다 — 판정식별 통과/미통과 + `/v3/api-docs`
노출 여부. ③이 "노출됨"이면 ADR 잔여위험 3 을 "확정 — 응답 필드명이 문서화된 계약이다"로 갱신한다.

**검증**.
```bash
cd apps/web && npx playwright test e2e/workflow-scheme.spec.ts   # 개수 실측 (positional 필터 삼킴 함정 회피)
```

---

### Task 9. A10 브라우저 눈확인 + 문서 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`TODOS.md`, `docs/decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md`, `docs/plans/2026-07-27-workflow-scheme-contract-align.md`]
- depends-on: [8]

**RED**. 없음 (문서 + 육안 확인 task).

**GREEN**.
1. **A10 브라우저 눈확인** — 스킴 목록·상세·생성·배정 4화면을 열어 렌더 확인.
   ⚠️ **MSW 로 본 것은 계약 증거가 아니다.** 계약은 A9 가 담당하고 A10 은 **시각 회귀** 확인 전용이다.
   확인 항목 — 표준 배지 표시 · ★ 기본 매핑 표시 및 정렬 최상단 · 사용 프로젝트 수/매핑 수 · 배정 드롭다운.
2. **TODOS.md** — 「project-workflow 워크플로우 스킴 프론트↔백엔드 계약 파손」 항목을 **해소 처리**
   (`✅ #<PR번호>` + 실제 규모가 3종이 아니라 응답 7 + 요청 1 이었음, 지목 DTO 2건 오기였음을 기록).
   신규 이연 3건 등재.
   - 도메인 객체 `WorkflowScheme.isDefault` · DB 컬럼 `is_default` → `isStandard` rename (ADR D2 이연분)
   - cross-BC 이슈타입 조회 실패가 무음 (`issueTypeKey`/`issueTypeName` null 로만 표현, ADR 잔여위험 2)
   - `classify-task.ts:45` 의 `'스키마'` 키워드가 Zod/GraphQL/JSON schema 작업을 전부 `migration` 으로
     오분류. 기존 항목 「`bts-review-plan` 분기 표에 `type=backend` 가 없다」의 형제 — **판별식 필요**
3. **ADR 잔여위험 갱신** — 1번(조립 부팅 미실시)을 A9 결과로 해소 또는 잔여 명시. 3번(springdoc) 확정.

**REFACTOR**. `bash scripts/verify-master-plan.sh` 실행. FR 카운트 불변 131 이므로 통과해야 한다.

**검증**.
```bash
bash scripts/verify-master-plan.sh; echo "EXIT=$?"    # 0 기대
cd apps/web && npx vitest run && npx tsc --noEmit -p tsconfig.app.json
cd backend && ./gradlew :modules:project-workflow:test :modules:project-workflow:ktlintCheck :modules:project-workflow:detekt --rerun-tasks
```

---

## Plan 메타

- **task 수**. 9
- **예상 wave**. 직렬 전제 — T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9.
  T2·T3 은 depends-on 이 `[1]` 로 같아 이론상 병렬이나, **같은 worktree 에서 백엔드·프론트가 동시 커밋하면
  git index.lock / 공유 stash 레이스**가 난다(메모리 [[parallel-dispatch-precommit-hook-race]] ·
  [[worktree-lint-staged-shared-git-stash-collision]] · [[subagent-git-stash-worktree-shared-collision]]).
  #314 도 직렬로 회피했다. **직렬 dispatch 를 권고한다.**
- **TDD 강제**. yes. 단 **T2 는 의도된 RED 커밋**(`test:`)이며 T4 에서 GREEN 이 된다 —
  bts-impl 의 "test: 가 feat: 보다 먼저" 검증과 정합한다.
- **추가 검증**. typecheck(파이프 없이) · eslint · ktlintCheck · detekt(`--rerun-tasks`) · vitest ·
  playwright(`apps/web` 에서 직접 호출) · `:app:test` 조립 부팅 · `verify-master-plan.sh`
- **`.bts-cache/classify.json` 미기록 (의도된 편차).** bts-plan SKILL.md Step 3 은 이 파일에 `task_count`
  를 쓰라고 하지만 **동시 세션 `backend/fr-co-02` 소유**다(메모리 [[bts-cache-multisession-collision]]).
  task 수는 이 plan 파일에만 기록한다 — **plan 이 진실출처**.

### Self-Review (writing-plans 체크리스트)

**1. 스펙 커버리지.** FR C1~C9 · NFR N1~N6 · EC-1~EC-12 · 완료기준 A1~A11 전부 task 에 매핑됨.

| 스펙 항목 | task |
|---|---|
| C1 응답 8 정합 | T3(백엔드) + T4(프론트) + T2(검증) |
| C2 요청 4 정합 | T4 |
| C3 정본 어휘 | T3 + T4 + T7 |
| C4 형태별 스키마 분리 | T4 |
| C5 `mappings[].isDefault` | T3 |
| C6 픽스처 백엔드 형태 | T5 |
| C7 `.transform()` 제거 | T4 |
| C8 `name` 필수화 | T4 |
| C9 낙관적 객체 타입 | T6 |
| A1·A3·A4 | T2 · T4 |
| A2 | T3 |
| A5 | T3 (EC-4 RED) |
| A6·A7·A8 | T7 · T3 · T9 |
| A9 | T8 |
| A10 | T9 |
| A11 | T9 |
| EC-1 404=미할당 유지 | T4 (명시) |
| EC-4 조회 실패 ≠ 기본 매핑 | T3 RED |
| EC-9 403 경로 | T8 (E2E 회귀 확인, 계약 증거로 쓰지 않음) |
| EC-10·EC-11 | T6 |
| EC-12 `description` 빈문자↔null | **T4 에서 계약 고정** — 착수 시 프론트가 무엇을 보내는지 확인 후 결정 |

**2. 플레이스홀더 스캔.** "TBD"·"적절히"·"나중에" 0건. 다만 T1 의 8 endpoint 호출 헬퍼는
기존 `WorkflowSchemeControllerTest` 의 슬라이스 설정 재사용을 지시하는 형태로 남겼다 —
그 파일의 `@WebMvcTest` / `@MockkBean` 구성을 발명하지 말고 복사하라는 것이 의도다.

**3. 타입 일관성.** T4 가 정의한 6 스키마 이름(`assignedSchemeSchema` · `schemeMutationResultSchema` ·
`schemeListItemSchema` · `mappingDetailSchema` · `schemeDetailSchema` · `mappingCreatedSchema` ·
`assignmentRecordSchema`)을 T2 · T5 · T6 이 동일하게 참조한다. T5 의 `MappingDetail` ·
T6 의 `AssignedScheme` 는 그 스키마의 `z.infer` 별칭이다.

**보완 1건 (self-review 에서 발견).** T2 가 참조하는 스키마는 T4 가 만든다 — **task 순서상 T2 가
먼저이므로 T2 는 컴파일되지 않는다.** 이는 의도된 RED 이며 T2 §GREEN 에 "없다. 의도된 RED"로
명시했다. bts-impl 의 verifier 가 이를 실패로 오판하지 않도록 **T2 dispatch 시 "이 task 의 성공 기준은
테스트가 실패하는 것"임을 prompt 에 명시**해야 한다.

## 리뷰 결과

### plan-eng-review (2026-07-27)

**Step 0 범위 도전.** 복잡도 게이트 발동(29파일 > 기준 8). **Maxi 결정 = 그대로 진행.**
근거 — 어휘 소비처 9파일 + 그 테스트 7파일은 rename 의 본질적 파급이고, 유일하게 뺄 수 있는 3파일
(계약 스냅샷)이 하필 재발 방지 장치 전부다. T5 로 픽스처가 Zod 를 참조하게 되면 픽스처↔Zod 는 묶이나
**Zod↔백엔드는 스냅샷만이 묶는다.**

**리뷰 체인 편차 (사유 기록).** SKILL.md Step 2 표는 `ui → plan-design-review` 이나
**시각 변경 0**(순수 계약 정렬, NFR N4)이라 design 리뷰의 한계효용이 낮다고 판단해 `plan-eng-review`
단독으로 실행했다. 메모리 [[bts-review-plan-autoplan-overkill]](Maxi 피드백 — eng 집중) 근거.

> ⚠️ **outside voice 부재 — 게이트 1 에서 감안할 위험.** `codex` 미설치 + 이 세션은 AgentTool 미승인이라
> **독립·교차모델 리뷰를 실행하지 못했다.** #313·#314 에서 독립 리뷰가 **3연속으로 사실오류를 적발**했고
> #314 는 ADR 의 결정적 근거가 거짓임을 그렇게 밝혀냈다. 이 리뷰는 자기검토이므로 같은 종류의
> 사각을 놓쳤을 수 있다. `npm install -g @openai/codex` 한 번이면 다음부터 진짜 교차모델이 된다.

#### 🔴 발견 1 (P1, 신뢰도 9/10) — 스냅샷을 슬라이스에서 만들면 틀린 계약을 박제한다

**Maxi 결정 = 1A (조립 환경에서 생성).**

인용 근거.
```
WorkflowSchemeControllerTest.kt:76  @ContextConfiguration(classes = [WorkflowSchemeControllerTest.TestMvcConfig::class])
WorkflowSchemeControllerTest.kt:80      @EnableWebMvc
WorkflowSchemeControllerTest.kt:108     private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
```

`JavaTimeModule` 부재. 그런데 이번에 다루는 `AssignmentResponse` 가 `assignedAt: Instant` ·
`projectId: UUID` · `assignedBy: UUID` 를 갖는다. 메모리
[[enablewebmvc-slice-localdate-array-serialization]] 이 **이 저장소에서 이 설정으로 `LocalDate` 가
배열로 직렬화된 사고**를 기록한다.

**왜 지금 버그보다 나쁜가.** 슬라이스가 `Instant` 를 숫자로 내면 스냅샷에 숫자가 담기고, 프론트
`.strict()` 를 통과시키려 Zod 를 숫자로 바꾼다 → 조립(Spring Boot 자동설정 + `JavaTimeModule`)은
ISO 문자열을 내므로 **봉인이 틀린 계약을 박제한 채 초록**이 된다.
Prior learning applied — [[already-works-is-not-proof-unless-real-server]] (9/10). 그때는
"MSW 끼리의 일치"였고 이번은 **"슬라이스끼리의 일치"** 로 같은 구조다.

**처방 (T1 재구성).** 스냅샷 생성을 조립 컨텍스트(`:app:test` 또는 `ProdAssemblyHttpTestBase`)로 옮긴다.
⇒ **T1 이 dev postgres 5433 을 요구하게 되고 T8(조립 부팅)과 같은 인프라를 쓴다.**
task 순서를 `T1(조립) → T2 → T3 → …` 로 유지하되 T1 의 agent 를 `backend-engineer`, 검증 명령을
`:app:test` 로 교체한다. 백엔드 CI 부재는 기존 조건이라(메모리
[[no-backend-ci-and-assembly-merge-verification-traps]]) 로컬 검증 체계와 정합한다.

#### ✅ 발견 2 (P2, 신뢰도 9/10) — A9-③ 은 조건부가 아니라 확정 사실이다 (실측으로 해소)

Prior learning applied — [[completion-criterion-without-task-or-feasibility]] (9/10) ·
[[spec-requires-what-repo-cannot-do]] (9/10). 두 학습이 *"완료기준을 쓸 때 (1) 어느 task 가 하는지
(2) 이 저장소에서 실현 가능한지를 세트로 확인하라"* 고 지시한다. 그대로 실측했다.

| 확인 | 근거 |
|---|---|
| springdoc 이 조립에 활성인가 | `:app/build.gradle.kts:52,55` 가 springdoc 보유 모듈 2개에 의존 |
| 경로가 열려 있는가 | `:app/application.yml:100` `path: /v3/api-docs` |
| 관측 수단이 실재하는가 | `OpenApiAnnotationTest.kt:209` 가 이미 그 경로를 GET 하는 선례 |

⇒ **스킴 컨트롤러 2개는 `/v3/api-docs` 에 노출된다(확정).** 응답 필드명은 **문서화된 계약**이다.
소비자 0건 실측이 있어 실질 위험은 낮으나 A9-③ 문구를 조건부("테스트 부재 시 curl")에서
**확정 + 기존 테스트 패턴 복사**로 교체한다.

#### ⚪ 발견 3 (범위 밖, 신뢰도 8/10) — `/v3/api-docs` 가 보안 필터 체인 밖이다

**Maxi 결정 = 3A' (별도 작업으로 바로 진행).** 처음 3B(이번 PR 포함)를 택했으나, 같은 등록이
**두 곳**에 있어 이 PR 이 3개 BC 를 건드려야 함이 드러나 재확인 후 단위를 분리했다.

```
issue-tracking/.../OpenApiSecurityConfig.kt:20-23   web.ignoring().requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
search-export-import/.../OpenApiConfig.kt:60-63     (동일)
```

`permitAll` 이 아니라 **`WebSecurityCustomizer.ignoring()`** — 경로를 필터 체인에서 통째로 제외한다.
**한 곳만 고치면 효과 0**(둘 중 하나라도 ignoring 하면 체인 밖). 소관은 `security-engineer`,
선례는 #275(「문을 열기 전에 잠금장치를 고친다」 — 잠금만 단독 PR).
잠금 수준(로그인만 / SYSTEM_ADMIN / prod 한정)은 그 작업에서 결정.

#### 🟡 접힌 발견 3건 (trade-off 없어 권고로 반영)

| # | 발견 | 처방 |
|---|---|---|
| F4 (P2, 7/10) | `schemeListItemSchema.mappings: z.array(z.unknown())` 가 목록 응답의 매핑 내용을 안 본다 → 그 endpoint 에서 봉인이 무력 | `z.array(mappingDetailSchema)` 로 교체. 빈 배열은 그대로 통과하므로 손실 0 |
| F5 (P2, 6/10) | T2 의 스냅샷 경로가 상대경로 6단계(`../../../../../docs/...`)로 취약 | vitest `resolve` 를 repo 루트 기준으로 잡거나 별칭 도입 |
| F6 (P2, 8/10) | EC-9(403) 회귀를 무엇이 잡는지 불명. D-Q7 로 **에러 경로는 Zod 를 안 타므로** 계약 테스트가 못 잡는다 | T8 E2E 에 403 시나리오를 명시 배정. "초록이어도 계약 증거 아님"은 유지 |

### 필수 산출물

#### NOT in scope (고려했으나 명시적으로 이연)

| 항목 | 사유 |
|---|---|
| 도메인 `WorkflowScheme.isDefault` · DB 컬럼 `is_default` → `isStandard` | ADR D2. 결함 원인이 아니고 리뷰 단위를 도메인 리팩토링과 섞는다. 마이그레이션 0 유지 |
| `/v3/api-docs` 필터 체인 제외 봉합 | 발견 3 → 별도 작업(3A'). 2 BC + security-engineer 소관 |
| cross-BC 이슈타입 조회 실패 무음 | 선재 결함. D3 이 `isDefault` 오표시만 막고 `issueTypeKey` null 표시는 그대로 |
| `user-fixtures.ts` ↔ `auth-fixtures.ts` 교집합 0 | 프론트 8,009 테스트 의존. TODOS 기존 항목 |
| `classify-task.ts` `'스키마'` 키워드 오분류 | 워크플로우 도구 결함. TODOS 신규 등재 |
| design 리뷰 | 시각 변경 0 (NFR N4) |
| 프론트 CI 배선 변경 | 기존 3잡 구조 유지 |

#### What already exists (재사용 vs 재구축)

| 기존 자산 | 이 계획의 처리 |
|---|---|
| `assignableSchemeResponseSchema` 의 `.transform()` (#314 D3=1A) | **제거** — D1 적용 후 불필요. 재구축 아니라 목적을 더 강하게 달성 |
| `WorkflowSchemeControllerTest` 슬라이스 설정 | **재사용**하되 스냅샷은 조립으로 이동(발견 1) |
| `CapturingPermissionResolverStub` · 403 핸들러 | **재사용** — 신규 인프라 0 |
| `OpenApiAnnotationTest.kt:209` GET `/v3/api-docs` 패턴 | **복사** — A9-③ 관측 수단 |
| `MappingResponseDetail.from()` | **확장** (필드 1 추가), 신규 DTO 0 |
| 프론트 E2E 16건 | **보강** (필드명 단정 추가), 신규 스펙 파일 0 |

#### 실패 모드 (신규 코드경로별 1개 + 테스트·에러처리·가시성)

| 코드경로 | 현실적 실패 | 테스트 | 에러처리 | 사용자 가시성 |
|---|---|---|---|---|
| 스냅샷 생성(조립) | dev postgres 미가동 → 부팅 실패 | T1 검증 명령 | 부팅 실패는 명시적 | 개발자만 (사용자 영향 0) |
| 스냅샷 stale | 백엔드 DTO 변경 후 재생성 누락 | T1 문자열 동등 단정 | 테스트 실패 | 개발자만 |
| 프론트 `.strict()` 파싱 | 백엔드가 필드 추가 | T2 계약 테스트 | ZodError → 테스트 실패 | 개발자만 |
| `MappingResponseDetail.isDefault` | cross-BC 조회 실패 매핑을 기본 매핑으로 오판 | **T3 EC-4 RED (필수)** | 산출식이 도메인 필드 기반 | ★ 잡지 않으면 UI 가 잘못된 ★ 표시 |
| 낙관적 업데이트 | 캐시 타입 불일치 | T6 | 롤백 존재 | 서버 성공인데 실패 표시 (현재 증상) |
| 배정 조회 404 | EC-1 미할당을 에러로 오해 | T4 명시 | `null` 반환 | ★ 깨지면 배정 화면 에러 |

**critical gap 0건** — 모든 실패 모드에 테스트 또는 명시적 에러처리가 있다. 단 `MappingResponseDetail.isDefault`
와 `EC-1 404` 두 건은 **테스트가 유일한 방어선**이라 T3·T4 의 해당 테스트를 삭제·skip 하면 무음으로 변한다.

#### 병렬화 전략

| 단계 | 건드리는 모듈 | 의존 |
|---|---|---|
| T1 스냅샷(조립) | `backend/modules/{project-workflow,app}` | — |
| T2 계약 테스트 | `apps/web/src/api` | T1 |
| T3 백엔드 뷰 레이어 | `backend/modules/project-workflow` | T1 |
| T4~T7 프론트 | `apps/web/src/{api,mocks,hooks,components,routes,i18n}` | T2·T3 |
| T8 조립+E2E | `backend/modules/app` · `apps/web/e2e` | T3~T7 |
| T9 문서 | `docs/` · `TODOS.md` | T8 |

**Lane A**: T1 → T3 (순차, `project-workflow` 공유) · **Lane B**: T2 → T4 → T5 → T6 → T7 (순차, `apps/web` 공유)
**⚠️ 충돌 플래그.** Lane A·B 는 모듈이 안 겹치나 **같은 worktree 라 git index.lock·공유 stash 레이스**가 난다
(메모리 3종 + 학습 `parallel-frontend-edit-only-controller-commit` 5/10 — 구현자는 Edit 만, controller 가 순차 커밋).
⇒ **직렬 실행 권고 유지.** 병렬을 쓰려면 구현자에게 git 조작을 금지하고 controller 가 커밋을 직렬화해야 한다.

### 판정

**BLOCKER 0건.** P1 2건(발견 1·2)은 Maxi 결정으로 처방 확정. 접힌 발견 3건은 권고 반영.
**DONE_WITH_CONCERNS** — 유일한 concern 은 **outside voice 부재**다.

### 리뷰 완료 요약

| 항목 | 결과 |
|---|---|
| Step 0 범위 도전 | 게이트 발동(29파일) → **그대로 진행** (Maxi 결정) |
| Architecture | **2건** (발견 1 P1 · 발견 3 범위밖) |
| Code Quality | **2건** (F4 스키마 무력화 · F5 상대경로 취약) |
| Test | 커버리지 표 작성, **1 gap** (F6 — 403 회귀 담당 불명) |
| Performance | **0건** (스냅샷 파일 I/O 뿐, DB 접근 패턴 무변경) |
| NOT in scope | 작성 (7항목) |
| What already exists | 작성 (6항목, 재사용 5 · 제거 1) |
| 실패 모드 | **critical gap 0** (단 2건은 테스트가 유일 방어선) |
| TODOS | 4항목 (T9 3건 + api-docs 별도작업 1건) |
| Outside voice | **미실행** — codex 미설치 + AgentTool 미승인 |
| 병렬화 | 2 lane 식별, **직렬 권고**(같은 worktree git 레이스) |
| Lake Score | 6/6 — 모든 발견에서 완전한 쪽을 택함 |

### 구현 task 추가 (리뷰 발견에서 파생)

- [x] **T1-mod (P1, human: ~3h / CC: ~20min)** — `backend/modules/app` — 스냅샷 생성을 조립 컨텍스트로 이동
  - Surfaced by: Architecture 발견 1 — 슬라이스 `@EnableWebMvc` + `JavaTimeModule` 부재
  - Files: `backend/modules/app/src/test/kotlin/com/bts/app/contract/WorkflowSchemeContractSnapshotTest.kt`
    · `docs/contracts/workflow-schemes.snapshot.json` · **`backend/modules/app/build.gradle.kts` (선언 외 1건 추가)**
  - Verify: `./gradlew :modules:app:test --tests "*ContractSnapshot*"` + 스냅샷의 `assignedAt` 이 ISO 문자열
  - ✅ **완료 (2026-07-27)** — `test:` 2bf6f1476 → `feat:` 066b0a998 → `refactor:` 70d1147d3.
    `tests=2 skipped=0 failures=0 errors=0`(XML 실측), `--rerun-tasks` 2회 독립 실행 모두 통과.
    스냅샷 `assignedAt` = `"2026-01-01T00:00:00Z"`(정규화 instant) — **조립은 `Instant` 를 ISO
    문자열로 낸다**가 실증됐다(발견 1 이 우려한 숫자·배열 직렬화는 조립에 없음). 전용 단정
    테스트 `배정 응답의 assignedAt 이 ISO-8601 문자열이다` 가 grep 검증을 대체한다.
  - ⚠️ **선언 외 파일 1건** — `backend/modules/app/build.gradle.kts` 에 3줄 배선 추가.
    Gradle 이 CLI `-D` 를 fork 된 테스트 JVM 에 전달하지 않아, 이 배선 없이는
    `-Dcontract.snapshot.update=true` 가 **조용히 무시**되어 스냅샷을 영원히 생성할 수 없다.
  - ⚠️ **스타일 이탈 1건** — 테스트 파일 340줄로 `DEVELOPMENT.md §2.1` 의 「파일 300줄 이내」 초과.
    분리하면 단일 소비자용 추상이 생겨 `CLAUDE.md §2 Simplicity`(single-use 추상 금지)와 충돌한다.
    초과분 대부분이 함정을 기록한 KDoc(로직 아님)이라 유지 쪽을 택했다 — 게이트 2 판단 대상.
- [ ] **T4-mod (P2, human: ~20min / CC: ~3min)** — `apps/web/src/api` — `mappings` 를 `z.array(mappingDetailSchema)` 로 + 선언 순서 준수
  - Surfaced by: Code Quality F4 — `z.unknown()` 이 목록 endpoint 의 봉인을 무력화
  - Verify: `npx vitest run src/api` + `npx tsc --noEmit -p tsconfig.app.json`
- [ ] **T2-mod (P2, human: ~10min / CC: ~2min)** — `apps/web/src/api/__tests__` — 스냅샷 경로를 repo 루트 기준으로
  - Surfaced by: Code Quality F5 — 상대경로 6단계 취약
- [ ] **T8-mod (P2, human: ~30min / CC: ~5min)** — `apps/web/e2e` — 403 시나리오 명시 배정
  - Surfaced by: Test F6 — 에러 경로는 Zod 를 안 타므로 계약 테스트가 403 회귀를 못 잡는다

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | codex 미설치 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (PLAN) | 6 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | 시각 변경 0 이라 스킵 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** ENG CLEARED — ready to implement. Outside voice 는 실행 불가(codex 미설치 + AgentTool 미승인)이며
이 부재는 게이트 1 에서 Maxi 가 감안할 위험으로 명시했다.

NO UNRESOLVED DECISIONS
