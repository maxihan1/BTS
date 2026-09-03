# 프로젝트 요약·활동 API — Jira 패리티 캠페인 PR ③

> T2 · BC = issue-tracking · 마이그레이션 0건 · PR #435

## Context

요약 화면(J4)이 착지 화면이 되는데 채울 데이터가 없다. 기존 조합으로는 위젯을 **하나도** 못 만든다.

- `AqlFields.kt:45` 화이트리스트에 `created_at`/`updated_at`/`due_date`/`type` 이 없어 AQL 로 날짜를 못 묻는다.
- `GET /api/v1/issues` 필터는 `status[] assignee[] label[] component[]` 뿐(`IssueController.kt:300-311`).
- 대시보드 가젯 `pie_chart`·`activity_stream` 은 `GadgetType.kt:163,214` 에 `enabled=false` 스텁.
- Activity 는 이슈 단건 범위뿐(`GET /api/v1/issues/{key}/changelog`).

신설 —
`GET /api/v1/projects/{projectKey}/summary` · `GET /api/v1/projects/{projectKey}/activity?limit=`

## Jira 대조

Jira Cloud 실물 조회로 확정한 규칙. 기억으로 다시 정하지 않는다.

| # | 항목 | 원문·근거 | 출처 | 조회일 | 구분 |
|---|---|---|---|---|---|
| J4-1 | 상단 카드 4종 | "the number of work items completed, updated, and created in the last 7 days, as well as the number of items due in the next 7 days" | [summary view](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-summary-view/) | 2026-09-03 | Cloud |
| J4-2 | 「최근 2주」의 적용 범위 | "Only items that have been completed in the last two weeks will appear in Done" — **Status overview 의 Done 버킷에만** 적용된다 | 같은 문서 | 2026-09-03 | Cloud |
| J4-3 | 우선순위·유형 분포 | 기간 제한 서술 없음 — 활성 이슈 전량 | 같은 문서 | 2026-09-03 | Cloud |
| J4-4 | Team workload | "Monitor the capacity of your team" — 담당자별 현재 배분 | 같은 문서 | 2026-09-03 | Cloud |

### 의도적 편차

- **X-J4-1 — 담당자 분포를 넣는다.** 목업 v2 의 `pageSummary` 에는 Team workload 위젯이 없으나
  Jira 에는 실재한다. Maxi 확정으로 **Jira 스펙 우선**. 목업은 ④에서 위젯 하나를 추가한다.
- **X-J4-2 — 창 파라미터를 노출하지 않는다.** ④가 기간 선택 UI 를 만들지 않으므로 `days` 는
  아무도 쓰지 않는 유연성이 된다. 창은 고정(카드 7일 · Done 버킷 2주).

## 확정 판정

- **D1 — 「최근 7일 완료」는 상태 이력의 DONE 진입 시각.** BTS 에 `resolved_at` 컬럼이 없어
  실제 완료 시각을 아는 유일한 원천이 상태 전환 이력이다. `updated_at` 기반은 3개월 전 완료된
  이슈의 라벨 하나만 바꿔도 「오늘 완료」로 잡히는 거짓 양성이 상시 발생한다. CycleTime 선례 동형.
  DONE **안에서의** 이동(완료→보류완료)은 재완료로 세지 않는다.
- **D2 — 2주 특례는 `statusOverview` 에만.** J4-2 원문 그대로. 나머지 분포 3종은 전체 스코프.
- **D3 — 마이그레이션 0건.** `issue_change_group` 프로젝트 스코프 인덱스를 일부러 넣지 않는다.
  넣으면 이 PR 이 T3 로 승격되고(ADR+spec+plan, 리뷰 2+ceo), 실측 없이 성능을 주장하는 건
  근거 없는 단정이다. 부채로 등재하고 `EXPLAIN (ANALYZE, BUFFERS)` 실측 후 별건으로 판단한다.

## 설계

`cfd` 패키지가 템플릿이다. 새 패턴 0개.

- 컨트롤러 — `CfdController` 동형. `CurrentActor.current()` 가 파라미터 검증보다 **위**(존재 probe 차단).
- 예외 핸들러 — `basePackages` 한정, catch-all **없음**(401/400→500 변질 차단, FR-WT-01 교훈).
- 서비스 — `BROWSE 선검증 → accessibleLevels → 원천 조회 → 카테고리 해석`.
- 리포지토리 — 신규 3메서드 전부 `buildActiveSecureWhere`(`IssueRepository.kt:969`) **재사용**.

### 계획 대비 이탈 2건 (구현이 옳다 — 계획을 여기서 갱신)

1. `StatusHistoryRepository.fetchStatusChangesSince(issueIds, since)` 대신
   `IssueRepository.fetchStatusChangesSinceForProject` 조인. 프로젝트 전체를 이슈 id `IN` 절로
   넘기면 절이 이슈 수만큼 커진다. 조인이면 보안 술어를 그대로 걸 수 있어 정합도 더 낫다.
2. 분포를 축마다 별도 쿼리로 나누지 않고 **조인 없는 단일 SELECT + 서비스 인메모리 집계**.
   축마다 나누면 같은 보안 술어를 4번 반복하고, 한 쿼리로 묶으면 다중 조인이 건수를 부풀린다.
   「행 하나 = 이슈 하나」 불변식으로 cartesian product 를 **구조적으로** 불가능하게 만든다.

---

## 리뷰 후속 — BLOCKER 3건 (게이트 2 에서 확정 · **3건 모두 해소**)

**공통 진단.** 같은 `issue_change_item` 행을 읽는 **새 경로가 기존 방어선 3겹을 우회한다.**
`/issues/{key}/changelog` 가 이미 하고 있는 것을 `/projects/{key}/activity` 가 하지 않는다.

### B1 — 필드 수준 마스킹(FR-PM-07) 미적용

`ProjectSummaryService.getActivity` 가 `FieldPermissionResolver` 를 부르지 않는다.
`IssueChangelogService.maskInvisibleFields`(`:351`)는 `MASKABLE_CORE_FIELDS`
(`description·environment·impact·labels·summary·priority`) + `assignee` + `customField:*` 의
`fromValue/toValue/fromLabel/toLabel` 4종을 null 로 치환한다.

**처방.** `maskInvisibleFields`·`maskItemIfInvisible`·`maskableFieldRef` 를 두 서비스가 함께 쓰는
**협력자로 추출**한다. 복사하면 그 순간 `[[two-lists-never-check-each-other]]` 양식이다.
프로젝트 피드는 프로젝트가 하나라 resolver 호출도 1회면 된다(changelog 보다 단순).

- red — 「가려진 필드를 가진 뷰어의 activity 항목 값 4종이 null」
- 비-공허 짝 — 같은 픽스처에서 필드 권한만 열면 원문이 보인다

**해소.** `IssueChangeItemMasker`(`com.bts.issue.application`)로 추출하고 두 서비스가 주입받는다.
`IssueChangelogService` 는 의존 3개(issueRepository·fieldPermissionResolver·commentRepository)가
전부 마스킹 전용이었으므로 masker 1개로 대체됐다. projectId 미해석 시 원본 유지 폴백은 보존.

### B2 — 삭제된 댓글 본문 마스킹(FR-CO-02) 미적용

`fetchProjectActivity` 는 `field` 를 한정하지 않아 `comment:<uuid>` 항목이 함께 실린다.
`maskDeletedCommentBodies`(`:455`)는 `CommentRepository.findActiveIds` 로 판정하고
**파싱 실패도 마스킹**한다(fail-closed).

**처방.** B1 과 같은 지점에서 적용한다. 단 기존 함수는 `issueId` 단건 스코프이므로
프로젝트 피드용으로 **이슈별 배치**로 확장해야 한다 — `findActiveIds` 를 이슈당 1회가 아니라
피드 1회로 묶는다(N+1 회피). 확장이 어려우면 `comment:` 항목을 피드에서 제외하고
그 사실을 응답 계약에 명시한다. **조용히 흘리는 선택지는 없다.**

**해소 — 제외가 아니라 마스킹을 골랐다.**
제외하면 「삭제된 댓글이 수정된 적 있다」는 감사 사실까지 사라져 단건 이력과 비대칭이 되고,
활성 댓글의 정상 수정 이력마저 피드에서 빠진다. 대신 `CommentRepository.findActiveIds(ids, issueId)`
→ `findActiveOwners(idsByIssue): Map<댓글id, 이슈id>` 로 바꿔 **이슈가 여럿이어도 쿼리 1회**를 지키면서
소속 대조를 `WHERE` 안에 남겼다 — `(issue_id = A AND id IN (..)) OR (issue_id = B AND id IN (..))`.
집합이 아니라 맵을 돌려주는 이유도 같다. 호출자가 항목마다 자기 이슈와 다시 대조해야 타 이슈의
활성 댓글 id 가 섞여 마스킹을 뚫는 경로가 막힌다(`CommentRepository` 클래스 KDoc 의 원칙 유지).
피드 원천에는 `ProjectActivityRow.issueId`(`ISSUE_CHANGE_GROUP.ISSUE_ID`)를 추가했다 —
`issueKey` 는 기록 시점 값이라 소속 판정의 근거가 못 된다.

### B3 — VIEW_ISSUE 게이트를 BROWSE_PROJECT 로 대체

`IdentityAccessIssuePermissionResolver.kt:181-182` 가 `BROWSE→BROWSE_PROJECT`,
`VIEW→VIEW_ISSUE` 를 **독립 매트릭스 권한**(FR-PM-05)으로 위임한다.
기존 경로는 `IssueApplicationService.assertViewIssueOrNotFound`(`:1674`)로 VIEW 없으면
`IssueNotFoundException`(404, 존재 숨김)을 던진다.

**처방.** 집계만 내보내는 `/summary` 는 BROWSE 로 충분하다(CFD 선례). 이슈 단위 내용을
내보내는 `/activity` 는 **항목별 VIEW 게이트**를 건다. 피드의 이슈 키 집합에 대해
배치로 VIEW 를 판정하고 통과하지 못한 이슈의 항목을 제거한다.

- red — 「BROWSE 는 있고 VIEW 는 없는 뷰어의 activity 에 그 이슈 항목이 0건」

**해소.** 이슈 키 캐시로 판정 1회/이슈. `IssuePermissionResolver` 에 배치 API 가 없고 그 추가는
shared-kernel 변경이라 T3 승격이므로, 피드가 최대 `limit`(50) 그룹이라는 근거로 개별 호출 + 캐시를 택했다.
이슈 키 접두사가 조회 대상 프로젝트 키와 다르면 **묻지 않고 거부**한다 — resolver 가 접두사로 프로젝트를
해석하므로 그대로 물으면 다른 프로젝트 기준 판정이 나온다(fail-closed).

## 재리뷰 후속 — BLOCKER N1 (**해소**)

### N1 — VIEW 게이트가 기록 시점 이슈 키로 판정한다

**이 PR 이 스스로 못박은 불변식을 이 PR 의 코드가 위반했다.** `ProjectSummaryRows.kt:49` 가
「`issueKey` 는 기록 시점 값이라 소속 판정의 근거가 되지 못한다」고 적어 놓고,
`retainViewableRows` 는 캐시 키와 VIEW 판정을 둘 다 `issueKey` 로 만들었다.

**재현 경로(프로덕션 기능으로 도달 가능).** `POST /api/v1/issues/{key}/move`(FR-MV-01)의
`IssueRepository.moveIssue` 는 `issues.project_id`·`key` 만 갱신한다.
`issue_change_group.issue_key` 는 `JdbcIssueChangeHistoryRepository` 의 INSERT 두 곳에서만
쓰이고 **UPDATE 는 0건** — 이동해도 기록 시점 값 그대로다.

1. `AAA-1` 에 이력이 쌓인다 → `issue_change_group.issue_key = 'AAA-1'`
2. `BBB` 로 이동 → `issues.key = 'BBB-N'`, change group 은 `'AAA-1'` 유지
3. `GET /projects/BBB/activity` 의 1단계 조인은 **현재** `project_id` 를 걸므로(`buildActiveSecureWhere`)
   이동 전 그룹이 정상 반환된다. 행의 `issueKey` 는 `AAA-1`
4. `canViewIssue(actor, "BBB", "AAA-1")` → 접두사 불일치 → 전량 제거 + 행마다 WARN

**결과** — 이동된 이슈의 이동 **전** 활동이 어느 프로젝트 피드에서도 보이지 않는다.
원 프로젝트 `AAA` 는 조인이 현재 `project_id` 라 애초에 나오지 않는다. 사용자에게 누락 신호가
없고 `limit` 50 요청마다 WARN 이 최대 50줄 찍힌다 — **조용한 데이터 소실**이다.

**접두사 가드 자체는 옳다.** resolver 가 `scope.key.substringBefore('-')` 로 프로젝트를 해석하므로
어긋난 키를 그대로 물으면 fail-open 이다. 옳지 않은 것은 **가드에 먹이는 키**였다.

**해소.** `fetchProjectActivity` 2단계에 `.join(ISSUES).on(ISSUES.ID.eq(ISSUE_CHANGE_GROUP.ISSUE_ID))`
를 더해 현재 키 `ISSUES.KEY` 를 `ProjectActivityRow.currentIssueKey` 로 싣고, `canViewIssue` 와
캐시 키를 그 값으로 바꿨다. **표시용 `issueKey`(기록 시점)는 그대로 둔다** — 이력의 박제값이라
바꾸면 감사 근거가 사라지고 응답 계약도 깨진다.

- red — ACT-5(원천이 현재 키를 싣지 않는다) + 서비스 2건(이동된 이슈 항목이 사라진다 ·
  이동 전후 그룹이 섞이면 판정이 2회). EXIT=1, 45건 중 3건 실패
- 비-공허 짝 — 같은 픽스처에서 **현재 키**의 VIEW 를 닫으면 그 항목이 사라진다
- 행 부풀림 없음 — `ISSUES.ID` 는 PK(V001:34)이고 술어가 등가 비교라 그룹 행마다 매칭 최대 1건.
  ACT-3(항목 2건 → 행 2건)·ACT-5(항목 1건 → 행 1건)로 실측 확인

## 리뷰 후속 — CONCERNS

| # | 무엇 | 처방 |
|---|---|---|
| C1 | `buildStatusOverview` 가 `groupBy { currentStateKey }` 뒤 `rows.first().typeId` 로 이름·카테고리를 해석 — 타입별로 같은 키가 다른 카테고리일 수 있다 | `(statusKey, category)` 로 묶거나 상태 키 전역 유일 전제를 KDoc 에 못 박고 테스트로 고정 |
| C2 | `priorityBreakdown` 만 표시명이 없다 — 형제 3종은 이름을 싣고 `IssueResponse.kt:93-94` 도 `priority`+`priorityName` 을 준다 | **해소.** DTO 매퍼가 `priorityName: String?` 을 채운다. `IssuePriority.fromNumberOrNull` 을 단일 출처에 추가해 예외를 삼키지 않고 "값 없음" 을 타입으로 표현 — 범위 밖이면 키만 빠지고 200 유지 |
| C4 | D3 의 성능 부채가 원장에 미등재 | `TODOS.md` 에 ① 인덱스 부재 ② 무제한 fetch 두 건 등재 |
| C5 | 계획이 저장소 밖이라 `jira-research-guard` 가 공허 통과 | **이 파일로 해소.** `## Jira 대조` 절 포함 |
| A1 | OpenAPI 에 `limit` 계약 미기재(기본 20 · 1~50 · 범위 밖 400) | `@Parameter` + `@Schema(minimum/maximum/defaultValue)` |
| A2 | `Instant` ISO-8601 직렬화가 실조립 Jackson 으로 미검증 | `WorkflowReadContractProdBootTest` 동형 prod-boot 계약 테스트 추가 |
| A3 | NON_NULL **6필드**(C2 로 `priorityName` 추가)는 ④의 Zod 가 `.nullish()` 여야 한다 | ④ 스펙에 계약으로 전달 |
| S1 | `StatusHistoryRepository` companion 을 `public` → `internal` | 같은 BC 안 재사용은 되고 타 BC 컴파일 의존은 막힌다 |
| T1 | `SUM-5` 의 labels 픽스처가 **도달 불가** — 그 쿼리는 조인이 없고 `labels` 는 배열 컬럼이라 막겠다는 결함이 구조적으로 불가능 | 근거를 「전방 회귀 가드」로 정정하거나 실제 조인 쿼리로 픽스처를 옮긴다 |
| T2 | 창 경계(시작 inclusive / 끝 exclusive)를 서비스 수준에서 단언하는 테스트 없음 | **해소.** `ProjectSummaryServiceTest` 「집계 창 경계」 2건. `inRecent` 시작 배타화·`inPrevious` 끝 포함화 두 뮤테이션에서 각각 red 를 확인했다 |
| C-a | `findActiveOwners` 의 `((A AND IN) OR (B AND IN)) AND deleted_at IS NULL` 에서 **괄호 우선순위가 실제로 걸리는 조합**(다중 이슈 × 삭제 댓글)이 SQL 로 한 번도 실행된 적 없음 | **해소.** Order(14) 픽스처에 두 OR 가지를 차례로 소프트 삭제하는 단언을 이어 붙였다. 뮤테이션(`deleted_at` 을 마지막 가지에만 결합)에서 red 확인 — 첫 가지의 삭제 댓글이 샌다 |
| C-b | 창 끝 배타 단언이 **도달 불가 픽스처** 위에 있었다(기준 시각보다 12시간 뒤 이슈) — `unreachable-state-fixture-is-fake-green` | **해소.** import 주입 가능성을 먼저 확인했고 **불가능**했다(아래 근거). 시작 경계는 도달 가능한 픽스처(`recentFrom` 정각 / 1초 전)로 다시 세우고, 끝 경계 단언은 지우지 않고 **전방 회귀 가드**로 정직하게 다시 썼다. 접점 단언은 그대로 |
| C-d | OpenAPI 가 「응답이 `limit` 보다 적을 수 있다」를 안 적는다 | **해소.** `@Parameter` description + 컨트롤러 KDoc 에 명시 |

### C-b — 「이슈 `created_at` 에 미래 값을 주입할 수 있는가」 확인 결과: 불가능

- `issues` 를 INSERT 하는 경로는 저장소 전체에서 `IssueRepository.insert` **하나**뿐이다
  (`insertInto(ISSUES)` 전수 검색 → 1건). 그 경로가 쓰는 `Issue.toInsertRecord()` 는
  `created_at`·`updated_at` 을 **SET 하지 않아** DB DEFAULT `NOW()` 가 채운다(V001:48-49).
- `ISSUES.CREATED_AT` 을 `.set(...)` 하는 코드는 main 전체에 **0건**. `UPDATED_AT` 은 9곳 모두
  `OffsetDateTime.now(UTC)` 다.
- import 경로 `IssueImportAdapter` 는 원본(Jira) 시각을 **댓글·첨부에만** 보존하고
  (`ImportComment.createdAt` · `importAttachment.createdAt`), 이슈는
  `IssueApplicationService.createIssue` 를 그대로 탄다.

따라서 `recentTo`(내일 UTC 자정) 이후 값을 가진 이슈는 프로덕션에 존재할 수 없다.
지적은 유효했고, 단언은 「전방 회귀 가드」로 근거를 명시해 남겼다 — 이슈 원본 시각 보존이
import 에 추가되는 순간 이 창은 실전에서 도달 가능해지기 때문이다.

## 검증

```bash
cd backend
# 창 경계 단언은 ProjectSummaryServiceTest 안에 있다(별도 클래스 아님).
./gradlew :modules:issue-tracking:test --tests '*ProjectSummary*' --tests '*IssueChangelog*' \
  --tests '*ChangelogCursorMode*' --tests '*CommentRepositoryTest*' \
  --tests '*CommentEditDeleteHistory*'           # 마스킹 협력자 추출로 기존 경로 회귀 확인 필수
./gradlew :modules:issue-tracking:ktlintCheck
./gradlew :modules:issue-tracking:detekt --rerun-tasks   # 캐시가 위반을 가린다
./gradlew :modules:app:test --tests '*BtsApplicationContextTest*'
./gradlew :modules:app:nonProdAssemblyTest
cd .. && bash scripts/verify-master-plan.sh && node scripts/build-doc-index.mjs --check
```

**CI 는 돌 수 없다** — 워크플로우 4종이 `workflow_dispatch` 전용이고 `[self-hosted, bts-local]`
러너가 0개다(`gh api repos/maxihan1/BTS/actions/runners` → `total_count: 0`). 검증은 전부 로컬이다.

**전체 모듈 테스트는 1시간을 넘긴다** — 컨테이너 71개를 순차로 띄우고 Ryuk 이 JVM 종료 때까지
정리하지 않는다. `--tests` 로 좁혀 돌린다.
