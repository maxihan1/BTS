<!-- ADR: 프로젝트 간 이슈 이동 의미론 — Jira식 매핑 마법사, id 보존 + 키 redirect, 비호환 항목만 매핑 -->

# ADR — issue-move-semantics (FR-MV-01)

**일자**. 2026-06-16
**상태**. Accepted
**관련 PR**. `feature/fr-mv-01` (#153)
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

FR-MV-01 — 프로젝트 간 이슈 이동 (issue-tracking BC, SDD §6.1.1 / product §6.1.1). 이슈를 프로젝트 A 에서 프로젝트 B 로 옮긴다.

DATA.md §1.1 + ADR `issue-key-prefix-policy` §68 이 본 기능을 직접 지목한다 — "prefix 변경이 필요하면 새 프로젝트 생성 + 이슈 이동(FR-MV-01), 옛 키는 `IssueKeyRedirect` 로 영구 보존". `issue_key_redirects` 테이블은 V001 에서 **스키마만 생성하고 "FR-MV-01 활성화 시 사용"** 으로 대기 중이었다. 본 작업이 이 테이블의 첫 실사용이다.

### 이동의 본질 — id 보존, key 만 변경

이슈 row 의 `id` (UUID, PK) 는 보존하고 `project_id` 와 `key` 만 변경한다. 이슈 id 를 참조하는 모든 FK — 히스토리(`issue_change_group`), 링크(`issue_links`), 워처(`issue_watchers`), 첨부(`issue_attachments`), 담당자/보고자 — 는 자동으로 보존된다. 이 점이 FR-MV-02(히스토리/링크 보존)를 구조적으로 충족시킨다.

### 난제 — 프로젝트 종속 데이터의 비호환

A 와 B 는 서로 다른 정의 집합을 가진다.

| 데이터 | 종속 | 이동 시 문제 |
|---|---|---|
| 이슈 타입 (`issue_types`) | **전역** (project_id 없음) | 없음 — 그대로 유지 |
| 컴포넌트 (`components`) | 프로젝트 종속 | A 의 컴포넌트는 B 에 없음 |
| 버전 affects/fix (`versions`) | 프로젝트 종속 | A 의 버전은 B 에 없음 |
| 커스텀 필드 (`custom_field_definitions` + `issues.custom_fields`) | 프로젝트 종속 | A 정의 기준 값이 B 정의에 안 맞음 |
| 워크플로우 상태 (`current_state_key`) | 프로젝트별 스킴 (`project_workflow_scheme_assignments` → `workflow_scheme_issue_type_mappings`) | A 워크플로우의 상태가 B 워크플로우에 없을 수 있음 |

## 결정

**Jira "Move Issue Wizard" 방식 채택 (Maxi 결정 2026-06-16).** 비호환 항목만 사용자가 매핑하고, 호환 항목은 자동 유지한다.

### 이동 불변식 (항상 보장)

1. **id 보존** — `issues.id` 불변. FK 참조 데이터(히스토리·링크·워처·첨부) 자동 보존.
2. **새 키 발급** — 대상 프로젝트 `projects.key_sequence` 를 `pg_advisory_xact_lock` 으로 보호하며 증가 → 새 `key` (예. `B-45`). 이슈 생성/클론과 동일한 발번 경로 재사용.
3. **옛 키 redirect** — `issue_key_redirects(old_key, new_key)` 에 append-only INSERT. UPDATE/DELETE 는 트리거로 차단. 체인 이동 시 최종 키로 갱신하지 않고 조회 시 체인 순회.
4. **옛 키 308 redirect** — 옛 키로 조회 시 308 Permanent Redirect → 새 키 경로.
5. **이슈 타입 전역 유지** — 타입은 전역이므로 변경 없음. 단, 대상 스킴이 해당 타입을 매핑하지 않은 경우는 워크플로우 매핑 단계에서 처리.

### 매핑 규칙 (비호환 항목만 사용자 입력)

| 항목 | 호환 시 | 비호환 시 |
|---|---|---|
| 워크플로우 상태 | 대상 워크플로우에 같은 state_key 존재 → 유지 | 사용자가 대상 워크플로우 상태 중 선택 (기본 제안 = 초기 상태) |
| 컴포넌트 | 이름 동일 컴포넌트가 B 에 존재 → 자동 매핑 제안 | 사용자가 B 컴포넌트로 매핑 또는 제거 |
| affects/fix 버전 | 이름 동일 버전이 B 에 존재 → 자동 매핑 제안 | 사용자가 B 버전으로 매핑 또는 제거 |
| 커스텀 필드 값 | B 에 같은 key 의 필드 정의 존재 → 값 유지 | B 정의에 없는 값은 제거. B 의 필수 필드가 비면 사용자가 값 입력 |

### API 형태 (preview + apply 2단계)

마법사는 "어떤 매핑이 필요한지"를 백엔드가 먼저 계산해야 단계를 구성할 수 있다. 따라서 2단계.

1. **`POST /api/v1/issues/{key}/move/preview`** — body `{ targetProjectKey }`. 대상 프로젝트 기준으로 필요한 매핑 항목(상태/컴포넌트/버전/커스텀필드)을 계산해 반환. 자동 매핑 가능 항목은 제안값 포함.
2. **`POST /api/v1/issues/{key}/move`** — body `{ targetProjectKey, statusMapping, componentMapping, versionMapping, customFieldValues, expectedVersion(OCC) }`. 검증 후 단일 트랜잭션으로 이동 실행.

세부 페이로드 스키마는 spec 에서 확정.

### cross-BC 경계

- issue-tracking 이 project-workflow 의 "대상 프로젝트 워크플로우 상태 목록 / 초기 상태"를 조회해야 한다. CLAUDE.md §핵심 패턴(BC 격리)에 따라 **직접 import 금지** — resolver/port 창구로 조회 (FR-PM 권한 resolver 선례, [[crossbc-permission-resolver-not-role-lookup]]).
- 컴포넌트/버전/커스텀필드는 issue-tracking 내부이므로 BC 경계 없음.

### 권한

- 원본 프로젝트에서 이슈 수정 권한(`EDIT_ISSUE` 계열) + 대상 프로젝트에서 이슈 생성 권한(`CREATE_ISSUE`) 둘 다 필요. spec 에서 권한 코드 확정 (security-engineer 검토).

## 본 작업 범위 / PR 분할

Jira식 마법사는 백엔드(preview + move + cross-BC 조회) + 프론트(다단계 마법사 UI) + E2E 로 범위가 크다. plan 단계에서 **백엔드(D1~D5) 먼저, 프론트/E2E(D6/D7) 후속 PR** 로 분할 가능성 높음 (이전 FR 다수의 분할 패턴 일치).

## 결과

### 긍정

- **Jira 이주 경험 일치** — 사용자에게 익숙한 이동 모델. 데이터 손실을 사용자가 명시적으로 통제.
- **키 영속성 일관** — 옛 키 308 redirect 로 외부 참조(Slack/이메일/위키) 보호.
- **FR-MV-02 구조적 충족** — id 보존으로 히스토리/링크/워처/첨부 자동 보존.

### 부정 / 위험

- **범위 큼** — preview 계산 로직 + cross-BC 조회 + 다단계 UI. PR 분할 필요.
- **체인 이동** — A→B→C 이동 시 `A-1` 조회는 `B-x` 거쳐 `C-y` 까지 체인 순회 필요. 무한 루프(순환) 방지 가드 필요 (spec).
- **부분 매핑 데이터 손실** — 미매핑 컴포넌트/버전은 제거됨. 이력에 "이동 + 제거된 연결" 기록으로 추적성 확보 (FR-MV-02 / 히스토리).

## 관련

- `DATA.md §1.1` — 이슈 키 영구 보존
- ADR `2026-05-22-issue-key-prefix-policy` §68 — 본 기능 지목
- `docs/plan/product/issue-tracking.md §6.1.1` — FR-MV-01 D1~D7
- `docs/plans/2026-06-16-fr-mv-01.md` — 본 작업 plan
