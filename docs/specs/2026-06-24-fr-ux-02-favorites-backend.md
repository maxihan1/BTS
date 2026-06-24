# FR-UX-02 즐겨찾기 / Star (백엔드 D1~D5) — 스펙

> slug: fr-ux-02-favorites-backend
> BC: notification (notification-dashboard 논리), 모듈 `backend/modules/notification`
> ADR: docs/decisions/2026-06-24-fr-ux-02-favorites.md
> 범위: 백엔드 D1~D5 (도메인·명세·데이터·API·테스트). 프론트 D6/D7(Star 버튼·사이드바·E2E)은 후속 PR.

## 사용자 시나리오 (Given-When-Then)

- **S1 (등록)**. Given 로그인 사용자가 이슈 `PROJ-123`을 보고 있을 때, When 즐겨찾기 등록을 요청하면(POST), Then 그 사용자의 즐겨찾기에 `(ISSUE, PROJ-123)`가 추가되고 201로 생성된 즐겨찾기를 돌려받는다.
- **S2 (멱등 등록)**. Given 이미 `(ISSUE, PROJ-123)`를 즐겨찾기한 사용자가, When 같은 대상을 다시 등록하면, Then 중복 행을 만들지 않고 기존 즐겨찾기를 200으로 돌려받는다(토글 UX 중 race 안전).
- **S3 (해제)**. Given `(ISSUE, PROJ-123)`를 즐겨찾기한 사용자가, When 대상 기준으로 해제를 요청하면(DELETE targetType=ISSUE&targetId=PROJ-123), Then 그 행이 물리 삭제되고 204를 받는다.
- **S4 (멱등 해제)**. Given 즐겨찾기하지 않은 대상을, When 해제 요청하면, Then 오류 없이 204를 받는다(이미 없음 = 목표 상태 달성).
- **S5 (목록)**. Given 여러 대상을 즐겨찾기한 사용자가, When 목록을 조회하면, Then 본인 즐겨찾기만 최근 등록순으로 받는다(타인 것 누출 0). `targetType` 필터로 한 종류만 좁힐 수 있다.
- **S6 (미인증)**. Given 미인증 요청이, When 어떤 즐겨찾기 API든 호출하면, Then 401로 거부된다(리소스 존재 probe 차단).
- **S7 (끊어진 참조)**. Given 즐겨찾기한 이슈가 이후 삭제돼도, When 목록을 조회하면, Then 즐겨찾기 행은 그대로 반환된다(형식만 검증, 대상 실존 미보장 — graceful 렌더는 프론트 D6 책임).

## 기능 요구사항 (FR)

- **FR-1**. 즐겨찾기 등록 — `(userId, targetType, targetId)` 저장. `id`(UUID)·`createdAt` 자동 부여.
- **FR-2**. 멱등 등록 — UNIQUE(user_id, target_type, target_id) 위반 시 신규 생성 대신 기존 반환(200). 신규 생성은 201.
- **FR-3**. 즐겨찾기 해제 — **대상 기준**(targetType+targetId) 하드 삭제. 멱등(없어도 204).
- **FR-4**. 목록 조회 — actorId 본인 것만, `createdAt DESC`. `targetType` 옵션 필터.
- **FR-5**. 대상 타입 — `FavoriteTargetType` enum 4종(ISSUE/FILTER/DASHBOARD/PROJECT). FILTER는 정의만(FR-SR-03 후속 실사용).
- **FR-6**. 형식 검증 — targetType은 enum 유효값, targetId는 non-blank·길이 상한. 대상 실존/권한은 **미검증**(ADR D6).
- **FR-7**. 인증 — 모든 엔드포인트 로그인 필수. actor=`currentActorId()`(JWT/세션 UUID). 본인 즐겨찾기만 관리.

## 비기능 요구사항 (NFR)

- **NFR-보안**. 모든 조회/변경은 actorId 스코프. 타인 즐겨찾기 접근·열람 불가. actor 추출을 리소스 조회보다 먼저(probe 차단, memory: auth-extraction-before-resource-lookup).
- **NFR-검증**. ★ notification 모듈은 Bean Validation provider 부재 → `@Valid`/`@NotBlank` 무동작(memory: fr-nt-04-user-subscription-done). 입력 검증은 **명시적 코드(도메인 예외)**로만. 어노테이션 의존 금지.
- **NFR-에러**. ★ 신규 `favorite` 하위 패키지 컨트롤러는 형제 `@RestControllerAdvice`(notification.web) 미적용 → 자체 `FavoriteExceptionHandler`로 도메인/입력 예외를 400 등으로 직접 매핑(memory: fr-db-01-dashboard-backend-done). catch-all이 401/입력예외를 500으로 변질시키지 않도록 명시 핸들러.
- **NFR-데이터**. 하드 삭제(`deleted_at` 없음). DATA.md §하드 삭제 허용 영역에 favorites 추가 동기화. UNIQUE 복합 제약.
- **NFR-성능**. 조회 패턴 `WHERE user_id=?` → UNIQUE 복합 인덱스 `(user_id, target_type, target_id)`가 leftmost로 커버. 별도 인덱스 불요.
- **NFR-격리**. user_id·target에 FK 미적용(BC 격리). target_id 문자열.

## API 인터페이스 (REST)

기존 notification 관례 계승: `DataResponse<T>` 래퍼, `currentActorId()`, 경로 prefix `/api/v1`.

### POST /api/v1/favorites — 등록 (멱등)
```
요청 body: { "targetType": "ISSUE", "targetId": "PROJ-123" }
응답 201 (신규) | 200 (기존): DataResponse<FavoriteResponse>
  FavoriteResponse = { id, targetType, targetId, createdAt }
에러: 400(targetType 무효/targetId blank·초과) · 401(미인증)
```

### DELETE /api/v1/favorites — 해제 (대상 기준, 멱등)
```
요청 query: ?targetType=ISSUE&targetId=PROJ-123
응답 204 (있으면 삭제, 없어도 204)
에러: 400(targetType 무효/targetId blank) · 401(미인증)
```

### GET /api/v1/favorites — 내 목록
```
요청 query: ?targetType=ISSUE (옵션, 생략 시 전체)
응답 200: DataResponse<FavoriteListResponse>
  FavoriteListResponse = { items: FavoriteResponse[] }  // createdAt DESC
에러: 400(targetType 무효) · 401(미인증)
```

> DELETE를 path id(`/{id}`)가 아닌 대상 기준 query로 한 이유: Star 토글 UX에서 프론트는 대상 키(이슈 키 등)만 알고 favorite UUID는 모름 → 대상 기준이 라운드트립 최소. product 명세도 `DELETE /api/v1/favorites`(컬렉션, path id 없음). (review-plan 게이트에서 재확인 가능)

## 데이터 모델 변경

### V406__favorites.sql (notification 모듈) + init_codegen.sql 미러
```sql
CREATE TABLE favorites (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    target_type VARCHAR(20)  NOT NULL,   -- ISSUE/FILTER/DASHBOARD/PROJECT
    target_id   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_user_target UNIQUE (user_id, target_type, target_id)
);
```
- FK 없음(BC 격리). `deleted_at` 없음(하드 삭제).
- UNIQUE 복합이 `WHERE user_id=?` 및 `(user_id,target_type)` 필터를 leftmost 커버 → 추가 인덱스 불요.
- ★ init_codegen.sql에 동일 DDL 미러 필수(memory: jooq-init-codegen-mirror) — 누락 시 jOOQ 코드 생성 누락.

## 엣지 케이스

| # | 상황 | 동작 |
|---|---|---|
| E1 | 중복 POST(이미 등록) | 멱등 200 + 기존 반환(신규행 X). UNIQUE + ON CONFLICT DO NOTHING 후 재조회 |
| E2 | 없는 대상 DELETE | 멱등 204 |
| E3 | 미인증 | 401(currentActorId) |
| E4 | targetType 무효 enum | 400(FavoriteDomainException, 명시 파싱) |
| E5 | targetId blank/null | 400(명시 require, @Valid 의존 금지) |
| E6 | targetId 255자 초과 | 400(명시 길이 검증) |
| E7 | 목록은 본인 것만 | actorId 필터, 타인 누출 0 |
| E8 | 끊어진 참조(대상 삭제됨) | 저장/조회 정상(graceful, 프론트 D6) |
| E9 | 동시 POST race(같은 대상) | UNIQUE 제약 + 멱등 처리(409 변환 불요) |

## 제약 조건

- 한 PR = 한 BC(notification). 다른 BC 직접 import 금지(대상 검증 미수행이라 cross-BC 포트 자체가 불필요).
- TDD red→green→refactor 강제. 단위(서비스/도메인) + 통합(repository/controller Testcontainers).
- ktlint/detekt 통과. notification 모듈 detekt baseline 준수.

## 측정 가능한 완료 기준

1. `favorites` 테이블 V406 생성 + init_codegen.sql 미러 일치(마이그레이션 스키마 테스트).
2. POST 등록(201)·멱등 재등록(200, 행 1개)·DELETE 해제(204)·없는 대상 DELETE(204) 동작.
3. GET 목록 본인 것만 createdAt DESC, targetType 필터 동작(타인 누출 0 통합 테스트).
4. 형식 검증: targetType 무효 400 · targetId blank 400 · 초과 400(명시 검증, 어노테이션 비의존).
5. 미인증 401 전 엔드포인트.
6. UNIQUE 멱등(동시/순차 중복 POST 행 1개).
7. 단위+통합 테스트 통과(Testcontainers), detekt/ktlint clean.
8. DATA.md §하드 삭제 허용 영역에 favorites 동기화(ADR 인용).

## Brainstorming Check

✅ 통과 (직접 sanity check — office-hours/brainstorming은 백엔드 기술 스펙에 overkill, memory: bts-spec-office-hours-mismatch 적용). 엣지 케이스 E1~E9 전수 점검. Maxi 확정 도메인 결정(4종/하드삭제/형식만검증) 반영. 잔여 결정(중복 POST 멱등 200·DELETE 대상 기준 query)은 watcher 선례+product 명세 근거로 spec에 명시, review-plan 게이트에서 재확인 가능.
