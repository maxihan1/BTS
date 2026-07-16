# 프로젝트 관리 기능 신설 — 생성 / 목록 / 설정 / 아카이브 + FR 추가

> slug: project-management-crud
> type: backend
> agent: backend-engineer
> 생성: 2026-07-17
> 브랜치: `backend/project-management-crud`

## Brief

### 사용자 원문

> "계획에 지라의 스페이스 개념이 빠져 있는데 추가 해줄래? 지라와 동일한 스페이스 스펙으로 잡아주고 현재 개발된 것과 조립 해줘"

### 조사로 확인된 것 — "스페이스"의 정체

지라 Cloud는 2025년에 **Project를 Space로 개명**했다. 데이터·API·JQL은 그대로다.

- Atlassian 공식 문서가 `"A Jira space is a collection of related work items"` 라고 정의하는데, 그 문서 URL은 여전히 `support.atlassian.com/jira-software-cloud/docs/what-is-a-jira-software-**project**/` 다 — 주소는 project, 내용은 space. 개명의 증거.
- 파트너사 분석: `"This is just a name change. Functionally, nothing about your data, workflows (JQLs), or permissions changes. Only the labels in the UI are the ones changing."` 2025년 12월까지 순차 롤아웃.
- 지라 Space 속성: space key / name / type(software·business) / template / management style(team·company-managed).
- **Space 위에 컨테이너는 없다.** 별개 개념인 `Atlassian Projects`(Atlassian Home의 cross-tool 상위 그릇)가 존재하나 Space와 다른 축이다.

**따라서 지라의 Space = BTS의 `projects`.** 개념은 이미 있다. 빠진 것은 **관리 기능과 그것을 추적할 FR**이다.

### 진짜 결핍 (조사 결과)

BTS는 지금 **스페이스가 하나(ATLAS)뿐이고 그마저 손으로 심은** 상태다.

| 결핍 | 근거 |
|---|---|
| 생성 API 0건 | `projects` 행 삽입은 dev 전용 시드 `data-dev.sql:16-18`의 ATLAS 1건뿐. prod는 `data-locations` 미설정이라 실행조차 안 됨 |
| 목록 API 0건 | `GET /api/v1/projects` 가 백엔드·프론트 양쪽에 없음 |
| 목록 화면 0건 | `apps/web/src/router.ts` 52개 라우트에 `/projects` 없음. 프로젝트 스코프 라우트 19개는 전부 `$projectKey`를 이미 안다는 전제 |
| 네비 진입점 0건 | `apps/web/src/routes/__root.tsx:21-30` = Header + main뿐. `Header.tsx:91-118` 메인 nav는 `/dashboards`·`/calendar` 둘뿐 |
| 이슈 생성 폼이 자유 텍스트 | `apps/web/src/routes/issues.new.tsx:39` `z.string().min(1)` — 목록 API가 없어 드롭다운 소스가 없음 |
| 추적할 FR 0개 | 123개 FR 중 프로젝트 CRUD 항목 없음. `FR-PM-01`(`02-requirements.md:192`)은 "프로젝트 행정"이나 내용은 **멤버 관리** |

**이 공백은 줄곧 인지돼 있었다.** ADR 3건이 명시적으로 이연을 기록했고, 받아줄 FR이 없어 갈 곳을 잃은 상태다.

- `docs/adr/2026-05-22-issue-key-prefix-policy.md:72` — "FR-IS-01 본 PR은 이슈 CRUD 코어에 집중하므로 프로젝트 생성 API를 제공하지 않는다."
- `docs/adr/2026-05-22-issue-key-prefix-policy.md:78` — "프로젝트 생성/수정/삭제 API는 별도 PR (Project Management) 에서 사용자 입력 검증 + 예약어 차단 + UI 도입."
- `docs/decisions/2026-06-01-project-membership-model.md:14` — "**프로젝트 생성 기능(API/서비스)은 미구현**이다."
- `docs/decisions/2026-06-01-project-membership-model.md:66` — "프로젝트 생성 API(생성자=자동 ADMIN)가 들어오면 이 부트스트랩은 '생성 시 생성자 멤버십 1행 삽입'으로 대체되어 창문이 닫힌다."
- `docs/decisions/2026-06-01-project-member-projectidorkey.md:60` — "프로젝트 생성 FR 도입 시 생성자=자동 admin으로 연결."
- `docs/plan/product/identity-access.md:272` — "프로젝트 생성 FR으로 이연" ← **그 대상 FR이 인덱스에 없음**

## Maxi 확정 결정 (질문-응답으로 잠김, 2026-07-16)

| # | 결정 | 이유 |
|---|---|---|
| D1 | **명칭은 프로젝트 유지** — 개명 없음. UI·DB·API 전부 `project` | "워크스페이스"가 이미 5가지 의미로 과적재(제품 자체 / Slack 워크스페이스 / pnpm workspace / Google Workspace / git worktree 기준 디렉터리). Atlas Wiki v0.5에 Confluence식 Space가 들어올 예정이라 용어 충돌 원천 회피. 지라도 DB·API는 project를 그대로 뒀다 |
| D2 | **범위는 코어만** — 생성 · 목록/조회 · 설정 변경 · 아카이브 | 지라의 type / template / management style 제외. management style(team·company-managed)은 권한·워크플로우 스킴 3종이 `project_id` PK 1:1 구조라 도입 시 동시 재설계 필요 → 폭발 반경 과대 |
| D3 | **상위 컨테이너 제외** — Atlassian Projects 미도입 | Space와 별개 축. 도입 시 `projects.key` 전역 UNIQUE 완화가 필요해지고 이는 `issues.key` 전역 UNIQUE · `issue_key_redirects.old_key` PK 전제 · DATA.md §1.1(이슈키 영구 보존)과 정면 충돌 |
| D4 | **SDD §5.3 괴리는 SDD를 코드에 맞춰 정정** | 코드는 prod 기출시. 이슈 키가 거기 매달려 있어 반대 방향 불가 |
| D5 | **#276(FR-AT-07 PR-B)과 병렬 진행** | 별도 worktree. 단 같은 issue-tracking BC라 충돌 관리 필요 (아래 §리스크) |

## 확인된 현황 — SDD vs 실제 코드 괴리 (D4 대상)

`docs/sdd/05-data-model.md:52-67` §5.3 Project는 필드 12개를 설계해 뒀으나 실제와 어긋난다.

| 축 | SDD 표기 | 실제 코드 | 근거 |
|---|---|---|---|
| `id` 타입 | BIGINT | **UUID** | `docs/decisions/2026-06-01-project-membership-model.md:17` |
| 리드 컬럼명 | `lead_id` | **`lead_user_id`** | `docs/adr/2026-06-06-project-lead-default-assignee-fallback.md` |
| 이슈 발번 | `next_issue_number` | **`key_sequence`** | `V001__issues_initial.sql:10` |
| 소속 BC | Project & Workflow (`04-architecture.md:19`) | **issue-tracking** | `project-membership-model.md:13` |

실제 `projects` 컬럼 9개 — `id`(UUID) / `key`(VARCHAR(10) UNIQUE, `^[A-Z][A-Z0-9]{1,9}$`) / `name`(VARCHAR(255)) / `key_sequence`(BIGINT) / `created_at` / `updated_at` / `deleted_at` / `lead_user_id`(V013) / `require_2fa`(V020).

생명주기 미설계 — SDD Project 표에 `status`·`archived_at` 없음. 대조군으로 Version은 `status`(`05-data-model.md:88` UNRELEASED/RELEASED/ARCHIVED)가, Issue는 `deleted_at`(`:35`)이 있다. **프로젝트만 둘 다 없다.** 실제 테이블엔 `deleted_at`만 있다.

API 미설계 — `docs/sdd/11-api-design.md:32-36`의 프로젝트 API는 **GET 4개가 전부**. 이슈(`:19-21`)는 POST/PATCH/DELETE가 다 있는 것과 대조된다.

## 함께 고칠 drift

- `docs/plan/fr-index.md:5` — `## §A.1 FR 역인덱스 (122개 전수)` 가 stale. 같은 파일 `:1`·`:8`·`:231` 및 실측은 모두 **123**. (`:253`에 `2026-06-14. FR-NT-05 신설 … 합계 122→123` 이력 있음 — 그때 `:5`만 놓침)

## 리스크

### R1. #276과 같은 BC 동시 수정 (Maxi 승인된 병렬)

Draft PR #276 `backend/fr-at-07-pr-b-fix-version`이 같은 issue-tracking BC의 `IssueMutationPort`를 수정 중.

- **Flyway V번호 충돌** — 아카이브 컬럼 마이그레이션 추가 시. 머지 직전 재확인 필수
- **머지 순서 의존** — 먼저 머지되는 쪽에 맞춰 rebase 필요

### R2. 프로젝트 생성은 단순 INSERT가 아니다 — 5계층 한 트랜잭션

이슈를 하나라도 쓰려면 프로젝트 · 권한 스킴 매핑 · 멤버십 · 워크플로우 스킴 · 기본 매핑이 함께 있어야 한다. **권한 스킴·멤버십은 identity-access BC 소유, `projects` 테이블은 issue-tracking 소유** → "한 PR = 한 BC" 규칙상 **PR 분할 설계가 spec 단계 선행 과제**.

선례 — FR-SL-06(PR-A 설정/CRUD → PR-B 라우팅 → D6/D7 UI), FR-AT-07(PR-A/B/C).

### R3. 이슈 키 영구 보존과 충돌 (learnings.md 사전등록 함정)

> "이슈 키는 절대 재사용 금지 — 이슈 키(`PROJ-123`)는 외부 시스템(Slack, 이메일, 다른 문서)에 영구 인용된다."

아카이브/삭제 설계가 정면으로 만난다. **아카이브 vs 소프트삭제(`deleted_at`) 의미 구분**이 spec 과제.

### R4. 프로젝트 키 입력 검증

`issue-key-prefix-policy.md:19,24,78` — 예약어 차단 + 영문 대문자 검증 + `"Atlas Issues" → ATLAS` 자동 제안 UX 구상이 기록돼 있음.

## FR 추가 지점 (조사 확인됨)

- **정본**: `docs/sdd/02-requirements.md` §2.2. 표 헤더 `| ID | 요구사항 | 우선순위 |`. 섹션은 BC가 아니라 **FR 프리픽스**로 묶임 (`### 2.2.N <영역명> (FR-XX)`). 새 프리픽스면 `:260`(FR-CA-02) 뒤 ~ `:262`(§2.3 NFR) 앞에 §2.2.16 신설
- **BC 매핑**은 `docs/plan/fr-index.md:12-13`에만 존재 (`| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |`)
- **프리픽스 선택** (FR-PM 확장 vs 신규 프리픽스) — domain/spec 단계 과제
- **전수 동기화 8종** (CLAUDE.md:29-36) + `bash scripts/verify-master-plan.sh` 통과 필수 (종료코드 4로 자동 차단)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
