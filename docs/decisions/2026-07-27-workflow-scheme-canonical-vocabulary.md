<!-- 워크플로우 스킴 식별자의 정본 어휘를 key + isStandard 로 확정하고 계약 정렬 범위를 뷰 레이어로 한정하는 결정 -->
# 워크플로우 스킴 정본 어휘 — `key` + `isStandard`, 정렬 범위는 뷰 레이어 한정

> 상태: 채택(Accepted)
> 날짜: 2026-07-27
> BC: project-workflow (단일) — cross-BC 0
> 맥락: #314 plan-eng-review outside voice 가 발견한 선재 계약 파손, slug `workflow-scheme-contract-align`
> 관련: [2026-07-26-workflow-scheme-read-permission-gate](2026-07-26-workflow-scheme-read-permission-gate.md) §잔여위험 6 (이 부채를 등재) ·
> [2026-06-30-fr-api-01-cursor-pagination-envelope](2026-06-30-fr-api-01-cursor-pagination-envelope.md) (응답 계약 변경의 선례)
> 선행: TODOS.md 「project-workflow — 워크플로우 스킴 프론트↔백엔드 계약 파손」

## 배경

### 파손 규모 — TODOS.md 기록보다 크다

TODOS.md 는 불일치 **3종**으로 기록했다. 착수 전 전수 재측정 결과 **응답 7종 + 요청 1종**이고,
지목된 백엔드 DTO **2건이 오기**였다. 전문은 `docs/plans/2026-07-27-workflow-scheme-contract-align.md`
§Brief. 메모리 [[spec-stated-count-becomes-blindfold]] 재발 — 기록된 개수를 물려받지 않는다.

**변환 계층 부재 확정.** `PropertyNamingStrategy` 0건 · `@JsonProperty` 0건 ·
`.transform()` 은 `assignableSchemeResponseSchema` 1건뿐. 못 본 변환 층은 없다.

`z.object()` 는 선언 키 부재 시 throw 하므로 응답 파싱 8 endpoint 중 **7 경로가 실서버에서
`ZodError`** 로 깨진다. 요청 쪽은 `POST /api/v1/workflow-schemes` 가 `schemeKey` 를 보내는데
백엔드 `CreateWorkflowSchemeRequest.key` 가 Kotlin non-null 이라 역직렬화 실패 400 이다.

### 근본 원인 — 개별 실수 7개가 아니다

**프론트 Zod 스키마 3장을 각각 서로 다른 두 개의 백엔드 DTO 에 재사용**하고 있다.

| Zod 스키마 1장 | 이 응답에도 | 저 응답에도 |
|---|---|---|
| `schemeResponseSchema` | `WorkflowSchemeDetailResponse` (카운트 보유) | `WorkflowSchemeResponse` (카운트 없음) |
| `assignmentResponseSchema` | `SchemeResponse` (스킴 정보) | `AssignmentResponse` (배정 이력) |
| `mappingResponseSchema` | `MappingResponseDetail` (키·이름) | `MappingResponse` (내부 PK) |

즉 스키마를 고치는 문제가 아니라 **endpoint 수만큼 스키마를 분리**하는 문제다.

### 어휘 규약은 이미 코드에 두 번 확립돼 있다

| 개념 | 백엔드 | 프론트 | 일치 |
|---|---|---|---|
| IssueType (`IssueTypeResponse.kt:27`) | `key` · `isStandard` | — | ✅ |
| Resolution | `key` · `isStandard` | `resolutions.ts:19,27` `key` · `isStandard` | ✅ |
| **WorkflowScheme** | `key` ✅ · **`isDefault`** ❌ | **`schemeKey`** ❌ · `isStandard` ✅ | **양쪽이 각각 절반씩 이탈** |

**정본은 이미 정해져 있었고 워크플로우 스킴만 예외다.** 백엔드 자체도 갈린다 —
`CreateWorkflowSchemeRequest` 는 `key`, `AssignSchemeRequest` 는 `schemeKey`.

### ★`isDefault` 라는 이름 하나가 서로 다른 두 개념을 가리킨다

| 개념 | 뜻 | 정확한 이름 | 현재 |
|---|---|---|---|
| **표준 스킴** | 시스템 4개(`software-scheme` 외). `key`/`name`/`description`/`is_default` lock + 삭제 차단 (D11) | `isStandard` | `isDefault` ❌ |
| **기본 매핑** | 스킴당 1개. `issue_type_id IS NULL` — 이슈타입 미지정 시 대체 워크플로우. 중복 시 409 `MAPPING_DEFAULT_DUPLICATE` | `isDefault` | `isDefault` ✅ |

DB 주석조차 `workflow_schemes.is_default` 를 *"true = 시스템 표준 스킴"* 이라 설명한다
(`V201__workflow_schemes.sql:37`) — **컬럼명과 주석이 이미 어긋나 있었다.**
프론트가 스킴만 `isStandard` 로 개명한 압력의 출처가 이 충돌로 보인다.
또한 권한 스킴의 「기본 스킴」(명시 매핑 없을 때 fallback, V013 `00000000-…-001`)과도 이름이 겹친다.

### 소비자 전수 (확정)

8 endpoint 전체에 대해 `apps/web` 밖 소비자 **0건** — 매치 전부 project-workflow 자체 테스트.
발행된 정적 OpenAPI 스펙 0건. ⇒ 응답 필드명을 바꿔도 깨질 외부 계약이 없다.

## 결정

### D1 — 정본 어휘를 `key` + `isStandard` 로 확정한다 (Maxi 결정, B안)

백엔드 응답 DTO 는 `isDefault` → `isStandard`, 프론트는 `schemeKey` → `key` 로 각각 수렴한다.
결과는 **변환 계층 0개 · 어휘 한 벌 · drift 지점 0.**

근거.
1. 형제 개념 2종(IssueType · Resolution)이 이미 이 규약으로 양쪽 일치. 취향이 아니라 **정합성** 사안이다.
2. 확립된 한국어 용어가 「표준 스킴」이고 DB 주석도 그렇게 적혀 있다. `isStandard` 가 용어와 일치한다.
3. 학습 2026-05-23 「fixture 옵션 B 패턴」이 *"회귀 가드 테스트는 보조 — 사람 의존 0 인 본질 차단
   패턴이 우선"* 이라고 명시한다. 변환 층은 사람이 매번 옳게 써야 하는 지점이고, 어휘 통일은 본질 차단이다.
4. 외부 소비자 0건 실측 — 응답 필드명 변경의 폭발 반경이 저장소 안에 갇힌다.

**부수 효과.** 스킴 쪽이 `isStandard` 가 되면 남은 `isDefault`(기본 매핑)는 진짜 "기본"이라
이름이 정확해진다. 두 개념의 이름 충돌이 해소된다.

### D2 — 백엔드 변경 범위는 뷰 레이어 한정 (Maxi 결정)

`WorkflowSchemeDto.kt`(7) · `WorkflowSchemeController.kt`(1) · `ProjectWorkflowSchemeController.kt`(5)
= **13 참조 / 3 파일**만 변경한다. 도메인 `WorkflowScheme.isDefault`(9) · 애플리케이션(14) ·
repository(5) = 28 참조와 DB 컬럼 `is_default` 는 **그대로 둔다.**

근거.
1. **이번 결함의 원인은 뷰 레이어다.** 도메인 언어는 이 파손에 기여하지 않았다.
2. 학습 2026-05-22 가 이 경계를 이미 그었다 — *"same BC 의 view layer (DTO + mapping) 갱신은
   frontend PR 안에서 처리 가능. 별도 PR 분리는 다른 BC 간 호출 / 도메인 모델 변경 / Flyway
   마이그레이션 같이 BC 경계를 명시적으로 넘는 경우만."*
3. **도메인 언어 ≠ 발행 언어(published language)는 결함이 아니다.** 같은 BC 안에
   `SchemeIssueTypeMapping.issueTypeId`(도메인 PK) → `issueTypeKey`(DTO 키) 변환이 이미 같은 패턴이다.
4. 마이그레이션 0 — 동시 세션(`backend/fr-co-02`)이 진행 중이라 V번호 충돌
   (메모리 [[migration-vnumber-concurrent-branch-collision]])과 적용된 마이그레이션 체크섬 함정
   (메모리 [[app-test-persistent-db-migration-checksum-trap]])을 감수할 이득이 없다.
5. CLAUDE.md §3 surgical — 변경은 요청받은 것만.

**이연.** 도메인 객체·DB 컬럼의 `isDefault` → `isStandard` 는 별도 리팩토링 PR. TODOS.md 등재.

### D3 — `mappings[].isDefault` 는 백엔드가 명시 필드로 낸다 (파생 불가, 코드로 확정)

`MappingResponseDetail` 에 `isDefault: Boolean` 을 추가하고
`from()` 에서 `mapping.issueTypeId == null` 로 산출한다.

**프론트에서 `issueTypeKey === null` 로 파생하면 새 결함이 생긴다.**
`WorkflowSchemeApplicationService.kt:502` 가

```kotlin
val issueTypeRef = mapping.issueTypeId?.let { issueTypeRefMap[it] }
```

이므로 `issueTypeRef` 가 null 인 경우가 **두 가지**다 — ① `issueTypeId == null` (진짜 기본 매핑)
② `issueTypeId != null` 인데 cross-BC 조회 결과에 없음 (issue-tracking lookup 누락/실패).
`issueTypeKey = issueTypeRef?.key` 라 둘이 구분되지 않는다.
⇒ 조회 실패한 실제 매핑이 UI 에서 **★ 기본 매핑으로 오표시되고 정렬 최상단으로 올라간다**
(`MappingTable.tsx:122,130,134,140,331-332` 가 `mapping.isDefault` 를 실제 소비).

`from()` 안에서는 `mapping.issueTypeId` 를 직접 볼 수 있어 모호성이 없다. **정보가 있는 곳에서 판정한다.**

### D4 — Zod 스키마를 endpoint 별로 분리한다 (어휘 결정과 독립적으로 필수)

한 스키마가 두 응답을 검사하는 구조 자체를 없앤다. D1 을 어느 안으로 정했더라도
`AssignmentResponse`(배정 이력)와 `SchemeResponse`(스킴 정보)는 **어휘가 아니라 의미가 다른 DTO** 라
분리가 불가피하다. 이것이 이 작업을 `type=ui` 로 판정한 근거이기도 하다.

### D5 — MSW 픽스처를 백엔드 응답 형태로 교체한다

현재 `scheme-fixtures.ts`·`scheme-handlers.ts` 가 **프론트 형태**를 돌려주기 때문에 단위·MSW·E2E
세 겹이 전부 초록이었다. 픽스처가 백엔드 형태가 되지 않으면 계약을 고쳐도 **테스트는 여전히
아무것도 증명하지 않는다.** 학습 2026-06-25 「MSW lexical 비교로 가짜그린」과 동질 —
*"초록은 MSW 가 MSW 와 맞는다는 뜻"* 을 끊는 지점이 여기다.

## 기각안

### A안 — 경계에서 `.transform()` 만 추가 (프론트 정규화, 백엔드 무변경)

**장점.** #314 D3=1A 가 `assignable` 에 이미 적용한 선례. 최소 변경. 프론트 컴포넌트 0 변경.
**기각 사유.** 계약 파손은 고치지만 **원인(어휘 이중화)을 남긴다.** 변환 지점이 1개 → 7개로 늘어
다음 endpoint 추가 시 같은 실수가 재발할 자리를 6곳 만든다. 학습 2026-05-23 의 "본질 차단 우선"에 반한다.
※ `assignable` 의 `.transform()` 은 D1 적용 후 불필요해지므로 함께 제거한다.

### C안 — 프론트가 백엔드 어휘(`key`/`isDefault`)를 그대로 채택

**장점.** 변환 0. 백엔드 무변경. 프론트만 수정(21 + 17 참조).
**기각 사유.** 이 레포 표준(IssueType · Resolution 의 `isStandard`)에서 이탈한다. 확립된 한국어 용어
「표준 스킴」·DB 주석과 코드가 계속 어긋난다. D11 이 의도적으로 붙인 `isStandard` 를 되돌리게 된다.

### 도메인·DB 컬럼까지 rename

**장점.** 이름이 전 계층 일치.
**기각 사유.** 결함 원인이 아닌 곳까지 건드려 리뷰 단위가 계약 정렬 + 도메인 리팩토링으로 섞인다.
DB rename 은 동시 세션 V번호 충돌 + 체크섬 함정 + `type` 이 migration 으로 바뀌어 db-engineer 필요.
이득 대비 비용이 맞지 않는다. TODOS.md 로 이연.

## 결과

- 스킴 관리·배정 화면이 **실서버에서 처음으로 동작 가능**해진다(D-미확정 2 확정 후 단정).
- 어휘 한 벌(`key` + `isStandard`) · 변환 계층 0 · 이름 충돌(표준 스킴 ↔ 기본 매핑) 해소.
- 마이그레이션 0 · 도메인 모델 변경 0 · cross-BC 0 · 신규 의존성 0.
- MSW 픽스처가 백엔드 형태가 되어 프론트 테스트가 회귀를 **실제로** 증명한다.

## 잔여 위험

1. ~~**★조립 부팅 실측 미실시.**~~ → **해소 (2026-07-27, Task 1·8).**
   `WorkflowSchemeContractSnapshotTest`(`:modules:app`, 실 Tomcat + prod 프로파일 + PAT 인증)가
   8 endpoint 를 **실 HTTP 로 왕복**해 응답 본문을 떴다. 코드 대조가 아니라 실응답이 정본이 됐다.
   실측이 뒤집은 것 — **`assignedAt` 은 조립에서 ISO 문자열로 나온다**(슬라이스에서 숫자·배열로
   나가는 사고를 우려했으나 조립에는 없다). 실측이 확인한 것 — 파손 7/8 은 그대로였다.
2. **cross-BC 조회 실패가 여전히 무음이다.** D3 은 `isDefault` 오표시를 막지만, `issueTypeId` 가
   있는데 조회 실패한 매핑은 `issueTypeKey`/`issueTypeName` 이 null 인 채 표시된다. 선재 결함이며
   이 PR 이 만든 것이 아니다. TODOS.md 등재.
3. ~~**springdoc 노출 범위 미확인.**~~ → **확정 (2026-07-27, Task 8 A9-③). 노출된다.**
   조립 `application.yml:99-105` 가 `/v3/api-docs` 를 활성화하고, `OpenApiSecurityConfig.kt:17-25` 의
   `WebSecurityCustomizer.ignoring()` 이 그 경로를 **필터체인에서 통째로 제외**한다(미인증 접근).
   springdoc 은 조립 컨텍스트의 전 `@RestController` 를 스캔하므로 두 스킴 컨트롤러의 응답 필드명이
   그대로 게시된다. ⇒ **응답 필드명은 문서화된 공개 계약이다.** 어휘를 미루면 잘못된 계약이 그만큼
   더 오래 게시된다는 뜻이므로, 이 ADR 의 정렬 결정을 더 강하게 지지한다.
   **api-docs 를 인증 뒤로 돌리는 봉합은 별도 작업**(결정 3A' — `OpenApiSecurityConfig` +
   `OpenApiConfig` **두 곳**, 한 곳만 고치면 효과 0. 2 BC + security-engineer 소관).
   판정 방식 한정 — `:modules:app` 에 OpenApi 테스트가 없어 **정적 증거**(설정 2개소)로 확정했다.
   `ignoring()` 은 조건 분기가 없는 무조건 제외라 정적 판정으로 충분하다.
4. **도메인 ↔ DTO 어휘 갈림.** D2 의 의도된 결과다. `toResponse()` / `from()` 매핑 지점이
   유일한 변환 지점이 되므로 그 한 줄이 load-bearing 이다. 이연된 도메인 rename 까지는 이 상태가 유지된다.
5. ~~**`UpdateSchemeInput.name` nullability 비대칭.**~~ → **해소 (Task 4).**
   `UpdateSchemeInput.name` 을 필수로 바꿔 백엔드 `UpdateWorkflowSchemeRequest.name`(non-null)과 맞췄다.
