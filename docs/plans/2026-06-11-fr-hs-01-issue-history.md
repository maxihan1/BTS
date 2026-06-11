# FR-HS-01 이슈 변경 이력 기록

> slug: fr-hs-01-issue-history
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-11

## Brief

**원문 요청**. FR-HS-01 이슈 변경 이력 기록 구현. 이슈의 필드 변경(상태/담당자/우선순위/본문 등)이 발생하면 변경 이력(누가/언제/무엇을/이전값→새값)을 이력 테이블에 기록하는 백엔드 전용 기능.

**FR**. FR-HS-01 (이슈 변경 이력) — SDD `02-requirements.md` §5.1.1, issue-tracking BC.
**선행**. FR-IS-01 (이슈 CRUD) 완료.
**후속 unblock**. FR-HS-02 (히스토리 조회 UI), FR-MV-02 (이슈 이동 시 히스토리 보존).

**병행 작업 (충돌 회피 대상)**.
- FR-MF-01 (identity-access, worktree `fr-mf-01-totp-authenticator`, PR #113) — 다른 BC, 충돌 없음.
- FR-MN-01 (issue-tracking 멘션, worktree `fr-mn-01-mention-notify`, PR #114) — **같은 BC**. Flyway 마이그레이션 V번호 충돌 + 같은 모듈 파일 충돌 주의.
  - issue-tracking 최신 마이그레이션 V017(FR-VR-03 조인테이블)까지 확인. 새 마이그레이션은 FR-MN-01이 선점할 번호를 피해 배정.
  - 본 작업은 **백엔드 전용**(이력 기록 리스너 + 테이블) — FR-MN-01의 이슈 상세 프론트와 표면 비중첩.

## 도메인 정리

- **BC**. issue-tracking (단일, 명확)
- **영향 엔티티**. Issue(기존, 변경 메서드에 이력 호출 추가), IssueChangeGroup(신규), IssueChangeItem(신규)
- **용어**. domain/issue-tracking.md가 이미 `IssueHistory`를 핵심 엔티티로 등재 → 신규 용어 아님. 하위 모델명 IssueChangeGroup/IssueChangeItem는 glossary 갱신 후보(머지 시 Obsidian sync).
- **기존 결정 충돌**. 없음. domain "이슈+히스토리+알림 한 트랜잭션" 규칙 + DATA.md append-only와 정합.
- **재사용 선례**. FR-AU-10 `auth_audit_logs`(append-only, BIGINT IDENTITY, FK 없음, JSONB, NamedParameterJdbcTemplate).

### 확정된 도메인 결정 (Maxi, 2026-06-11)

| # | 결정 | 선택 | 비고 |
|---|---|---|---|
| 1 | 기록 메커니즘 | **서비스 레이어 동기 기록** (같은 트랜잭션) | 이전값→새값 정확 + 이벤트 미발행 경로 커버 |
| 2 | 데이터 모델 | **Jira식 2테이블** (change_group + change_item) | 한 PATCH=1그룹, 필드별 from→to=N아이템 |
| 3 | 추적 범위 | **전 필드 + 생명주기** | 전 편집 필드 + 생성/소프트삭제 |
| 4 | 보존 | **append-only** | 삭제/수정 금지, 이슈 소프트삭제 후에도 보존 |

### 구현 시 ground truth (조사 결과)

- **변경 진입점**(이력 호출 추가 대상). `IssueApplicationService.updateIssue:365` / `transitionIssue:531` / `changeAssignee:645` / `changeComponents:710` / `changeAffectsVersions:764` / `changeFixVersions:805` / `assignSecurityLevel`(updateIssue 내) / `createIssue:149`(생성) / `softDeleteIssue:605`(삭제).
- **갭**. changeAssignee/Components/Versions는 현재 pgmq 이벤트 미발행 → 동기 기록 방식이 이를 자연 커버.
- **변경 감지**. `IssueApplicationService.buildChangedFields:1021`가 이미 existing↔request 비교 로직 보유 → from/to 추출에 확장 활용.
- **마이그레이션**. issue-tracking 최신 V017. FR-MN-01(PR #114) worktree 새 마이그레이션 없음 확인 → **V018 후보**. 단 FR-MN-01이 먼저 V018을 쓰면 rebase 필요(머지 직전 재확인).

- **관련 ADR**. [docs/adr/2026-06-11-issue-change-history-model.md](../adr/2026-06-11-issue-change-history-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-hs-01-issue-history.md](../specs/2026-06-11-fr-hs-01-issue-history.md)

핵심 요약.
- **기록만** 담당(부수효과). 공개 조회 API/UI는 FR-HS-02. 검증은 통합테스트가 변경 후 이력 테이블 직접 조회.
- 8개 변경 진입점(create/update/transition/assignee/components/affects·fixVersions/softDelete)에서 **이슈 변경과 같은 트랜잭션**에 이력 INSERT.
- 모델: `issue_change_group`(actor/issue/created_at) + `issue_change_item`(field/from_value/to_value/**from_label/to_label**). 한 변경=1그룹, 바뀐 필드별 N아이템. no-op이면 이력 0.
- 값=원시 ID/스칼라, 라벨=변경 당시 표시 이름 박제(Jira식). customFields는 키별 분해. 컬렉션은 정렬 JSON 배열. lifecycle은 created/deleted 단일 마커.
- append-only(UPDATE/DELETE 부재), 이슈 소프트삭제 후에도 보존. FK는 group→item만, issues로의 FK 없음.
- 마이그레이션 V018(FR-MN-01 #114와 조율) + init_codegen 미러.

## Brainstorming Check

✅ 통과 (셀프 적대적 sanity check 1회). gap 2건(표시값 손실·customFields 입자) Maxi 확정 해소.

## Plan

경로 접두사 생략형. `M = backend/modules/issue-tracking`.

### Task 1. V018 마이그레이션 — issue_change_group/item 2테이블 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`M/src/main/resources/db/migration/issue-tracking/V018__issue_change_history.sql`, `M/src/main/resources/db/init_codegen.sql`, `M/src/test/.../migration/IssueChangeHistorySchemaTest.kt`]
- depends-on: []

**RED**: Testcontainers Flyway 적용 후 `information_schema`로 `issue_change_group`·`issue_change_item` 테이블 + 컬럼(from_label/to_label 포함) + group→item FK 존재 단언. (없으면 fail)
**GREEN**: V018 SQL 작성 — 스펙 §데이터 모델 DDL 그대로(2테이블, BIGINT IDENTITY, append-only 컬럼 없음, idx 3종). init_codegen.sql에 동일 2테이블 미러(메모리: jooq-init-codegen-mirror).
**REFACTOR**: 인덱스/컬럼 주석 한국어 정리.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeHistorySchemaTest'`

### Task 2. 이력 모델 + 변경 디텍터 (순수 함수)

**메타**.
- agent: `backend-engineer`
- files: [`M/src/main/.../history/IssueChangeGroup.kt`, `M/src/main/.../history/IssueChangeItem.kt`, `M/src/main/.../history/IssueChangeDetector.kt`, `M/src/test/.../history/IssueChangeDetectorTest.kt`]
- depends-on: []

**RED**: `IssueChangeDetectorTest` — (a) priority 3→1 ⇒ item 1개(field='priority', from='3', to='1'), (b) summary+priority 동시 변경 ⇒ item 2개, (c) no-op ⇒ empty, (d) 컬렉션 순서만 다르고 집합 동일 ⇒ empty(정렬 비교), (e) components [A]→[A,B] ⇒ item 1개(JSON 배열), (f) customFields 키별 분해(field='customField:&lt;key&gt;'), (g) description null↔"" 구분, (h) lifecycle created/deleted 마커. **label은 이 단계에서 null**(value만).
**GREEN**: 이전 Issue 상태 + 새 상태(변경 요청) 비교. 필드별 비교 표. 컬렉션은 도메인 `distinct`/정렬 후 비교. 변경분만 `IssueChangeItem(field, fromValue, toValue)` 생성.
**REFACTOR**: 필드 비교를 `(field, prev→raw, next→raw)` 매핑 테이블로 추출.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeDetectorTest'`

### Task 3. 라벨 리졸버 — 변경 당시 표시 이름 박제

**메타**.
- agent: `backend-engineer`
- files: [`M/src/main/.../history/IssueChangeLabelResolver.kt`, `M/src/test/.../history/IssueChangeLabelResolverTest.kt`]
- depends-on: [2]

**RED**: `IssueChangeLabelResolverTest`(lookup mock) — assignee id→username, type id→타입명, resolution id→resolution명, components ids→이름 정렬배열, versions ids→이름, securityLevel id→레벨명. status는 stateKey passthrough(label=value). 값 자체가 표시인 필드(summary/priority/...)는 label=value.
**GREEN**: field별 분기. issue-tracking 내 name lookup(type/resolution/component/version/securityLevel) + assignee는 기존 user lookup 포트 재사용. 변경 시점 서비스 보유 표시값 우선, 부족분만 조회.
**REFACTOR**: field→resolver 전략 맵.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeLabelResolverTest'`

### Task 4. Repository — append-only INSERT (Jdbc)

**메타**.
- agent: `backend-engineer`
- files: [`M/src/main/.../history/IssueChangeHistoryRepository.kt`, `M/src/main/.../history/JdbcIssueChangeHistoryRepository.kt`, `M/src/test/.../history/JdbcIssueChangeHistoryRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers 통합테스트 — `record(group, items)` 후 직접 SELECT로 group 1행 + item N행(from/to value·label) 검증. 인터페이스에 update/delete 메서드 부재(append-only) 구조 단언.
**GREEN**: `JdbcIssueChangeHistoryRepository` — group INSERT(`RETURNING id`) → items batch INSERT. `NamedParameterJdbcTemplate`(FR-AU-10 JdbcAuthAuditLogService 패턴). 검증 전용 최소 `findByIssue`(FR-HS-02가 본 조회).
**REFACTOR**: SQL 상수 분리.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*JdbcIssueChangeHistoryRepositoryIntegrationTest'`

### Task 5. 서비스 통합 — 8개 변경 진입점 동기 기록 배선

**메타**.
- agent: `backend-engineer`
- files: [`M/src/main/.../application/IssueApplicationService.kt`, `M/src/test/.../application/IssueApplicationServiceTest.kt`(기존 — 생성자 주입 파급 갱신), `M/src/test/.../application/IssueHistoryRecordingTest.kt`]
- depends-on: [2, 3, 4]

**RED**: `IssueHistoryRecordingTest`(mock repository) — createIssue/updateIssue/transitionIssue/changeAssignee/changeComponents/changeAffectsVersions/changeFixVersions/softDeleteIssue 각각 호출 후 `repository.record`가 올바른 group+items로 1회 호출됨 검증. no-op update ⇒ record 미호출. **기존 IssueApplicationServiceTest는 새 의존성 주입으로 생성자 변경 → 동일 PR에서 갱신**(메모리: plan-files-constructor-injection-existing-tests).
**GREEN**: 각 메서드에서 변경 전 스냅샷 확보 → detector → labelResolver로 label 채움 → `repository.record`(이슈 변경과 **같은 트랜잭션** 내). actor=각 메서드 인증 주체(부재 시 nullable). 기록 호출을 private 헬퍼로 집약.
**REFACTOR**: `recordChange(issueBefore, issueAfter, actor)` 헬퍼 1곳으로 8개 진입점 중복 제거.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueHistoryRecordingTest' --tests '*IssueApplicationServiceTest'`

### Task 6. end-to-end 통합 — 실 repository 시나리오 + append-only/보존

**메타**.
- agent: `backend-engineer`
- files: [`M/src/test/.../history/IssueChangeHistoryE2EIntegrationTest.kt`]
- depends-on: [5]

**RED**: Testcontainers 실 repository로 8개 진입점 각각 변경 → 이력 테이블 직접 조회 검증. (a) 한 PATCH 다필드 ⇒ 1그룹 N아이템, (b) no-op ⇒ 이력 0, (c) 이슈 소프트삭제 후에도 과거 이력 조회 가능(보존), (d) 라벨 박제 영속(이후 이름 변경 가정해도 박제값 유지), (e) 트랜잭션 — 이력 기록 실패 시 이슈 변경 롤백.
**GREEN**: Task 5 배선으로 통과(검증 강화 task).
**REFACTOR**: 시나리오 헬퍼.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeHistoryE2EIntegrationTest'` + 풀 모듈 `./gradlew :backend:issue-tracking:test --rerun-tasks` + `ktlintCheck detekt`

## Plan 메타

- task 수: 6
- 의존 그래프 (예상 wave). wave1: T1·T2 / wave2: T3(←2)·T4(←1,2) / wave3: T5(←2,3,4) / wave4: T6(←5). 단 같은 모듈 test 컴파일 동시성은 bts-impl이 직렬화 판단(메모리: bts-plan-wave-gradle-module-compile).
- 예상 시간: 6 task × ~4분 ≈ 직렬 24분 / wave 적용 약 14분.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저).
- 추가 검증: init_codegen 미러(T1), 풀 모듈 `--rerun-tasks` + ktlint/detekt(T6). 프론트/E2E 없음(백엔드 전용).
- 충돌 주의: FR-MN-01(PR #114) 같은 모듈 — V018 선점 시 rebase, IssueApplicationService 동시수정 시 머지 충돌 가능(머지 직전 재확인).

## 리뷰 결과 (← /bts-review-plan 채움)
