# FR-IM-01 PR3 — 댓글 기능 신설(도메인+조회) + 댓글/Worklog Import — 스펙

> FR: FR-IM-01 (에픽 PR3 / 4). **댓글 도메인은 FR-IM-01에 흡수**(Maxi 확정) — 신규 FR ID 없음, **카운트 123 불변**. 단 SDD 10.6.3 + fr-index에 "PR3이 댓글 도메인(도메인+조회)을 prerequisite로 도입" 명시해 문서 drift 방지. 풀 댓글 기능(작성/UI/멘션 알림)은 미래 별도 FR.
> BC: issue-tracking(댓글 도메인 신설 + worklog + import 어댑터) + search-export-import(파서/커맨드).
> 선행: PR1(#218 코어)·PR2(#221 자동생성+상태전이) 완료. 도메인 정리: `docs/plans/2026-07-03-fr-im-01-pr3-comments-worklog.md §도메인 정리`.
> 결정 확정(Maxi): 댓글 기능=**도메인+조회 API**(작성 REST·UI·멘션 알림 별도 FR), 댓글/worklog 둘 다 import.

## 배경

PR1/PR2는 이슈 코어 필드·컴포넌트/버전·상태를 import했다. Jira 소스의 **댓글**과 **Worklog(작업 로그)**는 미처리였다.
- **Worklog**는 FR-TT-01로 완비(`WorklogService`) — import만 붙이면 된다. 단 `create`가 author=actor로 고정이라 원 작성자 보존 경로가 필요.
- **댓글**은 BTS에 **기능 자체가 미구현**(테이블·도메인·권한·서비스 전무). 따라서 PR3은 (a) 댓글 도메인을 도메인+조회 범위로 신설하고, (b) 댓글/worklog를 import한다.

**신규 마이그레이션 있음**(comments 테이블) — PR1/PR2는 0이었으나 PR3은 에픽 최초 마이그레이션.

## 사용자 시나리오 (Given-When-Then)

- **S1 (댓글 import)**. Given JSON `fields.comment.comments[]`에 댓글 2건(author.emailAddress 매칭), When import, Then 이슈에 댓글 2건 생성(원 작성자 보존, created 시각 보존, 본문 raw markdown 저장).
- **S2 (댓글 조회)**. Given 댓글 2건 생성된 이슈, When `GET /api/v1/issues/{key}/comments`(VIEW 권한), Then created_at 오름차순 목록(각 항목에 렌더된 bodyHtml 포함).
- **S3 (Worklog import + author 보존)**. Given JSON `fields.worklog.worklogs[]`에 worklog 1건(timeSpentSeconds=3600, started, author 매칭), When import, Then 이슈에 worklog 1건 생성(원 작성자·시각 보존), issues.time_spent_seconds 집계 반영, version **불변**(no-bump).
- **S4 (author 이메일 미매칭 → requester 폴백)**. Given 댓글/worklog author 이메일이 BTS에 없음, When import, Then requesterUserId를 author로 폴백 + 경고, 항목은 생성.
- **S5 (권한 없음 → best-effort)**. Given 실행자가 이슈 UPDATE 권한 없음, When 댓글/worklog 포함 행 import, Then 댓글/worklog 스킵 + 경고, **이슈는 생성**(사전 권한 체크로 create 미호출 — tx 오염 0).
- **S6 (timeSpent ≤ 0 → best-effort)**. Given worklog timeSpentSeconds=0(또는 음수/파싱실패), When import, Then 해당 worklog 스킵 + 경고(DB CHECK 23514 회피, 사전 검증), 다른 항목·이슈는 생성.
- **S7 (dry-run 미러)**. When dryRun=true + 댓글/worklog 포함 행, Then 실제 생성 없이 UPDATE 권한 미리 확인, 없으면 경고. 실제 생성/insert 0.
- **S8 (CSV 댓글)**. Given CSV `comment` 다중 컬럼/다중값, 각 셀 `날짜;작성자;본문`(세미콜론 3파트), When import, Then 파트 분해해 댓글 생성(3파트 아니면 전체를 본문·author=requester·created=now 폴백 + 경고).

## 요구사항 (R번호)

### 댓글 기능 신설 (도메인 + 조회)
- **R1 comments 테이블**. 신규 Flyway 마이그레이션 `V0NN__comments.sql`(V번호 머지 직전 재확인 — memory `migration-vnumber-concurrent-branch-collision`). 컬럼. `id UUID PK`, `issue_id UUID NOT NULL` (FK→`issues(id)` **ON DELETE CASCADE**, worklog V027 선례), `author_id UUID NOT NULL`(identity FK 미적용 — BC 격리, worklog 선례), `body TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `deleted_at TIMESTAMPTZ NULL`. 인덱스 `(issue_id, created_at) WHERE deleted_at IS NULL`(조회 정렬). init_codegen.sql 미러(memory `jooq-init-codegen-mirror`).
- **R2 Comment 도메인 + repo**. `Comment`(id/issueId/authorId/body/createdAt/updatedAt/deletedAt). `CommentRepository`(jOOQ) — `insert`, `listByIssue(issueId)`(미삭제, created_at ASC).
- **R3 CommentApplicationService**. `create(actor, issueKey, body, authorId): Comment` — 권한 `IssuePermission.UPDATE`(IssueScope.Issue) 게이트(worklog 선례, enum 추가 없음), 이슈 존재 확인(VIEW→findByKey). `authorId`는 보존할 원 작성자(actor는 권한 주체). `list(actor, issueKey): List<CommentView>` — VIEW 게이트, 본문을 `MarkdownRenderer.renderSafe`로 HTML 렌더해 반환.
- **R4 조회 API**. `GET /api/v1/issues/{key}/comments` — `IssuePermission.VIEW`+**`IssueScope.Issue`** 게이트(prod resolver가 이슈별 보안등급 `passesSecurityGate`를 자동 적용 → 기밀 이슈 댓글 누출 차단, worklog `listForIssue` 선례. Project/BROWSE 스코프 금지). `CommentView[]`(id, authorId, body[raw], bodyHtml[렌더], createdAt, updatedAt) created_at ASC. **페이지네이션 없음(MVP)** — 이슈당 댓글 수 제한적, 후속 cursor 여지(FR-API-01 선례). 작성/수정/삭제 REST는 **없음**(별도 FR).
- **R4b 전용 예외핸들러(필수)**. 신규 댓글 컨트롤러(`com.bts.issue.comment.web`)는 정본 `IssueExceptionHandler`의 `basePackages`(`com.bts.issue.adapter.inbound.rest`) 밖이라, 그대로 두면 `IssueAccessDeniedException`(403)·`IssueNotFoundException`(404)이 **500으로 변질**된다(memory `domain-exception-http-handler-basepackage-scope`). worklog/attachment/watcher 선례처럼 `@RestControllerAdvice(assignableTypes=[CommentController::class])` **`CommentExceptionHandler` 신설**(403/404 매핑).
- **R5 @멘션 억제**. 댓글 본문에 `@멘션`이 있어도 PR3은 **`IssueMentioned` 이벤트를 발행하지 않는다**(멘션 알림 통합=별도 FR). 렌더 시 `span.mention` 시각 강조만(MarkdownRenderer 기본). import 대량 처리 알림 폭발도 원천 차단.

### Worklog author 보존
- **R6 import 전용 worklog 경로**. `WorklogService`에 `createImported(actor, issueKey, authorId, timeSpentSeconds, startedAt, comment): Worklog` 추가(기존 `create` 시그니처 불변 — 기존 호출자/테스트 무영향, memory `plan-files-constructor-injection-existing-tests`). 권한=actor의 `UPDATE` 게이트, `authorId`=보존 원작성자, remaining estimate는 표준 auto-decrement(newRemainingEstimateSeconds=null — 정상 worklog 동형, remaining NULL이면 decrement 미적용). no-bump·pgmq 무발행 유지. **이력 기록 생략**(Maxi 확정) — `historyRecorder.record` 미호출. 대량 import 시 이력 테이블이 importer 귀속 엔트리로 폭주하는 것을 방지(worklog 행 자체가 authorId로 원작성자를 보존하므로 감사 추적은 유지).

### Import 확장
- **R7 커맨드 확장**. `IssueImportCommand`(shared-kernel)에 `comments: List<ImportComment>`, `worklogs: List<ImportWorklog>` 추가(기존 필드 불변). `ImportComment`(body, authorEmail:String?, createdAt:Instant?), `ImportWorklog`(timeSpentSeconds:Int, startedAt:Instant?, authorEmail:String?, comment:String?) — shared-kernel VO.
- **R8 파서 확장**. `ParsedImportRow`·`ImportRowParser`.
  - **JSON**(정본 경로). `fields.comment.comments[]`{`author.emailAddress`, `body`, `created`} → ImportComment. `fields.worklog.worklogs[]`{`author.emailAddress`, `timeSpentSeconds`, `started`, `comment`} → ImportWorklog. 중첩 배열 스트리밍 파싱(기존 스트리밍 유지).
  - **CSV**. Jira CSV는 댓글 N건을 **동명 `Comment` 컬럼 N개**로 export한다. 기존 `buildColumnIndex`는 `putIfAbsent`라 **첫 컬럼만 보존→2..N 조용히 유실**(eng-review C2) → **동명 `comment` 헤더의 전 위치를 수집**하도록 파서 확장(comment는 다중 인덱스 리스트, 나머지 필드는 기존 단일 인덱스 유지). 각 comment 셀 `date;author;body`(세미콜론 `split(";", limit=3)` — 본문 세미콜론 보존, 3파트 미만이면 전체=body·author=null·created=null 폴백). **worklog는 CSV 미지원**(Maxi 확정 — Jira CSV worklog 표준 export 없음, 자체 규약 실용성↓) → worklog는 JSON 전용. 파싱 실패 필드는 best-effort 폴백.
  - `ImportJobProcessor.toCommand` 매핑 추가(ISO 문자열→Instant, 이메일 소문자).
- **R9 어댑터 위임**. `IssueImportAdapter.executeImport`가 createIssue 후 댓글→worklog 순서로 생성. 이메일→author는 `userLookupPort.resolveByEmails`(reporter/assignee와 **동일 배치**에 합류) 매핑, 미매칭 시 requester 폴백. 각 항목은 **사전 권한 체크(UPDATE) 후 생성**(tx 오염 회피 PR2 원칙). 댓글/worklog는 issues.version 무변경 → OCC currentVersion 스레딩 무영향(체인 말미에 추가).
- **R10 best-effort 경고 3축 + 행당 집약**. (a) UPDATE 권한 없음 → 댓글·worklog 전부 스킵+경고(사전 체크, 이슈 생성 지속) / (b) worklog timeSpentSeconds ≤ 0 또는 파싱 실패 → 그 worklog 스킵+경고(DB CHECK 회피) / (c) author 이메일 미매칭 → requester 폴백+경고. **경고는 행당 유형별로 집약**(Maxi 확정) — "worklog 3건이 timeSpent≤0로 건너뜀", "댓글 2건 author 미매칭→작성자 폴백"처럼 행·유형당 1줄로 합쳐 항목당 개별 경고 폭증(만 행 × N건)을 방지. 집약 경고는 `Success.warnings` 누적 → G1 로그(PR2 계승).
- **R11 dry-run 미러 (★경고 경로 분리 — PR2 CONCERN-A 재발 방지)**. 댓글/worklog의 UPDATE 권한 없음은 실행 시 **경고(스킵, 이슈 생성 지속)**이지 행 실패가 아니다. 따라서 기존 `rowTriggersUpdate`(→`!hasUpdate`면 **FORBIDDEN 하드실패** 반환)에 **절대 엮지 말 것** — 엮으면 실제 성공(경고)할 행을 dry-run이 FORBIDDEN으로 오보한다. 상태의 `warnStatusIfNeeded`처럼 **별도 경고 경로**로 미러한다. dry-run이 미리 산출할 **유효성-예측 경고**(파싱 커맨드에서 결정론적): (i) UPDATE 권한 없음(댓글/worklog 스킵), (ii) worklog timeSpent≤0/파싱실패, (iii) worklog started 부재/파싱실패, (iv) 빈 body, (v) author 이메일 미매칭→requester 폴백. informational "생성했습니다"만 **실행 전용**(PR2 CONCERN-B). 실제 insert/create 0.

## 비기능 (NFR)
- **BC 격리(ArchUnit)**. 댓글/worklog 서비스는 issue-tracking 내부 → 어댑터 직접 호출(cross-BC 포트 불필요). search는 `IssueImportCommand` VO 확장만(issue-tracking 직접 의존 0). jOOQ는 `.repository`만(memory `archunit-shared-class-move-repository-package`).
- **스트리밍 유지**. 댓글/worklog 중첩 배열 파싱이 10만 행 스트리밍(부분 트리)을 깨지 않음.
- **tx 오염 회피**. 댓글/worklog 생성이 어댑터 REQUIRED tx 참여 → 실패 가능 지점(권한·CHECK)은 사전 체크로 강등(예외 throw 0). 행 원자성 유지(전-성공/전-실패).
- **권한 위임 불변**. 댓글/worklog 생성 모두 actor=requester로 기존 게이트(UPDATE) 재사용. search BC는 권한 모름.

## API 인터페이스
- **신규**. `GET /api/v1/issues/{key}/comments` (VIEW, `CommentView[]`).
- **변경 없음**. `POST/GET /api/v1/imports`·`/errors` PR1 그대로. 댓글/worklog는 파일 내용으로만 전달(하위호환).

## 데이터 모델 변경
- **신규**. `comments` 테이블(R1) + `V0NN__comments.sql` + init_codegen 미러. **첫 에픽 마이그레이션**.
- **불변**. worklogs(V027)·issues 집계 컬럼 기존 재사용.

## 엣지 케이스
- **E1**. timeSpentSeconds ≤ 0 / 비수치 → worklog 스킵+경고(DB CHECK 23514 회피, 사전 검증).
- **E2**. created/started 타임존 — ISO Instant 파싱(Jira는 offset 포함 ISO). 파싱 실패 시 created=now·경고, worklog started 파싱 실패는 worklog 스킵+경고(started NOT NULL).
- **E3**. author 이메일 미매칭 → requester 폴백+경고(reporter 폴백 선례).
- **E4**. 빈 댓글 본문(body 공백) → 스킵+경고(body NOT NULL). 빈 worklog 셀 → 무시(경고 없음, PR1 빈 셀 동형).
- **E5**. 대량 댓글 순서 — created_at ASC 정렬. 동일 created_at은 입력(삽입) 순서 보존.
- **E6**. CSV 셀 파싱은 **split limit 지정**(댓글 `split(";", limit=3)`, worklog `limit=4`) — 본문에 세미콜론이 있어도 앞의 date/author를 유실하지 않고 초과분을 body에 남긴다. 구분자 개수가 부족(3/4파트 미만)해 규약 미준수면 best-effort 폴백(전체=본문·author=null·시각=null)+경고.
- **E6b**. worklog `started`가 부재(파싱실패 아닌 소스 누락)여도 스킵+경고(started NOT NULL, E2와 동일 취급).
- **E6c (best-effort 경계 명문화)**. best-effort 대상(권한·timeSpent≤0·body빈값·started부재·author미매칭)은 전부 **사전 체크로 강등**한다. 사전 체크가 못 잡는 진짜 예상외 예외(repo insert의 예기치 못한 제약 위반 등)는 **경고로 삼키지 말고 행 실패로 전파**(catch→rollback-only, 행 원자성) — 좀비 이슈 방지.
- **E7**. 본문 formula injection/제어문자 → 파서 `sanitizeControlChars`(기존) + 조회 렌더 `renderSafe`(OWASP). 에러로그 경고는 `ExportCellSanitizer`(PR1).
- **E8**. 행 실패(다른 필드 예외) 시 댓글/worklog도 함께 롤백(행 원자성, ON DELETE CASCADE 무관하게 tx 롤백).
- **E9**. import 댓글의 @멘션 → 이벤트 미발행(R5). 렌더 span만.

## 제약 조건
- 신규 마이그레이션 V번호 머지 직전 재확인(동시 브랜치 충돌, memory `migration-vnumber-concurrent-branch-collision` — 동시 세션 FR-RP-02 등 진행 중).
- 댓글 권한은 UPDATE 재사용(enum 추가 0 — cross-module 카운트 가드 무영향, memory `enum-add-breaks-crossmodule-count-guard`).
- 작성/수정/삭제 REST·UI·멘션 알림·이력 기록은 PR3 범위 밖(별도 FR).

## 측정 가능한 완료 기준
- comments 테이블 + Comment 도메인/repo/service(create+list) + `GET .../comments` 통합테스트(Testcontainers) green.
- `WorklogService.createImported`(author 주입) 단위/통합테스트 green.
- 파서 댓글/worklog(CSV 3/4파트 + JSON 중첩) 단위테스트 green.
- 어댑터 import 통합테스트(Testcontainers 실 DB — **경고된 행은 이슈 커밋 / 예상외 throw 행은 이슈까지 롤백**을 실 tx로 검증, memory `tx-aware-dslcontext-rollback-test-gap`. 댓글/worklog 생성·author 보존·best-effort 3축·dry-run FORBIDDEN 미발생·행 원자성) green.
- `CommentExceptionHandler` 403/404 HTTP 통합테스트(500 미변질) green. CHECK 23514(timeSpent)·body NOT NULL 사전검증이 tx 무오염임을 실 DB로 확인.
- ktlint/detekt clean, ArchUnit(BC 격리·jOOQ repository) green, verify-master-plan 통과.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 분석 10축 → (A) 스펙 보강 3건 반영: CommentExceptionHandler 신설(500 변질 차단, R4b), dry-run 경고 경로 분리(rowTriggersUpdate/FORBIDDEN 미엮음, R11), CSV 세미콜론 split limit(E6). + 경미 보강(조회 IssueScope.Issue 명시·started 부재·best-effort 경계 E6c·tx-aware 통합테스트). (B) Maxi 결정 4건 확정: worklog import 이력 생략(R6)·경고 행당 유형별 집약(R10)·worklog JSON 전용(R8)·댓글 도메인 FR-IM-01 흡수+SDD/fr-index 명시(header). 코드 확인으로 VIEW+IssueScope.Issue 보안게이트 자동적용·remaining NULL decrement 미적용 방어 확인.
