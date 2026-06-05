<!-- FR-CM-04 컴포넌트 리드 부재 시 프로젝트 리드 기본 담당자 폴백 기술 스펙 -->

# FR-CM-04 — 컴포넌트 리드 부재 시 프로젝트 리드 기본 담당자 폴백 — 스펙

> BC: issue-tracking · 선행: FR-CM-03(PR #84) · ADR: docs/adr/2026-06-06-project-lead-default-assignee-fallback.md
> 모델: `projects.lead_user_id` 컬럼(옵션 B, in-BC) · 범위: 폴백 로직 + 지정/해제 API(UI 후속)

## 배경 요약

FR-CM-03은 이슈 생성·컴포넌트 변경 시 컴포넌트 리드를 기본 담당자로 자동 배정한다(미할당일 때만,
다중이면 이름 사전순 첫 리드). 컴포넌트 리드가 없으면 미할당으로 남는다. FR-CM-04는 그 빈자리에
**프로젝트 리드**(`projects.lead_user_id`)를 2순위 폴백으로 채운다. admin ≠ lead.

폴백 체인: **컴포넌트 리드(1순위) → 프로젝트 리드(2순위) → 미할당**.

## 사용자 시나리오 (Given-When-Then)

- **S1 (회귀)**. Given 이슈에 리드 보유 컴포넌트가 연결됨, When 이슈 생성, Then 컴포넌트 리드가 담당자
  (프로젝트 리드는 무시 — 1순위 우선).
- **S2 (핵심)**. Given 연결 컴포넌트에 리드가 하나도 없고 프로젝트 리드가 지정됨, When 이슈 생성,
  Then 프로젝트 리드가 담당자.
- **S3**. Given 이슈에 컴포넌트가 전혀 없고 프로젝트 리드가 지정됨, When 이슈 생성, Then 프로젝트 리드가 담당자
  (`componentIds.isEmpty()` 경로도 폴백 적용).
- **S4**. Given 컴포넌트 리드 없음 + 프로젝트 리드 미지정, When 이슈 생성, Then 미할당(null).
- **S5 (덮어쓰기 금지)**. Given 담당자가 이미 지정됨(예: 클론 includeAssignee), When 생성/컴포넌트 변경,
  Then 폴백 전체 미적용 — 기존 담당자 유지(FR-CM-03 S3 규칙 계승).
- **S6**. Given 다중 컴포넌트가 모두 리드 없음 + 프로젝트 리드 지정됨, When 이슈 생성, Then 프로젝트 리드.
- **S7 (컴포넌트 변경 경로)**. Given 담당자 미할당 이슈의 컴포넌트를 리드 없는 것으로 교체 + 프로젝트 리드 지정됨,
  When `changeComponents`, Then 프로젝트 리드가 담당자(재배정).
- **S8 (지정 API)**. Given 유효 사용자 UUID, When `PATCH /api/v1/projects/{idOrKey}/lead {leadUserId}`,
  Then 200 + 프로젝트 리드 지정. 미존재 사용자면 422.
- **S9 (해제 API)**. Given 프로젝트 리드 지정됨, When `PATCH .../lead {leadUserId: null}`, Then 200 + 리드 해제.
- **S10 (소급 없음)**. Given 기존 이슈들 존재, When 프로젝트 리드 변경, Then 기존 이슈 담당자 재계산 안 함
  (자동배정 trigger는 이슈 생성·컴포넌트 변경 시점만, FR-CM-03 계승).

## 기능 요구사항 (FR)

- **FR1**. `projects` 테이블에 `lead_user_id UUID NULL` 컬럼 추가(FK 미적용 — BC 격리, `components.lead_user_id` 동형).
- **FR2**. 자동배정 폴백 체인: 컴포넌트 리드(이름 사전순 첫) → 프로젝트 리드 → 미할당.
- **FR3**. `current != null`이면 폴백 미적용(덮어쓰기 금지).
- **FR4**. 컴포넌트가 없는 이슈(`componentIds.isEmpty()`)에도 프로젝트 리드 폴백 적용.
- **FR5**. 프로젝트 리드 지정/해제 API — `PATCH /api/v1/projects/{projectIdOrKey}/lead`, 2-state
  (`leadUserId` null=해제, UUID=지정). `ChangeComponentLeadRequest`/`changeLead` 동형.
- **FR6**. 리드 사용자 실존 검증은 **지정 시점**에 ApplicationService가 수행(미존재 → 422).
  자동배정 시점에는 저장된 `lead_user_id`를 신뢰(컴포넌트 리드와 동일 — 배정 시 재검증 없음).
- **FR7**. 프로젝트 리드 변경은 기존 이슈에 소급 재배정하지 않는다.

## 비기능 요구사항 (NFR)

- **NFR1**. 자동배정 경로에 프로젝트 리드 조회 쿼리 1회 추가(PK 조회). 이슈 생성/컴포넌트 변경은 저빈도라 허용.
- **NFR2**. `init_codegen.sql`에 컬럼 미러(jOOQ 상수 생성). 누락 시 repository 컴파일 불가(메모리 jooq-init-codegen-mirror).
- **NFR3**. 순수 도메인 함수(`DefaultAssigneeResolver`)는 cross-BC 조회를 포함하지 않는다 — 프로젝트 리드는
  ApplicationService가 미리 조회해 파라미터로 주입.

## API 인터페이스 (REST)

```
PATCH /api/v1/projects/{projectIdOrKey}/lead
  body: { "leadUserId": "<uuid>" | null }
  200 → { "data": { ...projectId, leadUserId } }   # 지정/해제 결과
  404 → 프로젝트 미존재 (ProjectNotFound)
  422 → leadUserId 사용자 미존재 (ProjectLeadNotFound — ComponentLeadNotFound 동형)
```

권한 가드는 현행 컴포넌트 리드 지정과 동형(placeholder actor + SecurityConfig 401 보장). 실제 권한
범위(누가 프로젝트 리드를 지정할 수 있는가 — PROJECT_ADMIN/시스템 admin)는 plan에서 security-engineer 검토.

## 데이터 모델 변경

- `backend/modules/issue-tracking/.../db/migration/issue-tracking/V0XX__project_lead.sql`
  — `ALTER TABLE projects ADD COLUMN lead_user_id UUID NULL;` + COMMENT(컴포넌트 리드 동형 문구).
- `db/codegen/init_codegen.sql`의 `projects` 정의에 같은 컬럼 미러.
- V번호는 머지 직전 fetch+ls로 재확인(메모리 migration-vnumber-concurrent-branch-collision).

## 엣지 케이스

- **EC1**. 프로젝트 리드 == 어느 컴포넌트 리드 동일인 → 컴포넌트 리드(1순위)로 결정(동일 결과).
- **EC2**. 프로젝트 리드가 이후 비활성/삭제된 사용자 → 자동배정은 저장된 UUID를 그대로 배정(컴포넌트 리드 동형,
  지정 시점만 검증). 활성 검증을 자동배정에 넣지 않음(FR-CM-03 일관).
- **EC3**. 리드 지정 대상이 프로젝트 멤버가 아님 → 검증하지 않음(컴포넌트 리드 동형, 존재만 검증).
- **EC4**. 동일 리드 재지정(idempotent) → 200, 변경 없음.
- **EC5**. 잘못된 UUID 형식 path/body → 400(프레임워크 검증).
- **EC6 (클론 제외)**. 이슈 클론은 `resolveDefaultAssignee`를 거치지 않고 담당자를 직접 복사
  (`includeAssignee`면 원본 담당자, 아니면 null)한다. 따라서 클론에는 프로젝트 리드 폴백을 적용하지 않는다
  (FR-CM-03 자동배정도 클론 미적용 — 명시적 복사 시맨틱 보존). 회귀 0.
- **EC7 (확인 수단)**. 지정/해제 결과는 `PATCH .../lead` 200 응답 body의 `leadUserId`로 확인한다.
  프로젝트 단건 조회 응답에 `leadUserId` 상시 노출은 이번 범위 외(후속 — 지정 UI FR와 함께).

## 제약 조건

- BC 격리 — issue-tracking in-BC만 수정(projects/issues 자기 테이블). cross-BC 포트 없음.
- 이슈 키·소프트 삭제 등 기존 절대 규칙 불변.
- FR-CM-03 자동배정 동작(S1/S3 of FR-CM-03)은 회귀 0으로 보존.

## 측정 가능한 완료 기준

1. `DefaultAssigneeResolver` 폴백 우선순위 단위 테스트(S1·S2·S4·S5·S6 + EC1).
2. `IssueApplicationService` 통합 테스트(Testcontainers) — 생성 경로(S2·S3·S4) + 컴포넌트 변경 경로(S7).
3. 프로젝트 리드 지정/해제 API 통합 테스트 — 200(S8·S9) / 404 / 422.
4. `init_codegen.sql` 미러 후 jOOQ 컴파일 통과 + 모듈 전체 test 그린.
5. FR-CM-03 기존 테스트 회귀 0.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 2건 보강 — EC6(클론은 폴백 제외, FR-CM-03 일관) · EC7(확인 수단 = 지정 API 응답).
권한 범위(프로젝트 리드 지정 주체)는 plan에서 security-engineer 검토 항목으로 이관.
