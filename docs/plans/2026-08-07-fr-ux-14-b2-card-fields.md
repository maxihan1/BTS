# FR-UX-14 B2 — 보드/백로그 카드 응답 필드 확장 (타입·라벨·추정)

> slug: fr-ux-14-b2-card-fields
> type: backend
> agent: backend-engineer
> primary_bc: agile-planning (+ shared-kernel 포트 · issue-tracking 어댑터)
> 생성: 2026-08-07

## Brief

**사용자 원문.** `/bts fr-ux-14`

**범위 확정 (Maxi 2026-08-07).** FR-UX-14 는 정본상 승계 PR 2건(`B2` 백엔드 → `F14` 프론트)이고,
이번 PR 은 **B2 백엔드 단독**이다. F14(카드 화면 밀도)는 후속 PR.
근거 — 정본 `docs/plan/product/personalization.md §4.12` 의 "승계 PR 2건",
선례 FR-UX-09 의 `B1` 이 별도 PR #328 로 처리된 전례.

**정본 정의.** `docs/plan/product/personalization.md §4.12 FR-UX-14 — 이슈 카드 밀도`
- **B2 = 이 FR 의 D4/D5** (2026-07-29 지위 정정 — 원래 "chore" 였던 것을 FR 의 D 단계로 승격)
- **B2 는 F14 의 유일한 차단점** — `BoardCardResponse` 에 타입·라벨·추정이 없다
- 정본 지목 대상. `shared-kernel .../board/BoardIssueLookupPort.kt` 의 `BoardIssueView` 에
  `typeKey`·`typeIconName`·`labels`·`originalEstimateSeconds` 추가
  + `issue-tracking .../repository/IssueRepository.kt` 보드 카드 SELECT·adapter 확장
  + `agile-planning .../web/dto/BoardResponses.kt`·`BacklogResponses`
- 정본 지정 성공 판정식. **N+1 회귀 가드** (라벨은 `TEXT[]` 컬럼이라 조인 불필요,
  타입은 `listWithType` 이 이미 조인 중)
- 정본 지정 템플릿. **선례 커밋 `dcbf130e6`** (FR-BD-02 보드 필터 — 같은 3모듈 조합 16파일)

## 작업 분류

| 항목 | classify 자동 | 확정값 | 정정 근거 |
|---|---|---|---|
| type | `ui` | **`backend`** | 정본 §4.12 D4/D5 책임 = backend-engineer · 이번 PR `apps/web` 0파일 |
| agent | `frontend-engineer` | **`backend-engineer`** | 위와 동일 |
| slug | `fr-ux-14-b2-shared-kernel-issue-tracking-agile-pla` | **`fr-ux-14-b2-card-fields`** | B1 선례가 동일 증상을 "관례 맞춰 정정" (`2026-07-31-fr-ux-09-b1-create-issue-fields.md:29`) |
| primary_bc | `agile-planning` | `agile-planning` | 유지 (진입점 컨트롤러 소유 BC) |

**오분류 원인.** 제목의 "카드 · 보드/백로그" 를 UI 신호로 읽었다.
**정정하지 않았을 때의 위험.** `type=ui` 는 `/bts` §Phase C 의 「ui 소규모 게이트 정책」을
발동시켜 **게이트 1(Maxi 정지)을 생략**시킨다. 안전 방향으로 정정했다.

## 착수 시 선행 확인 (learnings 반영)

이 작업에 직결되는 것으로 골라둔 항목 — 각 단계가 이 목록을 승계한다.

1. **`learnings.md:754` 파일 존재 ≠ 기능 존재.** "백엔드 완비" 판정은 컨트롤러 HTTP 매핑을
   세어서 한다. **정본 줄번호(`:157-166`·`:155-162`)를 믿지 말고 실측한다** —
   FR-UX-13 에서 정본 줄번호·행수가 **세 번** 틀렸다.
2. **`learnings.md:600` Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다.** 백엔드가 응답 필드를
   늘리면 프론트 Zod 스키마·MSW 핸들러·인라인 mock 이 동시에 깨질 수 있다.
   **B2 단독 PR 이라도 `apps/web` 영향면 전수 grep 이 필요하다** (이번 PR 에서 깨지는지,
   F14 로 미뤄도 되는지를 판정해야 한다).
3. **`learnings.md:647` ktlintFormat 모듈 전체 실행 금지.** 3모듈 Kotlin 작업이라 직결.
   신규 코드 lint 위반은 **파일 단위 수동 수정**. 검증은 `ktlintCheck`/`ktlintMainSourceSetCheck`
   (`runKtlintCheckOverMainSourceSet` 신뢰 금지).
4. **`learnings.md:663` worktree 산출물(docs) 커밋 누락.** 각 단계가 자기 docs 를 그 단계에서
   커밋한다. 머지 직전 `git status --porcelain` 전수 확인.
5. **`learnings.md:235` BC 격리 예외.** 이번은 프론트 없는 백엔드 3모듈이고,
   `shared-kernel` 포트 → `issue-tracking` 어댑터 → `agile-planning` 소비 방향이라
   선례 `dcbf130e6` 와 동형이다. plan §리스크에 사유를 명시한다.
6. **`learnings.md:776` 동시 PR 선점 확인.** 착수 시점 `git worktree list` 0건 ·
   `gh pr list --draft` 0건 실측 완료.

## 도메인 정리

- **BC**. agile-planning (응답 DTO 소유) — 포트는 `shared-kernel`, 어댑터는 `issue-tracking`.
  방향은 `shared-kernel` 정의 → `issue-tracking` 구현 → `agile-planning` 소비 **단방향**이라
  선례 커밋 `dcbf130e6`(FR-BD-02) 와 동형이다. BC 격리 예외 아님 — 세 모듈이 한 포트 계약의
  정의·구현·소비로 묶인 **하나의 논리 변경**이다.
- **영향 VO/DTO**. `BoardIssueView`(shared-kernel) · `BoardCardResponse` · `BacklogIssueResponse`(agile-planning)
- **신규 용어**. **0건.** glossary 의 「이슈 타입」·라벨·추정이 전부 기존 개념 → `glossary.md` 갱신 불필요
- **신규 엔티티/관계**. **0건** → `domain/agile-planning.md` 갱신 불필요
- **마이그레이션**. **0건** — 필요한 컬럼이 이미 전부 존재 (정본 D3 「없음 예상」 적중)
- **기존 결정 충돌**. **1건 — 의도적으로 뒤집는다.** `IssueRepository.kt:745` 의
  *"type 요약은 보드 카드에 불필요하므로 ISSUE_TYPES JOIN 생략"*. 근거는 같은 파일
  `listVisibleForTimeline:886` 이 그 조인을 이미 하고 있다는 것 (ADR §D-3). 주석은 이 PR 에서 정정한다.
- **관련 ADR**. [decisions/2026-08-07-fr-ux-14-b2-card-fields.md](../decisions/2026-08-07-fr-ux-14-b2-card-fields.md) (생성됨)

### 확정된 노출 필드 3개 (Maxi 확정 2026-08-07)

| 필드 | 출처 | 보드 조회에 이미 포함? | 필요 작업 |
|---|---|---|---|
| `typeKey` | `issue_types.key` | ❌ JOIN 의도적 생략 | **ISSUE_TYPES INNER JOIN 신규** |
| `labels` | `issues.labels TEXT[]` (V006) | ✅ `ISSUES.fields()` | 매핑만 (VO + 응답 2곳) |
| `originalEstimateSeconds` | `issues.original_estimate_seconds` (V027) | ✅ 동일 | 매핑만 |

`typeIconName`·`typeName` 은 **싣지 않는다** — 프론트가 기존 타입 목록 API(`api/issue-types.ts`)에서
얻는다. 이슈 상세 화면이 이미 그 방식이고, 자매 포트 `TimelineItemView` 도 식별자만 담는다 (ADR §D-1).

### 정본 전복 3건 (착수 전 실측)

1. 「타입은 `listWithType` 이 이미 조인 중」 → **경로 혼동**. 목록 경로와 보드 경로는 다르고,
   보드는 조인을 명문으로 생략해 뒀다.
2. 「보드 카드 SELECT 확장」 → **라벨·추정은 SELECT 무변경**. 이미 조회되는데 매핑에서만 버려진다.
   실제 쿼리 변경은 type JOIN 하나뿐.
3. 지정 필드 `typeKey`·`typeIconName` → **소비자 계약 불일치**. `IssueTypeIcon` 은 `typeKey` 를
   안 쓰고, 정본 조합엔 접근성 레이블이 없어 WCAG 임계를 스스로 깬다.

## 스펙

전체 스펙. [docs/specs/2026-08-07-fr-ux-14-b2-card-fields.md](../specs/2026-08-07-fr-ux-14-b2-card-fields.md)
— 시나리오 S1~S7 · 기능 요구사항 FR1~FR10 · 비기능 NFR1~NFR5 · 엣지 케이스 E1~E10 ·
제약 C1~C5 · 완료 기준 §9.1~§9.5.

핵심 3줄 요약.
- `BoardIssueView`(shared-kernel) 한 곳에 3필드를 더하면 보드·백로그 두 응답이 함께 따라온다.
- 라벨·추정은 **이미 조회되고 있어 매핑만** 추가하면 되고, 유형만 `ISSUE_TYPES` INNER JOIN 이 새로 필요하다.
- 성공 판정식은 **N+1 회귀 가드**이고, 그 가드가 공허하지 않음을 **일부러 N+1 을 만들어** 증명한다.

**`## Jira 대조` 는 비-UI 타입이라 생략**했다 (이 PR 은 화면 0파일).

### office-hours 를 그대로 돌리지 않은 이유 (절차 이탈 기록)

`/bts-spec` Phase A-3 은 `office-hours` 호출을 지시하지만, 그 스킬은 **제품 아이디어 검증**
도구다(수요 근거 · 고객 이름 · 최소 웨지 등 6문항, 원격 서브에이전트 · 웹검색 · `CLAUDE.md`
수정 제안 포함). 이 작업은 Maxi 가 이미 결정하고 **ADR 까지 채택한 백엔드 필드 추가**라
그 문답이 성립하지 않는다.

스킬 자체 규정 *"완성된 계획이 있으면 Phase 2 문답은 건너뛰되 Phase 3(전제 도전)·
Phase 4(대안 생성)는 수행한다"* 에 따라 **그 두 단계만** 실제로 수행했다 —
전제 도전의 산출물이 §도메인 정리의 **정본 전복 3건**이고, 대안 생성의 산출물이
**기본값 3안 비교**(Maxi 확정)다. 산출물은 스킬 기본 경로가 아니라 `docs/specs/` 에 썼다.

> **후속 과제.** `/bts-spec` 이 백엔드 타입에도 `office-hours` 를 무조건 지시하는 것은
> 워크플로우 결함이다. `TODOS.md` 등재 후보.

## Brainstorming Check

**✅ 통과 (1회 iteration)** — gap 3건 발견 후 스펙 §9.3(픽스처 요건) 신설로 봉합.

| ID | gap | 한 줄 |
|---|---|---|
| GAP-1 | 픽스처 대조군 부재 | 값이 같으면 매핑이 뒤바뀌어도 초록. FR-UX-13 F16 동형 |
| GAP-2 | 컴파일러 강제가 **팩토리 헬퍼 한 겹에서 흡수** | 「브랜드 타입은 한 홉만 막는다」 동형. 증인은 실 DB 값이어야 함 |
| GAP-3 | `BoardPortContractTest` 가 필드 계약 정본인데 갱신 요건 누락 | KDoc 열거 + 기본값 규약을 계약 테스트로 못박기 |

구조적 위험(「봉합이 절반」)은 **실측으로 없음을 확인**했다 — 백로그 두 배열이 같은 `from` 을
거치고, 포트 제3소비자(`SprintApplicationService`)는 단건 가시성만 쓰며,
`BoardCardPlacement` 는 VO 를 읽기만 한다. 프론트도 Zod 가 `.strict()` 가 아니라 안 깨진다.

`superpowers:brainstorming` 역시 대화형 설계 도구라 「gap 만 보고」 용도와 어긋나므로,
그 스킬이 요구하는 설계 문답 대신 **위 4개 축을 코드로 실측**하는 방식으로 sanity check 를 수행했다.

## Plan

**목표.** 보드/백로그 카드 응답에 `typeKey` · `labels` · `originalEstimateSeconds` 를 실어
F14(카드 화면 밀도)의 차단을 푼다.

**아키텍처.** `BoardIssueView`(shared-kernel) 한 곳을 늘리면 보드·백로그 두 응답이 함께 따라온다.
라벨·추정은 이미 조회되고 있어 매핑만 추가하고, 유형만 `ISSUE_TYPES` INNER JOIN 이 새로 필요하다.
조인은 같은 파일의 `listVisibleForTimeline` 을 그대로 베낀다.

### ★ 이 plan 의 지배 제약 — 컴파일 원자성

`typeKey` 를 **기본값 없는 필수 인자**로 두기로 확정(Maxi 2026-08-07)했기 때문에,
`BoardIssueView` 시그니처가 바뀌는 순간 **기존 생성 지점 21곳이 동시에 컴파일 에러**가 된다.
Kotlin 은 "한쪽만 고친 중간 상태"를 허용하지 않으므로 **VO · 저장소 entry · 어댑터 매핑 ·
기존 21곳 수선은 쪼갤 수 없는 하나의 커밋**이다. 이것이 Task 1 이 큰 이유다 —
잘게 나누면 나눈 조각이 컴파일되지 않는다. 대신 **Task 1 내부의 step 을 잘게** 쪼갠다.

`typeKey` 는 파라미터 목록에서 **`version` 뒤 · `epicKey` 앞**(7번째)에 넣는다.
그래야 기존 위치 인자 6개 호출(`BoardApplicationServiceTest:216-217`)과 명명 인자 호출이
**둘 다** 컴파일 에러가 나 컴파일러의 강제가 실제로 작동한다. 맨 뒤에 두면 위치 인자 호출이
조용히 통과할 여지가 생긴다.

### Task 1. 코어 — VO 3필드 + type JOIN + 어댑터 매핑 + 기존 21곳 수선

**메타**.
- agent: `backend-engineer`
- files: [
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`,
  `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardPortContractTest.kt`,
  `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewTest.kt`,
  `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`,
  `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/dto/BoardResponsesTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BacklogApplicationServiceTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardCardPlacementTest.kt`
  ]
- depends-on: []

- [ ] **Step 1 (RED). `BoardPortContractTest` 에 필드 계약 + 기본값 규약 테스트를 먼저 쓴다**

이 파일이 **필드 계약의 정본**이다(KDoc 이 필드를 열거한다 — 스펙 GAP-3).
같은 파일에 이미 있는 `WorkflowStateView 기존 2-arg 호출이 default 로 생성된다` 테스트가 선례다.

```kotlin
@Test
fun `BoardIssueView 는 typeKey 를 필수로 받고 labels 와 originalEstimateSeconds 는 default 로 생성된다`() {
    // typeKey 는 기본값이 없다 — 호출자가 반드시 명시해야 한다.
    val view =
        BoardIssueView(
            key = "PROJ-1",
            summary = "제목",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = "bug",
        )

    assertThat(view.typeKey).isEqualTo("bug")
    // 미지정 시 라벨은 null 이 아니라 빈 리스트다 (FR8 — 직렬화가 [] 가 되는 근거).
    assertThat(view.labels).isEmpty()
    assertThat(view.originalEstimateSeconds).isNull()
}

@Test
fun `BoardIssueView 는 labels 와 originalEstimateSeconds 를 명시하면 그 값으로 생성된다`() {
    val view =
        BoardIssueView(
            key = "PROJ-2",
            summary = "제목",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = "story",
            labels = listOf("urgent", "api"),
            originalEstimateSeconds = 3600,
        )

    assertThat(view.labels).containsExactly("urgent", "api")
    assertThat(view.originalEstimateSeconds).isEqualTo(3600)
}
```

- [ ] **Step 2. RED 확인 — 컴파일 실패를 눈으로 본다**

Run: `./gradlew :modules:shared-kernel:test --tests '*BoardPortContractTest'`
Expected: **컴파일 실패** — `No value passed for parameter 'typeKey'` / `Cannot find a parameter with this name: labels`

> 이 프로젝트의 규율은 **복사 전에 red 를 본다**([[decorative-annotation-copied-from-sibling]]).
> 컴파일 에러 메시지를 실제로 확인하고 넘어간다.

- [ ] **Step 3 (GREEN). `BoardIssueView` 에 3필드 추가**

`BoardIssueLookupPort.kt:157`.

```kotlin
data class BoardIssueView(
    val key: String,
    val summary: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val version: Long,
    val typeKey: String,
    val epicKey: String? = null,
    val rank: String? = null,
    val labels: List<String> = emptyList(),
    val originalEstimateSeconds: Int? = null,
)
```

- [ ] **Step 4 (GREEN). `BoardIssueEntry` 에 `typeKey` 추가 + type JOIN**

`IssueRepository.kt:823` 의 entry.

```kotlin
data class BoardIssueEntry(
    val issue: Issue,
    val epicKey: String?,
    val typeKey: String,
)
```

`listVisibleForBoard:792-808` 의 쿼리. `TYPE_KEY_ALIAS` 는 이미 있는 상수를 **재사용**한다
(`listWithType` · `listVisibleForTimeline` 과 같은 alias 를 공유해야 한 벌로 유지된다).

```kotlin
val fetched =
    dsl.select(
        ISSUES.fields().toList() +
            listOf(
                epicAlias.KEY.`as`(EPIC_KEY_ALIAS),
                ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
            ),
    )
        .from(ISSUES)
        .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
        .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
        .leftJoin(epicAlias).on(
            ISSUES.EPIC_ID.eq(epicAlias.ID)
                .and(epicAlias.DELETED_AT.isNull)
                .and(epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID)),
        )
        .where(where)
        .orderBy(ISSUES.CREATED_AT.desc())
        .limit(BOARD_CARD_FETCH_LIMIT + 1)
        .fetch { record ->
            val issue = record.into(ISSUES).toIssue()
            val epicKey = record.get(EPIC_KEY_ALIAS, String::class.java)
            val typeKey =
                record.get(TYPE_KEY_ALIAS, String::class.java)
                    ?: error("issue_types.key must not be null in board join result")
            BoardIssueEntry(issue = issue, epicKey = epicKey, typeKey = typeKey)
        }
```

**주의 3가지.**
1. `.join(...)` 은 **INNER** 다. `issues.type_id` 가 V005 에서 `SET NOT NULL` + FK 라 행이 사라지지 않는다.
2. `orderBy` 를 **건드리지 않는다**(제약 C4 — 보조 정렬 추가는 선재 결함이고 범위 밖이다).
3. `where` 도 건드리지 않는다(제약 C3 — 보안 술어 불변).

- [ ] **Step 5 (GREEN). 어댑터 매핑 3필드**

`BoardIssueLookupAdapter.kt:121`.

```kotlin
private fun IssueRepository.BoardIssueEntry.toBoardIssueView(): BoardIssueView =
    BoardIssueView(
        key = issue.key.value,
        summary = issue.summary,
        currentStateKey = issue.currentStateKey,
        assigneeId = issue.assigneeId?.value,
        priority = issue.priority,
        version = issue.version,
        typeKey = typeKey,
        epicKey = epicKey,
        rank = issue.rank,
        labels = issue.labels,
        originalEstimateSeconds = issue.originalEstimateSeconds,
    )
```

`issue.labels` · `issue.originalEstimateSeconds` 는 **이미 채워져 있다** —
`record.into(ISSUES).toIssue()` 가 ISSUES 전 컬럼으로 도메인을 만든다. 새로 조회하지 않는다.

- [ ] **Step 6 (GREEN). 기존 생성 지점 21곳 수선 — 전수 열거로 확인**

컴파일러가 전부 잡아주지만, **개수를 세지 말고 전수 열거로** 확인한다
([[orchestrator-instruction-counts-are-blindfolds]] — 지시에 쓴 개수는 10번 틀렸다).

```bash
grep -rn "BoardIssueView(" backend/ | grep -v "build/" | grep -v "data class BoardIssueView"
```

수선 대상(착수 시점 실측 — 7파일).

| 파일 | 줄 |
|---|---|
| `shared-kernel .../BoardIssueViewTest.kt` | 25 · 42 · 58 |
| `shared-kernel .../BoardPortContractTest.kt` | 88 · 116 · 136 · 253 |
| `agile-planning .../BoardResponsesTest.kt` | 47(**헬퍼 `card`**) · 189 · 204 |
| `agile-planning .../BoardControllerIntegrationTest.kt` | 307 · 789 |
| `agile-planning .../BoardApplicationServiceTest.kt` | 146 · 154 · 216 · 217 · 580 · 598 |
| `agile-planning .../BacklogApplicationServiceTest.kt` | 45 |
| `agile-planning .../BoardCardPlacementTest.kt` | 38(**헬퍼 `issueView`**) |

각 호출에 `typeKey = "task"` 를 더한다(배치·정렬 테스트는 유형과 무관하므로 임의 표준 타입).

> **★ 헬퍼 2곳은 컴파일러 강제를 흡수한다** (스펙 GAP-2). `BoardResponsesTest.card:47` 과
> `BoardCardPlacementTest.issueView:38` 에 `typeKey: String = "task"` 기본값을 주는 것은
> **허용**한다 — 그 테스트들은 3필드와 무관하다. 다만 **이 헬퍼를 쓰는 테스트를 3필드의
> 증인으로 세지 않는다.** 진짜 증인은 Task 2 의 실 DB 검증이다.

- [ ] **Step 7 (REFACTOR). 새 사실과 어긋나는 주석 3곳 정정** (FR9)

셋 다 **코드와 반대되는 서술**이 된다. 남기면 다음 독자가 속는다.

1. `BoardIssueLookupPort.kt:144` — *"보드 컬럼 배치와 카드 정렬에 필요한 최소 필드만 포함한다"*
   → 카드 **표시**에 필요한 필드를 포함한다는 사실을 반영하고, 새 3필드의 `@property` 를 추가한다.
   자매 VO `TimelineItemView` 도 같은 문구를 쓰면서 `issueType` 을 담고 있다는 점을 근거로 적는다.
2. `IssueRepository.kt:745` — *"type 요약은 보드 카드에 불필요하므로 ISSUE_TYPES JOIN 생략"*
   → 조인을 하게 된 사실과 근거(FR-UX-14, 타임라인과 동형)로 교체.
3. `BoardIssueLookupAdapter.kt:119` — *"최소 필드만 추출한다 (type/description 등은 제외)"*
   → `description` 은 여전히 제외이고 `type` 은 포함으로 갈라졌음을 명시.

- [ ] **Step 8. GREEN 확인**

Run: `./gradlew :modules:shared-kernel:test --tests '*BoardPortContractTest' --tests '*BoardIssueViewTest'`
Expected: PASS

Run: `./gradlew :modules:issue-tracking:compileKotlin :modules:agile-planning:compileTestKotlin`
Expected: BUILD SUCCESSFUL — 21곳 수선이 빠짐없이 됐다는 증거

- [ ] **Step 9. 커밋**

```bash
git add backend/modules/shared-kernel backend/modules/issue-tracking backend/modules/agile-planning
git diff --cached --name-only   # 내 파일만 담겼는지 확인 (병렬 dispatch 시 피어 파일 혼입 방지)
git commit -m "feat: FR-UX-14 B2 — BoardIssueView 3필드 + 보드 type JOIN"
```

**검증**. `./gradlew :modules:shared-kernel:test --tests '*BoardPortContractTest'` ·
`./gradlew :modules:agile-planning:compileTestKotlin`

---

### Task 2. 실 DB 매핑 검증 — 대조군 픽스처 + truncated 경계

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapterTest.kt`]
- depends-on: [1]

Task 1 의 매핑이 **실제로 DB 값을 카드까지 나른다**는 증인을 세운다.
스펙 §9.3 GAP-1/GAP-2 의 처방이 여기에 있다.

- [ ] **Step 1 (RED). 대조군 픽스처 + 키 단위 대조 테스트**

이 파일은 이미 Testcontainers 기반(`IssueTestcontainersBase`)이다. 기존 시드 방식을 따른다.

**픽스처 요건 — 축마다 서로 다른 값 2건 + 빈 값 대조군 1건.**

| 이슈 | typeKey | labels | originalEstimateSeconds |
|---|---|---|---|
| `PROJ-1` | `bug` | `["urgent","api"]` | 3600 |
| `PROJ-2` | `story` | `["docs"]` | 7200 |
| `PROJ-3` | `task` | `[]` (빈 배열) | `null` |

```kotlin
@Test
fun `보드 카드는 이슈별 유형 키를 각자 정확히 갖는다`() {
    val page = adapter.listVisibleIssuesByProject(PROJECT_KEY, viewerId)
    val byKey = page.issues.associateBy { it.key }

    // 키 단위 대조 — "어떤 카드엔가 bug 가 있다" 로는 조인이 틀린 행을 물어와도 통과한다.
    assertThat(byKey.getValue("PROJ-1").typeKey).isEqualTo("bug")
    assertThat(byKey.getValue("PROJ-2").typeKey).isEqualTo("story")
    assertThat(byKey.getValue("PROJ-3").typeKey).isEqualTo("task")
}

@Test
fun `보드 카드는 이슈별 라벨을 각자 정확히 갖고 라벨 없는 이슈는 빈 배열이다`() {
    val byKey = adapter.listVisibleIssuesByProject(PROJECT_KEY, viewerId).issues.associateBy { it.key }

    assertThat(byKey.getValue("PROJ-1").labels).containsExactly("urgent", "api")
    assertThat(byKey.getValue("PROJ-2").labels).containsExactly("docs")
    assertThat(byKey.getValue("PROJ-3").labels).isEmpty()
}

@Test
fun `보드 카드는 이슈별 추정을 각자 정확히 갖고 미추정 이슈는 null 이다`() {
    val byKey = adapter.listVisibleIssuesByProject(PROJECT_KEY, viewerId).issues.associateBy { it.key }

    assertThat(byKey.getValue("PROJ-1").originalEstimateSeconds).isEqualTo(3600)
    assertThat(byKey.getValue("PROJ-2").originalEstimateSeconds).isEqualTo(7200)
    assertThat(byKey.getValue("PROJ-3").originalEstimateSeconds).isNull()
}
```

- [ ] **Step 2 (RED). truncated 경계가 JOIN 으로 안 깨지는지** (T-TRUNC · NFR3)

INNER JOIN 은 N:1 이라 행이 늘지 않지만, **그것을 못박아야** 다음 사람이 LEFT JOIN 이나
1:N 조인을 넣었을 때 걸린다.

```kotlin
@Test
fun `type JOIN 이 붙어도 LIMIT 경계의 truncated 판정이 행 수 기준 그대로다`() {
    // BOARD_CARD_FETCH_LIMIT 을 넘기지 않는 규모에서는 truncated=false 여야 한다.
    val page = adapter.listVisibleIssuesByProject(PROJECT_KEY, viewerId)

    assertThat(page.truncated).isFalse()
    // 조인이 행을 불렸다면 이슈 수보다 카드 수가 많아진다 — 그것을 직접 잡는다.
    // 기대값은 상수로 두지 말고 **이 테스트가 시드한 건수**를 그대로 쓴다.
    assertThat(page.issues).hasSize(seededVisibleIssueKeys.size)
    assertThat(page.issues.map { it.key }).doesNotHaveDuplicates()
}
```

- [ ] **Step 3. RED 확인**

Run: `./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupAdapterTest'`
Expected: 신규 테스트 4건 FAIL (Task 1 이 이미 머지됐다면 픽스처 부재로 실패)

- [ ] **Step 4 (GREEN). 픽스처 시드 추가**

기존 시드 헬퍼에 위 표의 3건을 넣는다. `issue_types` 표준 5종은 V003 시드에 이미 있으므로
타입 행을 새로 만들지 않고 `key` 로 조회해 `type_id` 를 연결한다.

- [ ] **Step 5. GREEN 확인 + 커밋**

Run: `./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupAdapterTest'`
Expected: PASS (기존 테스트 포함 전량)

```bash
git add backend/modules/issue-tracking/src/test
git commit -m "test: FR-UX-14 B2 — 카드 3필드 실 DB 대조군 검증 + truncated 경계"
```

**검증**. `./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupAdapterTest'`

---

### Task 3. N+1 회귀 가드 — 정본 지정 성공 판정식

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardCardQueryCountTest.kt`]
- depends-on: [1]

**신규 파일**로 만든다 — Task 2 와 같은 파일을 쓰면 병렬이 막히고, 이 가드는 성격이 달라
독립 파일이 읽기도 낫다.

- [ ] **Step 1 (RED). 쿼리 수 계측 리스너 + 카드 수 무관 판정**

```kotlin
/** 실행된 SQL 문 수를 세는 jOOQ 리스너 — N+1 회귀 가드 전용. */
private class QueryCountListener : DefaultExecuteListener() {
    val count = AtomicInteger(0)

    override fun executeStart(ctx: ExecuteContext) {
        count.incrementAndGet()
    }
}

@Test
fun `보드 카드 조회 쿼리 수는 카드 건수와 무관하게 일정하다`() {
    val small = countQueriesFor(cardCount = 3)
    val large = countQueriesFor(cardCount = 30)

    // 카드가 10배로 늘어도 쿼리 수가 같아야 한다. 다르면 카드당 추가 조회(N+1)가 있다는 뜻이다.
    assertThat(large).isEqualTo(small)
}
```

`countQueriesFor` 의 구현. `IssueTestcontainersBase:112` 가 `dsl = DSL.using(dataSource, SQLDialect.POSTGRES)`
로 만들고 `:113` 이 `repository = IssueRepository(dsl)` 로 **생성자 주입**하므로,
리스너를 붙인 별도 `Configuration` 으로 **계측 전용 저장소**를 하나 더 만들면 된다.

```kotlin
private fun countQueriesFor(cardCount: Int): Int {
    seedVisibleIssues(cardCount)

    val listener = QueryCountListener()
    val countingDsl =
        DSL.using(
            DefaultConfiguration()
                .set(dataSource)
                .set(SQLDialect.POSTGRES)
                .set(DefaultExecuteListenerProvider(listener)),
        )
    val countingRepository = IssueRepository(countingDsl)

    countingRepository.listVisibleForBoard(PROJECT_KEY, viewerId, unrestrictedAccess)

    return listener.count.get()
}
```

`dataSource` 는 베이스 클래스가 이미 들고 있는 것을 재사용한다 — 컨테이너를 새로 띄우지 않는다.

- [ ] **Step 2. ★ 비-공허 확인 — 가드가 진짜인지 증명한다**

**이 step 을 건너뛰면 가드는 초록인 채 아무것도 재지 않는다.** 이 프로젝트가 반복해서 겪은
사고 유형이다([[unreachable-state-fixture-is-fake-green]] — PR #342 에서 같은 양식 2회 적발).

절차.
1. 어댑터에 **일부러 카드당 1회 조회를 넣는다**(임시. 예: `entries.map { issueRepository.… }`).
2. 위 테스트를 돌려 **FAIL 하는 것을 눈으로 확인**하고 출력을 기록한다.
3. 임시 코드를 **역방향 Edit 으로 되돌린다** — `git checkout --` / `stash` / `reset` 금지
   ([[parallel-wave-mutation-revert-destroys-peers]]. 같은 트리의 남의 미커밋 작업이 날아간다).
4. 다시 돌려 PASS 를 확인한다.
5. **FAIL 출력을 PR 본문에 인용한다.** 인용이 없으면 이 step 을 안 한 것으로 간주한다.

- [ ] **Step 3 (GREEN). 통과 확인 + 커밋**

Run: `./gradlew :modules:issue-tracking:test --tests '*BoardCardQueryCountTest'`
Expected: PASS

```bash
git add backend/modules/issue-tracking/src/test
git commit -m "test: FR-UX-14 B2 — 보드 카드 N+1 회귀 가드 (비-공허 확인 동반)"
```

**검증**. `./gradlew :modules:issue-tracking:test --tests '*BoardCardQueryCountTest'`

---

### Task 4. `BoardCardResponse` 3필드

**메타**.
- agent: `backend-engineer`
- files: [
  `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/dto/BoardResponsesTest.kt`
  ]
- depends-on: [1]

- [ ] **Step 1 (RED). `from` 이 3필드를 나르는지**

```kotlin
@Test
fun `BoardCardResponse from 은 BoardIssueView 의 유형 라벨 추정을 그대로 나른다`() {
    val view =
        BoardIssueView(
            key = "PROJ-1",
            summary = "제목",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = "bug",
            labels = listOf("urgent", "api"),
            originalEstimateSeconds = 3600,
        )

    val response = BoardCardResponse.from(view)

    assertThat(response.typeKey).isEqualTo("bug")
    assertThat(response.labels).containsExactly("urgent", "api")
    assertThat(response.originalEstimateSeconds).isEqualTo(3600)
}

@Test
fun `BoardCardResponse from 은 라벨 없는 뷰를 빈 배열로 추정 없는 뷰를 null 로 나른다`() {
    val view =
        BoardIssueView(
            key = "PROJ-2",
            summary = "제목",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = "task",
        )

    val response = BoardCardResponse.from(view)

    assertThat(response.labels).isEmpty()
    assertThat(response.originalEstimateSeconds).isNull()
}
```

- [ ] **Step 2. RED 확인**

Run: `./gradlew :modules:agile-planning:test --tests '*BoardResponsesTest'`
Expected: 컴파일 실패 — `Unresolved reference: typeKey`

- [ ] **Step 3 (GREEN). DTO 확장 + `from` 매핑**

```kotlin
data class BoardCardResponse(
    val issueKey: String,
    val summary: String,
    val assigneeId: UUID?,
    val priority: Int,
    val version: Long,
    val typeKey: String,
    val epicKey: String? = null,
    val rank: String? = null,
    val labels: List<String> = emptyList(),
    val originalEstimateSeconds: Int? = null,
) {
    companion object {
        /** cross-BC [BoardIssueView] 를 [BoardCardResponse] 로 변환한다. */
        fun from(card: BoardIssueView): BoardCardResponse =
            BoardCardResponse(
                issueKey = card.key,
                summary = card.summary,
                assigneeId = card.assigneeId,
                priority = card.priority,
                version = card.version,
                typeKey = card.typeKey,
                epicKey = card.epicKey,
                rank = card.rank,
                labels = card.labels,
                originalEstimateSeconds = card.originalEstimateSeconds,
            )
    }
}
```

- [ ] **Step 4 (REFACTOR). KDoc `@property` 3줄 추가**

`typeKey` 는 *"이슈 유형 키(`issue_types.key`). 소문자. 예: `bug`. 유형 이름·아이콘은 담지 않는다 —
클라이언트가 타입 목록 API 에서 얻는다(ADR §D-1)."* 로 적어 **왜 이름이 없는지**를 남긴다.

- [ ] **Step 5. GREEN 확인 + 커밋**

Run: `./gradlew :modules:agile-planning:test --tests '*BoardResponsesTest'`
Expected: PASS

```bash
git add backend/modules/agile-planning
git commit -m "feat: FR-UX-14 B2 — BoardCardResponse 3필드"
```

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardResponsesTest'`

---

### Task 5. `BacklogIssueResponse` 3필드 — backlog · sprints 두 배열

**메타**.
- agent: `backend-engineer`
- files: [
  `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BacklogResponses.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BacklogApplicationServiceTest.kt`
  ]
- depends-on: [1]

- [ ] **Step 1 (RED). `from` 3필드 + 두 배열 동시 검증**

`BacklogApplicationService:137`(backlog 배열)과 `:150`(sprints[].issues 배열)이 **같은 `from`**
을 거치므로 로직은 갈라질 수 없지만, **회귀 감시는 두 곳을 다 봐야** 한쪽이 조용히 빠지는 것을 잡는다.

```kotlin
@Test
fun `백로그 응답은 backlog 배열과 sprints 배열 양쪽에 유형 라벨 추정을 담는다`() {
    // given: 백로그 1건 + 스프린트 소속 1건, 서로 다른 유형/라벨/추정
    val response = service.getBacklog(PROJECT_KEY, actorId)

    val backlogIssue = response.backlog.single { it.key == "PROJ-1" }
    assertThat(backlogIssue.typeKey).isEqualTo("bug")
    assertThat(backlogIssue.labels).containsExactly("urgent")
    assertThat(backlogIssue.originalEstimateSeconds).isEqualTo(3600)

    val sprintIssue = response.sprints.single().issues.single { it.key == "PROJ-2" }
    assertThat(sprintIssue.typeKey).isEqualTo("story")
    assertThat(sprintIssue.labels).isEmpty()
    assertThat(sprintIssue.originalEstimateSeconds).isNull()
}
```

- [ ] **Step 2. RED 확인**

Run: `./gradlew :modules:agile-planning:test --tests '*BacklogApplicationServiceTest'`
Expected: 컴파일 실패 — `Unresolved reference: typeKey`

- [ ] **Step 3 (GREEN). DTO 확장 + `from` 매핑**

기존 8필드가 전부 기본값이 없으므로 `typeKey` 는 그 뒤에 두고, 기본값 있는 2필드를 마지막에 둔다.

```kotlin
data class BacklogIssueResponse(
    val key: String,
    val summary: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val rank: String?,
    val version: Long,
    val epicKey: String?,
    val typeKey: String,
    val labels: List<String> = emptyList(),
    val originalEstimateSeconds: Int? = null,
) {
    companion object {
        fun from(view: BoardIssueView): BacklogIssueResponse =
            BacklogIssueResponse(
                key = view.key,
                summary = view.summary,
                currentStateKey = view.currentStateKey,
                assigneeId = view.assigneeId,
                priority = view.priority,
                rank = view.rank,
                version = view.version,
                epicKey = view.epicKey,
                typeKey = view.typeKey,
                labels = view.labels,
                originalEstimateSeconds = view.originalEstimateSeconds,
            )
    }
}
```

- [ ] **Step 4. GREEN 확인 + 커밋**

Run: `./gradlew :modules:agile-planning:test --tests '*BacklogApplicationServiceTest'`
Expected: PASS

```bash
git add backend/modules/agile-planning
git commit -m "feat: FR-UX-14 B2 — BacklogIssueResponse 3필드 (backlog·sprints 양쪽)"
```

**검증**. `./gradlew :modules:agile-planning:test --tests '*BacklogApplicationServiceTest'`

---

### Task 6. 컨트롤러 통합 — 응답 JSON 에 3필드가 실제로 나가는지

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [4, 5]

DTO 단위 테스트는 객체까지만 본다. **직렬화된 JSON 까지** 확인해야 FR8(빈 배열 직렬화)이 증명된다.

- [ ] **Step 1 (RED). 보드 단건 조회 JSON**

> **★ 카드를 인덱스로 지목하지 않는다.** 카드 순서는 `rank` → `priority` → `key` 로 결정되므로
> `cards[0]` 이 어느 이슈인지는 픽스처에 달려 있다. 인덱스로 바로 필드를 단언하면 **엉뚱한 카드를
> 검증하고도 값이 우연히 같으면 초록**이 된다 — 스펙 §9.3 GAP-1 의 「키 단위 대조」 위반이다.
> 기존 관례(`BoardControllerIntegrationTest:329-330`)가 이미 **개수 고정 → 신원 확인 → 필드 검증**
> 순서를 쓰고 있으므로 그대로 승계한다.

```kotlin
@Test
fun `보드 단건 조회 응답 카드에 유형 라벨 추정이 담긴다`() {
    mockMvc.perform(get("/api/v1/projects/{key}/boards/{id}", PROJECT_KEY, boardId).with(authenticated()))
        .andExpect(status().isOk)
        // 신원을 먼저 고정한다 — 순서가 바뀌면 여기서 깨지고, 엉뚱한 카드를 검증할 수 없다.
        .andExpect(jsonPath("$.data.columns[0].cards[0].issueKey").value("BTS-1"))
        .andExpect(jsonPath("$.data.columns[0].cards[0].typeKey").value("bug"))
        .andExpect(jsonPath("$.data.columns[0].cards[0].labels[0]").value("urgent"))
        .andExpect(jsonPath("$.data.columns[0].cards[0].originalEstimateSeconds").value(3600))
}

@Test
fun `라벨 없는 카드의 labels 는 null 이 아니라 빈 배열로 직렬화된다`() {
    mockMvc.perform(get("/api/v1/projects/{key}/boards/{id}", PROJECT_KEY, boardId).with(authenticated()))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.data.columns[0].cards[1].issueKey").value("BTS-2"))
        // isEmpty() 가 아니라 배열 존재 + 길이 0 을 본다 — null 이면 이 단언이 깨진다.
        .andExpect(jsonPath("$.data.columns[0].cards[1].labels").isArray)
        .andExpect(jsonPath("$.data.columns[0].cards[1].labels.length()").value(0))
        .andExpect(jsonPath("$.data.columns[0].cards[1].originalEstimateSeconds").doesNotExist())
}
```

> `originalEstimateSeconds` 가 `null` 일 때 `doesNotExist()` 인지 `value(null)` 인지는
> 직렬화 설정(`@JsonInclude`)에 달렸다. **RED 단계에서 실제 응답 본문을 출력해 확인한 뒤**
> 맞는 단언으로 고정한다 — 추측으로 쓰면 둘 중 하나는 반드시 틀린다.

- [ ] **Step 2. RED 확인 → GREEN 확인**

Run: `./gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest'`
Expected: 먼저 FAIL(경로 없음) → Task 4·5 반영 후 PASS

- [ ] **Step 3. 커밋**

```bash
git add backend/modules/agile-planning/src/test
git commit -m "test: FR-UX-14 B2 — 카드 응답 JSON 3필드 통합 검증"
```

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest'`

---

### Task 7. 문서 동기화 + 전량 회귀

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`]
- depends-on: [1, 2, 3, 4, 5, 6]

- [ ] **Step 1. 정본 §4.12 의 D 마커 갱신 + D4 문구 정정**

`docs/plan/product/personalization.md §4.12`.

- `D1`·`D2`·`D3`·`D4`·`D5` 를 `- [x]` 로 바꾸고 근거를 한 줄씩 단다.
- **D4 문구를 정정한다** — 원문의 `typeIconName` 을 빼고, SELECT 확장 범위를
  "라벨·추정은 이미 조회 중이라 매핑만, 유형만 JOIN 신규"로 고친다. ADR §D-1/§전복 2 근거.
- `D6`(프론트)·`D7`(E2E)은 **F14 잔여이므로 `- [ ]` 유지**한다.

- [ ] **Step 2. 진척 카운트 재실측** — 계획 시점 숫자는 유통기한이 있다

```bash
grep -rhoE '^- \[x\] D[0-9]+\.' docs/plan/product/*.md | wc -l   # 완료
grep -rhoE '^- \[ \] D[0-9]+\.' docs/plan/product/*.md | wc -l   # 미완
```

`docs/plan/README.md` §1 의 진척 문단을 **실측값으로** 갱신한다. FR 총수 **139 불변**.

- [ ] **Step 3. verify + 대시보드**

Run: `bash scripts/verify-master-plan.sh`
Expected: 종료 0 (종료 4 면 문서 간 drift 가 남은 것)

Run: `node scripts/build-doc-index.mjs`
Expected: `PASS. 고아 0 · 깨진 링크 0`

- [ ] **Step 4. 전량 회귀 + 범위 증명**

```bash
./gradlew :modules:shared-kernel:test :modules:issue-tracking:test :modules:agile-planning:test
./gradlew :modules:shared-kernel:ktlintCheck :modules:issue-tracking:ktlintCheck :modules:agile-planning:ktlintCheck
git diff --name-only main...HEAD | grep -c '^apps/'              # → 0
git diff --name-only main...HEAD | grep -c 'db/migration'        # → 0
```

- [ ] **Step 5. 커밋**

```bash
git add docs/
git commit -m "docs: FR-UX-14 B2 — 정본 D1~D5 마킹 + D4 문구 정정"
```

**검증**. `bash scripts/verify-master-plan.sh` 종료 0

## Plan 메타

- task 수: **7**
- 예상 wave: **4** — W1[T1] → W2[T2·T3·T4·T5 병렬] → W3[T6] → W4[T7]
  - T1 은 컴파일 원자 단위라 단독 wave 다(§지배 제약).
  - T2·T3·T4·T5 는 파일 교집합이 없어 동시 실행 가능하다.
- 구현 규율: **TDD red→green→refactor** (`test:` 커밋이 `feat:` 커밋보다 먼저)
- 병렬 dispatch: `bts-impl` 이 위 `depends-on` + `files` 로 wave 계산
- 추가 검증: `ktlintCheck` · `detekt` · `verify-master-plan.sh` · `build-doc-index.mjs`
- **타협 불가 3건**
  1. Task 3 Step 2 의 **비-공허 확인** — 일부러 N+1 을 만들어 가드가 빨간불이 되는 것을 보고
     FAIL 출력을 PR 본문에 인용한다. 인용이 없으면 안 한 것으로 간주한다.
  2. Task 2 의 **대조군 픽스처** — 축마다 서로 다른 값 2건 + 빈 값 1건, 판정은 **키 단위 대조**.
  3. **Task 2 와 Task 6 은 둘 다 있어야 한다** (plan-eng-review ③ 판정).
     헬퍼에 기본값을 주는 절충은 *"진짜 증인이 따로 있다"* 는 조건 위에서만 성립한다.
     둘 중 하나라도 빠지면 컴파일러 강제는 헬퍼에서 흡수된 채 남고,
     **3필드가 전부 기본값으로 나가도 아무도 모르게 된다.** 축소 대상이 아니다.

### 병렬 dispatch 시 주의 (같은 worktree 공유)

- 커밋 직전 **`git diff --cached --name-only` 로 내 파일만 담겼는지 확인**한다 —
  `lint-staged` 의 stash/restore 가 피어의 미추적 파일을 인덱스에 올린 전례가 있다
  ([[worktree-lint-staged-steals-peer-untracked]], PR #343 에서 2회 관측).
- 원복이 필요하면 **역방향 Edit** 만 쓴다. `git checkout --` / `stash` / `reset` **전면 금지**.
- 임시 파일은 `/tmp/t<task번호>-*.log` 처럼 task 접두사를 붙인다.

## 리뷰 결과

### plan-eng-review (2026-08-07)

**BLOCKER: 없음.** 지적 3건은 전부 이 단계에서 plan 에 반영 완료.

리뷰 프레임(아키텍처 → 코드 품질 → 테스트 → 성능)만 취하고, 스킬이 요구하는
원격 동기화·`CLAUDE.md` 자동 수정·재확인 왕복은 실행하지 않았다(이번 세션 지침).

#### 중점 검토 3건에 대한 판정

**① Task 1 의 크기(파일 10개) — 현행 유지가 옳다.**

일반 기준으로는 8파일 초과가 냄새지만, 여기서는 **언어가 강제한 원자성**이다.
쪼개는 유일한 방법은 `typeKey` 에 임시 기본값을 줬다가 마지막 커밋에서 빼는 3단 분할인데,
**마지막 커밋을 빠뜨리면 Maxi 의 「필수 인자」 결정이 조용히 무효화**되고 그것을 잡을 가드가 없다.
얻는 것은 diff 가독성뿐이고 잃는 것은 결정의 보장이다. 분할하지 않는다.

다만 Task 1 Step 8 의 `compileTestKotlin` 성공은 **"컴파일된다"** 는 증거지
**"올바른 값을 넣었다"** 는 증거가 아니다. 그 공백은 Task 2(실 DB 대조군)와
Task 6(응답 JSON)이 메운다 — 두 task 가 없으면 Task 1 은 혼자서 아무것도 증명하지 못한다.

**② `typeKey` 를 7번째에 두는 결정 — 옳다.**

`BoardApplicationServiceTest:216-217` 이 **위치 인자 6개**로 호출한다
(`BoardIssueView("UNPL-1", "미매핑 이슈", "ghost-state", null, 1, 1L)`).
`typeKey` 를 맨 뒤에 두면 이 호출이 **그대로 컴파일된다** — 기본값이 없어도 위치 인자 6개는
앞의 6개 파라미터에 정확히 대응하기 때문이다. 그러면 컴파일러 강제가 이 두 줄을 놓친다.
`version` 뒤에 넣으면 7번째 자리가 비어 컴파일 에러가 난다. 근거가 성립한다.

**③ 팩토리 헬퍼 흡수(GAP-2) — 절충이 충분하다. 단 조건부다.**

헬퍼에 기본값을 주는 것 자체는 옳다. `BoardCardPlacementTest` 는 **정렬 로직** 테스트고
`BoardResponsesTest.card` 는 **배치 응답** 테스트라 유형·라벨·추정과 무관하다.
이들에게 3필드를 강제하면 무관한 소음만 늘고 정작 매핑은 검증되지 않는다.

절충이 성립하는 **조건**은 "진짜 증인이 따로 있을 것" 하나다. Task 2(실 DB 키 단위 대조) +
Task 6(응답 JSON)이 그 증인이다. **둘 중 하나라도 빠지면 이 절충은 즉시 무효**가 되고,
컴파일러 강제도 헬퍼에서 흡수된 채로 남아 3필드가 전부 기본값으로 나가도 아무도 모르게 된다.
이 조건을 Task 2·Task 6 의 `depends-on` 이 아니라 **plan 메타의 「타협 불가」에 못박아 둔다.**

#### 발견 3건 → 전부 수정 완료

| # | 축 | 지적 | 조치 |
|---|---|---|---|
| R1 | **테스트** | **Task 6 이 스펙 §9.3 GAP-1 을 자기 위반**했다. `cards[0].typeKey` 를 신원 확인 없이 단언해, 카드 순서(`rank`→`priority`→`key`)가 바뀌면 **엉뚱한 카드를 검증하고도 값이 우연히 같으면 초록**이 된다 | `cards[0].issueKey` 신원 확인을 앞에 넣었다. 기존 관례(`BoardControllerIntegrationTest:329-330`)가 이미 **개수 고정 → 신원 확인 → 필드 검증** 순서를 쓰고 있어 그대로 승계 |
| R2 | **코드 품질** | Task 3 의 `countQueriesFor` 가 *"리스너를 붙인 DSLContext 로"* 라는 서술뿐이었다. 구현 방법이 없으면 착수 시점에 막힌다(placeholder) | `IssueTestcontainersBase:112-113` 이 `DSL.using(dataSource)` + `IssueRepository(dsl)` **생성자 주입**임을 확인하고, `DefaultConfiguration` + `DefaultExecuteListenerProvider` 로 **계측 전용 저장소**를 만드는 실제 코드를 넣었다. 컨테이너는 재사용 |
| R3 | **코드 품질** | Task 2 가 정의되지 않은 상수 `SEEDED_VISIBLE_ISSUE_COUNT` 를 썼다 | 상수 대신 **그 테스트가 시드한 키 집합의 크기**(`seededVisibleIssueKeys.size`)로 교체. 시드와 기대값이 갈라질 여지를 없앴다 |

추가로 Task 6 에 **`null` 직렬화 형태를 추측하지 말 것**을 명시했다 —
`@JsonInclude` 설정에 따라 `doesNotExist()` 와 `value(null)` 중 하나만 맞고,
RED 단계에서 실제 응답 본문을 보고 고정해야 한다.

#### 축별 소견 (BLOCKER 아님)

- **아키텍처.** 단방향 `shared-kernel → issue-tracking → agile-planning` 이 유지되고
  신규 추상화 0개다. 조인은 같은 파일의 `listVisibleForTimeline` 을 베끼므로
  **혁신 토큰을 쓰지 않는다**(boring by default). 되돌리기도 쉽다 — 필드 추가는 가역적이다.
- **코드 품질.** 주석 정정 3곳(FR9)을 REFACTOR 에 넣은 것이 옳다.
  코드와 반대되는 주석은 stale 다이어그램과 같은 범주로, 남기면 다음 독자를 적극적으로 오도한다.
- **성능.** N:1 단일 조인이고 `issue_types` 는 프로젝트당 수 개 행이라 카드 수에 비례하지 않는다.
  기존에 이미 조인 2개(`PROJECTS` INNER + EPIC self LEFT)를 하고 있어 3번째가 임계를 바꿀 이유가 없다.
  NFR2(보드 200건 p95 1.5s)의 **실측은 BC 완료 게이트로 미뤘고 그 사실이 스펙에 명시**돼 있다 — 허용.
- **범위.** 선재 결함 1건(보드 조회 `orderBy` 보조키 부재로 1,000건 경계 truncation 비결정적)을
  **고치지 않고 기록만** 한 판단이 옳다. 정렬을 건드리면 카드 배치 회귀 위험이 이 PR 의 검증 범위를 넘는다.

#### 남은 위험 1건 (수용)

Task 1 이 기존 21곳에 `typeKey = "task"` 를 일괄로 넣는다. 배치·정렬 테스트에는 무해하지만,
`BoardControllerIntegrationTest:307·789` 는 **API 응답을 검증하는 테스트**라 그 값이 실제 응답에 실린다.
이 기존 테스트들은 3필드를 단언하지 않으므로 문제되지 않지만, **Task 6 이 새로 추가하는 단언과
픽스처가 어긋나면 혼란**이 생긴다. Task 6 착수 시 해당 두 테스트의 시드값을 먼저 읽고 맞춘다.
