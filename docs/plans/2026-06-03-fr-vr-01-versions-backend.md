# FR-VR-01 — 버전 생성 + 시작일/릴리즈 예정일 (백엔드 D1~D5)

> slug: fr-vr-01-versions-backend
> type: api (backend)
> agent: backend-engineer (+ security-engineer 권한 가드 검토)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-VR-01 버전(Version) CRUD 구현 — issue-tracking BC. 컴포넌트(FR-CM-01, PR #59) 선례를 따라 버전 등록/조회/수정/삭제 API.
권한 판정은 `VersionPermissionResolver` 포트로 추상화하고 prod 실판정은 후속 FR-PM-03에 이연(FR-CM-01과 동일 구조).

- 범위: 이번 PR은 **백엔드 D1~D5**만. 프론트 UI(D6)/E2E(D7)는 후속 PR (FR-CM-01: PR #59 백엔드 → PR #64 프론트 패턴).
- plan 정의 출처: `docs/plan/product/issue-tracking.md` §3.2.1 (Plan slug `issue/versions`, 선행 §2.1.1).
- 선행 관계: FR-VR-01은 FR-PM-03(버전/컴포넌트 등록 권한)의 기능 선행 FR. `docs/plan/product/identity-access.md` §4.3 메모 참조.
- 권한 이연 ADR 선례: `docs/adr/2026-06-02-component-model-and-permission-deferral.md`.

D1. 도메인 — Version Aggregate (backend-engineer)
D2. 명세 (backend-engineer)
D3. 데이터 모델 — `versions` (db-engineer)
D4. 백엔드 — CRUD API (backend-engineer + security-engineer)
D5. 백엔드 테스트 (backend-engineer)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
