# FR-IM-01 PR3 — 댓글/Worklog Import

> slug: fr-im-01-pr3-comments-worklog
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 에픽 3단계 (PR3). PR1(코어 이슈 생성)·PR2(컴포넌트/버전 자동생성 + 소스 상태 전환) 완료 이후 후속.

Jira 소스 이슈의 **댓글(comments)** 과 **Worklog(작업 로그)** 항목을 CSV/JSON에서 파싱해, 생성된 이슈에 함께 가져온다.

PR2와 동형 패턴 예상.
- 파서(`ImportRowParser`)·`IssueImportCommand`(shared-kernel) 확장
- `IssueImportAdapter`가 댓글/Worklog 생성 유스케이스를 actor=requester로 위임 (권한 게이트 재사용)
- best-effort 경고 (권한 없음/검증 실패 → 경고, 이슈는 생성)
- dry-run 미러 (실경로 유효성-예측 경고 미리보기)
- G1 경고 노출 (결과 로그 CSV) 계승

에픽이므로 D박스 마킹은 FR-IM-01 전체 완료 시. FR 카운트(123) 불변, 신규 FR 0.

## 도메인 정리

- **BC**: issue-tracking(댓글 도메인 신설 + worklog + import 어댑터) + search-export-import(파서/커맨드).
- **★중대 발견(Explore)**: **댓글(Comment) 기능이 BTS에 미구현**. CommentService·comments 테이블·COMMENT 권한·도메인 전부 없음(코드에 "향후 댓글 지원 시" 예약 주석만). FR 인덱스에도 댓글 독립 FR 없음(FR-MN-01 멘션=본문만, FR-HS/NT/AT에 부수 언급). Worklog는 FR-TT-01로 완비.
- **Maxi 결정(2026-07-03)**: (1) PR3에서 **댓글 기능 신설 + 댓글/Worklog 둘 다 Import**. (2) 댓글 기능 범위 = **도메인 + 조회 API**(comments 테이블·도메인·repo·service create+list·권한·GET 조회 REST). 작성 REST·UI·@멘션 알림 통합은 **별도 FR**(본 PR 범위 밖).

### 신규 — 댓글 기능(도메인+조회)
- 엔티티. `Comment`(id, issueId, authorId, body[raw markdown], createdAt, updatedAt, deletedAt[소프트삭제]). **신규 마이그레이션 `comments` 테이블**(에픽 최초 마이그레이션 — PR1/PR2는 0이었음). issue_id FK ON DELETE CASCADE(worklog V027 선례), author_id는 identity FK 미적용(BC 격리, worklog 선례).
- 서비스. `CommentApplicationService.create(actor, issueKey, body, authorId)` + `list(issueKey)`. 본문은 **raw markdown 저장 + 읽기 시 `MarkdownRenderer.renderSafe`로 HTML 렌더**(이슈 description 선례, OWASP allowlist+멘션 span). 
- 권한(스펙서 확정). create=이슈 편집과 동일 `IssuePermission.UPDATE`(worklog 선례) 재사용 예상(권한 enum 추가 회피=cross-module 카운트가드 무영향, memory enum-add-breaks-crossmodule-count-guard) / list=`VIEW`. 
- 조회 API. `GET /api/v1/issues/{key}/comments`(VIEW 게이트, 렌더 HTML 포함).
- **@멘션 이벤트는 import 경로에서 억제**(대량 import 알림 폭발 방지 — 스펙 확정). 사용자 작성 경로가 없으므로 PR3에선 멘션 발행 미도입.

### 기존 재사용 — Worklog(FR-TT-01)
- `WorklogService.create(actor, issueKey, timeSpentSeconds:Int, startedAt:Instant, comment:String?, newRemainingEstimateSeconds:Int?)`. **author=actor 고정** → 원 작성자 보존 위해 **import 전용 create(authorId 주입) 경로 필요**(authorId FK 없어 주입 가능). 권한=`UPDATE`(IssueScope.Issue). **no-bump**(issues.version 안 올림)+원자 SQL remaining 차감 → **import OCC currentVersion 스레딩에 무영향**(체인 어디에나 삽입 가능). **pgmq 이벤트 미발행**(알림 폭발 없음). `time_spent_seconds > 0` DB CHECK — 0/음수는 사전 검증으로 경고 처리(23514 tx 오염 회피).

### Import 확장(PR2 패턴 계승)
- `IssueImportCommand`(shared-kernel)에 댓글/worklog **중첩 VO 리스트** 추가(각 댓글=body/authorEmail/createdAt, 각 worklog=timeSpentSeconds/startedAt/authorEmail/comment). 기존 필드 불변.
- `ParsedImportRow`·`ImportRowParser`: CSV(comment/worklog 다중값·3파트 규약 스펙 확정) + JSON(`fields.comment.comments[]`{author.emailAddress,body,created}, `fields.worklog.worklogs[]`{author,timeSpentSeconds,started,comment}) 파싱 신설. `ImportJobProcessor.toCommand` 매핑 추가.
- `IssueImportAdapter.executeImport`: createIssue 후 댓글/worklog 생성(best-effort — **사전 권한 체크 후 호출**, tx 오염 회피 PR2 원칙). 이메일→author 매핑(`resolveByEmails` 재사용, 미매칭 시 requester 폴백).
- dry-run 미러(권한 예측 경고) + G1 경고 로그 노출 계승.

### tx/BC 함정
- 댓글/worklog 서비스 모두 issue-tracking BC 내부 → 어댑터 직접 호출(cross-BC 포트 불필요). 단 어댑터의 REQUIRED tx에 참여하므로 예외 throw 시 rollback-only 오염 → **사전 권한/유효성 체크로 강등**(컴포넌트/버전/상태 동형, memory transaction-self-invocation-requires-new).

- **기존 결정 충돌**: 없음. 댓글 도메인은 신규(첫 도입).
- **관련 ADR**: 신규 ADR 후보(댓글 도메인 도입 — 범위 한정 근거·권한 재사용·멘션 억제). PR1 Import ADR(`2026-07-02-fr-im-01-csv-json-import.md`) 참조.
- **★FR 동기화 쟁점(게이트1 확정 필요)**: 댓글 도메인을 (a) FR-IM-01 PR3의 prerequisite로 흡수(FR 카운트 123 불변, SDD 10.6.3 "댓글 보존" 이미 명시) vs (b) 신규 FR 부여(124, 8종 전수 동기화). SDD 10.6.3이 이미 import 범위에 댓글 보존을 적어둔 점 + 범위가 도메인+조회로 한정된 점에서 **(a) 흡수** 잠정 제안 — 게이트1에서 Maxi 확정.

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-im-01-pr3-comments-worklog.md](../specs/2026-07-03-fr-im-01-pr3-comments-worklog.md)

핵심 3줄 요약.
- 댓글 기능 신설(도메인+조회) — comments 테이블(신규 마이그레이션)·Comment 도메인·repo·`CommentApplicationService`(create+list)·`GET /issues/{key}/comments`(VIEW+IssueScope.Issue)·전용 `CommentExceptionHandler`(500 변질 차단). 권한 UPDATE 재사용(enum 추가 0).
- 댓글/Worklog import — 파서(JSON `comment.comments[]`/`worklog.worklogs[]` + CSV 댓글 3파트, worklog는 JSON 전용) + `IssueImportCommand` 중첩 VO + 어댑터 위임. worklog는 `createImported`(author 보존·이력 생략). best-effort 사전 체크(tx 오염 회피 PR2).
- dry-run은 유효성-예측 경고를 **별도 경로**로 미러(FORBIDDEN 미엮음, PR2 CONCERN-A 방지) + G1 경고 행당 유형별 집약.

Maxi 확정. worklog 이력 생략 · 경고 행당 집약 · worklog JSON 전용 · 댓글 도메인 FR-IM-01 흡수(SDD/fr-index 명시, 카운트 123 불변).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 10축 분석 → (A) 스펙 보강 3건(예외핸들러·dry-run 경고분리·split limit)+경미 4건 반영, (B) Maxi 결정 4건 확정.

## Plan

> 모듈 배치. shared-kernel(T1) → search 파서(T6) · issue-tracking 댓글도메인(T2→T3→T4)·worklog(T5)·어댑터(T7). 같은 모듈 task는 Gradle 컴파일이 직렬화(memory `bts-plan-wave-gradle-module-compile`) — depends-on은 코드 의존만 선언. 마이그레이션 V035 잠정(머지 직전 재확인 — 동시 세션, memory `migration-vnumber-concurrent-branch-collision`).

### Task 1. shared-kernel — IssueImportCommand + ImportComment/ImportWorklog VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueImportCommand.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueImportPortTest.kt`]
- depends-on: []

**RED**: `IssueImportPortTest`에 커맨드가 `comments: List<ImportComment>`·`worklogs: List<ImportWorklog>`를 담고 기본값 emptyList임을 검증하는 테스트 추가 → 컴파일 실패(VO 없음).
**GREEN**: `IssueImportCommand.kt`에 `ImportComment(body: String, authorEmail: String? = null, createdAt: Instant? = null)`, `ImportWorklog(timeSpentSeconds: Int, startedAt: Instant? = null, authorEmail: String? = null, comment: String? = null)` data class 추가 + 커맨드에 `comments`/`worklogs` 필드(끝에 추가, 기존 필드 불변).
**REFACTOR**: KDoc(각 VO 역할·nullable 의미) + 파일 L1 주석 유지.
**검증**: `./gradlew :modules:shared-kernel:test --tests '*IssueImportPortTest'`

### Task 2. issue-tracking — comments 마이그레이션 + Comment 도메인 + CommentRepository

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V035__comments.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/domain/Comment.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/repository/CommentRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/repository/CommentRepositoryTest.kt`]
- depends-on: []

**RED**: `CommentRepositoryTest`(Testcontainers, `IssueTestcontainersBase` 상속) — insert 후 `listByIssue(issueId)`가 created_at ASC로 반환·소프트삭제 제외를 검증 → 컴파일/실행 실패.
**GREEN**: `V035__comments.sql`(id UUID PK, issue_id UUID NOT NULL FK→issues(id) ON DELETE CASCADE, author_id UUID NOT NULL, body TEXT NOT NULL, created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ NULL, 인덱스 `(issue_id, created_at) WHERE deleted_at IS NULL`) + init_codegen.sql에 **동일 DDL 미러**(memory `jooq-init-codegen-mirror`) + `Comment` 도메인 + `CommentRepository`(jOOQ `insert`, `listByIssue`).
**REFACTOR**: 파일 L1 한국어 주석 + KDoc. jOOQ는 `.repository` 패키지 유지(ArchUnit).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*CommentRepositoryTest'` (jOOQ codegen 재생성 포함)

### Task 3. issue-tracking — CommentApplicationService (create + list)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/application/CommentApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/application/CommentView.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/application/CommentApplicationServiceTest.kt`]
- depends-on: [2]

**RED**: `CommentApplicationServiceTest`(Testcontainers 권장 — tx/권한 실검증, mockk 가짜그린 회피 memory `tx-aware-dslcontext-rollback-test-gap`) — (i) create가 UPDATE 권한 게이트(없으면 `IssueAccessDeniedException`)·authorId 보존, (ii) list가 VIEW+`IssueScope.Issue` 게이트·bodyHtml 렌더(`MarkdownRenderer.renderSafe`)·created_at ASC 검증 → 실패.
**GREEN**: `CommentApplicationService.create(actor, issueKey, body, authorId)`(UPDATE 게이트, 이슈 존재 findByKey, CommentRepository.insert) + `list(actor, issueKey)`(VIEW+IssueScope.Issue, listByIssue→renderSafe로 `CommentView`(id/authorId/body/bodyHtml/createdAt/updatedAt)). worklog `checkPermission`/`listForIssue` 선례 참조.
**REFACTOR**: 권한 헬퍼 추출 + KDoc(권한 스코프 근거).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*CommentApplicationServiceTest'`

### Task 4. issue-tracking — CommentController + CommentExceptionHandler (GET API)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/web/CommentController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/web/CommentExceptionHandler.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/web/CommentResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/web/CommentControllerIntegrationTest.kt`]
- depends-on: [3]

**RED**: `CommentControllerIntegrationTest`(@WebMvcTest 또는 full-boot) — `GET /api/v1/issues/{key}/comments` 200(목록)·권한없음 **403**·이슈없음 **404**·**미인증 401**(모두 500 미변질) 검증 → 실패.
**GREEN**: `CommentController`(GET, `CurrentActor.current()` **먼저** 추출 후 `CommentApplicationService.list` 위임, `CommentResponse[]`) + `CommentExceptionHandler`(`@RestControllerAdvice(assignableTypes=[CommentController::class])`) — **worklog `WorklogExceptionHandler` 전체 미러**: `IssueAccessDeniedException`→403·`IssueNotFoundException`→404·**`ResponseStatusException` 전파(미인증 401을 catch-all이 500으로 삼키는 것 차단 — C1, memory `catch-all-exceptionhandler-swallows-responsestatusexception`)**·catch-all→500. GET {key}는 문자열 path라 MethodArgumentTypeMismatch(400) 불필요. ProblemDetail 형식 worklog 선례.
**REFACTOR**: DTO 매핑 함수 추출 + KDoc.
**검증**: `./gradlew :modules:issue-tracking:test --tests '*CommentControllerIntegrationTest'`

### Task 5. issue-tracking — WorklogService.createImported (author 주입·이력 생략)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/application/WorklogService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/application/WorklogServiceImportTest.kt`]
- depends-on: []

**RED**: `WorklogServiceImportTest`(Testcontainers) — `createImported(actor, issueKey, authorId, timeSpentSeconds, startedAt, comment)`가 (i) 저장된 worklog.authorId==주입 authorId(≠actor), (ii) UPDATE 권한 게이트, (iii) issues.version **불변**(no-bump), (iv) **history 미기록**(historyRecorder 호출 0) 검증 → 컴파일 실패(메서드 없음).
**GREEN**: `createImported` 추가 — 기존 `create` 로직 재사용하되 authorId 주입 + `historyRecorder.record` **미호출** + remaining=auto-decrement(null). 기존 `create` 시그니처 불변.
**REFACTOR**: create/createImported 공통 **삽입+recompute 롤업**만 private 추출(중복 제거), before/after **스냅샷+historyRecorder는 create 전용 분기**(스냅샷은 이력용이라 createImported는 미생성) + KDoc(이력 생략 근거).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*WorklogServiceImportTest'`

### Task 6. search — 파서 확장 (댓글/worklog JSON·CSV + toCommand)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ParsedImportRow.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/parse/ImportRowParserTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`]
- depends-on: [1]

**RED**: `ImportRowParserTest` — (i) JSON `fields.comment.comments[]`{author.emailAddress,body,created}·`fields.worklog.worklogs[]`{author.emailAddress,timeSpentSeconds,started,comment} 추출(복합 원소 — `textArrayOf` 평면배열 헬퍼 재사용 불가, 신규 추출), (ii) CSV **동명 `comment` 컬럼 N개 전부 수집**(C2 — 2건 컬럼→댓글 2건, `putIfAbsent` 유실 회귀 테스트) 각 셀 `date;author;body` `split(limit=3)`(본문 세미콜론 보존)·3파트 미만 폴백, (iii) worklog CSV 미지원. `ImportJobProcessorTest`에서 toCommand가 ImportComment/ImportWorklog로 매핑(ISO→Instant, 이메일 소문자) 검증 → 실패.
**GREEN**: `ParsedImportRow`에 comments/worklogs 필드(shared `ImportComment`/`ImportWorklog` 재사용 또는 파서-로컬 VO — 선택 명시) + `buildColumnIndex`에 **comment 다중 인덱스 수집**(동명 헤더 전 위치, 나머지 필드는 단일 유지) + JSON 중첩 배열 복합원소 파싱(스트리밍 유지)·CSV 댓글 3파트(limit=3) + `ImportJobProcessor.toCommand`에 매핑 라인.
**REFACTOR**: 파싱 헬퍼(3파트 분해, 중첩 배열→VO) 추출 + 상수(헤더/JSON 경로) + KDoc.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportRowParserTest' --tests '*ImportJobProcessorTest'`

### Task 7. issue-tracking — IssueImportAdapter 위임 (댓글/worklog 생성·best-effort·dry-run·집약) + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [1, 3, 5]

**RED**: `IssueImportAdapterTest`(Testcontainers 실 tx) 시나리오 추가 — (S1)댓글 생성+author 보존+조회, (S2)worklog 생성+author 보존+version 불변, (S3)UPDATE 권한 없음→댓글/worklog 스킵+집약 경고·이슈 생성, (S4)timeSpent≤0→worklog 스킵+경고(23514 미발생), (S5)author 미매칭→requester 폴백+경고, (S6)dry-run→UPDATE 권한 없으면 경고이되 **FORBIDDEN 아님**(성공)·insert 0, (S7)예상외 throw 행→이슈까지 롤백 → 실패.
**GREEN**: `executeImport`에 createIssue 후 댓글→worklog 생성 단계 추가.
- 이메일→author. `FieldResolution`을 **댓글/worklog author 해석 결과까지 담도록 확장**(C3 — 현재 reporter/assignee만, `resolveByEmails` 단일 배치에 댓글/worklog author 이메일도 합류), 미매칭 requester 폴백.
- **사전 UPDATE 권한 체크** 후 `commentApplicationService.create`/`worklogService.createImported` 호출(권한 없으면 미호출+집약 경고, tx 오염 0). **잔여 throw 집합 = {UPDATE 권한, worklog timeSpent≤0(CHECK 23514), worklog started-null(NOT NULL)} 3개만 사전체크하면 완결**(body ''는 NOT NULL 만족→throw 안 함, 이슈는 직전 createIssue로 존재) — 이 셋을 하나라도 빠뜨리면 tx 오염(eng-review ★2).
- dry-run. `warnCommentsWorklogsIfNeeded`(별도 경고 경로) — **FORBIDDEN early-return(현 `validateDryRun` L189~194) 이후에 배치**(C3, FORBIDDEN 행이 댓글 경고 중복 방출 방지), `rowTriggersUpdate`/FORBIDDEN에 **절대 미엮음**(PR2 CONCERN-A). 행당 유형별 집약.
**REFACTOR**: 댓글/worklog 처리·집약 경고 헬퍼 추출 + KDoc(사전체크 근거).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueImportAdapterTest'`

## Plan 메타

- task 수: 7
- 예상 wave: shared/도메인/worklog(1) → 파서·서비스(2) → 컨트롤러·어댑터(3). 같은 모듈(issue-tracking) task는 Gradle 컴파일 직렬화.
- TDD 강제: yes (test 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on+files로 wave 계산
- 추가 검증: ktlint, detekt, ArchUnit(BC 격리·jOOQ repository), verify-master-plan, 통합테스트(Testcontainers 실 tx)
- FR 동기화: 댓글 도메인 FR-IM-01 흡수(카운트 123 불변, 신규 FR 0). product/search-export-import.md §4.1 PR3 진행노트 추가. DATA.md §9가 이미 PR3=댓글/Worklog를 에픽 정본으로 기재.
- E2E: **생략**. 이 PR은 UI 없는 백엔드 도메인+import — Playwright 대상 표면 없음. Testcontainers 실 tx 통합테스트(S1~S31)가 백엔드 전 경로 커버. D6/D7 프론트 후속 PR에서 E2E.
- 구현 결과: 8 task(T8=eng-review 후 발견한 댓글 createdAt 보존 갭 수정). 모듈 전체 2746 tests 0 failures, 3모듈 ktlint/detekt clean.

## 리뷰 결과

### plan-eng-review (2026-07-03, 적대적 엔지니어링 리뷰)
- ✅ **하드 BLOCKER 없음**. ★1 마이그레이션(V035 실제 다음 번호 확인·init_codegen 미러 포함·FK CASCADE 정합)·★2 tx 오염 사전체크(잔여 throw 집합 {권한,timeSpent≤0,started-null} 전수 강등)·★4 dry-run 별도 경고 경로 — 3 핵심축 실코드 근거로 건전 확정. 댓글 권한 VIEW+IssueScope.Issue 보안게이트 자동적용·BC 격리(신규 @MockBean 불필요)·테스트 tx-aware·wave 의존 정합 PASS.
- ⚠️ **CONCERN 3건 → plan 반영 완료**.
  - **C1(필수·회귀)**. CommentExceptionHandler를 worklog 핸들러 전체 미러(`ResponseStatusException` 401 전파 + catch-all), 403/404만이면 미인증 401→500 회귀 → Task 4 GREEN/RED에 401 명시 반영.
  - **C2(필수·스펙위반)**. CSV `buildColumnIndex` `putIfAbsent`가 동명 Comment 컬럼 첫개만 보존→2..N 유실 → 다중 인덱스 수집으로 spec R8 + Task 6 반영.
  - **C3(권장)**. dry-run `warnCommentsWorklogsIfNeeded`를 FORBIDDEN early-return 이후 배치 + `FieldResolution`에 댓글/worklog author 해석 확장 → Task 7 반영.
- 경미(impl 중 흡수). worklog 스냅샷 create 전용 분기(T5 반영)·파서 복합원소 VO 타입 명시(T6)·tx 사전체크 전수성 명문화(T7).
- BLOCKER: 없음.
