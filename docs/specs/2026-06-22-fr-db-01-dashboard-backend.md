# FR-DB-01 사용자 정의 대시보드 (백엔드 D1~D5) — 스펙

> slug: fr-db-01-dashboard-backend
> BC: notification-dashboard (모듈 notification)
> 명세 정본: SDD §14.1, product §3.1 | ADR: 2026-06-22-fr-db-01-custom-dashboard.md
> 범위: 백엔드만 (도메인·마이그레이션·CRUD API·테스트). D6 UI / D7 E2E는 후속 PR.

## 범위 경계

- **이번 작업** = 대시보드 컨테이너 CRUD + visibility 권한 + layout(JSONB) 저장.
- **제외** = 가젯(FR-DB-02), URL 공유/임베드·PUBLIC visibility(FR-DB-03).

## 사용자 시나리오 (Given-When-Then)

- S1. 생성. Given 로그인 사용자, When `POST /dashboards`로 name+visibility 전송, Then 본인 소유 대시보드 생성(201) + 빈 layout.
- S2. 목록. Given 사용자 A, When `GET /dashboards?limit&offset`, Then A 소유 ∪ A에게 TEAM 공유 ∪ ORG 대시보드를 updatedAt desc로 합쳐 페이지네이션 반환.
- S3. 단건 조회(PRIVATE). Given B의 PRIVATE 대시보드, When A가 `GET /dashboards/{id}`, Then 404(존재 숨김).
- S4. 단건 조회(TEAM). Given B의 TEAM 대시보드 + A가 공유 대상, When A가 조회, Then 200.
- S5. 단건 조회(ORG). Given B의 ORG 대시보드, When 임의 인증 사용자가 조회, Then 200.
- S6. 수정. Given A의 대시보드, When A가 `PATCH`로 name/layout/visibility/sharedUserIds + 일치 version 전송, Then 200 + version 증가.
- S7. 수정 권한. Given B의 ORG 대시보드(A가 조회는 됨), When A가 `PATCH`, Then 403(소유자 아님).
- S8. OCC 충돌. Given version=2 대시보드, When version=1로 PATCH, Then 409.
- S9. 삭제. Given A의 대시보드, When A가 `DELETE`, Then 204 + deleted_at 설정(소프트 삭제). 이후 조회 404.
- S10. visibility 정규화. Given visibility=PRIVATE로 변경 + sharedUserIds 포함, When 저장, Then shares는 빈 집합으로 정규화.

## 기능 요구사항 (FR)

- FR1. Dashboard CRUD (생성/내목록/단건/수정/삭제).
- FR2. visibility 3종(PRIVATE/TEAM/ORG)별 조회 권한 판정.
- FR3. TEAM 공유 = 명시 user_id 집합(`dashboard_shares`). PATCH에서 sharedUserIds 통째 replace.
- FR4. layout JSONB 저장/반환(가젯 없는 빈 그리드부터).
- FR5. OCC 낙관적 잠금(version) — 동시 수정 충돌 409.

## 비기능 요구사항 (NFR)

- NFR1. BC 격리 — identity-access 직접 import 금지. user_id는 논리 참조(FK 미설정). ArchUnit 강제.
- NFR2. 보안 — 접근 불가 리소스는 404(존재 probe 방지). actor 추출을 리소스 조회보다 먼저.
- NFR3. 방어적 상한 — name ≤ 200자, layout JSONB ≤ 64KB, sharedUserIds 개수 cap(예 200), 목록 limit 상한 100.
- NFR4. 트랜잭션 — 대시보드 + shares 변경은 단일 트랜잭션.
- NFR5. 목록 성능 — 1,000명 규모 ORG 대시보드 폭증 대비 페이지네이션 필수. owner_id / visibility / shares(user_id) 인덱스로 합집합 쿼리 지원.

## API 인터페이스 (REST)

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| POST | `/api/v1/dashboards` | 인증 사용자 | 201 DashboardResponse |
| GET | `/api/v1/dashboards?limit&offset` | 인증 사용자 | 200 페이지(owned ∪ shared-to-me ∪ ORG) |
| GET | `/api/v1/dashboards/{id}` | 조회 권한 | 200 / 404 |
| PATCH | `/api/v1/dashboards/{id}` | owner | 200 / 403 / 404 / 409 |
| DELETE | `/api/v1/dashboards/{id}` | owner | 204 / 403 / 404 |

- 요청(POST): `{ name, description?, visibility, layout?, sharedUserIds? }`
- 요청(PATCH): `{ name?, description?, visibility?, layout?, sharedUserIds?, version }` (version 필수, OCC). 필드 3-state — 키 없음/null=미변경, sharedUserIds `[]`=전체 제거, `[...]`=replace.
- 목록 쿼리: `limit`(기본 50, 상한 100), `offset`(기본 0). 정렬 updatedAt desc. 응답은 BTS 기존 목록 페이지네이션 관례를 따름(구현 시 동일 BC/유사 목록 API grep). 합집합은 DB에서 UNION + DISTINCT(중복 제거: owned이면서 ORG인 경우 1건).
- 응답(DashboardResponse): `{ id, ownerId, name, description, visibility, layout, sharedUserIds, createdAt, updatedAt, version }`
- 권한 주체: 수정/삭제는 owner 본인만 — **SYSTEM_ADMIN 예외 없음(1차)**. 이름 중복은 허용(소유자 내 동일 이름 가능).

## 데이터 모델 변경

V405 (notification 모듈, `db/migration/notification/V405__dashboards.sql`) + `init_codegen.sql` 미러.

```sql
CREATE TABLE dashboards (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID        NOT NULL,                 -- identity-access users.id (논리 참조)
    name        TEXT        NOT NULL,
    description TEXT,
    visibility  TEXT        NOT NULL,                 -- PRIVATE | TEAM | ORG
    layout      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,                          -- 소프트 삭제 (DATA.md §3, Maxi 확정)
    version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_dashboards_owner  ON dashboards(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_dashboards_visibility ON dashboards(visibility) WHERE deleted_at IS NULL;

CREATE TABLE dashboard_shares (
    dashboard_id UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,  -- 안전망
    user_id      UUID NOT NULL,
    PRIMARY KEY (dashboard_id, user_id)
);
CREATE INDEX idx_dashboard_shares_user ON dashboard_shares(user_id);  -- shared-to-me 목록
```

**삭제 정책 = 소프트 삭제** (DATA.md §1.2/§3, Maxi 확정). DELETE는 `UPDATE dashboards SET deleted_at = now()`. 모든 조회/목록 쿼리는 `deleted_at IS NULL` 필터. dashboard_shares는 자체 deleted_at 없이 부모 deleted_at으로 가려짐(읽을 때 부모 JOIN 필터).

## 엣지 케이스

- EC1. PRIVATE 남의 것 조회/수정/삭제 → 404 (존재 숨김).
- EC2. ORG 비owner 수정/삭제 → 403 (조회는 되나 변경 불가).
- EC3. visibility != TEAM인데 sharedUserIds 전달 → shares 빈 집합으로 정규화(무시).
- EC4. visibility TEAM→PRIVATE 변경 → 기존 shares 삭제.
- EC5. sharedUserIds에 owner 포함 → owner는 항상 접근하므로 정규화로 제거.
- EC6. 중복 sharedUserIds → Set dedup.
- EC7. 빈/공백 name → 400 (도메인 불변식).
- EC8. OCC version 불일치 → 409.
- EC9. 존재하지 않는 user_id를 shares에 → 느슨 허용(논리 참조, BC 격리상 검증 안 함). 그 사용자가 로그인 시 목록에 노출.
- EC10. layout 비-JSON / 상한 초과 → 400.
- EC11. 삭제된(deleted_at NOT NULL) 대시보드 재조회/수정/삭제 → 404 (deleted_at IS NULL 필터로 안 잡힘, 멱등).
- EC12. 모든 쿼리(단건/목록/update/delete)에 deleted_at IS NULL 필터 필수 — 누락 시 삭제 자원 누출.

## 제약 조건

- notification 모듈 내 `com.bts.notification.dashboard.{domain,application,repository,web}`.
- jOOQ repository + Flyway. init_codegen 미러 필수(메모리 교훈).
- 머지 직전 V번호 충돌 재확인(메모리 교훈).

## 측정 가능한 완료 기준 (D5)

- 도메인 단위 — 불변식(빈 name 거부, visibility별 shares 정규화, OCC version 증가).
- repository Testcontainers — CRUD + shares CASCADE + 3종 목록 쿼리(owned/shared/org).
- service — 권한 판정(404/403), 정규화 로직.
- controller(MockMvc/통합) — HTTP 상태코드 201/200/204/400/403/404/409.
- ArchUnit — BC 격리(identity-access import 0).
- ktlint + detekt green.

## Brainstorming Check

✅ 통과 (self-review 1회). 발견: 목록 ORG 포함 범위 gap → Maxi 결정(전부 포함 + 페이지네이션)으로 해소. 기타 default 보강(sharedUserIds 3-state, SYSTEM_ADMIN 예외 없음, 합집합 DISTINCT, 페이지네이션 상한). 무거운 office-hours/brainstorming 스킬 대신 직접 스펙 + self-review (BTS 직접-진행 패턴, 명세 명확·도메인 정리 완료).
