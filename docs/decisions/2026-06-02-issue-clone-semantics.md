<!-- ADR — 이슈 클론(FR-IS-06)의 복사/리셋 의미론 및 첨부·Watcher·댓글 이연 결정 -->

# ADR — 이슈 클론(FR-IS-06) 의미론

**일자.** 2026-06-02
**상태.** Accepted
**관련 PR.** FR-IS-06 (`claude/fr-is-06-work-GZ1cg`)
**작성자.** backend-engineer

---

## 컨텍스트

FR-IS-06은 기존 이슈를 복제해 새 이슈를 만드는 기능이다. FR 제목은 "이슈 클론 (옵션: 첨부/Watcher/댓글 포함)"으로,
복제 시 어떤 데이터를 함께 가져올지 옵션으로 선택하는 모델을 암시한다.

조사 결과, issue-tracking BC에 **첨부(Attachment) / Watcher / 댓글(IssueComment)은 도메인·테이블·리포지토리가
모두 미구현**이다(코드베이스 grep 0건, 마이그레이션 V001~V009에 해당 테이블 없음). 즉 현 시점에 클론이 복사할 수
있는 것은 Issue 애그리거트 자신의 필드뿐이다.

또한 IssueKey는 프로젝트별 시퀀스(`projects.key_sequence` + `pg_advisory_xact_lock`)로 발급되며,
이슈 키는 영구 불변(append-only `issue_key_redirects`)이라는 기존 결정이 있다.

---

## 결정

### 1. 클론은 Issue 애그리거트를 재사용한다 (신규 엔티티 없음)

클론은 별도 도메인 엔티티를 만들지 않는다. 원본 Issue를 조회해 기존 `Issue.create(...)` 팩토리로
새 Issue를 만들고 `IssueRepository.insert()`로 영속한다. 생성 흐름(`createIssue`)을 모사하되 필드 출처만
원본 이슈로 바꾼다. `CloneOptions`는 application 계층 입력 DTO이며 도메인 모델이 아니다.

### 2. 복사(carry-over) vs 새로 시작(reset) 의 경계

| 필드 | 클론 동작 | 근거 |
|---|---|---|
| `summary` | 복사 (옵션 `summaryOverride`로 덮어쓰기 가능) | 사용자 의도상 같은 작업의 변형 |
| `description` | 복사 | 본문 재사용 |
| `typeId` | 복사 | 같은 유형의 이슈 |
| `priority` | 복사 | |
| `labels` | 복사 | |
| `environment` | 복사 | |
| `impact` | 복사 | |
| `assigneeId` | 복사 (옵션 `includeAssignee=false` 시 미할당) | 담당자 carry-over 선택권 |
| `id` | **새 UUID** | 독립 애그리거트 |
| `key` | **새 시퀀스 발급** | 키 영구 불변 결정 준수 |
| `reporterId` | **클론 수행 actor** | 클론은 새 보고 행위 |
| `currentStateKey` | **워크플로우 초기 상태 재결정** | 클론본은 처음부터 시작 |
| `version` | **1** | 새 애그리거트 |
| `createdAt`/`updatedAt` | **now** | 클론 시각 |

> **복사 담당자(`assigneeId`)는 존재성을 재검증하지 않는다.** `changeAssignee` 경로는 `UserLookupPort.exists`로 사용자
> 실재를 guard하지만, 클론은 "원본 스냅샷 복사" 시맨틱이므로 원본이 가진 `assigneeId`를 그대로 carry-over한다. 원본 담당자가
> 그새 삭제됐다면 클론본은 원본과 **동일한** stale 참조를 갖는다 — 이는 원본 자신도 이미 가진 상태이며(`assignee_id`는 BC
> 격리로 FK가 없어 insert가 깨지지 않는다), "삭제된 사용자 정리"는 클론과 무관한 별도 관심사다. `includeAssignee=false`로
> 명시 미할당하는 선택권은 그대로 제공한다. (코드리뷰 C1 확인, Maxi 결정 2026-06-04 — 현행 유지.)

### 3. 첨부 / Watcher / 댓글 복사는 이연 (deferred)

해당 하위 시스템이 미구현이므로 클론 대상에 포함하지 않는다. FR 제목의 "옵션: 첨부/Watcher/댓글 포함"은
**그 기능들이 도입된 이후**에 `CloneOptions`를 확장해 충족한다. 지금은 동작하지 않는 placeholder 옵션
(예: `includeAttachments`)을 노출하지 않는다 — 호출자에게 거짓 약속이 되고(CLAUDE.md 추측 구현 금지),
완제품 품질 기준에 어긋난다.

### 4. 같은 프로젝트로만 클론 (이번 범위)

클론본은 원본과 **동일 프로젝트**에 생성한다. 다른 프로젝트로의 클론(프로젝트 간 이동/복제)은 FR-MV(이슈 이동)와
함께 다룰 별도 관심사로 본다. 이번 범위에서 `targetProjectKey` 옵션을 두지 않는다.

---

## 대안 검토

### 대안 1. 첨부/Watcher/댓글 옵션을 미리 노출하고 no-op 처리 (불채택)

`includeAttachments` 등을 받되 대상 시스템이 없으니 무시한다.

**불채택 이유.** API가 "복사하겠다"고 받아놓고 아무 일도 안 하는 거짓 계약. 옵션 도입 시점에 해당 기능이 생기면
의미가 달라져 하위호환이 깨진다. 미구현 기능에 대한 placeholder는 PoC 사고(금지).

### 대안 2. 클론본의 상태를 원본 상태 그대로 복사 (불채택)

원본이 "In Progress"면 클론도 "In Progress"로.

**불채택 이유.** 클론은 새로 시작하는 작업이다. 진행 중/완료 상태를 그대로 들고 오면 워크플로우 불변식
(초기 상태에서 시작)과 충돌하고, 전환 이력 없는 이슈가 중간 상태로 존재해 혼란을 준다.

---

## 결과

### 긍정

- 기존 `Issue.create` + `insert` 재사용으로 구현 표면적 최소, 회귀 위험 낮음.
- 키 영구 불변·워크플로우 초기 상태 등 기존 결정과 정합.
- 첨부/Watcher/댓글 기능 도입 시 `CloneOptions` 확장만으로 점진 충족 가능.

### 부정 / 위험

- FR 제목이 약속한 "첨부/Watcher/댓글 포함"을 이번에 완전히 충족하지 못함 → fr-index의 D 항목에 이연 사실 명시 필요.
- 같은 프로젝트 한정이므로, 다른 프로젝트로 복제하려는 사용자 요구는 후속 FR 대기.

---

## 참조

- `docs/plan/product/issue-tracking.md` §2.3.1 (FR-IS-06)
- `docs/plans/2026-06-02-issue-clone.md` (도메인 정리 / 스펙 / 플랜)
- `backend/modules/issue-tracking/.../domain/Issue.kt` (`create` 팩토리)
- `backend/modules/issue-tracking/.../application/IssueApplicationService.kt` (`createIssue` 흐름)
- `backend/modules/issue-tracking/.../repository/IssueRepository.kt` (`incrementKeySequence`, `insert`)
- CLAUDE.md §작업 기준(완제품, PoC 금지)
