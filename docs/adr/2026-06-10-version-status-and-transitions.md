# ADR — Version 상태 모델 + 전이 규칙 (FR-VR-02)

> 날짜: 2026-06-10
> 상태: 채택
> BC: issue-tracking
> 관련 FR: FR-VR-02 (버전 상태 Unreleased/Released/Archived), 선행 FR-VR-01 (버전 생성 + CRUD, PR #67/#68)
> 선례 ADR: 2026-06-03-version-model-and-permission-deferral (FR-VR-01) — D5에서 status를 본 FR로 이연

## 맥락

FR-VR-01은 `versions` 테이블 + Version Aggregate + CRUD를 status 없이 구현하고,
`versions.status` 컬럼과 상태 전이를 FR-VR-02로 명시 이연했다
(deferral ADR D5, V010 마이그레이션 주석 "status 컬럼은 FR-VR-02 로 이연").

SDD 데이터모델 §05 Version 테이블은 이미 다음을 명세한다.
- `status VARCHAR(20)` — UNRELEASED / RELEASED / ARCHIVED
- `released_at TIMESTAMPTZ NULL` — 실제 릴리스 시각 (`release_date`=릴리스 예정일과 별개)

권한 enum `VersionPermission.UPDATE` 의 KDoc은 이미 "name/description/releaseDate/released 등"으로
상태 전이가 UPDATE 권한 소관임을 예고한다.

## 결정

### D1 — VersionStatus enum 3종 + 기본값 UNRELEASED
`com.bts.issue.version.domain.VersionStatus` enum 신설 — `UNRELEASED`, `RELEASED`, `ARCHIVED`.
신규 생성 버전은 항상 `UNRELEASED` 로 시작한다(`Version.create` 기본값). DB 기본값도 동일.

### D2 — 전이 그래프 (Jira 정석, 양방향 + 되돌리기) — Maxi 결정 2026-06-10
허용 전이만 명시(나머지는 모두 거부).

```
UNRELEASED ──release──▶ RELEASED
UNRELEASED ◀─unrelease─ RELEASED
UNRELEASED ──archive──▶ ARCHIVED
RELEASED   ──archive──▶ ARCHIVED
ARCHIVED   ─unarchive─▶ UNRELEASED
```

- ARCHIVED → RELEASED **직행 없음**. 보관 해제(→UNRELEASED) 후 다시 release.
- 같은 상태로의 self-transition(예: RELEASED→release)은 거부(no-op 아님, 명시적 오류).
- 거부 시 도메인이 `IllegalStateException` 계열을 던지고, ApplicationService가
  `VersionTransitionNotAllowedException`(409 CONFLICT)으로 변환한다.

### D3 — released_at 자동 기록, status에 종속 — Maxi 결정 2026-06-10
- `release()`: UNRELEASED → RELEASED, `released_at = now()`.
- `unrelease()`: RELEASED → UNRELEASED, `released_at = null`.
- `archive()`: UNRELEASED/RELEASED → ARCHIVED, `released_at` **유지**(RELEASED였으면 시각 보존).
- `unarchive()`: ARCHIVED → UNRELEASED, `released_at = null`.

불변식: `status == UNRELEASED ⟹ released_at == null`,
`status == RELEASED ⟹ released_at != null`,
`status == ARCHIVED ⟹ released_at`는 보관 직전 상태 반영(null 또는 시각).

`released_at` 의 시각은 도메인이 `Instant.now()` 를 직접 호출하지 않고 **Clock 주입**으로 결정론적
처리한다(메모리 authcontroller-revokesession-timebomb — 시각 의존 로직은 Clock 주입). 기존
`Version.softDelete()` 의 `Instant.now()` 직접 호출은 본 FR 범위 밖이라 건드리지 않는다(surgical change).

### D4 — ARCHIVED는 읽기 전용 — Maxi 결정 2026-06-10
ARCHIVED 상태 버전은 다른 mutation을 거부한다.
- `rename` / `changeDescription` / `changeDates` → 도메인이 거부, 409.
- `delete`(soft delete) → ApplicationService에서 거부, 409.
- 단 `unarchive`(→UNRELEASED)는 허용. 보관 해제 후 정상 수정 가능.

근거: "보관 = 더 이상 건드리지 않는다"는 의미를 도메인 불변식으로 보장(Jira 동일). UI에서도
보관 버전의 수정/삭제 버튼을 비활성화한다(D6).

### D5 — 전이 API는 상태 전용 서브리소스 (PATCH .../status)
FR-VR-01의 `/dates` 서브리소스 패턴(`PATCH /{id}/dates`)을 동형 답습한다.
- `PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status` — body `{ "status": "RELEASED" }`.
- 단일 엔드포인트가 target status를 받아 현재 상태에서의 전이 가능 여부를 도메인이 판정.
- 액션별 엔드포인트(`/release`, `/archive`...) 대신 단일 status PATCH로 통일(엔드포인트 수 최소화).
- 세부 요청/응답 형식은 spec(D2)에서 확정.

### D6 — 권한은 VersionPermission.UPDATE 재사용 (새 enum 없음)
상태 전이는 버전 "수정"의 일종이므로 `VersionPermission.UPDATE` 를 재사용한다
(enum KDoc이 이미 예고). 새 권한 코드/enum 값을 추가하지 않는다 →
permission_schemes 시드 변경 불필요, FR-PM 카운트 가드 영향 없음
(메모리 enum-add-breaks-crossmodule-count-guard 회피).

### D7 — actorId는 SYSTEM_ACTOR_UUID placeholder 유지
모든 issue-tracking 컨트롤러(Component/Version)가 `SYSTEM_ACTOR_UUID` placeholder를 사용하며
actorId 실 추출은 FR-PM-03 소관으로 이연돼 있다. FR-VR-02는 이 기존 패턴을 그대로 따른다
(여기서 실 추출을 새로 발명하지 않음 — scope creep 금지).

## 결과

- 마이그레이션 V016 — `versions` 에 `status VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED'`
  (CHECK IN 3종) + `released_at TIMESTAMPTZ NULL` 추가. init_codegen.sql 미러 필수
  (메모리 jooq-init-codegen-mirror).
- 도메인 — `VersionStatus` enum + `Version.status`/`releasedAt` 필드 +
  `release`/`unrelease`/`archive`/`unarchive` 메서드(Clock 주입) + ARCHIVED 읽기 전용 불변식.
- 백엔드 — `VersionApplicationService.changeStatus` + 전이 거부 예외(409) +
  `PATCH /{id}/status` 엔드포인트.
- 프론트(D6) — 버전 목록에 상태 뱃지 + 전이 버튼(릴리스/보관/되돌리기) + 보관 버전 수정/삭제 비활성화.
- E2E(D7) — 전이 happy path + 거부 케이스.

## 미해결 / 후속

- FR-VR-03 (Affects/Fix Version 연결)이 status를 참조할 수 있음(예: RELEASED 버전만 Fix로?).
  본 FR 범위 아님 — FR-VR-03에서 결정.
- release 시 `release_date`(예정일) 미설정 상태면 경고? 본 FR은 강제하지 않음(Jira도 경고만).
