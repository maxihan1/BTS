# FR-HS-01 보강 — cross-BC 라벨 박제 (완전 Jira식)

> slug: fr-hs-01-crossbc-label
> type: feature (FR-HS-01 deviation 수정)
> primary_bc: issue-tracking (주) + shared-kernel + identity-access (다중 — Maxi 단계분할 승인)
> agent: backend-engineer (주) + security-engineer (identity-access port 검토)
> 생성: 2026-06-11
> 후속: FR-HS-02 조회 API+UI+E2E는 PR2로 분리 (이 PR 머지 후)

## Brief

FR-HS-02(히스토리 조회 UI) 진행 중, "조회 시 cross-BC 필드(assignee·securityLevel)를 어떻게 표시할까" 결정에서 Maxi가 **완전 Jira식**(기록 시점 표시명 박제)을 선택. Jira는 cross-BC 구분 없이 변경 당시 표시명(`fromString`/`toString`)을 박제해 "당시값 보존"의 감사 무결성을 확보한다.

BTS FR-HS-01(PR #115)은 BC 격리 때문에 cross-BC 필드(assignee·securityLevel)는 기록 시점 표시명 박제를 일부러 비워뒀다(`label=null`). 이 PR은 그 빈칸을 **기록 시점 박제**로 채운다. 그러면 FR-HS-02 조회는 박제된 label만 쓰면 되어 단순해진다.

**범위 결정 (Maxi 게이트)**
- 완전 Jira식 채택 (cross-BC 박제). [AskUserQuestion 2026-06-11]
- 단계 분할: PR1=FR-HS-01 보강(이 문서), PR2=FR-HS-02 조회/UI/E2E. [AskUserQuestion 2026-06-11]
- 한 PR로 전부는 다중-BC 거대 PR이 되어 기각.

## 도메인 정리

- **BC (다중, BC 격리 예외)**: 박제 호출 = issue-tracking, cross-BC 조회 port = shared-kernel 인터페이스 + identity-access 구현.
- **새 엔티티/용어**: 없음. 기존 모델/포트 확장.
- **활용/확장 엔티티 (git grep 실재 확인)**:
  - `IssueChangeLabelResolver`(issue-tracking, `history/`) — 현재 type·resolution·components·versions만 라벨링, assignee·securityLevel·status는 "cross-BC 조회 금지"로 label=null 유지(KDoc line 28-30). **이 PR이 assignee·securityLevel 라벨링 추가**.
  - `UserLookupPort`(shared-kernel, `com.bts.shared.user`) — 현재 `exists(userId)` + `findIdsByUsernames`(username→id). **역방향(userId→표시명) 추가 필요**. 구현체 `UserLookupAdapter`(identity-access).
  - `IssueSecurityDirectory`(shared-kernel, `com.bts.shared.permission`) — 보안등급 cross-BC 창구. **레벨명 조회 추가 필요**. 구현체 `IdentityAccessIssueSecurityDirectory`(identity-access). `IssueSecurityLevel`(identity-access)에 name 보유.
  - `IssueHistoryRecorder`(issue-tracking) — 기록 facade. LabelResolver 호출부.
  - `IssueApplicationService.changeAssignee/assignSecurityLevel`(issue-tracking) — 변경 진입점. `AppChangeAssigneeRequest(assigneeId: UUID?)`는 표시명 미보유 → 기록 시점 cross-BC 조회 불가피.
- **status 필드**: project-workflow BC, stateKey passthrough. Maxi는 assignee·securityLevel만 명시 → **status는 이 PR 범위 밖**(stateKey가 어느 정도 식별 가능, 추후 별도 판단).
- **actor 표시명**: actorId(UUID) group 레벨 저장. 변경 author 식별이라 "당시명 박제"보다 조회 시점 resolve가 일반적(Jira author도 계정 링크). **이 PR 범위 밖** — FR-HS-02 조회에서 처리.

### 기존 결정 뒤집기 (deviation — ADR 갱신 필수)
- ADR `docs/adr/2026-06-11-issue-change-history-model.md` §결정 1 "기록 시 cross-BC 호출 회피" + LabelResolver KDoc "cross-BC 조회 금지"를 **부분 뒤집음**: assignee·securityLevel은 기록 시점 cross-BC **읽기**(표시명 조회)를 허용. 단 graceful degrade(조회 실패 시 label=null 유지, 기록은 진행) — 기존 LabelResolver 정책과 동일.
- ADR에 보강 단락 추가 + 전수 동기화(아래).

### 함정 메모 (learnings/memory)
- cross-BC 조회는 read-only 표시명 한정. 권한/멤버십 직접 조회 금지(메모리 crossbc-permission-resolver-not-role-lookup).
- cross-BC resolver nullable fail-open 주의(메모리 crossbc-resolver-nullable-fail-open) — 표시명 못 가져와도 기록 차단 금지, label=null fallback.
- best-effort catch에 권한 예외 포함 금지(메모리 best-effort-loop-permission-exception) — 단 여기선 표시명 조회라 권한 예외 무관, graceful degrade 적정.
- 기록 트랜잭션 안 cross-BC 호출 추가 → self-invocation/트랜잭션 경계 점검(메모리 transaction-self-invocation-requires-new).

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-hs-01-crossbc-label.md](../specs/2026-06-11-fr-hs-01-crossbc-label.md)

핵심 요약.
- `IssueChangeLabelResolver`가 assignee(→display_name?:username)·securityLevel(→IssueSecurityLevel.name)을 기록 시점에 표시명 박제. detector가 채운 from/to 값(UUID)을 cross-BC port로 resolve.
- cross-BC port는 shared-kernel 인터페이스에 **default 메서드**로 역방향 추가(UserLookupPort.findDisplayNamesByIds, IssueSecurityDirectory.findLevelNames) → 기존 fake 다수 보호 + fail-safe(빈 Map→label=null). 구현은 identity-access.
- 조회 실패 graceful degrade(label=null, 기록 진행). 기록 트랜잭션 참여(readOnly port). BC 격리 유지(직접 import 0).
- FR-HS-01 기존 테스트(label=null 가정) → 박제 검증으로 갱신. ADR 보강. 테이블 변경 없음(마이그레이션 불요).

## Brainstorming Check

✅ 통과 (직접 sanity check — 완료 FR 보강이라 office-hours/brainstorming 대화형 생략, 메모리 bts-spec-office-hours-mismatch 학습).
- 검토 gap: actor 표시명 박제(→PR2 조회에서 처리), status 박제(Maxi 범위 제외, project-workflow BC), prod 구현 오버라이드 누락 시 fail-safe(label=null 보안 무영향), 통합테스트는 실 repo로 가짜그린 회피.
- 모두 의도적 범위 분리이거나 완료기준으로 커버됨. 신규 BLOCKER 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

## 전수 동기화 대상 (머지 전 verify-master-plan.sh)
- ADR `docs/adr/2026-06-11-issue-change-history-model.md` — cross-BC 박제 보강 단락
- `docs/plan/product/issue-tracking.md` §5.1.1 D2 노트 — "assignee/securityLevel은 cross-BC라 label=null" → 박제로 수정
- (FR 카운트 변동 없음 — FR-HS-01 보강이지 신규 FR 아님)
- Obsidian history/learnings 미러, 자동 메모리
