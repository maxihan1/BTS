# FR-PM-06 PR-B — 이슈 보안 수준 판정 결선 (스펙)

> slug: fr-pm-06-pr-b-security-decision · type: api · PR #90
> 선행: PR-A(#86, 관리 인프라) 머지. ADR `2026-06-06-issue-security-level-scheme-model.md` 정본.
> office-hours 스킵(정의된 FR + 확정 ADR, bts-spec-office-hours-mismatch 교훈) → 직접 기술 스펙.

## 배경 한 줄

PR-A가 "누가 이 등급을 볼 수 있나"의 **관리 인프라**(스킴→등급→멤버 5타입)를 만들었다. PR-B는 그 등급을 **이슈에 붙이고**, 등급 멤버가 아닌 사용자에게 이슈를 **실제로 가린다**(단건 404 + 목록 제외).

## 사용자 시나리오 (Given-When-Then)

**S1 — 등급 멤버 단건 조회 통과**
- Given 이슈 A가 보안등급 L(멤버: USER=bob) 지정, bob은 프로젝트 멤버(VIEW_ISSUE 통과)
- When bob이 `GET /api/v1/issues/ATLAS-1`
- Then 200, 이슈 본문 반환.

**S2 — 비멤버 단건 조회 404**
- Given 이슈 A가 L 지정, carol은 프로젝트 멤버지만 L 멤버 아님
- When carol이 `GET /api/v1/issues/ATLAS-1`
- Then **404**(403 아님 — 존재 숨김, `assertViewIssueOrNotFound` 일관).

**S3 — 등급 NULL = 공개**
- Given 이슈 B는 `security_level_id = NULL`
- When VIEW_ISSUE 매트릭스 통과하는 임의 사용자가 조회
- Then 200(등급 게이트 적용 안 함).

**S4 — REPORTER/ASSIGNEE 동적 멤버**
- Given 이슈 C가 등급 L'(멤버: REPORTER) 지정, dave가 C의 보고자
- When dave가 조회
- Then 200(actor==reporter_id). dave가 보고자 아니면 404.

**S5 — GROUP 멤버**
- Given 등급 L''(멤버: GROUP=g1), erin이 g1 소속(FR-PM-09)
- When erin이 조회
- Then 200.

**S6 — PROJECT_ROLE 멤버**
- Given 등급 L'''(멤버: PROJECT_ROLE=PROJECT_ADMIN), frank가 해당 프로젝트 PROJECT_ADMIN
- When frank가 조회
- Then 200.

**S7 — 관리자 우회 없음**
- Given 이슈 A가 L 지정, sysadmin은 SYSTEM_ADMIN이지만 L 멤버 아님
- When sysadmin이 조회
- Then **404**(ADR §결정 5 — 등급 멤버십이 유일 통과 경로).

**S8 — 목록에서 비멤버 이슈 제외**
- Given 프로젝트에 이슈 10개(3개는 carol이 멤버 아닌 등급), carol이 BROWSE 통과
- When carol이 `GET /api/v1/issues?projectKey=ATLAS`
- Then 보이는 이슈 7개(가린 3개는 목록·총개수에서 제외). 페이지네이션 정합 유지.

**S9 — 이슈에 등급 지정**
- Given alice가 SET_ISSUE_SECURITY 권한 보유, 등급 L은 프로젝트 적용 스킴 소속
- When alice가 생성 시 `securityLevelId=L` 또는 `PATCH`로 지정
- Then 201/200, `issues.security_level_id = L`.

**S10 — 지정 권한 없음**
- Given bob은 EDIT_ISSUE는 있으나 SET_ISSUE_SECURITY 없음
- When bob이 `securityLevelId` 포함 요청
- Then **403**(SET_ISSUE_SECURITY 가드).

**S11 — 적용 스킴 미소속 등급 지정**
- Given 등급 X가 프로젝트의 적용 스킴에 속하지 않음(다른 스킴/미적용)
- When alice가 `securityLevelId=X` 지정
- Then **422**(스킴 정합 검증 실패).

**S12 — 등급 해제**
- Given 이슈 A가 L 지정
- When alice가 `securityLevelId=null`(클리어 의미)로 PATCH
- Then 200, `security_level_id = NULL`(공개 복귀). merge-patch 3-state 주의(부재=무변경 vs 명시 null=클리어).

## 기능 요구사항 (FR)

- **FR1** issues에 `security_level_id UUID NULL` 추가(마이그레이션 + init_codegen.sql 미러).
- **FR2** 이슈 생성/편집 시 `securityLevelId` 수용. 설정·변경·해제 시 `SET_ISSUE_SECURITY` 가드(IssueScope.Project/Issue). 미보유 403.
- **FR3** 지정 등급이 프로젝트 적용 스킴 소속이 아니면 422.
- **FR4** 판정: `VIEW_ISSUE` 매트릭스 통과 AND (등급 NULL OR actor가 등급 멤버 5타입 충족). 미통과 단건 → 404.
- **FR5** 멤버 충족 5타입: REPORTER(=reporter_id) / ASSIGNEE(=assignee_id) / USER(=member_value UUID) / GROUP(actor∈그룹) / PROJECT_ROLE(actor의 ProjectMembership.role==member_value).
- **FR6** 목록(listIssues)에서 비멤버 이슈 제외(총개수·content 모두). 페이지네이션 정합.
- **FR7** 관리자 우회 없음(SYSTEM_ADMIN/PROJECT_ADMIN도 등급 멤버 아니면 거부).
- **FR8** cross-BC `IssueSecurityLookup` 포트로 issues의 security_level_id/reporter_id/assignee_id read(ProjectDirectory 동형, raw SQL, 직접 import 금지).

## 비기능 요구사항 (NFR)

- **NFR1 (성능)** 목록 필터 N+1 금지. 단건 판정도 라운드트립 최소화. 등급 미사용 프로젝트(스킴 미적용)는 추가 쿼리 0 또는 최소(빠른 경로).
- **NFR2 (보안 ground-truth)** non-prod `AlwaysAllowIssuePermissionResolver`가 판정을 통과시켜 마스킹 → 거부 시나리오(S2/S7)는 **prod 프로파일 Testcontainers 통합**으로만 검증(issue-scope-global-prod-hard-deny 교훈).
- **NFR3 (정보 누수 방지)** 거부는 항상 404, 403 아님. 등급 존재·이름 등 어떤 메타도 비멤버에게 노출 금지.
- **NFR4 (BC 격리)** issue-tracking↔identity-access 직접 import 0. shared-kernel 포트 또는 raw SQL read 포트만. ArchUnit 룰 유지.

## API 인터페이스 (REST)

- `POST /api/v1/issues` — `CreateIssueRequest`에 `securityLevelId: UUID?` 추가(옵션).
- `PATCH /api/v1/issues/{key}` — `UpdateIssueRequest`에 `securityLevelId` 추가. merge-patch 3-state(부재=무변경 / 명시 null=클리어 / 값=지정).
- `GET /api/v1/issues/{key}` · `GET /api/v1/issues?projectKey=` — 판정/필터 적용(요청 형태 불변).
- 신규 전용 엔드포인트는 만들지 않음(생성/편집에 필드 흡수, Jira 동일).

## 데이터 모델 변경

- `issues.security_level_id UUID NULL`(FK 미적용 — 등급은 identity-access 소유, BC 격리). issue-tracking 마이그레이션 V0NN + `init_codegen.sql` 미러.
- identity-access 신규 테이블 0(PR-A 테이블 read만).
- 신규 조회 메서드(identity-access): 등급의 프로젝트 적용 스킴 소속 확인(`ProjectSecuritySchemeRepository`/`IssueSecuritySchemeRepository` 확장), actor의 그룹 단건 소속 확인(`UserGroupRepository.isMemberOf` 등 성능용).

## 엣지 케이스

- 등급 NULL = 공개(게이트 미적용).
- 이슈의 등급이 사후에 스킴에서 삭제됨(고아 level_id) — 판정 시 멤버 조회 0 → 비멤버 취급(보수적 차단). 또는 정합 보장? → **갈림길 외, 보수적 차단으로 결정**(민감 이슈는 막는 쪽이 안전).
- merge-patch 3-state 혼동(부재 vs null) — FR-IS-04 description 선례 패턴 재사용.
- PROJECT_ROLE member_value가 비멤버 actor — ProjectMembership 없음 → 불충족.
- 다중 등급 동시 충족(REPORTER이면서 USER) — OR이므로 하나라도 충족하면 통과.

## 측정 가능한 완료 기준

- prod Testcontainers 통합 S1~S12 전부 그린(특히 S2/S7 거부 404, S8 목록 제외+총개수 정합).
- 단위: 멤버 충족 판정 순수 함수 5타입 + 경계(NULL, 고아 level).
- 목록 N+1 없음(쿼리 카운트 단언 또는 단일 쿼리 확인).
- ktlint/detekt 0, ArchUnit BC 격리 룰 그린.
- verify-master-plan FR 카운트 불변(121, FR 추가 없음).

## 설계 갈림길 (게이트1 Maxi 결정 — plan 구조에 영향)

### 갈림길 1 — 목록 필터 전략 (NFR1 직결)
보안등급 멤버 데이터는 identity-access 소유, 목록 SQL은 issue-tracking. 둘을 잇는 방식.

- **옵션 A — SQL 술어 푸시다운(신규 cross-BC 포트)**: identity-access가 actor 기준 "접근 가능 등급 집합 + REPORTER/ASSIGNEE 적용 등급 집합"을 계산해 issue-tracking 목록 쿼리 WHERE로 푸시(`security_level_id IS NULL OR IN(:static) OR (IN(:reporterLv) AND reporter_id=:actor) OR (IN(:assigneeLv) AND assignee_id=:actor)`). **페이지네이션 정합 유지 + N+1 0**. 비용: cross-BC 포트 1개(shared-kernel, IssuePermissionResolver 동형 방향) + SQL 복잡도. ★권장
- **옵션 B — 후처리 배치 필터**: 페이지 조회 후 (id,level,reporter,assignee) 배치로 판정, 비멤버 제외. 단순하지만 **페이지네이션 깨짐**(20개 중 일부 제외→페이지 크기·총개수 부정확) → over-fetch/재조회 루프 필요, 정합 보장 어려움. 비권장.

### 갈림길 2 — 등급 지정 422 검증 경로 (FR3)
- **옵션 A — IssueSecurityLookup 포트에 `levelBelongsToProjectScheme(levelId, projectId)` 추가**, issue-tracking이 지정 전 호출. cross-BC read 일관, 단순. ★권장
- 옵션 B — identity-access에 검증 엔드포인트/서비스 위임(왕복 추가). 비권장.

### 갈림길 3 — IssueController actor 결선 범위 (★범위 결정)
현재 `IssueController`가 `SYSTEM_ACTOR_UUID` 고정(FR-PM-04 C2 미해소 부채). 보안 수준은 **실제 로그인 사용자별** 판정이 핵심이라 고정 actor면 prod에서 무의미.

- **옵션 A — PR-B가 IssueController actor 결선 포함**(`CurrentActor` 패턴, PR #82 WorkflowSchemeController 선례 복제). 보안 수준이 prod에서 실제 작동. 범위↑(컨트롤러 7~N곳 actor 치환 + 인증/없는리소스 401 회귀). end-to-end 의미 있음.
- **옵션 B — PR-B는 판정 결선(service/resolver)만**, 컨트롤러 actor는 C2 부채로 별도 후속. 판정 ground-truth는 **service 계층 prod 통합테스트**(임의 actor 주입)로 검증. 범위↓·집중. 단 prod 컨트롤러는 후속 PR 전까지 고정 actor라 실사용 미완.
- (참고) FR-PM-05·FR-PM-04 등 기존 권한 FR도 같은 C2 부채 위에서 service 계층 검증으로 머지됨 → 옵션 B가 기존 리듬과 일관.
