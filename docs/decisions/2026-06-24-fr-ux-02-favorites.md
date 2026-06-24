# ADR: FR-UX-02 즐겨찾기 / Star (도메인 모델)

> 날짜: 2026-06-24
> 상태: 채택 (Accepted)
> 관련 FR: FR-UX-02 (notification-dashboard BC §5.1)
> Plan: docs/plans/2026-06-24-fr-ux-02-favorites-backend.md

## 맥락 (Context)

FR-UX-02는 사용자가 자주 보는 대상(이슈/필터/대시보드/프로젝트)을 개인 즐겨찾기로
등록·해제하고, 프론트의 Star 버튼·즐겨찾기 사이드바로 빠르게 접근하는 기능이다.
notification-dashboard BC §5.1에 속하며, 같은 BC의 대시보드(FR-DB-01)와 형제 관계다.

본 PR은 백엔드 D1~D5(도메인·명세·데이터 모델·API·테스트)만 담당하고, 프론트
D6/D7(Star 버튼·사이드바·E2E)은 후속 PR로 분리한다(Maxi 확정 2026-06-24).

product 명세는 대상 4종(이슈/필터/대시보드/프로젝트)을 정의하나, "필터"는 저장된 필터
엔티티(FR-SR-03 필터 저장 및 공유)가 아직 미구현이라 즐겨찾기할 실대상이 없는 상태다.
또한 대상은 서로 다른 BC(issue-tracking/search/notification/project-workflow)에 속해
BC 격리와 식별자 전략 결정이 필요하다.

## 결정 (Decision)

### D1. 모듈 위치 — notification 모듈 내 `favorite` 패키지
별도 Gradle 모듈을 만들지 않고 `backend/modules/notification` 안에
`com.bts.notification.favorite.{domain,application,repository,web}` 패키지로 추가한다.
FR-DB-01 D1(`com.bts.notification.dashboard.*`)의 결정을 그대로 계승한다(새 모듈은
빌드·ArchUnit·테스트 인프라 중복 비용이 커서 기각).

### D2. Aggregate — Favorite 단일 엔티티
- **Favorite** (Aggregate Root): id(UUID), userId(UUID), targetType(enum), targetId(문자열),
  createdAt. 자식 엔티티 없음(대시보드의 DashboardShare 같은 공유 개념 없음 — 개인 북마크).

### D3. 대상 타입 — 명세대로 4종 (Maxi 확정 2026-06-24)
`FavoriteTargetType` enum = ISSUE / FILTER / DASHBOARD / PROJECT. product 명세 충실.
단 FILTER는 FR-SR-03(필터 저장) 미구현이라 **enum에 정의는 하되 실사용은 FR-SR-03 이후**다.
백엔드는 4종을 모두 받아 저장한다(형식 검증만, D6 참조).

### D4. 식별자 전략 — target_id 문자열, FK 미적용 (BC 격리)
- targetId는 **문자열(VARCHAR)**로 저장한다. 대상별 자연 식별자가 타입이 달라(이슈=이슈 키
  `PROJ-123`, 프로젝트=프로젝트 키, 대시보드=UUID 문자열, 필터=향후 id 문자열) 문자열로 통일.
  프론트 라우팅 키(이슈 키·프로젝트 키)와도 자연스럽게 일치.
- userId는 identity-access `users.id`를 가리키나 **FK는 걸지 않는다**(BC 격리, FR-DB-01 D3 ·
  FR-NT-01 project_key 문자열 관례 계승). target 대상에도 FK 없음(cross-BC).

### D5. 삭제 정책 — 하드 삭제 (Maxi 확정 2026-06-24)
즐겨찾기 해제(unstar)는 행을 물리 삭제한다(`DELETE FROM favorites WHERE ...`). 개인 북마크
토글이라 복구 가치가 낮고, Watcher(`issue_watchers`, 복합 식별·ON CONFLICT 멱등·하드 삭제)와
같은 성격이다. DATA.md §하드 삭제 허용 영역에 favorites를 추가한다(첨부 옆). `deleted_at`
컬럼 없음.

### D6. 검증 수준 — 형식만 검증 (Maxi 확정 2026-06-24)
등록 시 targetType이 enum 유효 값인지, targetId가 non-blank인지 **형식만** 검증한다. 대상의
실존 여부·VIEW 권한은 **검증하지 않는다**(개인 북마크, BC 결합 회피, 대상 BC마다 포트 추가
비용 회피). 대상이 삭제/접근 불가가 된 끊어진 참조는 조회/렌더링 단계에서 graceful 처리한다
(본 백엔드 PR은 저장/조회만, graceful 렌더는 프론트 D6).

### D7. 멱등성 — UNIQUE(user_id, target_type, target_id)
같은 사용자가 같은 대상을 중복 즐겨찾기할 수 없도록 복합 UNIQUE 제약. 중복 POST의 처리
(멱등 200 vs 409)는 spec 단계에서 확정.

## 결과 (Consequences)

- 신규 마이그레이션 **V406** (`favorites`) — notification 모듈 `db/migration/notification/`,
  + `init_codegen.sql` 미러 필수(jOOQ 코드 생성).
- **DATA.md §하드 삭제 허용 영역**에 `favorites` 항목 추가(본 ADR 인용). 같은 PR에서 동기화.
- 인덱스: 조회 패턴 `WHERE user_id = ?`(내 즐겨찾기 목록) → `(user_id)` 또는
  UNIQUE 복합 인덱스가 커버. 구체 인덱스는 spec/plan에서 확정.
- API 형태(`POST /api/v1/favorites` 등록, `DELETE /api/v1/favorites` 해제, 목록 조회)의
  요청/응답 스키마·중복 처리·삭제 식별 방식(body vs path)은 spec 단계에서 확정.
- glossary 신규 용어 후보: 즐겨찾기(Favorite), 즐겨찾기 대상(Favorite Target). Maxi 승인 후
  머지 단계에서 glossary/domain 노트 동기화.
- 기존 결정 충돌: 없음. FR-DB-01 ADR(대시보드)의 모듈/격리/삭제 패턴을 계승·일관.
