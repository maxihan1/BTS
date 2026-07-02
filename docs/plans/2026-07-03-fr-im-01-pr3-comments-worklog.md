# FR-IM-01 PR3 — 댓글/Worklog Import

> slug: fr-im-01-pr3-comments-worklog
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 에픽 3단계 (PR3). PR1(코어 이슈 생성)·PR2(컴포넌트/버전 자동생성 + 소스 상태 전이) 완료 이후 후속.

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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
