# ADR: FR-DB-01 사용자 정의 대시보드 (도메인 모델)

> 날짜: 2026-06-22
> 상태: 채택 (Accepted)
> 관련 FR: FR-DB-01 (notification-dashboard BC §3.1)
> Plan: docs/plans/2026-06-22-fr-db-01-dashboard-backend.md

## 맥락 (Context)

notification-dashboard BC에서 알림(FR-NT) 영역은 완료됐으나 대시보드 영역은 미구현이다.
FR-DB-01은 대시보드 BC의 첫 작업으로, "사용자 정의 대시보드 컨테이너" — 그리드 레이아웃을
저장하고 개인/팀/조직 단위로 공유하는 화면 — 를 구현한다. 그 안에 들어갈 가젯(위젯)은
FR-DB-02, URL 공유/임베드는 FR-DB-03으로 분리되어 있다.

SDD 14.1은 visibility를 PRIVATE/TEAM/ORG/PUBLIC 4종으로 정의하나, "TEAM=지정 사용자"의
구현 방식과 대시보드 코드의 모듈 위치는 명세에 열려 있어 결정이 필요했다.

## 결정 (Decision)

### D1. 모듈 위치 — notification 모듈 내 `dashboard` 패키지
대시보드는 product 상 notification-dashboard 한 BC이므로 별도 Gradle 모듈을 만들지 않고
`backend/modules/notification` 안에 `com.bts.notification.dashboard.{domain,application,
repository,web}` 패키지로 추가한다. (기존 webhook/recipient/channel 도메인별 분리 관례 계승.
새 모듈은 빌드·ArchUnit·테스트 인프라 중복 비용이 커서 기각.)

### D2. TEAM 공유 — 대시보드별 명시 사용자 목록
visibility=TEAM은 `dashboard_shares(dashboard_id, user_id)` 조인 테이블로 명시 사용자에게
공유한다(Jira 대시보드 공유와 유사). FR-PM-09 user_groups 재사용은 "임의 몇 명에게 공유"가
어려워 기각. 그룹 기반 공유는 후속 확장 여지로 남긴다.

### D3. Aggregate 경계 — Dashboard Root + DashboardShare 자식
- **Dashboard** (Aggregate Root): id(UUID), ownerId(UUID), name, description?, visibility,
  layout(JSONB), createdAt, updatedAt, deletedAt(소프트 삭제), version(OCC 낙관적 잠금)
- **DashboardShare** (자식): (dashboard_id, user_id) 복합 PK, FK ON DELETE CASCADE(하드 삭제
  대비 안전망). 자체 deleted_at 없음 — 부모 Dashboard의 deleted_at을 따라감(읽을 때 부모
  deleted_at IS NULL 필터). TEAM visibility에서만 의미. Dashboard aggregate를 통해서만 변경.
- **DashboardVisibility** (enum): PRIVATE(owner만) / TEAM(owner+shared) / ORG(모든 인증
  사용자). PUBLIC(URL 토큰)은 FR-DB-03 범위라 이번엔 제외.

### D5. 삭제 정책 — 소프트 삭제 (Maxi 확정 2026-06-22)
DATA.md §1.2/§3 기본 원칙대로 소프트 삭제. DELETE는 `deleted_at` 설정(UPDATE)이며 모든 조회/
목록 쿼리는 `deleted_at IS NULL` 필터. 하드 삭제 ADR 불요. 복구 가능. dashboard_shares는
부모 필터로 가려짐(소프트 삭제 시 물리 잔존은 무해).

### D4. layout 저장 — JSONB, FR-DB-01은 빈 그리드
layout은 react-grid-layout 배치 정보({i,x,y,w,h} 배열)를 담는 JSONB 컬럼이다. 가젯이 없는
FR-DB-01 단계에서는 빈 배열/구조로 시작하고, 실제 가젯 배치는 FR-DB-02에서 채운다.

## 결과 (Consequences)

- 신규 마이그레이션 V405 (`dashboards` + `dashboard_shares`) + init_codegen.sql 미러.
- TEAM 공유 user_id 유효성 검증 수준(엄격 vs 느슨)은 spec 단계에서 확정.
- 조회 권한: PRIVATE=owner, TEAM=owner+shared, ORG=인증 사용자 전체. 수정/삭제=owner.
  SYSTEM_ADMIN 예외 여부는 spec에서 확정.
- ID/user_id는 BC 관례대로 UUID. user_id는 identity-access users.id를 가리키나 FK는 BC 격리상
  걸지 않는다(논리적 참조).
- 기존 결정 충돌: 없음 (BTS 첫 대시보드 ADR).
- glossary 신규 용어 후보: 대시보드(Dashboard), 공유 범위(Visibility), 대시보드 공유
  (Dashboard Share). Maxi 승인 후 머지 단계에서 glossary/domain 노트 동기화.
