# FR-IS-07 이슈 Resolution(해결 결과) 필드 — 스펙

> slug: fr-is-07-resolution-resolutions-e2e
> type: migration | BC: issue-tracking (데이터/전달) + project-workflow (게이트 설정)
> 강제 메커니즘: 옵션 A 워크플로우 게이트 — ADR 2026-06-03-resolution-required-on-done-transition
> 제품 요구사항: docs/plan/product/issue-tracking.md §2.1.5
> 작성: 2026-06-03

## 개요

이슈를 종료(StateCategory.DONE) 상태로 전이할 때 Resolution(해결 결과: Fixed / Won't Fix / Duplicate 등)을 필수로 선택하게 한다. Resolution 미설정 시 종료 전이를 거부한다. 강제는 기존 project-workflow `RequiredFieldValidator`를 재사용한다(Jira 방식, ADR 채택).

Resolution은 이슈의 상태(open/closed)와 **별개의 축**이다. "왜 닫혔는가"를 표현한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 종료 시 Resolution 필수 (happy)
- **Given** 사용자가 "진행 중" 이슈의 상세 페이지를 보고 있다.
- **When** 상태 셀렉터에서 종료(DONE 카테고리) 전이("완료"/"Done")를 선택한다.
- **Then** Resolution 선택 모달이 뜬다. 표준 Resolution 목록(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done)이 드롭다운에 표시된다.
- **And** Resolution을 고르고 확인하면 전이가 성공하고, 이슈에 선택한 Resolution이 표시된다.

### S2. Resolution 미선택 시 종료 거부 (guard)
- **Given** 종료 모달이 떠 있다.
- **When** Resolution을 고르지 않고 확인하려 한다.
- **Then** 확인 버튼이 비활성(클라이언트 1차 방어)이거나, 우회 시 서버가 전이를 거부한다(409 TRANSITION_NOT_ALLOWED, RequiredFieldValidator Fail → resolution 누락. 코드 확인: IssueExceptionHandler.kt:37/224).
- **And** 이슈 상태는 변하지 않는다.

### S3. 비종료 전이는 Resolution 불필요
- **Given** "할 일" 이슈를 보고 있다.
- **When** "진행 중"(IN_PROGRESS 카테고리) 전이를 선택한다.
- **Then** 모달 없이 즉시 전이된다. Resolution은 묻지 않는다.

### S4. 재오픈 시 Resolution clear (edge)
- **Given** 종료된(DONE) 이슈에 Resolution=Fixed가 설정돼 있다.
- **When** 비종료 상태(TODO/IN_PROGRESS)로 재전이(재오픈)한다.
- **Then** 이슈의 resolution_id가 null로 초기화된다(Jira 동작). 다시 종료할 때 Resolution을 새로 골라야 한다.

### S5. 표준 Resolution 목록 조회
- **Given** 프론트가 종료 모달을 그린다.
- **When** Resolution 드롭다운을 채운다.
- **Then** `GET /api/v1/resolutions`로 활성 Resolution 목록을 받아 표시한다.

## 기능 요구사항 (FR)

- **FR1** `resolutions` 테이블 + 표준 5종 seed(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done). 표준은 `is_standard = true`, 불변(수정/삭제 불가). IssueType 표준 5종 패턴과 동형(companion object 상수 + V003 seed 선례).
- **FR2** `issues.resolution_id`(nullable, FK 미적용 — BC 격리). 이슈가 Resolution을 참조.
- **FR3** 종료(DONE) 전이 가드: project-workflow DONE 전이에 `RequiredField` validator(config `{ "field": "resolution" }`) 설정. issue-tracking은 전이 시 `issueFields`에 resolution 전달.
- **FR4** 전이 요청 DTO에 `resolutionId` 추가. transport `TransitionIssueRequest`(toStatusKey/expectedVersion → + resolutionId nullable), application `TransitionIssueRequest`(toStateKey/expectedVersion → + resolutionId nullable).
- **FR5** 전이 성공 시 issue.resolution_id 영속. DONE→비DONE 전이 시 resolution_id = null(clear). **영속은 기존 전이 영속 경로(raw jOOQ `IssueRepository.applyTransition`, 낙관락 UPDATE)를 확장**(resolutionId 파라미터 추가) — 전이 영속이 raw jOOQ인 게 이 BC의 정본 설계다. resolution 필수 불변식은 Issue Aggregate가 아니라 **워크플로우 RequiredField validator가 `plan()`(EXECUTION phase)에서 강제**하므로 patch-merge-domain-bypass(도메인 검증 우회)에 해당하지 않는다. (plan B6 갱신과 일치 — 2026-06-03 재개 시 정정.)
- **FR6** `GET /api/v1/resolutions` — 활성 Resolution 목록(id/key/name/description/displayOrder/isStandard). 종료 모달 드롭다운 소스.
- **FR7** 가용 전이 응답에 목표 카테고리 노출(아래 §결정 1). 프론트가 어떤 전이가 종료인지 판별해 모달 트리거.
- **FR8** 종료 모달(프론트): DONE 카테고리 전이 선택 시 Resolution 드롭다운 표시. 미선택 시 확인 비활성. IssueResponse에 resolution 표시.

## 결정 (스펙 단계 확정 필요 — gate 1)

### 결정 1. 종료 모달 트리거 — 가용 전이에 `toCategory` 노출 (추천)
`AvailableTransitionView`(shared-kernel published language)와 REST `TransitionItem`에 `toCategory: StateCategory`(또는 string "DONE"/"IN_PROGRESS"/"TODO") **additive 추가**. 프론트는 `toCategory === 'DONE'`인 전이 선택 시 모달을 띄운다.
- 장점: "서버가 정답지" 원칙 일관(메모리/ADR bulk-available-transitions-server-side), 1회 호출로 판별, UX 매�끄러움.
- 단점: 가용 전이 published DTO 변경(bulk 교집합 응답도 동반). 단 additive라 회귀 위험 낮음.
- **대안(기각 후보)**: 프론트가 모달 없이 전이 시도 → 서버 409(resolution 누락) → 그때 모달 표시 후 재시도. 2회 왕복·UX 저하. 기각 권장.

### 결정 2. 커스텀 Resolution CRUD 범위
이번 FR 범위 = 표준 5종 seed + `GET /api/v1/resolutions`(목록)만. 커스텀 생성/수정/삭제 admin CRUD는 **후속**(IssueType이 FR-WF-02에서 표준 seed만, 커스텀 CRUD는 FR-IS-02로 분리한 선례와 동형).

## API 인터페이스 (REST)

- `GET /api/v1/resolutions` → `{ data: ResolutionItem[] }`, `ResolutionItem { id, key, name, description?, displayOrder, isStandard }`. 활성(미삭제)만, displayOrder asc.
- `POST /api/v1/issues/{key}/transition` body: `{ toStatusKey, expectedVersion, resolutionId? }`. DONE 전이인데 resolutionId 누락 → **409 TRANSITION_NOT_ALLOWED**(전이 거부 — 기존 전이거부 계약 재사용, IssueExceptionHandler.kt:37/224). 비DONE 전이에 resolutionId 무시(또는 검증 없음).
- `GET /api/v1/issues/{key}/transitions` 응답 TransitionItem에 `toCategory` 추가(결정 1).

### 결정 3. [BLOCKER — Brainstorming 발견] availableTransitions가 RequiredField로 DONE 전이를 숨기는 문제

`WorkflowEngine.availableTransitions`(line 303~314)는 validator가 Fail이면 해당 전이를 목록에서 **제거**한다. DONE 전이에 `RequiredField(resolution)`를 설정하면, 가용 전이 목록 조회 시점(사용자가 아직 resolution 미선택)엔 resolution이 null → **DONE 전이가 목록에서 사라져 사용자가 이슈를 영영 닫지 못한다.** Jira는 전이를 노출하고 transition screen(=종료 모달)에서 필수를 강제한다. 즉 RequiredField는 "노출(availability)"이 아니라 "실행(execution)" 시점 검증이어야 한다.

- **→ 결정: 옵션 A 채택 (2026-06-03 Maxi).** `WorkflowValidator`에 적용 시점 속성(availability/execution) 추가. RequiredField는 execution-only로 분류. `availableTransitions`(WorkflowEngine line 303~)는 availability validator(Permission/NotStatusCategory)만 평가하고 execution validator는 건너뛴다. `plan`(실행, line 241~)은 전부 평가. 프레임워크 일반화 — 향후 필드류 validator 확장에 견고.
- 기각: 옵션 C(화이트리스트 최소 변경) — "어떤 validator가 execution-only인지"가 코드 한 곳에 암묵적으로 박혀 확장 시 누락 위험.
- ✅ project-workflow phase 프레임워크(WorkflowValidator SPI phase 속성 + validator 분류 + availableTransitions phase 필터): **FR-WF-03(PR #66)에서 이미 구현 완료**. 본 FR은 toCategory 노출(A1/A4)만 project-workflow에 추가. (2026-06-03 재개 시 정정 — 원래 A2/A3 task는 제거됨.)

### 결정 4. 재오픈 clear는 카테고리 불필요 (확정)
모든 전이에서 `issue.resolution_id = request.resolutionId`로 영속한다. 비DONE 전이는 종료 모달이 없어 resolutionId=null → resolution_id 자동 clear. issue-tracking이 목표 카테고리를 알 필요 없다(S4/FR5 충족). 영속 경로는 FR5 참조(raw jOOQ applyTransition 확장, 도메인 우회 아님 — 불변식은 validator가 강제).

## 데이터 모델 변경

- **V011__resolutions.sql** (issue-tracking) — 마이그레이션. ⚠️ V010은 versions(FR-VR-01) 선점, FR-IS-07은 **V011**.
  - `resolutions(id uuid pk, key text unique, name text not null, description text null, display_order int not null, is_standard boolean not null default false, created_at, updated_at, deleted_at null)`
  - 표준 5종 seed INSERT(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done).
  - `issues` 테이블에 `resolution_id uuid null` 컬럼 추가(FK 미적용, BC 격리).
- **init_codegen.sql 미러 필수** — 컬럼/테이블 추가를 codegen/init_codegen.sql에도 반영해야 jOOQ 상수 생성(메모리 jooq-init-codegen-mirror, V005 선례). 누락 시 repository 컴파일 불가.

## 엣지 케이스

- E1. DONE→비DONE 재오픈 시 resolution_id clear(S4).
- E2. resolutionId가 존재하지 않는 UUID(위조) → 검증 후 거부. ⚠️ RequiredFieldValidator는 값 존재만 검사(존재성 미검증) → 존재성 검증 책임/에러코드 미확정 = plan 재개 설계 결정 Q3(게이트1). 채택 시 코드 확정.
- E3. 표준 Resolution 수정/삭제 시도 → 거부(is_standard 불변). 단 이번 범위에 CRUD 없으므로 seed 불변만 보장.
- E4. 동일 전이를 두 사용자가 동시에(낙관락) → 기존 IssueVersionConflictException 경로 유지(resolution 추가가 락 시맨틱 변경 안 함).
- E5. 여러 DONE 상태가 있는 워크플로우 → 모든 DONE 전이에 validator 설정돼야 함(seed/설정 책임, plan에서 다룸).
- E6. validator가 issueFields["resolution"] 검사 — issue-tracking이 resolution을 **문자열 형태로 전달**할지 UUID로 전달할지 통일 필요(plan에서 RequiredFieldValidator 시맨틱과 정합).

## 제약 조건

- BC 격리: issue-tracking↔project-workflow 직접 import 금지. resolution 전달은 기존 workflowPort(issueFields) 경유.
- 2 BC 걸침 → PR 분리 또는 learning 2026-05-22 선례(plan에서 결정).
- resolution 영속은 기존 전이 영속 경로(raw jOOQ applyTransition) 확장. 필수 불변식은 워크플로우 validator가 plan()에서 강제하므로 patch-merge-domain-bypass 비해당(FR5 참조).
- 프로파일 한정 빈 부팅(메모리 profile-scoped-bean-boot-failure): 새 컨트롤러/빈 추가 시 모듈 전체 test로 부팅 확인.

## 측정 가능한 완료 기준

- [ ] V010 마이그레이션 + init_codegen 미러 → jOOQ 컴파일 통과.
- [ ] DONE 전이 시 resolution 누락 → 422 거부(Testcontainers 통합 테스트로 표면화 — validator/락 경로).
- [ ] DONE 전이 시 resolution 설정 → 성공 + 영속 확인.
- [ ] DONE→비DONE 재오픈 → resolution_id null 확인.
- [ ] `GET /api/v1/resolutions` 표준 5종 반환.
- [ ] 종료 모달 E2E(S1 happy / S2 미선택 거부 / S4 재오픈 clear) green.
- [ ] 4모듈 detekt + ktlint green(메모리 subagent-ktlint-false-green — controller 직접 검증).

## 스펙 외 (후속)
- 커스텀 Resolution admin CRUD(생성/수정/삭제).
- Resolution별 통계/리포트.
