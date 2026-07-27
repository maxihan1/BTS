<!-- 워크플로우 스킴 프론트 Zod ↔ 백엔드 응답·요청 DTO 계약 정렬 스펙 — FR 불변 131, 스펙 준수 복원 -->
# 워크플로우 스킴 계약 정렬 — 스펙

> slug: workflow-scheme-contract-align
> type: ui / agent: frontend-engineer (+ 백엔드 뷰 레이어 동반)
> BC: project-workflow (단일) — cross-BC 0
> **FR 불변 131** — 신규 FR 없음. FR-WF-02 의 **스펙 준수 복원**이다(#314 와 동형).
> ADR: [2026-07-27-workflow-scheme-canonical-vocabulary](../decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md)
> plan: [2026-07-27-workflow-scheme-contract-align](../plans/2026-07-27-workflow-scheme-contract-align.md)

## 이것은 새 기능이 아니라 기능 복원이다

`docs/specs/2026-05-28-fr-wf-02-d6-ui-crud.md` 가 명세한 스킴 관리·배정 화면은 **코드 대조 기준으로
실서버에서 동작할 수 없다.** 응답 파싱 8 endpoint 중 7 이 `ZodError` 를 던지고, 생성 요청은 필드명이 달라 400 이다.

> ⚠️ **주장의 강도를 구분한다.** 여기까지는 **코드 대조**(변환 계층 부재 3축 + 백엔드 테스트 34건의
> 단정 필드)로 확정된 것이다. **"화면이 실서버와 통신한 적이 한 번도 없다"는 더 강한 주장은
> 실서버 관측 없이 단정하지 않는다** — 완료 기준 **A9(조립 부팅)** 에서 확정한다.
> 메모리 [[workflow-scheme-read-permission-gate-done]] 이 다친 지점("이미 동작 중이다"를 관측 없이
> 근거로 씀)의 **역방향**이며, 같은 규율을 적용한다.

학습 2026-07-17 「파일 존재 ≠ 기능 존재」의 프론트 판본이다. 그때는 *"도메인·서비스·repo 가 다 있어도
REST 노출이 없으면 기능이 없다"* 였고, 이번은 *"화면·라우트·테스트가 다 있어도 계약이 안 맞으면
기능이 없다"* 다. 판정 기준도 같다 — **컨트롤러의 실제 응답을 세어서** 한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 시스템 관리자가 스킴 목록을 본다 (현재 깨짐 → 복원)

- **Given** `SYSTEM_ADMIN` 으로 로그인해 `/admin/workflow-schemes` 에 진입한다
- **When** 화면이 `GET /api/v1/workflow-schemes` 를 호출한다
- **Then (현재)** 백엔드가 `key`·`isDefault` 를 보내는데 Zod 가 `schemeKey`·`isStandard` 를 요구해
  `ZodError` → `useWorkflowSchemes` 가 error 상태 → **목록이 렌더되지 않는다**
- **Then (복원 후)** 스킴 4개가 이름·설명·표준 배지·사용 프로젝트 수·매핑 수와 함께 렌더된다

### S2. 시스템 관리자가 스킴을 새로 만든다 (현재 깨짐 → 복원)

- **Given** `/admin/workflow-schemes/new` 에서 키·이름·설명을 입력한다
- **When** 저장을 누른다 — 화면이 `{schemeKey, name, description}` 을 POST 한다
- **Then (현재)** 백엔드 `CreateWorkflowSchemeRequest.key` 가 Kotlin non-null 이라 역직렬화 실패 →
  **400** → toast.error. **스킴이 생성되지 않는다**
- **Then (복원 후)** 201 + 목록 캐시 무효화 + 성공 toast

### S3. 시스템 관리자가 스킴 이름을 수정한다 (현재 「성공했는데 실패로 보임」 → 복원)

- **Given** 스킴 상세에서 이름을 인라인 편집한다
- **When** 저장을 누른다
- **Then (현재)** 백엔드는 **200 으로 수정에 성공**한다. 그런데 응답 `WorkflowSchemeResponse` 에
  `usedByProjectsCount`·`mappingsCount` 가 없고 `key`/`isDefault` 어휘라 Zod 가 던진다 →
  `onError` 가 낙관적 업데이트를 **롤백** + toast.error →
  **사용자는 실패로 인식하나 서버에는 이미 반영돼 있다**
- **Then (복원 후)** 200 파싱 성공 → 성공 toast → invalidate 후 최신값 표시

### S4. 시스템 관리자가 매핑을 추가한다 (현재 「성공했는데 실패로 보임」 → 복원)

- **Given** 스킴 상세에서 이슈 타입과 워크플로우를 골라 매핑을 추가한다
- **Then (현재)** 백엔드는 **성공**한다. 응답 `MappingResponse{id,schemeId,issueTypeId,workflowId,createdAt}` 가
  Zod 의 `{id,issueTypeKey,issueTypeName,workflowKey,workflowName,isDefault}` 와 `id` 만 겹쳐 던진다 →
  롤백 + toast.error. 직후 `onSettled` invalidate 가 재조회를 시도하나 **S1 과 같은 이유로 그것도 실패**
- **Then (복원 후)** 성공 → 매핑 행이 표시되고 기본 매핑이면 ★ 표시

### S5. 프로젝트 관리자가 프로젝트에 스킴을 배정한다 (현재 깨짐 → 복원)

- **Given** `ASSIGN_SCHEME` 보유자가 `/projects/{key}/settings/workflow-scheme` 에 진입한다
- **When** 현재 배정을 조회한다 — `GET /api/v1/projects/{key}/workflow-scheme`
- **Then (현재)** 백엔드가 `SchemeResponse{id,key,name,description,isDefault}` 를 주는데 Zod 는
  `{projectKey,schemeKey,schemeName}` 을 요구 — **필드 교집합 0** → 던짐 → 화면 에러
- **When** 스킴을 골라 배정한다 — `PUT` 은 **성공**하나 응답
  `AssignmentResponse{projectId,workflowSchemeId,assignedAt,assignedBy}` 가 같은 Zod 를 타 또 던진다 → 롤백
- **Then (복원 후)** 조회·배정 모두 정상. 배정 후 현재 스킴 이름이 갱신 표시된다
- **참고** 배정 후보 목록(`assignable-workflow-schemes`)은 **지금도 정상**이다 — #314 가 `.transform()` 을 붙였다

### S6. 개발자가 계약을 다시 어긋나게 만든다 (신규 봉인)

- **Given** 누군가 백엔드 DTO 필드를 바꾸거나 새 endpoint 를 추가한다
- **When** 프론트 Zod 를 함께 갱신하지 않는다
- **Then** MSW 픽스처가 **백엔드 응답 형태**이므로 프론트 단위 테스트가 **실패**한다
  (현재는 픽스처가 프론트 형태라 초록으로 통과 — 이 초록이 아무것도 증명하지 않는 상태)

## 기능 요구사항 (FR)

**신규 FR 없음. FR 총수 131 불변.** 아래는 FR-WF-02 이행 항목이다.

| ID | 요구사항 | 근거 |
|---|---|---|
| C1 | 응답 파싱 8 endpoint 전부에서 프론트 Zod 가 백엔드 실제 응답을 파싱한다 | ADR D1·D4 |
| C2 | 요청 4종 전부에서 프론트가 백엔드가 요구하는 필드명·nullability 로 보낸다 | ADR D1 |
| C3 | 정본 어휘는 `key` + `isStandard`. 백엔드 응답 DTO 3파일이 `isStandard` 로 노출하고 프론트가 `key` 를 쓴다 | ADR D1 |
| C4 | **한 Zod 스키마가 형태가 다른 두 응답을 검사하지 않는다.** 형태가 같은 응답(예: `GET assignment` 와 `GET assignable` 이 둘 다 `SchemeResponse`)은 **재사용이 정답**이다 — "endpoint 수만큼 스키마"가 목표가 아니다 | ADR D4 |
| C9 | **낙관적 업데이트 객체는 그 캐시가 담는 타입(= 조회 응답 타입)을 만족한다.** 부분 객체·다른 타입 금지 | 아래 §D-Q8 |
| C5 | `MappingResponseDetail` 에 `isDefault` 를 추가하고 `mapping.issueTypeId == null` 로 산출한다 | ADR D3 |
| C6 | MSW 픽스처·핸들러가 백엔드 응답 형태를 반환한다 | ADR D5 |
| C7 | `assignableSchemeResponseSchema` 의 `.transform()` 을 제거한다 (D1 적용 후 무의미) | ADR D1 |
| C8 | `UpdateSchemeInput.name` 을 필수로 바꿔 백엔드 non-null 계약과 정합시킨다 | 아래 §D-Q3 |

## 코드로 확정한 판정 (spec 단계 실측)

### D-Q1. 변환 계층 부재 — 3축 확정

| 축 | 명령 | 결과 |
|---|---|---|
| 전역 필드명 변환 | `grep -rn "PropertyNamingStrategy\|property-naming-strategy" backend` | **0건** |
| 필드별 별칭 | `grep -rn "JsonProperty" backend/modules/project-workflow/src/main` | **0건** |
| 응답 본문 재작성 | `grep -rn "ResponseBodyAdvice\|beforeBodyWrite" backend`(비테스트) | **0건** |

**★백엔드 자신의 테스트가 어긋남을 증언한다.** `WorkflowSchemeControllerTest`(MockMvc — 실제 Jackson
직렬화)가 단정하는 JSON 경로는 `$.data.key` · `$.data[0].key` ·
`$.data.mappings[0].{issueTypeKey,issueTypeName,workflowKey,workflowName}` ·
`$.data.{usedByProjectsCount,mappingsCount}` 다.

- `$.data.schemeKey` 단정 **0건** ⇒ 프론트가 요구하는 `schemeKey` 는 응답에 없다
- `$.data.mappings[0].isDefault` 단정 **0건** ⇒ 그 필드는 존재하지 않는다
- `isDefault`/`isStandard` 단정 **0건** ⇒ 백엔드 테스트가 표준 여부를 아예 검증하지 않는다(별도 갭)

⇒ **두 진영의 테스트가 서로 다른 필드명을 단정하며 둘 다 초록이다.** 이것이 파손의 결정적 증거다.

### D-Q2. create/update 응답의 카운트를 화면이 쓰는가 → **안 쓴다**

`useCreateWorkflowScheme.onSuccess`(`use-workflow-schemes.ts:98-101`)는 반환값을 무시하고
`invalidateQueries(SCHEME_KEYS.list)` 만 한다. `useUpdateWorkflowScheme` 도 `onSuccess` 는 toast 뿐이고
낙관적 업데이트는 **`input`** 에서만 값을 취하며(`:131-132`) `onSettled` 가 detail·list 를 invalidate 한다.
⇒ **R3/R4 응답은 카운트가 없어도 된다.** 스키마 분리로 끝. 추가 refetch 배선 불요.

### D-Q3. `UpdateSchemeInput.name` nullability 비대칭 → **범위에 포함 (비용 0)**

프론트는 `name?`(optional), 백엔드 `UpdateWorkflowSchemeRequest.name` 은 non-null 필수다.
유일한 호출부 `SchemeMetaPanel.tsx:63` 이 `mutate({ name, description })` 으로 **항상 둘 다 보낸다** —
따라서 현재 400 은 발생하지 않는 **잠복**이다.
`name` 을 필수로 바꾸면 호출부 변경 0 으로 **타입 레벨에서 닫힌다.**
메모리 [[fr-co-01-comment-create-list-done]] 의 *"필드를 두고 무시 ≠ 필드가 없음"* 과 같은 결.

### D-Q4. PUT 배정 응답을 화면이 쓰는가 → **안 쓴다. 단 파싱 실패가 성공을 실패로 만든다**

`useUpdateAssignment`(`use-workflow-scheme-assignment.ts:52-98`)는 응답 본문을 소비하지 않는다
(`onSuccess` 는 toast, `onSettled` 가 invalidate). 그런데 `assignSchemeToProject` 가 Zod parse 를 타므로
**서버 200 인데 mutation 이 에러로 처리돼 롤백 + toast.error** 가 난다.

⇒ 처방. `PUT` 전용 스키마(배정 이력 형태)를 신설해 **파싱은 하되 소비하지 않는다.** 파싱을 생략하지 않는
이유는 계약을 코드에 명시해 두어야 다음 변경 때 잡히기 때문이다(C4 의 정신).

**★같은 캐시 키를 GET 과 PUT 이 공유한다.** `ASSIGNMENT_KEYS.byProject(projectKey)` 에 GET 결과와
낙관적 객체(`:65-69`)가 함께 들어간다. 캐시에 담길 타입은 **GET 응답 기준**으로 정하고
낙관적 객체도 그 형태로 맞춘다. 화면이 배정 상태 표시에 필요한 값은 `key`(어느 스킴) + `name`(표시)이며
GET 이 돌려주는 `SchemeResponse` 에 둘 다 있어 **충분하다.**

### D-Q5. 회귀 봉인 위치

**픽스처를 백엔드 형태로 바꾸는 것(C6)만으로는 부족하다.** 픽스처는 사람이 손으로 백엔드 형태를
유지해야 하고, 백엔드가 바뀌면 여전히 조용히 어긋난다.

⇒ **2축으로 봉인한다.**
- **축1 (C6).** 픽스처·핸들러를 백엔드 형태로 → 프론트 단위·E2E 가 실제 계약 위에서 돈다
- **축2 (신규).** 백엔드 MockMvc 테스트가 응답 JSON 을 파일로 내보내고, 프론트 테스트가 그 파일을
  자기 Zod 로 파싱하는 **계약 픽스처 대조**를 둔다. 백엔드가 필드를 바꾸면 프론트 테스트가 깨진다

**축2 의 한계를 미리 명시한다**(메모리 [[seal-blinds-existing-guard]] · [[verify-logic-vs-verify-guard]]).
내보낸 파일이 stale 하면 축2 는 **양쪽이 옛 계약에 합의한 상태로 초록**이다. 따라서 파일 생성은
백엔드 테스트 실행의 부산물이어야 하고(수동 갱신 금지), 파일이 없으면 프론트 테스트가 **실패**해야 한다
(skip 금지 — vacuous 통과 차단).

**★축2 가 여전히 못 막는 것 (정직하게 남긴다).** 파일 생성이 백엔드 테스트의 부산물이어도,
**백엔드 테스트 자체가 새 필드를 단정하지 않으면** 그 필드는 파일에 담기지 않는다.
실측 근거 — 현재 `WorkflowSchemeControllerTest` 34건 중 `isDefault`/`isStandard` 를 단정하는 테스트가
**0건**이다(스킴의 표준 여부를 아무도 검증하지 않는다). 즉 축2 는 "백엔드가 내보낸 것"만 대조하고
"내보내야 할 것을 내보냈는가"는 못 본다.
⇒ **보완.** 백엔드 응답 스냅샷은 **필드 집합 전체**를 직렬화해 저장하고(선택적 단정이 아니라 전량),
프론트 Zod 는 `.strict()` 로 파싱해 **예상 밖 필드가 있으면 실패**하게 한다. 그러면 백엔드가 필드를
추가·삭제할 때 양방향으로 걸린다.

### D-Q6. DTO 만 개명해도 컴파일되는가 → **된다 (확정)**

`src/main` 에서 응답 DTO 의 `isDefault` **프로퍼티를 읽는 코드 0건**. 생산 코드는 전부
`WorkflowScheme.isDefault`(도메인)를 읽어 DTO 생성자에 넘길 뿐이다.
`WorkflowSchemeRepositoryIntegrationTest.kt:196,215` 의 `saved.isDefault` 2건도 **도메인 객체**를 읽는다.
⇒ `from()` / `toResponse()` 의 인자 이름만 바꾸면 되고 ADR D2 의 뷰 레이어 한정이 **실현 가능하다.**

### D-Q7. ★에러 경로는 Zod 를 타지 않는다 — 봉인 판정에 직결

`parseResponse`(`client.ts:127-134`)는 `!res.ok` 이면 `ApiError(status, errorBody)` 를 던지고
**스키마 파싱을 건너뛴다.** `throwSchemeApiError` 의 `rfc7807ErrorSchema` 도 두 필드 모두 `.default()` 라
safeParse 가 실패할 수 없다.

**따라서.**
- 403 / 404 / 409 경로 테스트가 초록인 것은 **계약 정합의 증거가 아니다.** 이 PR 전에도 초록이었고 후에도 초록이다
- #314 가 추가한 403 안내 회귀 테스트는 이 변경에 **무영향** — 그 테스트로 "회귀 없음"을 주장할 수 없다
- 완료 기준 **A1 은 성공(2xx) 경로에만** 해당한다
- DELETE 2종을 "파싱 없음"으로 둔 판단은 **맞다** (`res.ok` 확인만, 본문 없음)

메모리 [[negative-guard-needs-body-discriminator]] 계열 — 초록의 의미 범위를 좁게 읽는다.

### D-Q8. ★낙관적 업데이트가 새 캐시 타입과 충돌한다 (구현 블로커)

세 mutation 이 캐시에 **직접 객체를 써 넣는다.** 스키마를 분리·개명하면 그 객체들도 함께 바뀌어야 하며,
한 곳은 **현재 형태로는 만들 수 없다.**

| 위치 | 현재 낙관적 객체 | D1·D4 적용 후 문제 |
|---|---|---|
| `use-workflow-scheme-assignment.ts:65-69` | `{projectKey, schemeKey, schemeName}` | 캐시 타입이 GET 응답(`{id?,key,name,description?,isStandard}`) 기준이 되면 **`projectKey` 는 그 타입에 없다.** 낙관적 객체가 캐시 타입을 만족할 수 없다 |
| `use-workflow-schemes.ts:140` | `prevList.map(s => s.schemeKey === schemeKey …)` | `s.schemeKey` → `s.key` |
| `use-workflow-schemes.ts:220-227` | `MappingResponse` 타입으로 `{id,issueTypeKey,issueTypeName,workflowKey,workflowName,isDefault}` | D4 로 detail 의 매핑 원소 타입과 addMapping 응답 타입이 **분리**된다. 낙관적 객체는 **detail 원소 타입**을 써야 한다(캐시가 담는 것이 detail 이므로) |

⇒ **C9 로 규칙화한다** — "낙관적 객체는 그 캐시가 담는 타입을 만족한다." `projectKey` 는 이미
queryKey 에 들어 있어(`ASSIGNMENT_KEYS.byProject(projectKey)`) 값에서 제거해도 정보 손실이 없다.

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| N1 | 마이그레이션 0 · DB 스키마 변경 0 | `git diff --stat` 에 `*.sql` 0건 |
| N2 | 도메인 모델 변경 0 (ADR D2) | `WorkflowScheme.kt` · `WorkflowSchemeApplicationService.kt` · `WorkflowSchemeRepository.kt` diff 0 |
| N3 | cross-BC 0 · 신규 의존성 0 | `build.gradle.kts` diff 0 |
| N4 | 시각 변경 0 | 스타일·레이아웃 diff 0. 필드명 변경에 따른 참조만 |
| N5 | 프론트 테스트 회귀 0 | 기준선 8009 tests / 513 files 대비 실패 0 (신규분 제외) |
| N6 | 백엔드 project-workflow 테스트 회귀 0 | 기준선 553 tests 대비 실패 0 |

## API 인터페이스 (REST) — 변경 후 계약

경로·메서드·상태코드는 **변경 없다.** 응답 필드명만 정본 어휘로 정렬한다.

| endpoint | 응답 (변경 후) | 프론트 Zod (1:1) |
|---|---|---|
| `GET /api/v1/workflow-schemes` | `{id,key,name,description?,isStandard,createdAt,updatedAt,usedByProjectsCount,mappingsCount,mappings:[]}` | `schemeListItemSchema` |
| `GET /api/v1/workflow-schemes/{key}` | 위 + `mappings:[{id,issueTypeKey?,issueTypeName?,workflowKey,workflowName,isDefault}]` | `schemeDetailSchema` |
| `POST /api/v1/workflow-schemes` | `{id,key,name,description?,isStandard,createdAt,updatedAt}` — 카운트 없음 | `schemeMutationResultSchema` |
| `PUT /api/v1/workflow-schemes/{key}` | 위와 동일 | `schemeMutationResultSchema` (재사용) |
| `GET /api/v1/projects/{k}/workflow-scheme` | `{id?,key,name,description?,isStandard}` | `assignedSchemeSchema` |
| `PUT /api/v1/projects/{k}/workflow-scheme` | `{projectId,workflowSchemeId,assignedAt,assignedBy}` | `assignmentRecordSchema` (파싱만) |
| `POST /{key}/mappings` | `{id,schemeId,issueTypeId?,workflowId,createdAt}` | `mappingCreatedSchema` |
| `GET /projects/{k}/assignable-workflow-schemes` | `{id?,key,name,description?,isStandard}` | `assignedSchemeSchema` 재사용 검토 |
| `DELETE` 2종 | 204 no content | 파싱 없음 |

**요청 (변경 후).**

| endpoint | body |
|---|---|
| `POST /api/v1/workflow-schemes` | `{key, name, description?}` — `schemeKey` → **`key`** |
| `PUT /api/v1/workflow-schemes/{key}` | `{name, description?}` — `name` **필수** (C8) |
| `POST /{key}/mappings` | `{issueTypeKey?, workflowKey}` — 변경 없음 |
| `PUT /projects/{k}/workflow-scheme` | `{schemeKey}` — **변경 없음**(백엔드가 이 이름을 받는다) |

> ⚠️ `AssignSchemeRequest.schemeKey` 는 **요청** 필드이고 그대로 둔다. 정본 어휘 결정은 **응답 필드**와
> 프론트 내부 어휘에 관한 것이다. 요청 body 는 백엔드가 이미 받는 이름을 따른다 — 바꾸면 계약 파손을
> 새로 만든다. (`CreateWorkflowSchemeRequest.key` 도 백엔드 쪽을 정본으로 두고 프론트를 맞춘다.)

## 데이터 모델 변경

**없음.** DB 컬럼 `workflow_schemes.is_default` 유지. 도메인 `WorkflowScheme.isDefault` 유지.
변환은 `WorkflowSchemeDetailResponse.from()` · `WorkflowSchemeResponse.from()` ·
`ProjectWorkflowSchemeController.toResponse()` **3지점**에서만 일어난다(ADR 잔여위험 4 — load-bearing).

## 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| EC-1 | 프로젝트에 배정이 없다 | `GET assignment` 404 → `null` = "미할당". **현행 동작 유지**(`workflow-schemes.ts:239-241`). 404 를 에러로 바꾸지 않는다 |
| EC-2 | `description` 이 null | Zod `.nullable()`. 현행 `z.string()` non-null 이 파손 원인 중 하나였다 |
| EC-3 | 기본 매핑 (`issueTypeKey` null) | `isDefault: true` 를 **백엔드가 명시**. ★ 표시 + 정렬 최상단 |
| EC-4 | cross-BC 이슈타입 조회 실패 | `issueTypeKey`·`issueTypeName` null 이나 `isDefault: false`. **★ 로 오표시되지 않는다** (ADR D3 의 핵심) |
| EC-5 | 매핑 추가 낙관적 업데이트 | 서버 응답(`mappingCreatedSchema`)에 `workflowName` 이 없다. 낙관적 항목은 `input` 으로 만들고 `onSettled` invalidate 로 실제값 교체 — **현행 구조 유지** |
| EC-6 | 스킴 생성 직후 목록 | create 응답에 카운트 없음. `invalidateQueries(list)` 로 재조회 — **현행 구조 유지**(D-Q2) |
| EC-7 | 표준 스킴 삭제 시도 | 409 `SCHEME_STANDARD_NOT_DELETABLE`. `isStandard` 로 UI 가 삭제 버튼 disabled (D11) |
| EC-8 | `id` 가 null | `SchemeResponse.id: Long?` — 프론트 `z.number().int().nullable()`. 현행 유지 |
| EC-9 | 비-`SYSTEM_ADMIN` 이 `/admin/workflow-schemes` 진입 | #314 게이트로 403. **이 경로는 Zod 를 타지 않으므로**(D-Q7) 이 PR 이 바꾸지 않는다. 다만 403 안내가 여전히 뜨는지 **회귀 확인 대상**이며, 초록이어도 계약 증거로 쓰지 않는다 |
| EC-10 | 낙관적 업데이트 중 요청 실패 | 롤백 객체(`context.prev*`)는 **조회 응답 타입**이라 자동 정합. 반면 전진 객체는 C9 를 지켜야 한다. `projectKey` 는 queryKey 에 있으므로 값에서 제거 |
| EC-11 | 배정 없는 프로젝트에 처음 배정 | `prevAssignment` 가 `null` → 낙관적 객체를 `null` 에서 만들어야 한다. 현재 `prevAssignment?.schemeName ?? ''` 로 빈 문자열을 넣는데, 새 타입에서는 `name` 이 필수라 같은 처리가 필요하다. `onSettled` invalidate 가 즉시 실제값으로 교체 |
| EC-12 | `description` 을 빈 문자열로 지우기 | 백엔드 `UpdateWorkflowSchemeRequest.description: String?` — `null` 이면 설명 제거(KDoc `:199`). 프론트가 `''` 과 `null` 중 무엇을 보내는지 확인 후 계약 고정 |

## 제약 조건

- **완제품 기준.** PoC·임시 코드 금지 (CLAUDE.md §작업 기준)
- **한 PR = 한 BC.** project-workflow 단일. 백엔드 뷰 레이어 동반은 학습 2026-05-22 「옵션 C 패턴」의
  문서화된 예외 — plan §리스크에 사유 명시 필수
- **동시 세션 `backend/fr-co-02`(PR #316) 활성.** 마이그레이션 0 이라 V번호 충돌 없음.
  `.bts-cache/classify.json` 은 그쪽 소유 — 읽지도 쓰지도 않는다
- **`user-fixtures.ts` 를 건드리지 않는다.** 프론트 8,009 테스트가 의존 (TODOS.md 등재된 별건)
- **FR 카운트 불변 131.** 전수 동기화 대상 아님 (fr-index·README·CLAUDE 카운트 무변경)
- 검증 함정 — `detekt` 는 `--rerun-tasks`, `typecheck` 는 파이프 없이,
  `pnpm test:e2e` 는 `apps/web` 에서 `npx playwright test <파일>` 직접 호출

## 측정 가능한 완료 기준

| # | 기준 | 측정 방법 |
|---|---|---|
| A1 | **성공(2xx) 응답 8 endpoint** 의 Zod 가 백엔드 실제 응답을 파싱한다 | 축2 계약 스냅샷 대조 테스트 통과. ※ 에러 경로는 Zod 를 타지 않으므로 대상 밖 (D-Q7) |
| A2 | 요청 4종이 백엔드 계약과 정합 | 백엔드 MockMvc 가 프론트가 보내는 **정확한 body 형태**로 요청해 2xx 를 받는다 (형태를 테스트에 하드코딩하지 말고 프론트 타입에서 유래한 예시 body 를 쓴다) |
| A3 | **한 Zod 스키마가 형태 다른 두 응답에 쓰이지 않는다** | 판정식 — `workflow-schemes.ts` 의 각 API 함수가 참조하는 스키마를 열거해, **같은 스키마를 쓰는 함수 쌍의 백엔드 반환형이 동일**한지 대조. 스키마 개수·이름은 판정 기준이 아니다 |
| A4 | `.transform()` 잔존 0건 | `grep -rn "\.transform(" apps/web/src/api/ apps/web/src/hooks/ \| grep -i scheme` = 0. **파일 한정 grep 금지** (다른 파일로 옮기면 통과하므로) |
| A5 | `MappingResponseDetail.isDefault` 가 `issueTypeId == null` 로 산출 | 백엔드 단위 테스트 2건 — ① 기본 매핑 → `isDefault=true` ② **`issueTypeId` 는 있으나 cross-BC 조회 실패** → `issueTypeKey=null` 이면서 `isDefault=false` (EC-4). ②가 없으면 A5 미충족 |
| A6 | 프론트 회귀 0 | `pnpm test` — 기준선과 **같은 집계법**으로 재측정 후 델타 대조 (메모리 [[test-count-baseline-grep-vs-xml]] — `grep -c "@Test"` 류 금지) |
| A7 | 백엔드 회귀 0 | `./gradlew :modules:project-workflow:test` 후 **`build/test-results/test/TEST-*.xml` 로 실행 수·skipped 실측**. `BUILD SUCCESSFUL` 만으로 판정 금지 (메모리 [[gradle-batched-task-partial-test-run]]) |
| A8 | 린트·타입 0 | `pnpm lint` · `pnpm typecheck`(**파이프 없이** — `$?` 가 `tail` 것이 됨) · `ktlintCheck` · `detekt --rerun-tasks`(캐시 false-green) |
| A9 | **조립 부팅 실증** | dev postgres 5433 + `:app:test`. **판정식 3개** — ① 8 endpoint 응답 JSON 의 최상위 키 집합이 프론트 Zod 의 요구 키 집합을 **포함**한다 ② 파손 7종이 수정 전에는 실제로 실패했음을 재현(수정 전 커밋에서 1회) ③ `/v3/api-docs` 에 이 컨트롤러가 나오는지 확정 (ADR 잔여위험 3). ②가 §이것은 새 기능이 아니라 기능 복원이다 의 단정을 **관측으로 승격**한다 |
| A10 | **브라우저 눈확인** | 스킴 목록·상세·생성·배정 4화면을 실제로 열어 렌더 확인. **★MSW 로 확인한 것은 계약 증거가 아니다** — 계약은 A9 가 담당하고 A10 은 **시각 회귀** 확인 전용이다. 이 구분을 두지 않으면 "MSW 가 MSW 와 맞는다"를 또 증거로 오인한다. FR-UX-06 이 22 PR 을 끝내고도 미실시로 남긴 절차이며, 그 미실시가 auth-fixtures ↔ user-fixtures 교집합 0 을 늦게 발견하게 만들었다 |
| A11 | 문서 동기화 | FR 카운트 불변 131 이므로 `verify-master-plan.sh` 대상 무변경. 단 **실행해 통과 확인** + TODOS.md 의 계약 파손 항목을 **해소 처리**하고 신규 이연 항목(도메인·DB rename · cross-BC 조회 실패 무음 · classify 키워드 충돌) 등재 |

## Brainstorming Check

✅ **통과 (2회 iteration — gap 10건 발견 후 전량 보강).**

`superpowers:brainstorming` 은 전체 대화형 설계 흐름이라 `bts-spec` Phase B 규약("스펙을 재작성하지 않고
gap 만 보고")과 맞지 않아, 메모리에 기록된 #314 선례에 따라 **체크리스트 7번 Spec Self-Review 절만**
적용했다(편차 사유 기록). 발견 gap 과 처방.

| # | gap | 처방 |
|---|---|---|
| 1 | DELETE 2종 "파싱 없음" 판단의 근거가 없었다 | D-Q7 에 근거 명시 (`res.ok` 확인만, 본문 없음) |
| 2 | **★에러 경로가 Zod 를 우회한다는 사실이 없었다** | D-Q7 신설. A1 을 "성공(2xx) 경로 한정"으로 좁힘. #314 의 403 회귀 테스트를 이 PR 의 증거로 쓰지 않음을 명시 |
| 3 | 401/403 화면 경로가 EC 에 없었다 | EC-9 신설 (초록이어도 계약 증거 아님) |
| 4 | **★낙관적 업데이트가 새 캐시 타입과 충돌 — 구현 블로커** | D-Q8 신설 (3지점 실측), FR **C9** 신설, EC-10·EC-11 신설 |
| 5 | A3 "구 스키마 0건" 판정 불가 (무엇이 "구"인지 미정, 이름 재사용 가능) | 판정식을 **"같은 스키마를 쓰는 함수 쌍의 백엔드 반환형이 동일한가"** 로 교체. C4 문구도 정정 |
| 6 | A9 조립 부팅의 통과 기준이 없었다 | 판정식 3개 명시. ②(수정 전 실패 재현)가 단정을 관측으로 승격 |
| 7 | **★A10 을 MSW 로 하면 또 "MSW 가 MSW 와 맞는다"** | A10 을 **시각 회귀 전용**으로 한정하고 계약 증거는 A9 가 담당함을 명시 |
| 8 | 본문이 "실서버와 통신한 적 없다"를 단정형으로 썼으나 ADR 잔여위험 1 은 미관측이라 함 — **내부 불일치** | 본문에 주장 강도 구분 박스 추가. #314 가 다친 지점의 역방향 규율 |
| 9 | assignable 스키마 재사용이 "검토"로 미결 + D4 문구와 형식 충돌 | C4 를 "형태가 다른 두 응답" 으로 정정 — 같은 형태는 재사용이 정답 |
| 10 | A4 `.transform()` 측정이 파일 한정이라 다른 파일로 옮기면 통과 | scheme 관련 전체 grep 으로 확대. 파일 한정 금지 명시 |

**코드로 확정한 2건** (추측 아님).
- D-Q6 — DTO 만 개명해도 컴파일된다. `src/main` 에서 응답 DTO 의 `isDefault` 를 읽는 코드 0건 ⇒ ADR D2 실현 가능
- D-Q7 — `parseResponse:128-131` 이 비-2xx 에서 Zod 를 건너뛴다 ⇒ 에러 경로 초록은 계약 증거가 아니다

**축2 봉인의 남은 구멍도 정직하게 기록했다** — 백엔드 테스트가 단정하지 않는 필드는 스냅샷에 담기지
않는다(실측: 34건 중 `isStandard` 단정 0건). 보완으로 스냅샷은 필드 집합 전량 직렬화 + 프론트는
`.strict()` 파싱을 요구한다.
