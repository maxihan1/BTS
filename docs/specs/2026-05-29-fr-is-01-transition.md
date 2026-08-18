# FR-IS-01 상태전환 (Transition) — 스펙

> slug: fr-is-01-transition-e2e
> BC: issue-tracking (project-workflow를 WorkflowKeyResolver SPI로 소비)
> scope: backend(가용전환 엔드포인트) + frontend(전환 UI) + qa(프론트 E2E + 백엔드 통합 테스트)
> 작성: 2026-05-29

## 배경

D6에서 이슈 상태는 읽기전용 배지로만 표시하고 전환 UI를 의도적으로 제외했다(메모 `issue-transition-backend-gap`). 백엔드 전환 wiring은 PR #27(`WorkflowKeyResolver` consumer)/#28(케이스 misalign hot-fix)에서 완료됐다. 이번 작업이 그 후속 slice로, 전환 UI를 붙이고 런타임 동작을 E2E·통합 테스트로 최초 검증한다.

전환 실행 엔드포인트(`POST /api/v1/issues/{key}/transition`)는 이미 존재한다. 단, "현재 상태에서 갈 수 있는 전환 목록"을 주는 능력이 없어 신규 추가한다.

**가용전환 설계 결정 (Maxi 2026-05-29, 옵션 A 풀버전).** 지라(`GET /rest/api/3/issue/{key}/transitions`)와 동일하게 **서버가 이슈별로 가용 전환을 권위 있게 계산**한다. 단순 구조 필터(출발 상태)가 아니라, 기존 `WorkflowEngine` + validator(`PermissionValidator`/`CustomExpressionValidator`/`RequiredFieldValidator`/`NotStatusCategoryValidator`)로 **조건·권한까지 평가**해 실제 실행 가능한 전환만 반환한다. 이유. BTS 워크플로우 모델이 이미 조건부 전환(SpEL)를 지원하므로, 클라이언트 구조 필터는 "보이는데 눌러도 거부되는" 전환을 노출할 위험이 있다.

**BC 격리 예외 (Maxi 승인).** 이 작업은 shared-kernel SPI + project-workflow + issue-tracking 3개 백엔드 모듈 + frontend + qa에 걸친다. "한 PR = 한 BC" 원칙의 명시적 예외 — SPI 확장은 sanctioned cross-BC 채널이며 Maxi가 풀버전을 알고 선택했다(PR #13 옵션 C 선례).

## 사용자 시나리오 (Given-When-Then)

### S1 — 행복 경로: open → in_progress
- **Given** alice 로그인 + software-default 워크플로우의 신규 이슈(현재 상태 `open`)
- **When** 이슈 상세에서 상태 컨트롤을 열어 "Start Work (In Progress)"를 선택
- **Then** 상태 배지가 `in_progress`로 갱신되고, 가용 전환 목록도 `in_progress` 기준으로 갱신된다(Submit for Review 등).

### S2 — 가용 전환만 노출
- **Given** 현재 상태 `open`인 이슈
- **When** 상태 컨트롤을 연다
- **Then** software-default에서 `open` 출발 전환만 보인다 — Start Work(→in_progress), Cancel(→closed). `in_review`로 직접 가는 선택지는 없다.

### S3 — 전환 거부 (409)
- **Given** 백엔드가 허용하지 않는 전환을 시도(레이스로 상태가 이미 바뀐 경우 등)
- **When** 전환 실행이 409 Transition Not Allowed로 거부
- **Then** UI는 에러 메시지를 표시하고 상태는 변경 전 그대로 유지된다.

### S4 — 낙관락 충돌 (409 Version Conflict)
- **Given** 다른 곳에서 이슈가 수정돼 version이 올라간 상태
- **When** stale version으로 전환 실행 → 409
- **Then** UI는 충돌 안내 + 최신 데이터 재조회를 유도한다.

### S5 — 워크플로우 미설정 (422)
- **Given** 프로젝트에 워크플로우 스킴이 없는 이슈
- **When** 가용 전환 조회 또는 전환 실행 → 422 Workflow Not Configured
- **Then** UI는 전환 컨트롤을 비활성/숨김 처리하고 안내를 표시한다.

### S6 — 종료 상태 (전환 없음)
- **Given** 현재 상태에서 나가는 전환이 없는 상태(예: `closed`)
- **When** 상세 진입
- **Then** 전환 컨트롤은 노출되지 않거나 비활성 + "더 진행할 전환 없음" 안내.

## 기능 요구사항 (FR)

### shared-kernel (SPI 확장)
- **FR-T-S1** `WorkflowTransitionPort`(또는 신규 SPI)에 가용전환 열거 메서드 추가. 예: `availableTransitions(req): AvailableTransitionsResult`. 입력은 `plan`과 같은 컨텍스트(workflowKey, issueKey, fromStateKey, actorId/roles, issueFields, version). 출력은 **validator/조건/권한을 통과한** 전환 목록 + 미설정/미존재 케이스. `Propagation.MANDATORY` 일관.

### project-workflow (SPI 구현)
- **FR-T-P1** `WorkflowTransitionAdapter`(또는 동등)에 위 SPI 구현. 워크플로우 로드 → `fromStateKey` 출발 전환 enumerate → 각 후보를 기존 `WorkflowEngine`/`WorkflowValidator`로 평가 → **통과한 전환만** 반환. `plan`과 동일한 검증 경로 재사용(중복 로직 금지).

### Backend (issue-tracking)
- **FR-T-B1** `GET /api/v1/issues/{key}/transitions` 추가. 이슈 조회 → `WorkflowKeyResolver`로 워크플로우 resolve → SPI `availableTransitions` 호출(actor/roles 전달) → 통과 전환 반환.
  - 응답: `{ data: { transitions: [{ fromStateKey, toStateKey, name, key }] } }` (transition identity = (from,to), ADR 2026-05-28-workflow-transition-identity-policy 준수).
  - 이슈 없음/삭제 → 404. 워크플로우 미설정 → 422.
- **FR-T-B2** (기존) `POST /api/v1/issues/{key}/transition` body `{ toStatusKey, expectedVersion }` → 200 + 갱신 `IssueResponse`. 이번 작업에서 신규 구현 아님 — 통합 테스트로 런타임 검증.

### Frontend (issue-tracking)
- **FR-T-F1** 이슈 상세(`issues.$key.tsx`/`IssueMetaPanel.tsx`)에 전환 컨트롤 추가. 가용 전환을 드롭다운/버튼으로 노출(shadcn `select` 또는 `dropdown-menu`, DESIGN.md 컨벤션 준수).
- **FR-T-F2** 전환 선택 시 `POST /issues/{key}/transition` 호출(`toStatusKey` = 선택한 toStateKey, `expectedVersion` = 현재 issue.version). 성공 시 TanStack Query 캐시 무효화로 상태/가용전환/메타 갱신.
- **FR-T-F3** API 클라이언트 함수 + Zod 스키마 신규(`fetchIssueTransitions`, `transitionIssue`). **Zod 스키마는 실제 backend DTO와 grep으로 정합 검증**(메모 `frontend-zod-backend-dto-contract-gap`).
- **FR-T-F4** MSW 핸들러 추가 — `GET /issues/:key/transitions`, `POST /issues/:key/transition`. stateful: 전환 후 후속 조회에 새 상태/가용전환 반영(기존 issue-handlers stateful 패턴 준수).
- **FR-T-F5** 에러 처리 — 409(전환 거부/충돌), 422(미설정)에 대한 사용자 메시지. i18n `ko` 문자열 추가.

### QA
- **FR-T-Q1** 프론트 Playwright E2E(`apps/web/e2e/issue-transition.spec.ts`) — happy path(S1) + 가용전환 필터(S2) + 에러(S3/S5 중 mock 가능 범위). 기존 `issue-fixtures.ts`/MSW 위에서 동작.
- **FR-T-Q2** 백엔드 통합 테스트(Testcontainers) — 실제 Postgres + 실제 워크플로우 시드. 이슈 생성 → 가용전환 조회 → 전환 실행 → 상태 갱신 확인. **mock 없이** 런타임 wiring 검증(메모 `issue-transition-backend-gap`이 우려한 "mock로만 통과" 갭 차단). cross-BC 마이그레이션 의존(메모 `bts-cross-bc-test-migration`) — issue-tracking + project-workflow 시드 둘 다 필요.
- **FR-T-Q3** 가용전환 validator 평가 검증 — 조건/권한 가드가 있는 전환이 actor 조건 미충족 시 가용전환 목록에서 **제외**되는지 통합 테스트. software-default엔 가드가 없으므로, 가드 있는 워크플로우(예: `PermissionValidator` 적용) 시드 또는 기존 검증 자원 활용. 풀버전 가치(조건 평가)의 실증.

## 비기능 요구사항 (NFR)
- **NFR1** 전환 컨트롤 WCAG AA — 터치 타깃 min-h 44px, 키보드 접근(기존 UI 컨벤션).
- **NFR2** 가용전환 조회는 이슈 상세 진입 시 1회(또는 전환 성공 후 무효화 재조회). 불필요한 폴링 금지.
- **NFR3** 전환 실행 중 컨트롤 disabled(중복 클릭 방지).

## API 인터페이스 (REST)
```
GET  /api/v1/issues/{key}/transitions
  200 { data: { transitions: [{ fromStateKey, toStateKey, name, key }] } }
  404 이슈 없음/삭제   422 워크플로우 미설정

POST /api/v1/issues/{key}/transition           (기존)
  body { toStatusKey: string, expectedVersion: number }
  200 { data: IssueResponse }
  404 / 409(전환 거부·낙관락 충돌) / 422
```

## 데이터 모델 변경
없음. 신규는 **읽기 전용 조회 엔드포인트**뿐. 스키마/마이그레이션 변경 없음.

## 엣지 케이스
- E1 가용 전환 0건(종료 상태) → 컨트롤 비노출/비활성 (S6).
- E2 전환 실행 직후 재조회 전 다시 클릭 → disabled로 차단(NFR3).
- E3 같은 (from,to) 이름이 둘? — transition identity는 (from,to)라 unique(ADR 준수). name은 표시용.
- E4 currentStateKey 케이스 — 백엔드 resolved 소문자(`open`), UI도 그대로 사용. 대문자 OPEN 잔재 없음(PR #28에서 정렬).
- E5 워크플로우 미설정(422)과 전환 0건(정상, 종료 상태)은 구분해서 안내.

## 제약 조건
- BC 격리 — 한 PR = issue-tracking BC. project-workflow는 기존 `WorkflowKeyResolver` SPI로만 소비, 직접 import 금지.
- 완제품 품질(CLAUDE.md §작업 기준). TDD red→green 강제.
- transition identity = (fromStateKey, toStateKey). transitionName 식별 사용 금지(ADR 2026-05-28).

## 측정 가능한 완료 기준
1. `GET /issues/{key}/transitions`가 현재 상태 출발 전환만 반환(통합 테스트 통과).
2. 이슈 상세에서 전환 선택 → 상태 배지 갱신(프론트 E2E happy path 통과).
3. 가용 전환만 노출(E2E S2 통과).
4. 백엔드 통합 테스트가 mock 없이 이슈 생성→전환→상태 갱신을 실제 DB+워크플로우 시드로 검증 통과.
5. `pnpm verify`(lint+typecheck+test+build) + `./gradlew test` 그린.
6. Zod 스키마 ↔ backend DTO 정합 grep 검증 완료.

## Brainstorming Check

✅ 통과 (Phase B에서 3건 gap 발견·해소).
1. **가용전환 SPI 부재 발견** — `WorkflowTransitionPort`엔 `plan`(단일 검증)만 있고 enumerate 없음. → 가용전환 공급 방식을 Maxi에게 옵션 제시(B 전환 후보 vs A) → 지라 레퍼런스 검토 후 **옵션 A 풀버전 확정**. SPI 확장(FR-T-S1) + project-workflow 구현(FR-T-P1) 추가.
2. **이슈가 workflow_key 미영속 발견** — 전환마다 `resolveStart`로 재resolve. → 가용전환 엔드포인트도 동일하게 서버에서 resolve(클라이언트 workflowKey 의존 불필요).
3. **조건부 전환 미고려 발견** — software-default엔 가드 없으나 모델은 SpEL/validator 지원. → 풀버전에서 validator 평가 포함 + FR-T-Q3로 실증.

남은 가정(plan/impl에서 확정).
- 통합 테스트의 기본 프로젝트가 어떤 워크플로우로 resolve되는지(software-scheme 자동 배정 D10) — 테스트는 resolve 결과에 robust하게 단언.
- FR-T-Q3용 가드 워크플로우 시드 방법(기존 시드 재활용 vs 테스트 전용 시드).
