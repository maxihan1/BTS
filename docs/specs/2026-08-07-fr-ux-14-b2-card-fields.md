# FR-UX-14 B2 — 보드/백로그 카드 응답 필드 확장 (유형·라벨·추정) — 스펙

> 날짜: 2026-08-07 | BC: agile-planning (+ shared-kernel 포트 · issue-tracking 어댑터)
> 정본: `docs/plan/product/personalization.md` §4.12 FR-UX-14 **D4/D5**
> ADR: [2026-08-07-fr-ux-14-b2-card-fields](../decisions/2026-08-07-fr-ux-14-b2-card-fields.md)
> plan: [2026-08-07-fr-ux-14-b2-card-fields](../plans/2026-08-07-fr-ux-14-b2-card-fields.md)
> PR: #346 | 후속: F14(카드 화면 밀도) — 별도 PR

`## Jira 대조` 는 **비-UI 타입이므로 생략**한다 (이 PR 은 화면을 0파일 바꾼다).

## 1. 문제

보드/백로그 카드가 **제목·키·담당자 3필드**뿐이라 한눈에 읽히는 정보가 없다.
원인은 화면이 아니라 **응답**이다 — `BoardCardResponse` 에 유형·라벨·추정이 없어 프론트가
그릴 재료를 못 받는다. 정본이 **B2 를 F14 의 유일한 차단점**으로 지목한 이유다.

## 2. 사용자 시나리오 (Given-When-Then)

이 PR 은 API 계약 변경이므로 시나리오의 "사용자"는 **카드를 그리는 클라이언트**다.
눈에 보이는 변화는 F14 에서 나온다.

- **S1.** Given 프로젝트에 유형이 `bug` 이고 라벨 `["urgent","api"]` · 추정 3600초인 이슈가 있고,
  When 클라이언트가 `GET /api/v1/projects/{key}/boards/{boardId}` 를 호출하면,
  Then 그 이슈의 카드 객체에 `typeKey="bug"` · `labels=["urgent","api"]` ·
  `originalEstimateSeconds=3600` 이 담겨 온다.
- **S2.** Given 같은 이슈가 백로그(스프린트 미할당)에 있고, When
  `GET /api/v1/projects/{key}/backlog` 를 호출하면, Then 동일 3필드가 같은 값으로 담겨 온다.
  **보드와 백로그가 같은 값을 준다** (둘 다 `BoardIssueView` 를 미러하므로).
- **S3.** Given 라벨이 하나도 없는 이슈가 있고, When 카드를 조회하면,
  Then `labels` 는 `null` 이 아니라 **빈 배열 `[]`** 로 온다 (`issues.labels` 가
  `NOT NULL DEFAULT '{}'` 이므로 도메인에서 이미 빈 리스트다).
- **S4.** Given 추정을 입력하지 않은 이슈가 있고, When 카드를 조회하면,
  Then `originalEstimateSeconds` 는 **`null`** 로 온다 (`INT NULL` 컬럼).
- **S5.** Given 스프린트에 할당된 이슈가 있고, When 백로그를 조회하면,
  Then 스프린트 섹션의 이슈에도 3필드가 동일하게 담긴다 (`SprintIssuesResponse.issues`
  역시 `BacklogIssueResponse` 배열이다).
- **S6.** Given 보드 카드 필터(담당자/라벨/컴포넌트)가 걸린 조회이고, When 필터 결과를 받으면,
  Then 필터 통과 카드에 3필드가 정상 포함된다 (필터는 WHERE 술어일 뿐 SELECT 를 바꾸지 않는다).
- **S7.** Given 뷰어가 볼 수 없는 보안 등급 이슈가 섞여 있고, When 카드를 조회하면,
  Then 그 이슈는 **여전히 결과에서 제외**된다 — 3필드 추가가 보안 술어를 건드리지 않는다.

## 3. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR1 | `BoardIssueView`(shared-kernel)에 `typeKey: String` · `labels: List<String>` · `originalEstimateSeconds: Int?` 를 추가한다 |
| FR2 | `typeKey` 는 **기본값을 두지 않는다**(필수 인자). `labels` 는 `= emptyList()`, `originalEstimateSeconds` 는 `= null` 기본값을 둔다 (Maxi 확정 2026-08-07) |
| FR3 | `listVisibleForBoard` 에 `ISSUE_TYPES` **INNER JOIN** 을 추가하고 `ISSUE_TYPES.KEY` 를 별칭으로 뽑는다. `listVisibleForTimeline:886` 과 **동일한 조인 형태**를 쓴다 |
| FR4 | `BoardIssueEntry` 에 `typeKey: String` 을 추가하고, fetch 람다에서 `?: error(...)` 비-null 단언으로 추출한다 (타임라인 `:899-901` 승계) |
| FR5 | `toBoardIssueView()` 가 `typeKey`(entry) · `labels`(issue) · `originalEstimateSeconds`(issue) 3필드를 매핑한다 |
| FR6 | `BoardCardResponse` 에 3필드를 추가하고 `from(card: BoardIssueView)` 가 매핑한다 |
| FR7 | `BacklogIssueResponse` 에 3필드를 추가하고 `from(view: BoardIssueView)` 가 매핑한다 |
| FR8 | `labels` 는 값이 없을 때 `null` 이 아니라 **빈 배열**로 직렬화된다 |
| FR9 | `IssueRepository.kt:745` 의 *"type 요약은 보드 카드에 불필요하므로 ISSUE_TYPES JOIN 생략"* 주석과 `BoardIssueLookupAdapter.kt:119` 의 *"type/description 등은 제외"* 주석, `BoardIssueView` KDoc 의 "최소 필드만" 서술을 **새 사실에 맞게 정정**한다 |
| FR10 | 기존 7/8필드의 값·순서·널 여부는 **불변**이다 (회귀 0) |

**범위 밖 (명시적 제외).**
- `typeName` · `typeIconName` 노출 — 프론트가 기존 타입 목록 API 에서 얻는다 (ADR §D-1)
- `apps/web` 변경 — 이 PR 은 **0파일**. F14 소관
- 보드 조회 정렬 보조키 추가 — 선재 결함, ADR §범위 밖 참조

## 4. 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| NFR1 | **N+1 미발생.** 카드 건수와 무관하게 보드 조회의 SQL 실행 횟수가 일정하다 | 통합테스트에서 쿼리 수 계측 (§7 참조) |
| NFR2 | 보드 200건 p95 **1.5s** 유지 (SDD §2.3.1 기존 임계). 조인 1개 추가가 이를 깨지 않는다 | BC 완료 게이트 k6 (이 PR 은 회귀 0 확인) |
| NFR3 | `BOARD_CARD_FETCH_LIMIT = 1000` 의 **LIMIT+1 truncated 판정이 불변**이다. INNER JOIN 은 N:1 이라 행이 늘지 않는다 | §7 T-TRUNC |
| NFR4 | 응답 크기 증가는 카드당 3필드로 한정한다 (유형 이름·아이콘 미포함이 이 상한의 근거) | 코드 리뷰 |
| NFR5 | 기존 프론트가 깨지지 않는다 — `boardCardSchema`·`backlogIssueSchema` 는 `.strict()` 가 아니라 미지의 키를 버린다 | 프론트 유닛 무수정 통과 |

## 5. API 인터페이스 (REST)

**엔드포인트 신규 0건.** 기존 2경로의 응답 객체에 필드만 는다.

### 5.1 `GET /api/v1/projects/{projectKey}/boards/{boardId}` — 카드 객체

```jsonc
{
  "issueKey": "ATLAS-42",
  "summary": "로그인 실패 시 안내 문구 개선",
  "assigneeId": "3f2a…",
  "priority": 2,
  "version": 7,
  "epicKey": "ATLAS-1",
  "rank": "0|hzzzzz:",
  // ↓ 이 PR 이 추가하는 3필드
  "typeKey": "bug",
  "labels": ["urgent", "api"],
  "originalEstimateSeconds": 3600
}
```

7필드 → **10필드**. `BoardCardResponse` 에는 `currentStateKey` 가 없다 — 컬럼 배치를
서버가 이미 끝냈기 때문이며 이 PR 도 그대로 둔다.

### 5.2 `GET /api/v1/projects/{projectKey}/backlog` — 이슈 객체

```jsonc
{
  "key": "ATLAS-42",              // ← 보드는 issueKey, 백로그는 key (기존 비대칭, 유지)
  "summary": "로그인 실패 시 안내 문구 개선",
  "currentStateKey": "in-progress",
  "assigneeId": "3f2a…",
  "priority": 2,
  "rank": "0|hzzzzz:",
  "version": 7,
  "epicKey": "ATLAS-1",
  // ↓ 이 PR 이 추가하는 3필드
  "typeKey": "bug",
  "labels": [],                   // 라벨 없으면 빈 배열 (null 아님)
  "originalEstimateSeconds": null // 미추정이면 null
}
```

8필드 → **11필드**. `backlog` 배열과 `sprints[].issues` 배열 **양쪽에 동일 적용**된다.

## 6. 데이터 모델 변경

**없음 — 확정.** 마이그레이션 0건. 필요한 컬럼이 이미 전부 존재한다.

| 필드 | 컬럼 | 도입 | 보드 조회에 이미 포함? |
|---|---|---|---|
| `labels` | `issues.labels TEXT[] NOT NULL DEFAULT '{}'` | V006 | ✅ `ISSUES.fields()` |
| `originalEstimateSeconds` | `issues.original_estimate_seconds INT NULL` | V027 | ✅ 동일 |
| `typeKey` | `issue_types.key VARCHAR(30) NOT NULL UNIQUE` | V003 | ❌ JOIN 필요 |

`issues.type_id` 는 V005 에서 `SET NOT NULL` + FK 라 **INNER JOIN 이 행을 잃지 않는다**.

## 7. 엣지 케이스

| ID | 상황 | 기대 |
|---|---|---|
| E1 | 라벨 0개 | `labels: []` (빈 배열). `null` 이면 실패 |
| E2 | 추정 미입력 | `originalEstimateSeconds: null` |
| E3 | 라벨이 많은 이슈 | 전량 그대로 노출. 잘라내지 않는다 |
| E4 | 커스텀 이슈 타입(표준 5종 밖) | `typeKey` 는 그 타입의 key 를 그대로 준다. 프론트가 아이콘 매핑에 실패하면 fallback 아이콘을 쓰는 것은 F14 소관 |
| E5 | soft-deleted 이슈 | 기존과 동일하게 제외 (WHERE 술어 불변) |
| E6 | 뷰어 비가시 보안등급 이슈 | 기존과 동일하게 제외. **3필드 추가가 보안 경로를 우회하지 않는다** |
| E7 | cross-project 에픽 | `epicKey` 는 기존대로 null. 이 PR 이 건드리지 않는다 |
| E8 | 카드 1,001건 (LIMIT 초과) | `truncated: true` + 1,000건 반환 — **판정이 기존과 동일** (NFR3) |
| E9 | 보드 필터 적용 조회 | 필터 통과 카드에 3필드 정상 포함 (S6) |
| E10 | 스프린트 할당 이슈 | 백로그 응답의 `sprints[].issues` 에도 3필드 포함 (S5) |

## 8. 제약 조건

- **C1. BC 격리.** `shared-kernel` 정의 → `issue-tracking` 구현 → `agile-planning` 소비 **단방향**.
  세 모듈이 한 포트 계약으로 묶인 하나의 논리 변경이며, 선례 커밋 `dcbf130e6`(FR-BD-02)와 동형이다.
- **C2. ktlintFormat 모듈 전체 실행 금지.** 신규 코드의 lint 위반은 파일 단위 수동 수정.
  검증은 `ktlintCheck` / `ktlintMainSourceSetCheck` (`runKtlintCheckOverMainSourceSet` 신뢰 금지) —
  `learnings.md:647`.
- **C3. 보안 경로 불변.** `buildActiveSecureWhere` 를 건드리지 않는다. 조인 추가는 SELECT 절과
  FROM 절에만 영향을 준다.
- **C4. 정렬 불변.** `orderBy(ISSUES.CREATED_AT.desc())` 를 건드리지 않는다 (선재 결함이지만 범위 밖).
- **C5. TDD.** red → green → refactor. `test:` 커밋이 `feat:` 커밋보다 먼저여야 한다.

## 9. 측정 가능한 완료 기준

### 9.1 테스트 (D5)

| ID | 테스트 | 위치 | 무엇을 못박나 |
|---|---|---|---|
| T-VO | `BoardIssueView` 3필드 보유 + 기본값 규약 | `shared-kernel BoardIssueViewTest` | FR1·FR2 |
| T-PORT | **필드 계약 정본 갱신** + 기본값 규약 못박기 (§9.3) | `shared-kernel BoardPortContractTest` | FR1·FR2 |
| T-REPO | `listVisibleForBoard` 가 `typeKey` 를 채워 반환 | `issue-tracking` Testcontainers | FR3·FR4 |
| T-LABEL | 라벨 있는 이슈 → 값 노출 / 없는 이슈 → `[]` | 동일 | FR5·E1 |
| T-EST | 추정 있는 이슈 → 값 / 없는 이슈 → `null` | 동일 | FR5·E2 |
| T-ADAPT | `toBoardIssueView()` 3필드 매핑 | `BoardIssueLookupAdapterTest` (Testcontainers) | FR5 |
| **T-N1** | **쿼리 수가 카드 수와 무관하게 일정** | `BoardIssueLookupAdapterTest` | **NFR1 (정본 지정 판정식)** |
| T-TRUNC | 1,000/1,001건 경계에서 `truncated` 판정 불변 | 동일 | NFR3·E8 |
| T-SEC | 비가시 등급 이슈 여전히 제외 | 기존 보안 테스트 확장 | E6 |
| T-BOARD-DTO | `BoardCardResponse.from` 3필드 매핑 | `BoardResponsesTest` | FR6 |
| T-BACKLOG-DTO | `BacklogIssueResponse.from` 3필드 매핑 | 백로그 DTO 테스트 | FR7 |
| T-API-BOARD | 보드 단건 조회 JSON 에 3필드 | `BoardControllerIntegrationTest` | S1 |
| T-API-BACKLOG | 백로그 조회 JSON 에 3필드 (backlog + sprints 양쪽) | 백로그 컨트롤러 통합테스트 | S2·S5·E10 |

### 9.2 T-N1 의 관측 방법 — **비-공허 확인 필수**

`BoardIssueLookupAdapterTest` 는 이미 Testcontainers 기반(`IssueTestcontainersBase`)이라
**실 DB 쿼리를 셀 수 있다**. 계측은 jOOQ `ExecuteListener` 로 `executeStart` 횟수를 세는 방식을
1순위로 한다.

판정식은 **"쿼리 수가 상수"** 다 — 카드 N건과 M건(N≠M, 예: 3건 vs 30건)에서 실행 횟수가
**같아야** 한다. `accessibleLevels` 1회 + `listVisibleForBoard` 1회가 카드 수와 무관해야 한다.

> **★ 가짜 그린 금지 (이 프로젝트 반복 사고).** 가드를 넣은 뒤 **일부러 N+1 을 만들어
> 그 테스트가 실제로 빨간불이 되는지**를 확인하고, 그 관측을 PR 본문에 인용한다.
> 도달 불가능한 상태를 지키는 테스트는 초록인 채로 아무것도 재지 않는다
> ([[unreachable-state-fixture-is-fake-green]] · PR #342 에서 같은 양식 2회 적발).

### 9.3 픽스처 요건 — 대조군 없는 테스트는 초록인 채 아무것도 안 잰다

Phase B sanity check 가 적발한 gap 3건의 처방이다. 이 절은 **선택이 아니라 완료 기준**이다.

#### GAP-1. 값이 같은 픽스처는 매핑 오류를 못 잡는다

이슈를 전부 같은 값으로 만들면, 매핑이 **뒤바뀌거나**(예: A 이슈의 라벨이 B 카드에 붙음)
조인이 **틀린 행을 물어와도**(예: 항상 첫 타입) 테스트가 초록이다.
FR-UX-13 F16 이 정확히 이 사고를 겪었다 — 백로그 픽스처 7건이 전부 `epicKey: null` 이라
간판 기능에 **증인이 아예 없었다**.

**요건.** 저장소/어댑터 레벨 픽스처는 축마다 **서로 다른 값 2건 이상 + 빈 값 대조군 1건**을 갖는다.

| 축 | 최소 픽스처 |
|---|---|
| `typeKey` | 서로 **다른 타입** 2건 이상 (예: `bug` · `story`). 단일 타입이면 조인이 틀려도 초록 |
| `labels` | 라벨이 **서로 다른** 이슈 2건 + **빈 배열** 이슈 1건 |
| `originalEstimateSeconds` | 값이 **서로 다른** 이슈 2건 + **`null`** 이슈 1건 |

**판정.** 각 카드가 **자기 이슈의 값**을 갖는지 키 단위로 대조한다. "어떤 카드엔가 `bug` 가 있다"는
판정으로는 부족하다 — 어느 카드에 붙었는지를 봐야 한다.

#### GAP-2. 컴파일러 강제는 팩토리 헬퍼 한 겹에서 흡수된다

`typeKey` 를 필수 인자로 두면 컴파일러가 생성 지점을 강제하지만, **그 강제는 한 홉만 간다**.
기존 테스트 중 둘은 이미 **팩토리 헬퍼**를 갖고 있다.

- `BoardResponsesTest.card(key, epicKey)` (`:44`)
- `BoardCardPlacementTest.issueView(key, currentStateKey, priority, summary, rank)` (`:32`)

헬퍼에 `typeKey = "task"` 같은 기본값을 넣는 순간 **그 아래 테스트들은 아무것도 강제받지 않는다.**
[[fr-ux-13-f16-backlog-filter-epic-done]] 의 *"브랜드 타입은 한 홉만 막는다"* 와 동형이다.

**요건.**
1. **매핑의 진짜 증인은 실 DB 다.** `toBoardIssueView()` · `listVisibleForBoard` 검증은
   헬퍼로 만든 VO 가 아니라 **Testcontainers 에 넣은 실제 행**의 값으로 판정한다.
2. 헬퍼에 기본값을 넣는 것 자체는 허용한다(배치·정렬 테스트는 3필드와 무관하므로).
   다만 **그 헬퍼를 쓰는 테스트를 3필드의 증인으로 세지 않는다.**
3. 완료 기준은 "컴파일이 통과했다"가 아니라 **"실 DB 값이 카드까지 도달했다"** 이다.

#### GAP-3. `BoardPortContractTest` 가 필드 계약의 정본이다

이 테스트의 KDoc 은 검증 항목을 *"[BoardIssueView] 필드 계약(key/summary/currentStateKey/
assigneeId/priority/version)"* 으로 **열거**한다. 3필드를 추가하고 이 목록을 갱신하지 않으면
계약 테스트가 새 필드를 안 지키는 채로 초록이 된다.

**요건.**
1. KDoc 열거와 검증 본문에 **3필드를 추가**한다.
2. **기본값 규약을 계약으로 못박는다.** 이 파일에는 이미 선례가 있다 —
   *"`WorkflowStateView` 기존 2-arg 호출이 category DEFAULT_TODO 와 displayOrder 0 으로 생성된다"*.
   같은 형태로 **`labels` 미지정 시 빈 리스트 · `originalEstimateSeconds` 미지정 시 null** 을
   테스트로 고정한다. FR2 가 코드 주석이 아니라 **테스트로** 지켜지게 하기 위함이다.

### 9.4 빌드·회귀

- `:modules:shared-kernel:test` · `:modules:issue-tracking:test` · `:modules:agile-planning:test` 전량 green
- `ktlintCheck` · `detekt` 신규 위반 0
- **`apps/web` 변경 0파일** — `git diff --name-only main...HEAD | grep -c '^apps/'` → `0`
- **마이그레이션 0파일** — `git diff --name-only main...HEAD | grep -c 'db/migration'` → `0`
- 프론트 유닛 무수정 통과 (NFR5 확인 — 백엔드 필드 추가가 Zod 파싱을 안 깬다)

### 9.5 문서 동기화

- 정본 `docs/plan/product/personalization.md` §4.12 의 D1~D5 마커 갱신 + **D4 문구 정정**
  (`typeIconName` 제외 · SELECT 확장 범위 축소)
- `bash scripts/verify-master-plan.sh` 통과 (종료 4 차단 없음)
- FR 총수 **139 불변** (신규 FR 0)

## 10. Brainstorming Check

**✅ 통과 (1회 iteration — gap 3건 발견 후 §9.3 신설로 보강)**

검토 축은 이 프로젝트의 반복 사고 2종이었다 — 「가드가 공허하다」(도달 불가능한 상태를 지키는
테스트가 초록인 채 아무것도 안 잼)와 「봉합이 절반」(두 경로 중 하나만 고침).

### 통과한 항목 (실측으로 위험 없음 확인)

| 의심 | 실측 결과 |
|---|---|
| 백로그 응답의 두 배열(`backlog` · `sprints[].issues`)을 한쪽만 고칠 위험 | **없음.** `BacklogApplicationService:137`·`:150` 이 각각 호출하지만 **같은 `from` 함수**를 거친다. 구조적으로 갈라질 수 없다 |
| 포트에 제3의 소비자가 있어 빠뜨릴 위험 | **없음.** 소비자 3개 중 `SprintApplicationService` 는 `isVisibleIssue`(단건 boolean)만 쓴다(`:320`). 3필드와 무관 |
| 도메인 배치 로직이 VO 를 재생성해 매핑 지점이 늘 위험 | **없음.** `BoardCardPlacement` 는 `BoardIssueView` 를 **읽기만** 한다(정렬 Comparator). 생성·copy 0건 |
| 백엔드 필드 추가가 프론트를 깰 위험 | **없음.** `boardCardSchema`·`backlogIssueSchema` 모두 `.strict()` 없는 `z.object` 라 미지의 키를 버린다. 프론트 계약 스냅샷 테스트도 0건 |

### 발견된 gap 3건 → §9.3 으로 봉합

| ID | gap | 처방 |
|---|---|---|
| GAP-1 | 픽스처 값이 같으면 매핑이 뒤바뀌거나 조인이 틀린 행을 물어와도 초록이다. FR-UX-13 F16 이 겪은 사고와 동형 | §9.3 — 축마다 **서로 다른 값 2건 + 빈 값 대조군 1건**, 판정은 **키 단위 대조** |
| GAP-2 | `typeKey` 필수화의 컴파일러 강제가 **팩토리 헬퍼 한 겹에서 흡수**된다 (`BoardResponsesTest.card:44` · `BoardCardPlacementTest.issueView:32`). 「브랜드 타입은 한 홉만 막는다」와 동형 | §9.3 — 매핑의 증인은 헬퍼 VO 가 아니라 **Testcontainers 실 행**. 완료 판정은 "컴파일 통과"가 아니라 "실 DB 값이 카드까지 도달" |
| GAP-3 | `BoardPortContractTest` 가 필드 계약의 **정본**인데(KDoc 이 필드를 열거) 스펙이 갱신 요건을 안 적었다 | §9.3 — KDoc·검증 본문에 3필드 추가 + `WorkflowStateView` 선례대로 **기본값 규약을 계약 테스트로 못박기** |

**결론.** 구조적 위험(봉합 절반)은 없고, 실제 위험은 전부 **테스트가 무엇을 재느냐**에 몰려 있었다.
FR2 의 기본값 결정이 편의가 아니라 **안전장치**로 작동하려면 §9.3 이 함께 지켜져야 한다.
