# FR-VR-02 — 버전 상태 (Unreleased/Released/Archived) — 스펙

> BC: issue-tracking · 우선순위: 필수 · 선행: FR-VR-01(완료)
> 관련 ADR: docs/adr/2026-06-10-version-status-and-transitions.md
> 작성: 2026-06-10

## 개요

프로젝트 버전에 3가지 상태(UNRELEASED/RELEASED/ARCHIVED)와 전이를 추가한다.
FR-VR-01이 생성/날짜/CRUD를 status 없이 구현했고, 본 FR이 status 컬럼·전이·UI를 얹는다.

## 사용자 시나리오 (Given-When-Then)

### S1. 버전 릴리스
- **Given** UNRELEASED 상태의 버전 "1.0.0"이 있고, 사용자가 UPDATE 권한을 가짐
- **When** 버전 상태를 RELEASED로 전이 요청
- **Then** status=RELEASED, released_at=현재 시각으로 변경, 200 응답

### S2. 릴리스 되돌리기 (unrelease)
- **Given** RELEASED 상태의 버전 "1.0.0"
- **When** 상태를 UNRELEASED로 전이 요청
- **Then** status=UNRELEASED, released_at=null로 클리어, 200 응답

### S3. 버전 보관 (archive)
- **Given** UNRELEASED 또는 RELEASED 상태의 버전
- **When** 상태를 ARCHIVED로 전이 요청
- **Then** status=ARCHIVED로 변경, released_at은 직전 상태값 유지(RELEASED였으면 시각 보존), 200 응답

### S4. 보관 해제 (unarchive)
- **Given** ARCHIVED 상태의 버전
- **When** 상태를 UNRELEASED로 전이 요청
- **Then** status=UNRELEASED, released_at=null, 200 응답

### S5. 불허 전이 거부
- **Given** ARCHIVED 상태의 버전
- **When** 상태를 RELEASED로 직접 전이 요청 (그래프에 없는 전이)
- **Then** 409 CONFLICT + errorCode=VERSION_TRANSITION_NOT_ALLOWED, 상태 불변

### S6. 보관 버전 읽기 전용
- **Given** ARCHIVED 상태의 버전
- **When** 이름/설명/날짜 수정(PATCH) 또는 삭제(DELETE) 요청
- **Then** 409 CONFLICT + errorCode=VERSION_TRANSITION_NOT_ALLOWED("ARCHIVED 버전은 수정/삭제 불가"), 변경 없음

### S7. 목록/상세에서 상태 확인
- **Given** 여러 상태의 버전들
- **When** 버전 목록/단건 조회
- **Then** 각 버전 응답에 status, released_at 포함

## 기능 요구사항 (FR)

- **FR-1**. `VersionStatus` enum 3종(UNRELEASED/RELEASED/ARCHIVED). 신규 버전은 UNRELEASED로 시작.
- **FR-2**. 전이 그래프(허용 전이만, 나머지 거부):
  - UNRELEASED → RELEASED (release)
  - RELEASED → UNRELEASED (unrelease)
  - UNRELEASED → ARCHIVED (archive)
  - RELEASED → ARCHIVED (archive)
  - ARCHIVED → UNRELEASED (unarchive)
  - self-transition(같은 상태로) 및 그 외 모든 전이 거부.
- **FR-3**. released_at 자동 관리: RELEASED 진입 시 now(=Clock 주입), UNRELEASED 진입 시 null, ARCHIVED 진입 시 직전값 유지.
- **FR-4**. ARCHIVED 읽기 전용: rename/changeDescription/changeDates/delete 거부. unarchive만 허용.
- **FR-5**. 전이 API: `PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status`. 권한 VersionPermission.UPDATE.
- **FR-6**. 응답 DTO에 status, releasedAt 추가(목록/단건/생성/수정 모든 VersionResponse).
- **FR-7**. UI: 버전 목록 행에 상태 뱃지 + 전이 액션(릴리스/되돌리기/보관/보관해제), ARCHIVED 행은 수정/삭제 버튼 비활성화.

## 비기능 요구사항 (NFR)

- **NFR-1**. 전이 판정은 도메인 순수 메서드(부수효과 없음, 테스트 용이). ApplicationService가 거부를 409로 변환.
- **NFR-2**. released_at 시각은 `Clock` 주입으로 결정론적(메모리 authcontroller-revokesession-timebomb). `Instant.now()` 직접 호출 금지.
- **NFR-3**. 트랜잭션 경계 = VersionApplicationService(`@Transactional`). 컨트롤러는 경계 미담당.
- **NFR-4**. BC 격리 유지(issue-tracking 단일). 권한은 VersionPermissionResolver 포트 경유.
- **NFR-5**. actorId는 SYSTEM_ACTOR_UUID placeholder 유지(FR-PM-03 패턴). 본 FR에서 실 추출 미도입.
- **NFR-6**. 마이그레이션 V016은 init_codegen.sql에 미러(메모리 jooq-init-codegen-mirror).

## API 인터페이스 (REST)

### PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status

상태 전이 전용 서브리소스(FR-VR-01 `/dates` 동형).

**요청 body** (`ChangeVersionStatusRequest`):
```json
{ "status": "RELEASED" }
```
- `status`: 필수(@NotNull). VersionStatus enum 값. 잘못된 문자열은 400 VALIDATION_FAILED(Jackson 역직렬화 실패).

**응답 200** (`VersionResponse`, status/releasedAt 추가):
```json
{
  "data": {
    "id": "uuid",
    "projectId": "uuid",
    "name": "1.0.0",
    "description": null,
    "status": "RELEASED",
    "startDate": "2026-06-01",
    "releaseDate": "2026-06-30",
    "releasedAt": "2026-06-10T12:34:56Z"
  }
}
```

**에러 응답**:
| 상황 | 상태 | errorCode |
|---|---|---|
| 그래프에 없는 전이 / self-transition | 409 | VERSION_TRANSITION_NOT_ALLOWED |
| ARCHIVED 버전 수정/삭제 시도 | 409 | VERSION_TRANSITION_NOT_ALLOWED |
| status 값 누락/오타 | 400 | VALIDATION_FAILED |
| 버전 미존재 | 404 | VERSION_NOT_FOUND |
| 프로젝트 미존재 | 404 | PROJECT_NOT_FOUND |
| 권한 없음(prod) | 403 | VERSION_ACCESS_DENIED |

### 기존 엔드포인트 영향
- GET(목록/단건), POST(생성), PATCH(name/desc), PATCH /dates 응답 모두 status/releasedAt 포함하도록 VersionResponse 확장.
- PATCH(name/desc), PATCH /dates, DELETE는 대상이 ARCHIVED면 409 거부(FR-4).

## 데이터 모델 변경

### V016__version_status.sql
```sql
ALTER TABLE versions
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED',
  ADD COLUMN released_at TIMESTAMPTZ NULL;

ALTER TABLE versions
  ADD CONSTRAINT ck_versions_status
  CHECK (status IN ('UNRELEASED', 'RELEASED', 'ARCHIVED'));
```
- 기존 행은 DEFAULT로 UNRELEASED, released_at NULL.
- init_codegen.sql의 versions 정의에도 동일 컬럼/제약 미러.

## 엣지 케이스

- **EC1**. 그래프에 없는 전이(ARCHIVED→RELEASED 등) → 409, 상태 불변.
- **EC2**. self-transition(RELEASED→RELEASED 등) → 409(허용 목록에 없음).
- **EC3**. status에 정의되지 않은 문자열("FOO") → 400(enum 역직렬화 실패).
- **EC4**. ARCHIVED 버전에 rename/changeDates/delete → 409.
- **EC5**. RELEASED→ARCHIVED 후 released_at 유지 확인. 이후 unarchive(→UNRELEASED)면 released_at=null.
- **EC6**. 동시 전이(두 요청). FR-VR-01과 동일하게 OCC 미적용 — last-write-wins(단순 UPDATE라 무결성 위험 없음). 향후 필요 시 별도 FR.
- **EC7**. 소프트 삭제된(deleted_at != null) 버전은 findActiveVersion에서 이미 404 — 전이 대상 아님.

## 제약 조건

- 권한 enum 신규 추가 금지(UPDATE 재사용) — permission_schemes 시드/카운트 가드 영향 없음.
- actorId 실 추출 도입 금지(FR-PM-03 소관).
- 기존 Version.softDelete의 Instant.now()는 미변경(surgical change).
- BC 격리: identity-access 직접 import 금지.

## 측정 가능한 완료 기준

- 도메인 단위 테스트: 허용 전이 5종 각각 + 거부(self/그래프 외) + ARCHIVED mutation 거부 + released_at 규칙(now/null/유지).
- 통합 테스트(Testcontainers): PATCH /status happy(S1~S4) + 거부 409(S5/S6) + 400(EC3) + 404 + 응답 status/releasedAt 포함.
- 마이그레이션 테스트: V016 컬럼/제약/기본값.
- 프론트 단위 + MSW: 상태 뱃지 렌더 + 전이 버튼 + ARCHIVED 비활성화.
- E2E: 릴리스→보관→보관해제 happy + 거부 케이스.
- 전체 빌드/테스트 그린, ktlint/detekt 통과.

## Brainstorming Check ✅ 통과

self-review(적대적) 1회. 치명적 누락 없음. plan에서 task로 다룰 보강점 3건:
1. VersionResponse status/releasedAt 추가 → 프론트 Zod 스키마·MSW fixture 전수 파급(grep 검증).
2. ARCHIVED 읽기전용은 도메인에서 강제(rename/changeDescription/changeDates + softDelete 가드).
3. V016 마이그레이션 번호 머지 직전 재확인(동시 브랜치 충돌 방지).
