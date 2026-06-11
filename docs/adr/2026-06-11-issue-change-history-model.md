# 이슈 변경 이력 모델 (FR-HS-01)

> 상태: Accepted
> 날짜: 2026-06-11
> BC: issue-tracking
> 관련 FR: FR-HS-01 (이슈 변경 이력 기록)
> 관련: [[2026-06-10-auth-audit-log-persistence]] (append-only 선례), DATA.md §감사 로그, domain/issue-tracking.md (IssueHistory 엔티티 + "한 트랜잭션" 규칙)

## 맥락

이슈의 필드(상태/담당자/우선순위/본문 등)가 변경될 때 "누가/언제/무엇을/이전값→새값"을 영구 기록해야 한다(FR-HS-01). `domain/issue-tracking.md`는 이미 `IssueHistory`를 핵심 엔티티로, "이슈 + 히스토리 + 알림 이벤트는 한 트랜잭션"을 절대 규칙으로 등재해 이 기능을 예견했다.

조사로 드러난 제약.
- 기존 `IssueEventPublisher`(pgmq `q_issue_events`)의 `IssueUpdated` 이벤트는 **바뀐 필드 이름 세트만** 담고 이전값→새값이 없다.
- `changeAssignee / changeComponents / changeAffectsVersions / changeFixVersions`는 **현재 이벤트를 발행하지 않는다** → 이벤트 소비 방식이면 이 변경들이 누락된다.
- FR-AU-10 `auth_audit_logs`가 append-only(BIGINT IDENTITY PK, FK 없음, JSONB) 선례를 제공한다.

## 결정

### 1. 기록 메커니즘 — 서비스 레이어 동기 기록

각 변경 메서드(`updateIssue`, `transitionIssue`, `changeAssignee`, `changeComponents`, `changeAffects/FixVersions`, `assignSecurityLevel` 등)에서 변경 직전 이슈 상태(existing)와 새 상태(request)를 비교해, **이슈 변경과 같은 트랜잭션 안에서** 이력을 INSERT 한다.

근거. (a) domain "한 트랜잭션" 규칙과 정합, (b) 이전값→새값을 정확히 알 수 있는 유일한 지점이 변경 시점의 서비스, (c) 이벤트 미발행 경로도 빠짐없이 커버. pgmq 컨슈머 방식은 이벤트 페이로드 확장 + 미발행 경로 전수 이벤트화가 선행돼야 해 비용/누락 위험이 크다.

### 2. 데이터 모델 — Jira식 2테이블 (change group + change item)

- `issue_change_group` — 한 번의 변경(한 PATCH/전이) = 1행. 누가(actor)/언제(created_at)/어느 이슈.
- `issue_change_item` — 그 변경에 포함된 필드별 from→to = N행.

근거. 한 PATCH에서 여러 필드가 바뀌어도 "한 번의 편집"으로 묶여 FR-HS-02(조회 UI)에서 자연스럽게 그룹 표시된다. 필드별 from/to를 정규 컬럼으로 둬 인덱스/필터가 JSONB 연산에 의존하지 않는다. Jira 정석(ChangeGroup + ChangeItem)과 동일.

### 3. 추적 범위 — 전 필드 + 생명주기

추적 필드. summary, description, priority, labels, environment, impact, type, assignee, 상태전이(status), resolution, components, affectsVersions, fixVersions, securityLevel, customFields.
생명주기 이벤트. 이슈 생성(created), 소프트 삭제(soft_deleted)도 이력 그룹으로 기록(필드 변경이 아닌 생명주기 항목).

### 4. 보존 — append-only

이력은 삭제/수정하지 않는다(DATA.md 감사 정책 정합). 이슈가 소프트 삭제돼도 이력은 보존. FK는 `issue_change_item → issue_change_group`(부모-자식 무결성)에만 둔다. `issue_change_group`은 **`issues`로의 FK를 두지 않는다**(FR-AU-10 `auth_audit_logs` 패턴, 이력 보존 우선) — issue_id + issue_key 동시 저장으로 이동/리다이렉트에도 추적 가능.

## 대안 (기각)

- **pgmq 이벤트 컨슈머 기록** — 비동기/느슨한 결합이나 이전값 손실 + 미발행 경로 전수 이벤트화 선행 필요. 기각.
- **단일 테이블 + JSONB changes 배열** — 간단하나 필드별 필터/인덱스가 JSONB 연산 의존. FR-HS-02 필터 요구에 불리. 기각.
- **단일 테이블 필드별 1행** — "한 번의 편집" 그룹화가 없어 조회 UI에서 묶음 표현 약함. 기각.

## 결과

- 신규 마이그레이션 2테이블(V018 후보 — FR-MN-01 PR #114와 V번호 조율). init_codegen 미러 필요.
- `IssueApplicationService`의 변경 메서드들이 이력 기록 호출을 추가(같은 트랜잭션). 기존 변경 동작/OCC 불변.
- from/to 값의 직렬화 형식(ID vs 표시값)과 actor 출처는 spec에서 확정.

## 보강 (2026-06-11) — cross-BC 라벨 박제

### 배경

PR #115(FR-HS-01 초기 구현)에서 §결정1 "기록 시 cross-BC 호출 회피"를 근거로 assignee/securityLevel 필드의 표시명(label)을 null로 남겼다. Maxi가 "완전 Jira식"을 선택함에 따라(2026-06-11), 두 필드에 한해 이 결정을 부분적으로 뒤집는다.

### 변경 결정

assignee 및 securityLevel 필드의 표시명을 **변경 당시 기록 시점에 박제**한다(Jira의 fromString/toString 정석).

- **assignee** — 변경 당시 담당자의 `display_name`을 박제. 조회 실패 시 `username` 폴백. 그것도 실패 시 label=null로 graceful degrade하며 이력 기록은 진행한다.
- **securityLevel** — 변경 당시 보안등급의 레벨명을 박제. 조회 실패 시 label=null로 graceful degrade하며 이력 기록은 진행한다.

### BC 격리 유지

cross-BC read는 기존 shared-kernel 포트를 경유한다. 직접 import는 없다.

- **assignee 표시명** — `UserLookupPort`(역방향: identity-access→issue-tracking 방향의 기존 포트)를 통해 userId로 표시명 조회.
- **securityLevel 레벨명** — `IssueSecurityDirectory.findLevelNames`(issue-tracking 내부 포트)를 통해 levelId로 레벨명 조회.

### 범위 밖

- **status 필드**(project-workflow BC 소관)는 이번 보강 범위가 아니다. status label 박제는 project-workflow와의 별도 협의가 필요하며, 현재로서는 null 유지.

### 단계 분할

- **PR1(기록 보강, 현 작업)** — assignee/securityLevel 표시명 박제 구현.
- **PR2(FR-HS-02 조회 UI)** — 박제된 label을 타임라인에 표시하는 조회 UI 구현.
