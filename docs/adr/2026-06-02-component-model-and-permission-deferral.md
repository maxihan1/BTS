# ADR — Component 도메인 모델 + 권한 가드 리졸버 포트 이연 (FR-CM-01)

> 날짜: 2026-06-02
> 상태: 채택
> BC: issue-tracking
> 관련 FR: FR-CM-01 (컴포넌트 CRUD), 선행 FR-IS-01 / FR-PM-02, 후속 FR-PM-03

## 맥락

FR-CM-01은 프로젝트별 컴포넌트(프로젝트 내 하위 영역 분류) CRUD + 컴포넌트 리드를 구현한다.
권한("누가 컴포넌트를 관리하나")은 **FR-PM-03(버전/컴포넌트 등록 권한)**이 담당하도록 plan에 명시돼
있다(§4.3 D4 "@PreAuthorize 추가"). 따라서 FR-CM-01과 FR-PM-03의 책임 경계를 정해야 한다.

기존 패턴 조사:
- IssueController 쓰기 경로는 컨트롤러에 권한 호출이 없고, FR-IS-01이 enforcement를
  `AlwaysAllowIssuePermissionResolver`(비prod 통과)로 두고 실 RBAC를 후속 FR로 이연했다.
  그 후속이 FR-PM-02의 `IdentityAccessIssuePermissionResolver`(@Profile prod)다.
- `IssuePermissionResolver` 포트는 PR #53에서 shared-kernel(`com.bts.shared.permission`)로 이동했다.
- "PROJECT_ADMIN 인지"를 issue-tracking에서 묻는 cross-BC 포트는 아직 없다(ProjectMembership은 identity-access 전용).

## 결정

### D1 — components 테이블은 issue-tracking BC, projects를 실 FK로 참조
컴포넌트는 issue-tracking이 소유한 `projects`를 참조하므로 **같은 BC → 실 FK 적용 가능**
(reporter/assignee처럼 FK 생략할 이유 없음). 컬럼: `id`(UUID PK), `project_id`(FK→projects),
`name`, `description`(nullable), `lead_user_id`(nullable), `created_at`/`updated_at`,
`deleted_at`(soft delete, DATA.md §3). 활성 행 기준 `(project_id, name)` 유일.

### D2 — 권한 가드는 리졸버 포트 패턴으로 FR-PM-03에 이연
컴포넌트 CRUD 권한 판정을 `ComponentPermissionResolver` 포트로 추상화한다(IssuePermissionResolver 동형).
- 포트 정의 위치: shared-kernel(cross-BC 계약, IssuePermissionResolver 선례) — 모듈 배치 세부는 plan에서 확정.
- 비prod: AlwaysAllow 구현(개발/테스트 통과).
- `!prod` fallback 빈 + 부팅 가드(메모리 profile-scoped-bean-boot-failure: @Profile prod 단독 주입 시 비prod 컨텍스트 부팅 실패 → 연쇄 깨짐 방지).
- prod 실 판정 구현: **FR-PM-03**이 채운다(permission_schemes 매트릭스 기반).

근거: 이슈 권한(FR-IS-01→FR-PM-02)과 동일 구조라 일관적이고, FR-PM-03 도착 시 prod impl만 끼우면
되어 재작업이 없다. PROJECT_ADMIN 고정은 FR-PM-03의 scheme 매트릭스로 재작업될 위험, 인증만 두는 방식은
포트 스캐폴딩 부재로 FR-PM-03이 통째 신설해야 함. (Maxi 결정 2026-06-02.)

### D3 — 컴포넌트 CRUD는 인증 필수 + 프로젝트 존재 + 리드 사용자 검증
- 인증(JWT/PAT) 필수.
- 프로젝트 존재 검증: issue-tracking이 projects를 소유하므로 자기 BC 직접 조회.
- 리드 사용자 검증: `lead_user_id`는 identity-access users 참조라 **BC 격리상 FK 없이**
  `UserLookupPort`(shared-kernel, FR-IS-03 담당자 선례)로 존재 검증 → 없으면 422 `COMPONENT_LEAD_NOT_FOUND`
  (ASSIGNEE_NOT_FOUND 선례 동형).

### D4 — 리드는 선택값(nullable)
컴포넌트는 리드 없이도 생성 가능(Jira 동일). 리드 지정/해제는 PATCH로.

## 결과

- FR-CM-01: 컴포넌트 도메인 + 테이블 + CRUD API + 인증/검증 가드 + AlwaysAllow 리졸버 + 백엔드 테스트.
- FR-PM-03: `ComponentPermissionResolver` prod 구현 + permission_schemes에 컴포넌트 관리 권한 추가.
- 임시 보안 포스처: FR-PM-03 이전엔 인증된 사용자면 컴포넌트 CRUD 가능. Phase 1(운영 배포 전)이라 수용.
- plan §4.3 FR-PM-03 선행에 "기능 선행 FR-CM-01/FR-VR-01" 누락 → 별도 정정 필요(이 PR 범위 밖, 마킹 정정과 함께).
