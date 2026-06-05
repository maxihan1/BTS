<!-- 워크플로우 스킴 컨트롤러 actor 결선 기술 스펙 -->
# 워크플로우 스킴 컨트롤러 actor 결선 — 스펙

> slug: fr-pm-04-actor-wiring · type: auth · BC: project-workflow
> 도메인 결정 A 확정(프레임워크 중립 Authentication 추출). ADR `2026-06-05-workflow-scheme-controller-actor-wiring`.

## 배경

스킴 컨트롤러 2개가 하드코딩 sentinel actor를 넘긴다. 인증 주체로 교체해 FR-PM-04 권한 판정기가
실제 주체를 평가하도록 한다. **prod 활성화가 아니라 코드 부채 해소 + test-assembled 검증**.

actor 생성 지점 7곳:
- `ProjectWorkflowSchemeController` — `ActorId(SYSTEM_ACTOR_UUID)` 2곳: `assignScheme`, `getAssignedScheme`.
- `WorkflowSchemeController` — `systemActor()` 5곳: `create`, `update`, `delete`, `addMapping`, `deleteMapping`.

각 지점은 actor를 (a) `permissionResolver.requirePermission(actor.toUuid(), ...)` 권한 평가와
(b) `applicationService.<op>(actor = actor, ...)` 앱 서비스 호출 양쪽에 전달한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (happy)**: Given 인증된 사용자(주체 UUID = U), When 스킴 mutating 엔드포인트 호출,
  Then actor = U → 권한 판정기가 U로 평가, 앱 서비스에 U 전달, `assignedBy` 등 응답에 U 기록.
- **S2 (익명 fail-closed)**: Given 인증 주체 없음/익명, When 엔드포인트 호출, Then 401 Unauthorized
  (actor 미해결 — 하드코딩 우회 제거). 권한 판정기에 sentinel을 넘기지 않는다.
- **S3 (형식 방어)**: Given Authentication은 있으나 `name`이 UUID 형식 아님, When 호출,
  Then 401(인증 주체 식별 불가, 방어적 거부).
- **S4 (인증 방식 무관)**: Given JWT든 PAT든 인증 필터가 채운 주체, When 호출, Then 동일하게
  주체 UUID를 actor로 사용(PAT 특별 분기 없음 — Maxi 확정, 필터 체인 위임).

## 기능 요구사항 (FR)

- **FR-1**: 7곳의 하드코딩 actor 생성을 제거하고 SecurityContext 인증 주체 UUID로 대체.
- **FR-2**: **단일 추출 지점**. `Authentication → ActorId` 변환을 한 곳(공통 헬퍼 또는 argument
  resolver)에 두어 7곳이 동일 로직 사용(drift 차단). 각 컨트롤러에 추출 로직 중복 금지.
- **FR-3**: 인증 주체 부재/미인증/익명 → 401(fail-closed). sentinel fallback 금지.
- **FR-4**: `authentication.name`을 UUID로 파싱. identity-access JWT subject 규약(= 사용자 UUID)에
  의존. 파싱 실패 → 401.
- **FR-5**: PAT 경로 특별 처리 없음. actor 추출은 인증 방식 무관.
- **FR-6**: 하드코딩 sentinel 상수(`SYSTEM_ACTOR_UUID`, `SYSTEM_ACTOR_UUID_STRING`)와 임시 주석 제거.

## 비기능 요구사항 (NFR)

- **NFR-1**: 신규 Gradle 의존성 0(oauth2-resource-server 추가 금지 — 결정 B 배제).
- **NFR-2**: 기존 테스트 회귀 0. `WorkflowControllerMvcTest`(@WithMockUser) 등 기존 슬라이스 유지.
- **NFR-3**: ktlint(`ktlintMainSourceSetCheck`/`ktlintTestSourceSetCheck`) + detekt 그린.
- **NFR-4**: 추출 지점은 test-assembled MockMvc(`webAppContextSetup` + `springSecurity()`)에서
  `@WithMockUser`/`SecurityMockMvcRequestPostProcessors`로 검증 가능해야 한다.

## API 인터페이스 (REST)

- **변경 없음**. 7개 엔드포인트의 경로·메서드·요청/응답 스키마 동일.
- 내부 actor 출처만 변경. 응답의 actor UUID 필드(`assignedBy`)가 sentinel → 실제 주체로 바뀜
  (test-assembled에서만 관측, prod 경로 부재).

## 데이터 모델 변경

- 없음. 마이그레이션 없음.

## 엣지 케이스

- 익명/`AnonymousAuthenticationToken` → 401(`isAuthenticated`가 true여도 anonymous면 거부).
- `Authentication == null`(SecurityContext 비어 있음) → 401.
- `authentication.name`이 UUID 형식 아님 → 401.
- 인증 주체는 정상이나 권한 없음 → 기존대로 권한 판정기가 403(이 작업 범위 밖, FR-PM-04 동작).

## 제약 조건

- 도메인 결정 A: 프레임워크 중립(`Authentication` 기반), `Jwt` 타입 의존 금지.
- BC 격리 유지: identity-access 직접 import 금지(Spring Security 프레임워크 타입만 사용).
- 추측성 코드 금지: 존재하지 않는 PAT 필터 경로용 분기 작성 금지.

## 측정 가능한 완료 기준

- [ ] 7곳 모두 인증 주체 추출 사용, 하드코딩 sentinel 상수·주석 제거(grep로 0 확인).
- [ ] 단일 추출 지점 1개 신설 + 단위/슬라이스 테스트(happy=UUID 추출, 익명=401, 비-UUID=401).
- [ ] mutating 엔드포인트 슬라이스 테스트에서 `@WithMockUser(username=UUID)` → actor 전파 검증.
- [ ] 기존 project-workflow 모듈 test 회귀 0(`:modules:project-workflow:test`).
- [ ] ktlint + detekt 그린.

## Brainstorming Check

직접 기술 스펙(메모리 `bts-spec-office-hours-mismatch` — 정의된 FR+grill 완료 작업은 office-hours
부적합). 자체 sanity-check로 검토한 잠재 gap:
- ✅ 추출 mechanism 형식(헬퍼 vs argument resolver)은 plan/impl(security-engineer)이 결정 —
  스펙은 "단일 지점 + test-assembled 검증 가능"만 규정.
- ✅ 익명/형식오류/null 3종 fail-closed 경로 명시(S2/S3 + 엣지).
- ✅ PAT 분기 추측성 코드 배제(Maxi 확정).
- ✅ assignedBy 응답 필드 의미 변화 기록(sentinel → 실제 주체).
