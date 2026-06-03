# FR-PM-03 — 버전/컴포넌트 등록 권한 — 스펙

> slug: fr-pm-03-version-component-permissions · BC: identity-access · type: auth
> 도메인 ADR: docs/decisions/2026-06-03-version-component-permission-prod-resolver.md
> 선례: FR-PM-02(IdentityAccessIssuePermissionResolver, PR #53)

## 배경 / 범위

FR-CM-01/FR-VR-01이 컴포넌트·버전 CRUD를 구현하며 권한 판정을 두 포트
(`ComponentPermissionResolver`, `VersionPermissionResolver`, shared-kernel)로 추상화하고
prod 실판정을 FR-PM-03으로 이연했다. 현재 prod 프로파일엔 구현이 없어 운영 부팅이 차단되고,
non-prod는 `AlwaysAllow*PermissionResolver`(`@Profile("!prod")`)가 통과시킨다.

**이번 범위(D1~D5 backend 중심)**
- prod 리졸버 2개 추가(identity-access BC) — 멤버 게이트 + 권한 매트릭스 판정.
- 권한 코드 매핑 2개(ComponentPermission/VersionPermission → `MANAGE_COMPONENTS`/`MANAGE_VERSIONS`).
- Flyway V009 — 기본 스킴에 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 시드(PROJECT_ADMIN 전용).
- 권한 매트릭스 테스트(허용/거부 양 경로) + 부팅 테스트 + 마이그레이션 테스트.

**이번 범위 밖(별도 D6/D7 또는 후속 FR)**
- 호출부(VersionApplicationService/ComponentApplicationService)는 **이미 포트를 호출**하므로 변경 없음.
- 403/404 매핑은 서비스/예외 핸들러가 이미 처리(VersionAccessDeniedException → 403, *ProjectNotFound → 404).
- D6 프론트 UI(권한 없는 관리 버튼 비활성화) + D7 E2E.
- 비기본 스킴에 MANAGE_* 부여하는 스킴 편집 API/UI(스킴 관리 후속 FR).

## 사용자 시나리오 (Given-When-Then)

- S1. **PROJECT_ADMIN이 버전 생성** — Given prod 프로파일 + actor가 해당 프로젝트의 PROJECT_ADMIN 멤버,
  When `POST /projects/{key}/versions`, Then 201(리졸버 true).
- S2. **MEMBER가 버전 생성 시도** — Given actor가 MEMBER 역할 멤버(기본 스킴에 MANAGE_VERSIONS 미부여),
  When `POST .../versions`, Then 403(리졸버 false). **핵심 거부 경로 — 반드시 테스트.**
- S3. **비멤버가 컴포넌트 수정 시도** — Given actor가 프로젝트 멤버 아님,
  When `PATCH .../components/{id}`, Then 403(멤버 게이트 false). 역할 무관 거부.
- S4. **누구나 버전/컴포넌트 조회** — Given 인증된 actor(멤버/비멤버 무관),
  When `GET .../versions`, Then 200(READ는 게이트 없음, Jira 정책).
- S5. **존재하지 않는 프로젝트** — When `POST /projects/UNKNOWN/versions`,
  Then 404(서비스 resolveProject 단계, 리졸버 도달 전).
- S6. **non-prod 동작 불변** — Given dev/test 프로파일, When 임의 CRUD,
  Then AlwaysAllow stub이 통과(기존 동작 유지, 부팅 정상).

## 기능 요구사항 (FR)

- FR1. `IdentityAccessComponentPermissionResolver`(`@Component @Profile("prod")`)는
  `ComponentPermissionResolver`를 구현하고, `hasPermission(actorId, permission, projectId)`에서
  (a) `ProjectMembershipRepository.findByProjectAndUser(projectId, actorId)`가 null이면 false 반환,
  (b) 아니면 `PermissionSchemeRepository.roleHasPermission(projectId, membership.role.name, "MANAGE_COMPONENTS")` 반환.
- FR2. `IdentityAccessVersionPermissionResolver` — FR1과 동형, 코드만 `MANAGE_VERSIONS`.
- FR3. 권한 코드 매핑 — `ComponentPermission.{CREATE,UPDATE,DELETE}` 모두 `MANAGE_COMPONENTS`,
  `VersionPermission.{CREATE,UPDATE,DELETE}` 모두 `MANAGE_VERSIONS`. `when` else 없이 3종 전부 명시
  (enum drift를 컴파일 에러로 차단). 세 값이 같은 코드라 non-null 반환.
- FR4. Flyway `V009__manage_components_versions_permissions.sql` — 기본 스킴
  (`00000000-0000-0000-0000-000000000001`)에 `('PROJECT_ADMIN','MANAGE_COMPONENTS')`,
  `('PROJECT_ADMIN','MANAGE_VERSIONS')` 2행 INSERT. MEMBER 미부여.
- FR5. `@Profile` 배타성 — prod impl은 `@Profile("prod")`, 기존 stub은 `@Profile("!prod")` 유지.
  non-prod 컨텍스트 부팅 시 prod 빈 부재로 인한 실패가 없어야 한다(stub이 항상 존재).

## 비기능 요구사항 (NFR)

- NFR1. 권한 가드 오버헤드 — 리졸버 호출당 쿼리 2개(멤버십 단건 + 매트릭스 단건), 인덱스 활용
  (`idx_project_memberships_project`, `idx_role_permissions_scheme_role`). identity-access §NFR 임계 10ms 내.
- NFR2. SQL 인젝션 방어 — 모든 쿼리는 기존 Jdbc 리포지토리(`:param` 바인딩) 재사용. 신규 SQL 없음.
- NFR3. BC 격리 — 소비자(issue-tracking)는 shared-kernel 포트만 의존. prod 구현은 identity-access 내부.
  ArchUnit 룰(issue-tracking이 `com.atlas.bts.identity.*` import 금지) 위반 없음.

## API 인터페이스 (REST)

신규 엔드포인트 **없음**. 기존 컴포넌트/버전 CRUD 엔드포인트의 권한 판정 결과만 prod에서 바뀐다
(이전: 인증되면 통과 → 이후: PROJECT_ADMIN 멤버만 관리).

## 데이터 모델 변경

- 신규 테이블 없음. `permission_schemes`/`role_permissions`(V008) 재사용.
- V009 시드 2행 추가(위 FR4). identity-access는 jOOQ 미사용(JdbcTemplate)이라 init_codegen 미러 불필요.
- V번호 충돌 없음(identity-access 최신 V008, 다음 V009). issue-tracking 마이그레이션과 별도 네임스페이스.

## 엣지 케이스

- EC1. **MEMBER 역할 거부** — 멤버이지만 MANAGE_* 미보유 → false. vacuous pass 방지를 위해
  테스트에서 명시 검증(메모리 best-effort-loop-permission-exception-nonprod-mask, archunit-vacuous-rule-silent-pass).
- EC2. **비기본 스킴** — `project_permission_scheme`로 비기본 스킴이 매핑된 프로젝트는 그 스킴에
  MANAGE_* 행이 없으면 PROJECT_ADMIN도 거부. 현재 스킴 할당 API/UI 부재로 실사용 0이나 동작은 명세대로
  (roleHasPermission fallback 규칙). ADR "미해소"로 기록.
- EC3. **prod 빈 미조립** — production BC 조립 앱이 아직 없음(메모리 no-cross-bc-deployment-assembly).
  prod 와이어링은 **test-assembled boot**로만 검증 가능. 본 FR은 리졸버 단독(identity-access Testcontainers)
  + 부팅 빈 등록 테스트까지 책임. 전체 cross-BC 결선은 BC 조립 FR 소관.
- EC4. **소프트 삭제 프로젝트** — 서비스 resolveProject가 deleted_at 제외하므로 리졸버 도달 전 404.
- EC5. **권한 enum 추가** — 향후 ComponentPermission/VersionPermission에 값 추가 시 매핑 함수
  `when`이 컴파일 에러 → drift 차단.

## 제약 조건

- 임시 우회 stub 금지(포트 KDoc). prod는 실 판정 빈만 허용.
- non-prod 동작/테스트 그린 유지(AlwaysAllow 경로 불변).
- TDD red→green 강제(테스트 커밋이 구현 커밋 선행).

## 측정 가능한 완료 기준

- [ ] prod 리졸버 2개 + 매핑 2개 구현, `@Profile("prod")`.
- [ ] V009 시드 마이그레이션(PROJECT_ADMIN × MANAGE_COMPONENTS/MANAGE_VERSIONS 2행).
- [ ] 매트릭스 테스트: (비멤버→false) × (MEMBER→false) × (PROJECT_ADMIN→true) × (Component/Version) × (CREATE/UPDATE/DELETE 대표) 전수, 양 경로 모두.
- [ ] 부팅 테스트: prod 프로파일에서 두 리졸버 빈 등록 확인(IssuePermissionResolverBootTest 동형).
- [ ] 마이그레이션 테스트: V009 적용 후 default 스킴 roleHasPermission(PROJECT_ADMIN, MANAGE_*) == true, (MEMBER, MANAGE_*) == false.
- [ ] non-prod 전체 테스트 그린 유지 + 모듈 detekt/ktlint 그린.
- [ ] (D6) 프론트 — MANAGE_* 미보유 시 관리 버튼 비활성화(FR-PM-01/02 권한 질의 UI 게이팅 선례 재사용).
- [ ] (D7) E2E — PROJECT_ADMIN 관리 가능 / MEMBER 차단 시나리오.

## Brainstorming Check

✅ 통과 (적대적 1-pass 자체 검토 — office-hours/brainstorming 인터랙티브 스킵은 메모리
bts-spec-office-hours-mismatch 근거: 정의된 FR + 도메인 grill 완료).

검토 결과 — 보강 반영 완료.
- 거부 경로(EC1 MEMBER, S3 비멤버)를 시나리오·완료기준에 명시(vacuous pass 차단).
- BC 조립 부재(EC3)로 prod 와이어링은 test-assembled boot까지만 책임 — 범위 명확화.
- V번호 충돌·init_codegen 미러 불요(identity-access JdbcTemplate) 명시.
- enum drift 차단(FR3 when 전수), @Profile 배타 부팅(FR5) 명시.

미결정(게이트에서 Maxi 확인) — **PR 분할**. backend D1~D5만 이번 PR vs D6/D7 프론트 포함.
FR-PM-02 선례는 분리(PR #53 backend / PR #55 frontend). non-prod는 AlwaysAllow라 D6 게이팅이
prod 전까지 cosmetic → backend 우선 머지 안전. 권장: 이번 PR backend D1~D5, D6/D7 후속.
