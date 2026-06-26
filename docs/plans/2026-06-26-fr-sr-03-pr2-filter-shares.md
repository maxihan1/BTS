# FR-SR-03 PR2 — 저장 필터 공유

> slug: fr-sr-03-pr2-filter-shares
> type: backend
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-sr-03 pr2 진행해줘" (저장 필터 공유 기능)

FR-SR-03 "필터 저장 및 공유"의 2번째 PR. PR1(#191)에서 SavedFilter CRUD(PRIVATE 전용) + 실행을 완료했고, PR2는 **공유** 기능을 담당한다.

PR1 완료 메모리 기준 PR2 범위(잠정 — bts-spec에서 확정).
- **공유 = 대상 지정** 방식(PROJECT / GROUP / AUTHENTICATED, 지라식). `saved_filter_shares` 조인 테이블 신설.
- **cross-BC 멤버십 포트** 2종(GroupMembership / ProjectAccess) 신설 — 둘 다 shared-kernel에 부재.
- **가시성 4경로**(소유 / PROJECT 공유 / GROUP 공유 / AUTHENTICATED 공유).
- 공유받은(소유 아님) 필터용 **403 재도입**(spec EC4). PR1에서 데드코드로 제거했던 `SavedFilterForbiddenException` + handleForbidden을 visible-but-not-owner 용도로 부활.
- 별표 UI 연동(FR-UX-02 favorites 재사용) — 프론트 범위 여부 spec에서 확정.

classify 결과: type=backend, agent=backend-engineer, slug=fr-sr-03-pr2-filter-shares.

## 도메인 정리

- **BC**: search-export-import (PR1에서 첫 영속성 부트스트랩 완료, PR2는 공유 확장)
- **영향 엔티티**:
  - `SavedFilter` (PR1 기존) — 변경 없음(본체).
  - `SavedFilterShare` (신규) — 공유 대상 1행. `saved_filter_shares(filter_id, share_type, target_id)`.
- **새 cross-BC 포트 (shared-kernel, 신규)** — phantom 아님, 직접 조사로 부재 확인.
  - `GroupMembershipPort` — "사용자가 특정 그룹 소속인가". identity-access 구현(`com.atlas.bts.identity.group.UserGroupRepository` 위임).
  - `ProjectMembershipPort` — "사용자가 특정 프로젝트(키) 멤버인가". identity-access 구현(`ProjectMembershipRepository` 위임). **기존 포트 재사용 불가** 확정:
    - `IssueVisibilityPort`(shared-kernel)는 이슈 VIEW 가시성 전용 — 프로젝트 멤버 판정 아님.
    - `ProjectMembershipRepository`(identity-access)는 BC 내부 전용(노출 포트 아님) + `project_id UUID` 키. 공유는 `project_key` 문자열 저장 → 키↔id 변환 포트 필요.
- **확립된 결정 (PR1 ADR `2026-06-26-fr-sr-03-saved-filters.md` D2/D3/D4 — Maxi 확정)**:
  - D2 공유 모델: 대상 지정(PROJECT=project_key / GROUP=group id / AUTHENTICATED=로그인 전체). view 전용 MVP(소유자만 수정/삭제, 공유받은 사용자는 조회/실행/복제).
  - D3 가시성 판정: OR 4경로(소유 / AUTHENTICATED / PROJECT 멤버 / GROUP 소속). **fail-closed**(멤버십 포트 default 금지, 빈 부재 시 부팅 실패 — `IssueVisibilityPort` 선례).
  - D4 부트스트랩: PR1에서 완료(V600). PR2는 V601 `saved_filter_shares`.
- **403 재도입**: PR1에서 데드코드로 제거한 `SavedFilterForbiddenException`(현재 예외 계층에 없음)을 부활. 의미 = "필터를 볼 수는 있으나(공유받음) 소유자가 아니라 수정/삭제/공유관리 불가"(spec EC4). 비가시(존재 은닉)는 기존 404(`SavedFilterNotFoundException`) 유지(EC5).
- **기존 결정 충돌**: 없음. PR1 ADR이 SDD 10.3 모델을 이미 superseded. 본 PR은 그 ADR의 PR2 범위 실행.
- **새 용어(glossary 후보, Maxi 승인 필요)**: "저장된 필터 공유(SavedFilter Share)", "GroupMembershipPort/ProjectMembershipPort(cross-BC 멤버십 포트)". 대시보드 공유(`dashboard_shares`, 명시 사용자 + user_groups 재사용 안 함)와는 **다른 모델**(필터는 Jira식 PROJECT/GROUP/AUTHENTICATED 대상 지정) — 의도된 차이.
- **관련 ADR**: [docs/decisions/2026-06-26-fr-sr-03-saved-filters.md](../decisions/2026-06-26-fr-sr-03-saved-filters.md) (PR1, PR2 설계 포함) · [2026-05-27-shared-kernel-extraction.md](../decisions/2026-05-27-shared-kernel-extraction.md) (shared-kernel 포트 패턴) · cross-BC resolver 선례 (`2026-06-04-workflow-scheme-permission-prod-resolver.md` 등). **신규 ADR 불필요** — PR1 ADR이 PR2 결정을 이미 포함.

## 스펙

전체 스펙. [docs/specs/2026-06-26-fr-sr-03-pr2-filter-shares.md](../specs/2026-06-26-fr-sr-03-pr2-filter-shares.md)

핵심 결정(Maxi).
- **공유 = 필터 본문 임베드**. `POST/PUT /api/v1/filters` 바디 `shares:[{shareType,targetId}]` 교체(replace-all), 기존 OCC version 재사용. 전용 공유 엔드포인트 없음.
- **공유받은 목록 = 신규 `GET /api/v1/filters/shared`**. 기존 `GET /filters`는 소유만 유지.

핵심 시나리오 5줄.
- 소유자가 필터 바디에 PROJECT/GROUP/AUTHENTICATED 공유 지정 → 대상이 조회/실행 가능.
- 읽기(get/search)는 가시성 4경로(소유/AUTHENTICATED/PROJECT멤버/GROUP소속), 비가시 404 은닉.
- 쓰기(put/delete)는 소유자만. 가시-비소유 = 403, 비가시 = 404.
- 실행은 viewerUserId=actor라 공유받아도 권한 상승 0(PR1 패턴).
- cross-BC 멤버십 포트 2종(shared-kernel 신규 + identity-access 구현, fail-closed). project_key는 ProjectDirectory read-only 선례로 매핑.

데이터: `V601__saved_filter_shares.sql`(FK CASCADE 동일 BC, UNIQUE NULLS NOT DISTINCT, target NULL=AUTHENTICATED).
403 재도입: `SavedFilterForbiddenException` 부활(PR1 데드코드).

## Brainstorming Check

✅ 통과 (1 iteration). U1(project_key↔id)·U2(group UUID)를 코드(ProjectDirectory/UserGroup)로 검증·해소. 가시성 404/403 분기·멱등·CASCADE·fail-closed 커버. EC13(미멤버 프로젝트 공유) 저위험 수용, 적대적 리뷰 재검토 표시.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
