# FR-VR-02 — 버전 상태 (Unreleased/Released/Archived)

> slug: fr-vr-02-versions-status
> type: feature
> agent: backend-engineer (주력) + db-engineer + frontend-engineer + qa-engineer
> 생성: 2026-06-10

## Brief

FR-VR-02 — 버전 상태 (Unreleased/Released/Archived). issue-tracking BC, §3.2.2, 우선순위 필수.
선행 FR-VR-01(버전 생성, versions 테이블 + Version Aggregate, PR #67/#68) 완료.

명세 D단계 (product/issue-tracking.md §3.2.2):
- D1. 도메인
- D2. 명세 — 상태 전이 규칙
- D3. 데이터 모델 — versions.status
- D4. 백엔드 — 상태 전이 API + 가드
- D5. 백엔드 테스트
- D6. 프론트 UI
- D7. E2E

classify: E2E/UI 키워드로 qa 오판 → 풀스택 feature로 교정 (backend-engineer 주력).

## 도메인 정리

- **BC**: issue-tracking (단일 BC, 격리 유지)
- **영향 엔티티**: Version Aggregate (FR-VR-01 기존) — `status`/`releasedAt` 필드 추가, 전이 메서드 신설
- **새 도메인 개념**: `VersionStatus` enum (UNRELEASED/RELEASED/ARCHIVED) + 전이 동사 4종(release/unrelease/archive/unarchive)
- **권한**: `VersionPermission.UPDATE` 재사용 (새 enum 없음, enum KDoc이 이미 "released 등" 예고)
- **actorId**: `SYSTEM_ACTOR_UUID` placeholder 유지 (FR-PM-03 패턴, Component/Version 컨트롤러 동일)

### 전이 규칙 (Maxi 결정 2026-06-10 — Jira 정석)

```
UNRELEASED ──release──▶ RELEASED      (released_at = now())
UNRELEASED ◀─unrelease─ RELEASED      (released_at = null)
UNRELEASED ──archive──▶ ARCHIVED      (released_at 유지)
RELEASED   ──archive──▶ ARCHIVED      (released_at 유지)
ARCHIVED   ─unarchive─▶ UNRELEASED    (released_at = null)
```
- ARCHIVED → RELEASED 직행 없음. self-transition 거부(409).
- ARCHIVED는 읽기 전용 — rename/changeDescription/changeDates/delete 거부(409). unarchive만 허용.
- released_at 시각은 Clock 주입(결정론적). 기존 softDelete의 Instant.now()는 본 FR 범위 밖 — 미변경.

### 데이터 모델 (SDD §05 명세 준수)

- V016 마이그레이션 — `versions.status VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED'` (CHECK IN 3종)
  + `versions.released_at TIMESTAMPTZ NULL`. init_codegen.sql 미러 필수.

### API (D5 — spec에서 형식 확정)

- `PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status` — body `{ "status": "RELEASED" }`.
  FR-VR-01 `/dates` 서브리소스 패턴 동형.

### 기존 결정 충돌 / 관련 ADR

- 기존 결정 충돌: 없음 (FR-VR-01 deferral ADR D5가 이 작업을 예고).
- 관련 ADR: [docs/adr/2026-06-10-version-status-and-transitions.md](../adr/2026-06-10-version-status-and-transitions.md) (생성됨)
- 선행 ADR: docs/adr/2026-06-03-version-model-and-permission-deferral.md (D5에서 이연)
- glossary 추가 대기: "버전 상태"(VersionStatus) — Maxi 승인 후 Obsidian 반영 (게이트 1에서 확인)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
