# ADR — Version 도메인 모델 + 권한 가드 리졸버 포트 이연 (FR-VR-01)

> 날짜: 2026-06-03
> 상태: 채택
> BC: issue-tracking
> 관련 FR: FR-VR-01 (버전 생성 + 시작일/릴리즈 예정일), 선행 §2.1.1(프로젝트), 후속 FR-PM-03 / FR-VR-02
> 선례 ADR: 2026-06-02-component-model-and-permission-deferral (FR-CM-01) — 본 ADR은 그 구조를 동형 답습

## 맥락

FR-VR-01은 프로젝트별 버전(릴리스 단위) 생성 + 시작일/릴리즈 예정일 + 조회/수정/삭제 CRUD를 구현한다.
권한("누가 버전을 관리하나")은 **FR-PM-03(버전/컴포넌트 등록 권한)**이 담당하도록 plan에 명시돼
있다(identity-access §4.3 D4 "@PreAuthorize 추가"). 또한 FR-VR-01은 FR-PM-03의 **기능 선행 FR**이다
(identity-access §4.3 메모: "FR-PM-03 착수 전 FR-VR-01 필요").

선례(거의 1:1 답습 대상):
- FR-CM-01(컴포넌트)이 `com.bts.issue.component` 패키지에 도메인/리포지토리/애플리케이션/웹 레이어 + `AlwaysAllowComponentPermissionResolver`(비prod 통과) 구조를 확립(PR #59).
- 권한 포트는 `IssuePermissionResolver`(shared-kernel, PR #53) → `ComponentPermissionResolver`(FR-CM-01) 동형 계보.

## 결정

### D1 — versions 테이블은 issue-tracking BC, projects를 실 FK로 참조
버전은 issue-tracking이 소유한 `projects`를 참조하므로 **같은 BC → 실 FK 적용**(FR-CM-01 D1 동형).
컬럼: `id`(UUID PK), `project_id`(FK→projects), `name`, `description`(nullable),
`start_date`(DATE nullable), `release_date`(DATE nullable), `created_at`/`updated_at`,
`deleted_at`(soft delete, DATA.md §3). 활성 행 기준 `(project_id, name)` 부분 유니크.

컴포넌트와의 유일한 차이: 컴포넌트의 `lead_user_id`(identity-access users 참조) 자리에
버전은 **날짜 두 컬럼**(`start_date`, `release_date`)이 들어간다. 따라서 버전은 cross-BC 사용자 참조가
없어 `UserLookupPort` 의존이 불필요하다.

### D2 — 권한 가드는 리졸버 포트 패턴으로 FR-PM-03에 이연
버전 CRUD 권한 판정을 `VersionPermissionResolver` 포트로 추상화한다(ComponentPermissionResolver 동형).
- 포트/AlwaysAllow 구현 위치: `com.bts.issue.version.adapter`(컴포넌트 선례와 동일 모듈 배치).
- 비prod: AlwaysAllow 구현(개발/테스트 통과).
- `!prod` fallback 빈 + 부팅 가드(메모리 profile-scoped-bean-boot-failure: @Profile prod 단독 주입 시 비prod 컨텍스트 부팅 실패 → 연쇄 깨짐 방지).
- prod 실 판정 구현: **FR-PM-03**이 채운다(permission_schemes 매트릭스 기반).

근거: FR-CM-01과 완전 동형이라 일관적이고, FR-PM-03 도착 시 컴포넌트/버전 두 리졸버의 prod impl만
끼우면 되어 재작업이 없다.

### D3 — 버전 CRUD는 인증 필수 + 프로젝트 존재 검증
- 인증(JWT/PAT) 필수.
- 프로젝트 존재 검증: issue-tracking이 projects를 소유하므로 자기 BC 직접 조회(ProjectLookup 선례 재사용).
- 사용자 참조 없음(리드 개념 부재) → UserLookupPort 불필요.

### D4 — 날짜 두 필드는 선택값(nullable) + 순서 미강제
`start_date`/`release_date`는 둘 다 선택값. **둘 다 입력돼도 `start_date ≤ release_date` 순서를 강제하지 않는다**
(Jira 기본 동작 동일, Maxi 결정 2026-06-03). 순서 경고는 향후 UI(D6) 보완 영역. name은 필수,
trim 후 1~255자(컴포넌트 MAX_NAME 동형). 날짜 타입은 시각 무관 날짜 전용이라 `LocalDate`(DB `DATE`).

### D5 — status(Unreleased/Released/Archived)는 FR-VR-02 이연
plan(issue-tracking §3.2.2 FR-VR-02 D3)이 `versions.status` 컬럼 추가를 FR-VR-02 소관으로 명시한다.
따라서 FR-VR-01은 status 컬럼/필드 없이 생성+날짜+CRUD만 다룬다.

## 결과

- FR-VR-01: 버전 도메인(Version Aggregate) + `versions` 테이블 + CRUD API + 인증/프로젝트 검증 가드 + AlwaysAllow 리졸버 + 백엔드 테스트. (백엔드 D1~D5, 프론트 D6/E2E D7은 후속 PR)
- FR-PM-03: `VersionPermissionResolver`(+ `ComponentPermissionResolver`) prod 구현 + permission_schemes에 버전 관리 권한 추가.
- FR-VR-02: `versions.status` 컬럼 + 상태 전환.
- 임시 보안 포스처: FR-PM-03 이전엔 인증된 사용자면 버전 CRUD 가능. Phase 1(운영 배포 전)이라 수용.
