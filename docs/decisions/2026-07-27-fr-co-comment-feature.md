# ADR: 댓글을 정식 기능으로 승격 — `FR-CO` 신설 · Worklog 동형 패턴 · 권한 enum 미신설

> 날짜: 2026-07-27
> 상태: 채택 (Accepted)
> 관련 FR: **FR-CO-01 신설** (작성+목록) · **FR-CO-02 신설** (수정+삭제) — **카운트 129 → 131**
> 선행 ADR: `2026-07-17-fr-ux-06-jira-redesign.md` §D6 ("댓글은 별도 FR", Maxi 확정) — 이 결정의 출발점
> 형제 선례: `2026-07-13-fr-at-04-conflict-analysis.md` (댓글 권한 부재를 갭으로 기록한 곳)
> Plan: `docs/plans/2026-07-27-fr-co-01.md`
> PR: #315

## 맥락 (Context)

### 댓글 백엔드는 존재하지만 기능이 아니다

`comment/{domain,application,repository,web}` 4계층이 모두 존재한다. 그런데 `CommentController` 는
**`@GetMapping` 하나뿐**이고 POST·PATCH·DELETE 가 **0건**이다. `CommentApplicationService.create()` 는
존재하나 REST 로 미노출이며, 프론트 `*comment*` 는 **0건**이다.

최초 커밋이 출처를 말해준다 — `029dd82fe [feature] FR-IM-01 PR3 — 댓글/Worklog Import (#224)`.
**외부 시스템에서 댓글을 가져오려면** Comment 도메인이 필요했던 것이고, 따라서
**댓글 백엔드는 댓글 기능이 아니라 Import 의 부산물**이다. 파일 L1 주석도 정직하게
`// 댓글 REST 컨트롤러 — 목록 조회 엔드포인트 (FR-IM-01 PR3)` 라고 적혀 있다.

**REST 미노출 = 기능 없음.** 도메인·서비스·repo 파일의 존재는 기능의 근거가 아니다
(learnings 2026-07-17 — 같은 착각으로 Maxi 에게 오보한 사고가 이미 있었다).

### 문서가 이미 기다리고 있었다

`fr-index.md` 에 댓글 FR 이 없고, 오히려 여러 FR 이 **명시적으로 이연하며 대기**한다.

| FR | 문구 |
|---|---|
| `FR-MN-01` | *"댓글 멘션은 댓글 기능 부재로 제외(**댓글 FR 도입 시** `sourceField="comment"` 로 확장)"* |
| `FR-IS-06` | *"Attachment/Watcher/IssueComment 가 미구현이라 이번 범위에서 제외"* |
| `FR-HS-01` | 댓글을 전제한 문구 보유 |

문서가 "없다" 고 말하는데 코드가 "있다" 고 보이면, 둘 중 하나가 틀린 게 아니라 **판정 기준이 틀린 것**이다.

### 착수 전 반증 — 초안 기록 1곳을 정정했다

| 초안 기록 | 실측 결과 |
|---|---|
| "POST 노출이 **잠자던 알림을 깨운다**" | ❌ **기각.** 경로는 **이미 프로덕션에서 발화 중**이다 — `V401__seed_default_policies.sql:24-26` 이 `issue.commented` × REPORTER/ASSIGNEE/WATCHER × `IN_APP` × `enabled=TRUE` 로 시드돼 있고, `ActionExecutor.kt:233-236` 의 automation `AddCommentAction` 이 `create()` 를 호출하는 **살아 있는 생산자**다(FR-AT-02 완결). 정확한 위험은 "활성화" 가 아니라 **"기존 소비자에게 사람 저작이라는 새 입력 분포가 유입된다"** |

`NotificationEventType.ISSUE_COMMENTED("issue.commented", publishable = **false**)` — 외부 웹훅·이메일
채널 **불가**, 인앱 전용. 사외 유출·메일 폭주 종류의 위험은 구조적으로 없다.

### 실측한 형제 선례 — Worklog 가 같은 문제를 이미 다 풀었다

| 축 | Worklog | Comment (현재) |
|---|---|---|
| 계층 구조 | `worklog/{domain,application,repository,web}` | **동일** |
| REST 경로 | `/api/v1/issues/{key}/worklogs` 중첩 | **동일 형태**(`/comments`) |
| HTTP 매핑 | POST · GET · PATCH · DELETE **4종** | **GET 1종** |
| 전용 예외 핸들러 | `WorklogExceptionHandler` | `CommentExceptionHandler` (worklog 전체 미러) |
| 권한 scope | `IssueScope.Issue` | **동일** |
| 작성 권한 | `IssuePermission.UPDATE` (`:111`) | **동일** (`:85`) |
| 조회 권한 | `IssuePermission.VIEW` (`:369`) | **동일** (`:142`) |
| 수정·삭제 권한 | `UPDATE` + `existing.authorId != actor` → 403 (`:254`, `:328`) | 메서드 자체가 없음 |
| 사람 경로 저작자 | `create()` 가 `authorId = actor.value` **내부 강제** (`:120`) | `create(…, authorId, …)` — **호출자가 지정** |
| Import 경로 저작자 | `createImported(…, authorId, …)` **별도 메서드** (`:178`) | 같은 `create()` 재사용 |

## 결정 (Decision)

### D1. `FR-CO` 프리픽스를 신설하고 **2분할**한다 (129 → 131)

| FR ID | 범위 |
|---|---|
| `FR-CO-01` | 이슈 댓글 **작성 + 목록 조회** |
| `FR-CO-02` | 댓글 **수정 + 삭제** (소프트) |

**왜 2분할인가.** 두 덩어리의 위험 성격이 다르다. CO-01 은 `create()` 재사용으로 백엔드가 얇고
실질 위험이 **알림 입력 분포 변화** 하나다. CO-02 는 도메인 확장(`Comment` 전 필드 `val` → 본문 변경)
+ repo 쓰기 메서드 신설 + **"누가 남의 댓글을 지울 수 있나" 정책 결정**이 붙는다. 한 PR 에 섞으면
회귀 원인 분리가 안 된다 — PR #314 가 프론트 계약 부채를 `TODOS.md` 로 밀며 쓴 것과 같은 논리다.

**왜 `CO` 인가.** `FR-CM` 은 **컴포넌트(Component)** 가 선점했다. `Comment` 의 자연스러운 2글자는
`CO` 이고 전 원격 브랜치 `docs/` 전수 스캔에서 **0건**으로 미선점을 확정했다.

**왜 두 행을 이 PR 에서 함께 등록하는가.** FR 대장은 **완료 기록이 아니라 계획서**다(129 FR 중
다수가 미구현 상태로 등재돼 있다). 한 번의 동기화로 끝내는 편이 카운트 drift 위험이 낮고,
`FR-PJ` 신설 때 #277 이 첫 PR 에서 프리픽스를 등록한 선례와 같다.

### D2. Comment 는 **Issue 에 종속된 child entity** 다 — Worklog 동형으로 확정

자체 리포지토리를 가지면서 Issue 를 resolve 해 Issue-scope 권한으로 게이트하는 현재 구조는
**혼합형 anomaly 가 아니라 BTS 의 확립된 패턴**이다(Worklog 가 선례). 이대로 확정한다.

**따라서 강제되는 것.**
- 단독 조회 창구를 만들지 않는다. 항상 `/api/v1/issues/{key}/comments` 중첩 경로를 경유한다.
- 권한 scope 는 `IssueScope.Issue` **고정**. 프로젝트 scope 로 게이트하면 이슈 보안등급(FR-PM-06)
  우회로 기밀 이슈의 댓글이 샌다.
- **FR-CO-02 의 권한 모델이 선례로 예고된다** — Worklog 는 수정·삭제를 **작성자 한정**
  (`existing.authorId != actor` → 403)으로 두고 관리자 우회를 두지 않았다. CO-02 는 이 선례를
  따를지(대칭) 모더레이션을 도입할지(비대칭) 를 **자기 ADR 에서** 결정한다. 이 ADR 은 예고만 한다.

### D3. `IssuePermission.UPDATE` 를 재사용한다 — `ADD_COMMENT` enum 신설 **기각**

| 대안 | 판정 |
|---|---|
| **A. `UPDATE` 재사용** | ✅ **채택.** Worklog `create`/`update`/`delete` 가 전부 이 방식. 신규 코드 0. 근거 = "댓글 작성은 이슈에 부수 정보를 더하는 수정 행위" (기존 `CommentApplicationService` KDoc 이 이미 이 논리를 적어둠) |
| B. `ADD_COMMENT` enum 신설 | ❌ **기각.** 폭발 반경 — shared-kernel enum + prod resolver 분기 + 권한 시드 마이그레이션 + `PermissionSchemaMigrationTest` 커플링 + `enum-add-breaks-crossmodule-count-guard` 회귀. **PR #314 ADR 이 `VIEW_SCHEME` 신설을 똑같은 사유로 기각한 선례**가 있다 |

`2026-07-13-fr-at-04-conflict-analysis.md` 가 *"AddComment 액션은 `IssuePermission` 에 댓글 권한이
없어 권한 분석 제외"* 라고 **갭으로 기록**해 둔 것은 이 결정으로 해소된다 — 갭이 아니라
**의도된 재사용**임을 여기서 정본화한다. 댓글 전용 권한이 필요해지는 조건(제3의 주체가 댓글만
읽거나 쓰는 요구)이 생기면 재검토한다.

### D4. 사람 경로는 `authorId = actor` 를 **강제**하고, Import 경로를 **별도 메서드로 분리**한다

현재 `create(actor, issueKey, body, authorId, createdAt)` 는 `authorId` 를 호출자가 지정한다.
이대로 POST 를 노출하면 **남의 이름으로 댓글을 쓸 수 있는 표면**이 생긴다.

Worklog 선례대로 둘로 쪼갠다.

| 메서드 | 저작자 | 시각 | 호출자 |
|---|---|---|---|
| `create(actor, issueKey, body)` | `authorId = actor.value` **강제** | `Instant.now(clock)` | REST · automation |
| `createImported(actor, issueKey, body, authorId, createdAt)` | 주입값 (원본 보존) | 주입값 | `IssueImportAdapter` 전용 |

**이것은 리팩토링이 아니라 보안 요구다.** 파라미터를 열어둔 채 노출하면 컨트롤러의 성실성에만
의존하게 되고, 그 의존은 테스트로 고정되지 않는다.

### D5. 알림은 **기존 활성 경로에 새 생산자를 붙이는 것**으로 취급한다

"신규 활성화" 가 아니므로 알림 정책·시드·소비자를 건드리지 않는다. 대신 **사람 저작에서 처음
의미를 갖는 규칙**을 검증 대상으로 못 박는다.

1. **작성자 제외** — automation 경로는 `actor = 룰 actor` 였다. 사람 경로는 `actor = 작성자` 이므로
   "작성자 제외" 가 **처음으로 실제 의미를 갖는다.** 자기 댓글에 자기가 알림받는 회귀가 여기서
   처음 드러날 수 있다.
2. REPORTER / ASSIGNEE / WATCHER 3역할 fanout 이 실제 발화하는지 통합 테스트로 고정.

### D6. glossary 는 **댓글만** 등재한다 (Maxi 확정)

`Maxi_wiki/BTS/glossary.md` §핵심 엔티티에 `댓글 | Comment` 행 추가 +
`domain/issue-tracking.md` §핵심 엔티티에 `Comment` 추가.

**Worklog · Attachment · Watcher 도 glossary 미등재**라는 동질 부채를 발견했으나 이 PR 범위 밖으로
두고 `TODOS.md` 에 등재한다 — 기존 FR 소관이고, 보안·기능 PR 을 용어사전 정리로 번지게 하면
리뷰 단위가 무너진다(#314 가 프론트 계약 부채에 쓴 것과 같은 잣대).

## 검증 (Verification)

| 대상 | 방법 |
|---|---|
| FR 카운트 정합 129 → 131 | `bash scripts/verify-master-plan.sh` (종료 4 = 카운트 drift 차단). **SDD `02-requirements.md` 누락 시 실패** — `PLAN_COUNT`(product 실집합)와 `SDD_COUNT` 를 대조하는 구조 |
| D4 저작자 강제 | `createImported` 없이 `create` 로 임의 `authorId` 를 넣을 수 없음을 **시그니처 수준**에서 확인 + 컨트롤러 통합 테스트 |
| D5 작성자 제외 | notification 통합 테스트 — 작성자에게 알림이 **가지 않음**을 음성 판정자로 고정 |
| D2 scope 고정 | 기밀 이슈(보안등급 보유)에 대한 댓글 목록·작성이 403 임을 확인 |

## 결과 (Consequences)

**좋아지는 것.**
- 댓글이 Import 부산물에서 정식 기능으로 승격되고, 대기 중이던 FR 3건(`FR-MN-01`·`FR-IS-06`·`FR-HS-01`)의
  이연 해제 경로가 열린다.
- `IssuePermission` 이 커지지 않는다 — 9 모듈 재컴파일·시드 마이그레이션·테스트 커플링 회피.
- 저작자 위조 표면이 **노출 전에** 닫힌다.

**나빠지는 것 / 감수하는 것.**
- `UPDATE` 재사용은 "이슈를 수정할 수 있는 사람 = 댓글을 쓸 수 있는 사람" 을 뜻한다. 읽기 전용
  참여자가 댓글만 남기는 시나리오는 **지원하지 않는다.** 그 요구가 생기면 D3 을 재검토한다.
- FR 총수가 131 로 늘어 문서 8종 동기화 비용이 발생한다.

### 잔여 위험

| # | 항목 | 처리 |
|---|---|---|
| 1 | **FR-CO-02 모더레이션 정책 미결** — 작성자 한정(Worklog 대칭) vs PROJECT_ADMIN 우회 허용 | CO-02 자기 ADR 에서 결정. 이 ADR 은 선례만 예고 |
| 2 | `GadgetType.COMMENTS_RECENT` 가 `enabled = false` + 데이터 조회 구현 0 | FR-DB-02 소관. 댓글 기능이 생겨도 자동으로 켜지지 않음 |
| 3 | Worklog · Attachment · Watcher **glossary 미등재** | `TODOS.md` 등재 (D6) |
| 4 | `TODOS.md` 가 병행 PR #314 와 **유일한 충돌 파일** | 양쪽 모두 EOF append → 머지 시 수동 병합 1회 |

## 함정 기록 (다음 사람을 위해)

1. **"백엔드 완비" 판정은 컨트롤러의 HTTP 매핑을 세어서 한다.** 4계층 파일이 다 보이면 완성으로
   읽힌다. `@PostMapping` 이 없으면 쓰기 기능은 없다.
2. **FR ID 선점은 `fr-index.md` 가 아니라 `docs/specs/` 에서 일어난다.** fr-index 반영은 한참 뒤다
   (백엔드 진행 중엔 문서 동기화를 마지막에 하는 순서). 전 원격 브랜치 `git grep` 이 유일한 확정
   수단이다 — learnings 2026-07-17 (#279↔#277) 사고.
3. **`create()` 의 `authorId` 파라미터를 그대로 노출하지 말 것.** Worklog 가 왜 메서드를 둘로
   쪼갰는지가 그 이유다.
4. **알림이 "새로 켜진다" 고 단정하기 전에 시드와 생산자를 실측할 것.** 이 작업에서도 초안이
   틀렸고, `V401` 시드 + `ActionExecutor` 생산자 확인으로 뒤집혔다.
