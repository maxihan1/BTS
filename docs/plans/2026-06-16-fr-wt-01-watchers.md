# FR-WT-01 — Watcher 추가/제거 + 자동 Watcher (백엔드 D1~D5)

> slug: fr-wt-01-watchers
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-16

## Brief

사용자 원문. "fr-wt-01 진행해줘"

명세 — `docs/plan/product/issue-tracking.md §4.3.1 FR-WT-01`.
이슈 Watcher(이슈를 지켜보며 알림 대상이 되는 사용자) 추가/제거 + Reporter/Assignee 자동 Watcher 등록.

이번 PR 범위 — **백엔드 D1~D5** (BTS 표준 관례에 따라 프론트 D6/D7은 후속 PR `fr-wt-01-watchers-ui-e2e`로 분리).

- D1. 도메인 (backend-engineer)
- D2. 명세 — Reporter/Assignee 자동 Watcher (backend-engineer)
- D3. 데이터 모델 — `issue_watchers` (db-engineer)
- D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/watchers` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)

classify 결과. type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: issue-tracking (단일, 충돌 없음)
- **용어**: "워처(Watcher) — 이슈 변경 알림 수신자" glossary 기존 등록. 신규 용어 0.
- **신규 엔티티**: `Watcher` = (issue, user) 관계. `issue_watchers` 테이블 신설.
  - 마이그레이션 V번호 = **V024** (issue-tracking 모듈 최신 V023, 경로 `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/`). init_codegen.sql 미러 필수 ([[jooq-init-codegen-mirror]]). 머지 직전 V번호 재확인 ([[migration-vnumber-concurrent-branch-collision]]).
- **cross-BC**: `user_id`는 identity-access 소유 → **FK 미적용** (BC 격리, `assignee_id` 선례 동일 — ADR `2026-06-01-issue-assignee-user-lookup-port`).
  - watcher 추가 시 사용자 실재 검증 = `UserLookupPort.exists(userId)` 재사용 (assignee 선례, shared-kernel port).
- **자동 Watcher**: `IssueApplicationService.createIssue`(reporter 자동 watch) + assign 흐름(assignee 자동 watch)에 부착. 트랜잭션 경계 = 이슈 변경과 동일 트랜잭션(절대 규칙 — 이슈+부수효과 원자성).
- **경계 분리**: FR-WT-01은 watcher **목록 소유(추가/제거/저장)**만. 알림 수신자 해석(목록 읽어 알림 발송)은 **FR-NT-03 RecipientResolver** 경계 — ADR `2026-06-12-notification-inapp-channel-delivery` §결정3에서 명시 분리. 본 PR 침범 금지.
- **web 패키지**: `com.bts.issue.watcher.web.IssueWatcherController` (하위 도메인별 패키지 관례 — attachment/template/customfield/bulk 선례).
- **기존 결정 충돌**: 없음. 오히려 두 ADR이 FR-WT-01 도입을 예견.
  - `2026-06-02-issue-clone-semantics` — Watcher 미구현 확인 + 도입 시 `CloneOptions` 확장 가능(본 PR 범위 외, 클론 watcher 복사는 FR-IS-06 후속 이연 유지).
  - `2026-06-12-notification-inapp-channel-delivery` — 알림 측이 이 목록을 FR-NT-03에서 소비 예정.
- **관련 ADR**: 신규 ADR 후보 = "Watcher 추가 의미론(self-only vs 타인 추가) + 자동 Watcher 정책". **spec 단계에서 핵심 결정 확정 후 작성**.
- **grill-with-docs 스킵 사유**: 명세가 product 문서 §4.3.1에 D1~D7로 명확, 도메인 선례(assignee/UserLookupPort) 강함. 대화형 검증 비용 > 효익 ([[bts-spec-office-hours-mismatch]] 정신).

### spec에서 확정할 핵심 갈림길 (도메인 영향)

1. **Watcher 추가 대상** — self-only(GitHub식, 본인만 watch/unwatch) vs 타인도 watcher 추가 가능(Jira식, 권한 필요). D6 "Watch 버튼"은 self 토글 시사. 권한 모델·API body 형태에 직결.
2. **자동 Watcher ↔ 수동 unwatch 상호작용** — reporter/assignee 자동 watch 후 본인이 수동 unwatch 가능한가(보통 가능). 재배정 시 이전 assignee watcher 유지 여부.
3. **권한** — watch 추가/제거에 필요한 권한(이슈 VIEW면 충분한지).

## 스펙

전체 스펙. [docs/specs/2026-06-16-fr-wt-01-watchers.md](../specs/2026-06-16-fr-wt-01-watchers.md)

**Maxi 확정(2026-06-16)** — ① 타인 추가 가능(Jira식) ② 자동 Watcher 표준(해제 허용·재배정 유지).

핵심 요약.
- API 3종 — `GET /watchers`(VIEW, 목록+count+isWatching) · `POST /watchers {userId?}`(본인=VIEW/타인=UPDATE, 멱등) · `DELETE /watchers/{userId}`(본인=VIEW/타인=UPDATE, 멱등).
- 권한 분기 = 대상 userId가 본인이면 VIEW, 타인이면 UPDATE. 타인 추가 시 UserLookupPort.exists 검증(422).
- 자동 watcher 2종 — createIssue(reporter + 있으면 assignee), changeAssignee(신규 assignee). 동일 트랜잭션·멱등. cloneIssue 제외(ADR 이연).
- 데이터 — `issue_watchers(issue_id, user_id, created_at)` 복합PK, user_id FK 미적용. V024 + init_codegen 미러.
- 경계 — 알림 발송은 FR-NT-03. 본 PR은 watcher 목록 소유만.

## Brainstorming Check

✅ 통과 (직접 sanity check, gap 3건 보강 — 생성시 assignee 자동watch / clone 범위제외 / GET 정렬).

## Plan

> 템플릿 = attachment 하위도메인(jOOQ DSLContext repository + domain/repository/application/web 4계층). 권한 = 기존 `IssuePermissionResolver`/`IssuePermission` 재사용(신규 권한코드 0).

### Task 1. DB 마이그레이션 V024 issue_watchers + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V024__issue_watchers.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/watcher/WatcherSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `WatcherSchemaMigrationTest` — Testcontainers로 flyway migrate 후 `issue_watchers` 테이블 + 컬럼(issue_id/user_id/created_at) + 복합 PK(issue_id,user_id) + issue_id 인덱스 존재 검증 (`AttachmentSchemaMigrationTest` 패턴 복제).

**GREEN**:
```sql
CREATE TABLE issue_watchers (
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);
CREATE INDEX idx_issue_watchers_issue ON issue_watchers (issue_id);
```
+ `init_codegen.sql`에 동일 DDL 미러(jOOQ ISSUE_WATCHERS codegen 필수 — [[jooq-init-codegen-mirror]]).

**REFACTOR**: 컬럼/제약 주석(L1 한글 주석), V번호 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*WatcherSchemaMigrationTest'`

### Task 2. IssueWatcherRepository (jOOQ) — add(멱등)/remove/listByIssue/countByIssue/existsForUser

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/repository/IssueWatcherRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/watcher/IssueWatcherRepositoryTest.kt`]
- depends-on: [1]

**RED**: `IssueWatcherRepositoryTest`(Testcontainers) — add→listByIssue 반영, **ON CONFLICT DO NOTHING 멱등**(같은 (issue,user) 2회 add → 1행), remove 멱등(없어도 예외 없음), countByIssue, existsForUser true/false, listByIssue created_at ASC 정렬.

**GREEN**: `@Repository` + jOOQ `DSLContext` → `ISSUE_WATCHERS`. add=`insertInto…onConflictDoNothing()`, remove=`deleteFrom…where(issueId,userId)`, listByIssue=`selectFrom…orderBy(CREATED_AT.asc())`, count, exists. 모든 메서드 `@Transactional`.

**REFACTOR**: KDoc 메서드 목록(attachment repo 스타일), Record→(userId,createdAt) 매핑 헬퍼.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueWatcherRepositoryTest'`

### Task 3. IssueWatcherService — watch/unwatch/list + 권한 분기 + UserLookupPort 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/application/IssueWatcherService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/application/WatcherUserNotFoundException.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/watcher/IssueWatcherServiceTest.kt`]
- depends-on: [2]

**RED**: `IssueWatcherServiceTest`(MockK repo/resolver/userLookup) —
- **권한 분기**: 대상==actor → `hasPermission(VIEW)` 호출, 대상!=actor → `hasPermission(UPDATE)` 호출. 거부 시 예외(403 매핑).
- 타인 추가 시 `userLookupPort.exists(userId)` false → `WatcherUserNotFoundException`(422). 본인·자동은 미검증.
- 이슈 미존재/소프트삭제 → 404(존재 probe 차단, **권한 체크를 이슈조회보다 먼저** — 첨부 선례).
- 멱등(이미 watch 재추가 no-op), list = watchers(displayName via `findDisplayNamesByIds`) + count + isWatching(actor 기준).

**GREEN**: `@Service` + 생성자 주입(IssueWatcherRepository, IssuePermissionResolver, UserLookupPort, IssueRepository for key→id/존재). watch(key,actor,targetUserId?)·unwatch·list. 권한 scope=`IssueScope.Issue(key)`.

**REFACTOR**: `checkPermission` private 헬퍼(self/타인 분기), KDoc.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueWatcherServiceTest'`

### Task 4. 컨트롤러 + DTO + 예외핸들러 — GET/POST/DELETE watchers

**메타**.
- agent: `backend-engineer` (권한 가드 — codereview 시 security 관점 검증, 컨트롤러 직접 확인 [[subagent-ktlint-false-green-controller-verify]])
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/web/IssueWatcherController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/web/WatcherDtos.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/watcher/web/WatcherExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/watcher/IssueWatcherControllerTest.kt`]
- depends-on: [3]

**RED**: `IssueWatcherControllerTest`(통합, 실 서비스+stub resolver) —
- `GET /api/v1/issues/{key}/watchers` 200 `{watchers,count,isWatching}`.
- `POST` body 없음(self) 201/200, body `{userId}` 타인 201, 권한 없는 타인 403, 없는 userId 422.
- `DELETE /watchers/{userId}` 204, 멱등.
- 이슈 404. actor는 인증 헤더에서 추출(기존 컨트롤러 패턴).

**GREEN**: `IssueWatcherController`(`@RestController`, `/api/v1/issues/{key}/watchers`) → IssueWatcherService 위임. `AddWatcherRequest(userId: UUID?)` + `WatcherListResponse`/`WatcherSummary`. `WatcherExceptionHandler` — WatcherUserNotFoundException→422, 권한예외→403, IssueNotFound→404(메시지 일반화 [[fr-pm-04-guard-exception-message-http-leak]]).

**REFACTOR**: 경로 상수, KDoc, `@JsonInclude` 정합.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueWatcherControllerTest'`

### Task 5. 자동 watcher 배선 — createIssue + changeAssignee

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceWatcherTest.kt`]
- depends-on: [2]

**RED**: `IssueApplicationServiceWatcherTest` —
- createIssue → reporter가 watcher. resolvedAssignee non-null이면 assignee도 watcher(생성 동시 배정).
- changeAssignee(non-null) → 새 assignee watcher(멱등).
- **updateIssue 컴포넌트 자동재배정(L799-807) → 새 resolved assignee watcher** (CONCERN-1 — assignee 배정의 모든 통로 일관 적용).
- 재배정 A→B → B 추가, **A 유지**.
- unassign(null) → watcher 무변경.
- 모두 같은 트랜잭션(중간 실패 시 롤백).

**GREEN**: `IssueWatcherRepository` 생성자 주입(시스템 자동등록 — 권한 체크 없음, repo 직접). 자동 watch 진입점 **3종**:
- `createIssue` L236(insert) 직후 `autoWatch(saved.id, listOfNotNull(reporter, resolvedAssignee))`.
- `changeAssignee` 신규 assignee non-null이면 `autoWatch(issueId, listOf(assignee))`.
- `updateIssue` 컴포넌트 자동재배정 L805~807(`setAssignee` 직후) resolved non-null이면 `autoWatch(issueId, listOf(resolved))`.
self-invocation 무관(repo 직접 호출, 클래스 레벨 @Transactional 안).

**REFACTOR**: `private fun autoWatch(issueId, userIds)` 헬퍼(멱등 set), KDoc에 "자동 watcher 정책(FR-WT-01)" 명시. cloneIssue 제외 주석(ADR 이연).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueApplicationServiceWatcherTest'` + 기존 `IssueApplicationServiceTest` 회귀.

## Plan 메타

- task 수: 5
- 의존성 그래프: 1 → 2 → {3 → 4, 5}
- 예상 wave: wave1[T1] → wave2[T2] → wave3[T3, T5 병렬] → wave4[T4]. 레이어드 직렬성 강함(같은 issue-tracking 모듈 test 컴파일 단위 [[bts-plan-wave-gradle-module-compile]]).
- TDD 강제: yes (test 커밋이 feat보다 먼저)
- 추가 검증: 머지 전 `:backend:modules:issue-tracking:test ktlintCheck detekt` 그린 + V번호 재확인 + verify-master-plan(D 마킹은 머지 단계).
- 범위 경계: 알림 발송 금지(FR-NT-03), cloneIssue 자동watch 제외(ADR 이연), 프론트 D6/D7 후속 PR.

## 리뷰 결과

### plan-eng-review (2026-06-16, 직접 eng 집중 리뷰)
- ✅ 트랜잭션 경계 — `IssueApplicationService` 클래스 레벨 `@Service @Transactional`(L99). 자동 watch는 같은 트랜잭션·repo 직접 호출(self-invocation 무관).
- ✅ resolver prod 빈 — `IdentityAccessIssuePermissionResolver`(@Profile("prod")) 존재, attachment 검증됨. watcher 동일 resolver 재사용 → fail-open 위험 없음.
- ✅ 멱등성 — DB 복합PK + ON CONFLICT DO NOTHING(Task 1/2). jOOQ codegen은 init_codegen 미러로 보장(1→2 의존 정확).
- ✅ 권한 우선·존재 probe 차단 — 첨부 선례(권한 체크 후 이슈조회), actor 추출 먼저.
- 🟡 **CONCERN-1 (반영 완료)** — `updateIssue` 컴포넌트 자동재배정(L799-807)이 changeAssignee를 안 거쳐 자동 watch 누락. Task 5 진입점을 **3종(createIssue·changeAssignee·updateIssue)**으로 확장, spec FR-7 동기화.
- **BLOCKER: 없음.**

### plan-devex-review (2026-06-16, 직접 API 일관성 리뷰)
- ✅ 경로 — `/api/v1/issues/{key}/watchers`가 기존 `/links`·`/attachments`·`/changelog`와 동일 컨벤션.
- ✅ POST body 옵셔널 userId(self 생략/타인 명시) — Jira식 일관. 멱등 시 200, 신규 201 명시.
- ✅ 응답 `{watchers, count, isWatching}` — 프론트 D6(버튼+카운트)에 충분.
- **BLOCKER: 없음.**
