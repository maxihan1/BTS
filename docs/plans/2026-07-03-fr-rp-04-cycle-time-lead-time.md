# FR-RP-04 — Cycle Time / Lead Time 분포 리포트

> slug: fr-rp-04-cycle-time-lead-time
> type: backend
> agent: backend-engineer
> 생성: 2026-07-03

## Brief

사용자 원문. "fr-rp-04진행하자"

FR-RP-04 (Cycle Time / Lead Time 분포, 우선순위: 높음, BC: notification-dashboard, SDD §4.4).
FR-RP 리포트 시리즈(FR-RP-01 번다운 · FR-RP-02 벨로시티 · FR-RP-03 CFD, 모두 완결 95/123)의 다음 항목.
분류. type=backend, agent=backend-engineer. 백엔드 D1~D5 우선 (프론트 D6/D7 후속 PR 예상).

## 도메인 정리

- **BC**. 물리 모듈 = `issue-tracking`(상태 전이 이력·이슈 메타 로컬 소유, cross-BC 포트 0 — 스프린트 미참조). 논리 라벨 = notification-dashboard(fr-index, 카운트 14 불변). CFD(FR-RP-03) 동형.
- **데이터 소스**. on-the-fly 역산 — `issue_change_group`/`issue_change_item`(V018, FR-HS-01) status 전이 이력 + `issues.created_at`. **신규 스키마·스케줄러 0**. CFD 인프라 재사용(`CfdStatusHistoryRepository.fetchStatusChanges`, `IssueRepository.fetchActiveVisibleIssuesForCfd`, `CfdIssueSourceRow`, `IsolatedWorkflowStateLookup`).
- **CFD와의 핵심 차이**. CFD=날짜별 누적 카운트(day 절삭). FR-RP-04=이슈별 소요 **기간(duration)**의 **분포** → 실제 타임스탬프 기반 **초 단위**(day 절삭 시 같은 날 완료 이슈가 0이 되어 분포 무의미).
- **새 도메인 개념(read-model VO, 영속 아님)**. `CycleTimeSample`(이슈별 `{issueKey, cycleTimeSeconds?, leadTimeSeconds, completedAt}`) + `CycleTimeDistribution`(집계 통계). 애그리거트/테이블 없음. CFD `CfdPoint`/`CfdResult` 동형.
- **정의**(SDD §13.5.5). Cycle Time = In Progress(카테고리) → Done. Lead Time = Created → Done. 카테고리 매핑은 CFD의 `WorkflowStateCatalog.category`(TODO/IN_PROGRESS/DONE) 재사용.
- **보안**. 프로젝트 BROWSE + 이슈별 가시성 필터(`buildActiveSecureWhere` 재사용, CFD D4 동형 — 개수/샘플로 기밀 이슈 추론 차단).
- **엔드포인트**. `GET /api/v1/projects/{projectKey}/cycle-time`(product §4.4 D4). 창=from/to + 상한(CFD 동형).
- **새 용어(glossary 추가 후보, Maxi 승인)**. Cycle Time(작업 시작→완료 소요), Lead Time(요청/생성→완료 소요).
- **기존 결정 충돌**. 없음. CFD ADR(`2026-07-03-fr-rp-03-cfd`) 선례 계승.
- **관련 ADR**. `docs/decisions/2026-07-03-fr-rp-04-cycle-lead-time.md`(스펙 확정 후 생성 예정).

### 스펙에서 Maxi 확정 필요한 갈림길
1. Cycle Time 시작점 정의 + IN_PROGRESS 미경유/재오픈 이슈 처리.
2. 분포 모집단 + 창 필터 기준(완료일).
3. 응답 형태(이슈별 샘플 + 백분위 통계 vs 사전 버킷 히스토그램).

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-rp-04-cycle-time-lead-time.md](../specs/2026-07-03-fr-rp-04-cycle-time-lead-time.md)

핵심 3줄 요약.
- `GET /api/v1/projects/{key}/cycle-time?from=&to=` — 창 내 **완료 이슈**(마지막 DONE 전이일 기준)의 Lead(created→done)·Cycle(첫 IN_PROGRESS→done, 미경유 제외) 소요를 **초 단위** 분포로 반환.
- 응답 = 이슈별 `samples[{issueKey,seconds}]` + 서버 계산 통계(count·min·max·avg·p25·p50·p75·p90, nearest-rank, count=0→null). CFD 인프라(status 이력·상태 카탈로그·보안 술어) 재사용, 신규 스키마 0.
- 보안 = BROWSE 선검사 + `buildActiveSecureWhere` 재사용 가시성 필터(기밀 누출 0). 창 상한 180일, 잘못된 창 400, 미존재/무권한 403.

Maxi 확정(2026-07-03). Cycle=첫 IN_PROGRESS→완료(미경유 Cycle 제외) · 모집단=완료일 기준 창 내 완료 · 응답=샘플+요약 통계.

## Brainstorming Check

✅ 통과 (2회 iteration). gap 4건 보강 — p25(박스플롯 Q1) 추가 · 미존재 프로젝트 403 정합 · EC8 직접생성 전이기반 제외 · NFR4 samples O(issues) 캡없음 명문화.

## Plan

패키지 `com.bts.issue.cycletime`(CFD `com.bts.issue.cfd` 미러). **재사용**(신규 아님) — `CfdStatusHistoryRepository`·`CfdStatusChangeRow`·`CfdCategory`(같은 모듈 feature 간 재사용, CFD↔velocity 선례) + `IsolatedWorkflowStateLookup`. **신규** — `CycleTimeIssueSourceRow`(repository 패키지) + `IssueRepository.fetchActiveVisibleIssuesForCycleTime`(보안 술어 재사용). 모든 도메인 VO는 read-model(영속 아님).

### Task 1. 요약 통계 VO + nearest-rank 백분위 (순수 도메인)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/CycleTimeStats.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/cycletime/domain/CycleTimeStatsTest.kt`]
- depends-on: []

**RED**. `CycleTimeStatsTest` —
- `count==0` → `CycleTimeStats(count=0, min/max/avg/p25/p50/p75/p90 = null)`.
- n=1 `[100]` → 모든 백분위=100, avg=100, min=max=100.
- 홀수 `[10,20,30]` → nearest-rank: p50=`ceil(0.5·3)=2`→idx1→20, p25=`ceil(0.25·3)=1`→idx0→10, p75=`ceil(0.75·3)=3`→idx2→30, p90=`ceil(0.9·3)=3`→idx2→30.
- 짝수 `[10,20,30,40]` → p50=`ceil(2)=2`→idx1→20, p75=`ceil(3)=3`→idx2→30.
- avg 반올림 검증(`[10,15]`→13, `Math.round`).
- 입력이 정렬 안 됨 → 내부 정렬 후 계산(결정성).

**GREEN**. `CycleTimeStats` data class + `companion fun of(seconds: List<Long>): CycleTimeStats`. 정렬 후 `percentile(sorted, p) = sorted[clamp(ceil(p/100·n)−1, 0, n−1)]`. 빈 리스트→count 0 + null. avg=`Math.round(mean)`.

**REFACTOR**. `percentile` private helper 추출 + KDoc(nearest-rank 공식·근거 명시).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*CycleTimeStatsTest'`

### Task 2. 계산기 + 결과 VO (순수 도메인)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/IssueDurationInput.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/CycleTimeSample.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/CycleTimeMetric.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/CycleTimeResult.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/domain/CycleTimeCalculator.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/cycletime/domain/CycleTimeCalculatorTest.kt`]
- depends-on: [1]

입력. `IssueDurationInput(issueKey, createdAt: Instant, firstInProgressAt: Instant?, lastDoneAt: Instant?)` — 서비스가 상태 이력을 축약해 전달(전이 기반; 초기 event 미사용, EC8).

**RED**. `CycleTimeCalculatorTest`(`calculate(from, to, inputs): CycleTimeResult`) —
- **S1 정상**. lastDone∈창 이슈 → lead=lastDone−created, cycle=lastDone−firstInProgress. 통계 위임(Task 1).
- **S2 미경유**(firstInProgressAt=null) → lead 표본 포함, cycle 표본 제외(cycle.count < lead.count).
- **S3 재오픈**. lastDone(마지막) 기준 창 판정 + cycle=lastDone−firstInProgress.
- **S4 창 밖**. lastDone 날짜 창 밖 → lead·cycle 모두 제외.
- **EC1/EC8**. lastDoneAt=null → 모집단 제외(lead·cycle 둘 다).
- **EC3 음수**. firstInProgressAt > lastDoneAt → cycle 제외, lead 유지.
- **EC7 zero**. created==firstInProgress==lastDone → seconds=0 표본 유지.
- **빈 입력** → cycle/lead 각 count 0·samples [].
- **정렬**. samples seconds 오름차순.

**GREEN**. `CycleTimeCalculator.calculate` object — (1) 모집단=`lastDoneAt!=null && lastDoneAt.utcDate∈[from,to]`. (2) lead 표본=`Duration.between(created,lastDone).seconds` 전원. (3) cycle 표본=`firstInProgress!=null && lastDone>=firstInProgress`인 이슈만 `lastDone−firstInProgress`. (4) `CycleTimeStats.of` 위임. `CycleTimeMetric(stats, samples 정렬)`, `CycleTimeResult(projectKey?, from, to, cycleTime, leadTime)`. utcDate=`instant.atZone(ZoneOffset.UTC).toLocalDate()`.

**REFACTOR**. 모집단 필터·표본 빌드 private 분리 + KDoc(정의·엣지 근거).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*CycleTimeCalculatorTest'`

### Task 3. 가시 이슈 원천 조회 (repository, issueKey 포함)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/CycleTimeIssueSourceRow.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryCycleTimeSourceTest.kt`]
- depends-on: []

**RED**. `IssueRepositoryCycleTimeSourceTest`(Testcontainers) —
- 활성·가시 이슈만 반환 + `issueKey` 채워짐.
- soft-deleted 이슈 제외.
- 타 프로젝트 이슈 제외.
- 뷰어 접근 불가 보안 등급(기밀) 이슈 제외(비-vacuous — 기밀 이슈 시드 후 대조).

**GREEN**. `CycleTimeIssueSourceRow(issueId, issueKey, typeId, currentStateKey, createdAt)` + `IssueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, viewerUserId, access)` — `fetchActiveVisibleIssuesForCfd` 미러 + `ISSUES.KEY` select. `buildActiveSecureWhere` **재사용**(복제 금지).

**REFACTOR**. KDoc(CFD 원천 조회와의 차이=issueKey 추가·보안 술어 재사용 명시).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*IssueRepositoryCycleTimeSourceTest'`

### Task 4. 조회 유스케이스 서비스 (오케스트레이션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/application/CycleTimeService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/cycletime/application/CycleTimeServiceTest.kt`]
- depends-on: [2, 3]

**RED**. `CycleTimeServiceTest`(mockk) —
- BROWSE 미보유 → `IssueAccessDeniedException`(repo 조회 전).
- 가시 이슈 0건 → 즉시 빈 `CycleTimeResult`(status/카탈로그 조회 스킵, EC6).
- `buildDurationInput` 정확성 — 전이 이력에서 firstInProgressAt=첫 IN_PROGRESS 전이 시각, lastDoneAt=마지막 DONE 전이 시각 추출(카테고리 매핑=stateCache). IN_PROGRESS 미경유→firstInProgressAt=null.
- 삭제된 상태 키 → TODO 폴백(CfdCategory).

**GREEN**. `CycleTimeService`(`@Service`, `@Transactional(readOnly=true)`) — CFD `CfdService` 오케스트레이션 미러. (1) `checkBrowsePermission`(선검사). (2) `securityDirectory.accessibleLevels` + `issueRepository.fetchActiveVisibleIssuesForCycleTime`. (3) 빈→빈 결과. (4) `cfdStatusHistoryRepository.fetchStatusChanges`(**재사용**) groupBy issueId. (5) `loadTypeIdToKeyMap` + `buildStateCache`(N+1 차단, CFD 미러). (6) 이슈별 `buildDurationInput`→`CycleTimeCalculator.calculate`. categoryOf=`CfdCategory.fromCategoryString`(재사용).

**REFACTOR**. `buildDurationInput`·`resolveStateCategories`(WorkflowSchemeNoDefault 폴백) private 분리 + KDoc.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*CycleTimeServiceTest'`

### Task 5. REST 컨트롤러 + 응답 DTO + 예외 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/web/CycleTimeController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/web/dto/CycleTimeResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/cycletime/web/CycleTimeExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/cycletime/web/CycleTimeControllerTest.kt`]
- depends-on: [4]

**RED**. `CycleTimeControllerTest`(MockMvc 슬라이스, service mock) —
- 200 + `DataResponse<CycleTimeResponse>` 외피 + from/to echo + cycleTime/leadTime 구조(count·min·max·avg·p25·p50·p75·p90·samples).
- from/to 생략 → 기본 30일 창(Clock.fixed 주입 결정성).
- `from>to` / 파싱 실패 / 180일 초과 → 400.
- actor 미인증(CurrentActor) → 401.
- 빈 결과 → count 0·samples []·통계 null 직렬화.

**GREEN**. `CycleTimeController`(`GET /api/v1/projects/{projectKey}/cycle-time`) — `CfdController` 미러(actor 최상단·parseLocalDate·resolveWindow·validateWindow DEFAULT_WINDOW_DAYS=30·MAX_WINDOW_DAYS=180·Clock 기본 systemUTC). `CycleTimeResponse.from(result)` + `MetricResponse`/`SampleResponse`. `CycleTimeExceptionHandler`(basePackages `com.bts.issue.cycletime.web` 한정, ResponseStatusException 상태보존 + IssueAccessDeniedException 403, catch-all 없음 — CfdExceptionHandler 미러, errorCode `ISSUE_CYCLE_TIME_VALIDATION_FAILED`).

**REFACTOR**. KDoc(엔드포인트·창 해석·Clock 주입 명시). DTO `@property` KDoc.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*CycleTimeControllerTest'`

### Task 6. 통합 테스트 (Testcontainers 풀 부팅)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/cycletime/CycleTimeIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**. `CycleTimeIntegrationTest`(CFD `CfdIntegrationTest` 미러, 실 repo+시드) —
- S1 정상. 이슈 생성→IN_PROGRESS 전이→DONE 전이 시드 후 조회 → cycle/lead 표본·통계 실측.
- S2 미경유. TODO→DONE 직접 전이 이슈 → lead 표본에 포함·cycle 표본 제외(cycle.count < lead.count) **실측**.
- S4 창 밖. 창 밖 완료 이슈 제외.
- **S6 기밀 누출 차단**(비-vacuous). 뷰어 미가시 기밀 이슈 시드 후 조회 → 샘플·통계에서 제외 확인.
- S7 403(BROWSE 없음), S8 400(잘못된 창), S5 빈 결과(200).

**REFACTOR**. 시드 헬퍼(전이 이력 생성) 추출 + 시나리오 KDoc.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*CycleTimeIntegrationTest'`

### Task 7. 문서 전수 동기화 (진척 불변)

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/notification-dashboard.md`, `docs/sdd/13-board-backlog-timeline.md`, `docs/decisions/2026-07-03-fr-rp-04-cycle-lead-time.md`]
- depends-on: []

**작업**(TDD 비대상 — 문서).
- product `notification-dashboard.md §4.4` — D1~D5 `[x]` 마킹 + `구현 범위` deviation 박스 추가(Maxi 확정 3결정·CFD 인프라 재사용·프론트 D6/D7 후속). **진척 95/123 불변**(백엔드만·D6/D7 미완).
- SDD `§13.5.5` — 구현 상세(전이 기반 duration·초 단위·응답 샘플+백분위·엔드포인트·on-the-fly) + ADR 링크. CFD §13.5.4 형식 미러.
- ADR `2026-07-03-fr-rp-04-cycle-lead-time.md` — 맥락·결정(D1 Cycle 정의+엣지·D2 모집단/창·D3 응답형태·D4 모듈 issue-tracking·D5 보안·D6 CFD 재사용)·새 개념·SDD 동기화 대상. CFD ADR 형식 미러.
- fr-index/README/CLAUDE 카운트 **불변**(BC 라벨 유지) — 변경 없음 확인.

**검증**. `bash scripts/verify-master-plan.sh`(카운트 drift 0 확인).

## Plan 메타

- task 수: 7 (Task 1~6 TDD, Task 7 문서)
- 예상 wave: 3~4 (wave1 = T1·T3·T7 병렬 / wave2 = T2 / wave3 = T4 / wave4 = T5 → T6). Gradle 모듈 컴파일 직렬화 감안.
- TDD 강제: yes (T1~T6, test 커밋 선행)
- 신규 마이그레이션: 0
- 재사용: CfdStatusHistoryRepository·CfdStatusChangeRow·CfdCategory·IsolatedWorkflowStateLookup·buildActiveSecureWhere
- 추가 검증: ktlint·detekt·`verify-master-plan.sh`. E2E는 프론트 D6/D7 후속 PR.

## 리뷰 결과

### plan-eng-review (2026-07-03)

**Step 0 스코프 챌린지**. ✅ blocker 없음.
- CFD(FR-RP-03) 인프라 재사용으로 신규 표면 최소화(재구축 회피). 신규 클래스 11개는 CFD 미러(검증된 패턴)라 스코프 크립 아님.
- 백분위 손수 nearest-rank = [Layer 3], 외부 의존성 회피(DEVELOPMENT.md §외부 의존성) — 올바름.
- 완결성: 엣지 EC1~8·비-vacuous 테스트·백분위 경계 테스트 = boil the ocean 충족.

**Section 1 아키텍처**.
- ✅ 계산기(순수)↔서비스(오케스트레이션) 분리 명확. `IssueDurationInput` 축약으로 도메인 순수성 유지.
- ✅ 보안 술어 `buildActiveSecureWhere` **재사용**(복제 아님) — isomorphic-clone 회귀 방지 정합.
- ⚠️ CONCERN-1(taste, 비-blocker). cycletime이 `Cfd*` 접두 프리미티브(`CfdStatusHistoryRepository`·`CfdCategory`·`CfdStatusChangeRow`) 재사용 → 이름이 feature-coupling으로 읽힘. **권장**. 지금은 재사용-as-is(surgical, CFD↔velocity 선례). 3번째 소비자 등장 시 `com.bts.issue.statushistory` 중립 패키지로 추출(구조+행위 동시 변경 회피, Beck "make the change easy then change"). 본 PR 확장 금지. → 게이트1에서 Maxi 확인.

**Section 2 코드 품질**.
- ✅ 신규 마이그레이션 0. `@Service`+`@Transactional(readOnly)` 명시(트랜잭션 무력화 회귀 방지).
- ✅ 예외 핸들러 basePackages 한정 + catch-all 없음(ResponseStatus. 401/400 삼킴 방지, CFD 선례).
- 주의. avg 반올림 방식(`Math.round` half-up) 계약 명시 — Task 1 테스트에 고정.

**Section 3 테스트**.
- ✅ S6 기밀 누출 **비-vacuous** 강제(기밀 시드 후 제외 대조) — vacuous-rule 학습 정합.
- ✅ S2 통합 테스트가 `cycle.count < lead.count` 실측(미경유 제외를 non-vacuous 검증).
- ✅ 백분위 경계(n=1·짝/홀·count=0 null) 단위 테스트. EC3 음수/EC1·8 무-DONE 제외 계산기 테스트.

**Section 4 성능**.
- ✅ status 전이 배치 1쿼리 + 타입별 카탈로그 캐싱(N+1 차단, CFD 미러).
- ✅ samples O(issues) 무캡 = 히스토그램 정확도 우선(NFR4 명문화, 무언의 절삭 없음). 1K·180일 상한서 유계.

**BLOCKER: 없음.** CONCERN-1(Cfd* 재사용 네이밍)만 게이트1 taste 확인.

**VERDICT**: 진행 승인. 검증된 CFD 패턴 미러 + 보안/엣지/테스트 견고. 유일 열린 결정 = CONCERN-1(재사용-as-is 권장).
