# FR-DB-03 대시보드 공유 (URL 토큰 + iframe 임베드) — 스펙

> 날짜: 2026-07-02 | BC: notification-dashboard | slug: fr-db-03-dashboard-share
> 도메인 ADR: docs/decisions/2026-07-02-fr-db-03-dashboard-share.md
> 확정 결정: D1 직교 공유 토큰 / D2 익명뷰=정적 가젯만

## 배경 요약

FR-DB-01/02로 인증 사용자 대상 대시보드(PRIVATE/TEAM/ORG) + 가젯이 완성됐다. FR-DB-03은
**비로그인 URL 공유 + iframe 임베드**(SDD 14.5 "토큰 기반 익명 접근, 임원 보고용")를 추가한다.

핵심 원칙 (ADR 확정).
- 공개는 **직교 공유 토큰**(`dashboard_share_tokens`)으로 모델링. `DashboardVisibility`는 불변.
- 익명/임베드 뷰는 **정적 가젯(text_widget/link_list)만** 렌더. 데이터 가젯은 "로그인 필요" 플레이스홀더.

## 사용자 시나리오 (Given-When-Then)

### S1. 공유 링크 발급
- **Given** 소유자가 자기 대시보드 상세 페이지를 본다
- **When** "공유" 버튼 → 공유 모달에서 "링크 생성"을 누른다
- **Then** 서버가 불투명 토큰을 발급하고, 모달에 공개 URL(`/dashboards/shared/{token}`)이 1회 노출된다. "복사" 버튼으로 클립보드 복사.

### S2. 익명 열람 (비로그인)
- **Given** 로그인하지 않은 사용자가 공유 URL을 연다
- **When** 페이지가 로드된다
- **Then** 대시보드 이름 + 그리드 레이아웃이 읽기 전용으로 보인다. 정적 가젯(텍스트/링크)은 정상 렌더. 데이터 가젯(내 이슈/차트 등)은 "로그인이 필요한 가젯입니다" 플레이스홀더로 표시. 편집 UI·헤더·소유자 정보는 노출 안 됨.

### S3. iframe 임베드
- **Given** 소유자가 공유 모달을 연다
- **When** "임베드 코드" 탭을 본다
- **Then** `<iframe src="{origin}/dashboards/shared/{token}?embed=1" ...>` 스니펫이 표시되고 복사 가능. 임베드 뷰는 헤더/여백 없는 순수 그리드로 렌더된다.

### S4. 링크 목록·취소
- **Given** 소유자가 대시보드에 발급한 공유 링크가 여러 개 있다
- **When** 공유 모달의 "발급된 링크" 목록을 본다
- **Then** 각 링크의 생성일·(만료일)·마지막 접근일이 보이고, "취소"를 누르면 그 링크는 즉시 무효(하드 삭제)가 되어 이후 접근 시 404.

### S5. 무효 토큰 접근
- **Given** 취소됐거나 존재하지 않거나 만료된 토큰
- **When** 익명 사용자가 해당 URL을 연다
- **Then** 404(존재 숨김). 토큰 유효/무효를 타이밍·메시지로 구분할 수 없다(계정/리소스 열거 차단).

### S6. 삭제된 대시보드
- **Given** 소유자가 대시보드를 소프트 삭제했다
- **When** 기존 공유 토큰으로 익명 접근한다
- **Then** 404. 부모 `deleted_at IS NULL` 필터로 익명 조회도 차단.

## 기능 요구사항 (FR)

- **FR-1** 소유자는 대시보드에 공유 토큰을 발급할 수 있다. 원문 토큰은 발급 응답에서 1회만 노출되고 DB에는 SHA-256 해시만 저장한다.
- **FR-2** 한 대시보드는 0개 이상의 공유 토큰을 가질 수 있다(다중 링크). 소유자만 발급/조회/취소.
- **FR-3** 토큰은 선택적 만료(`expiresAt`, null=무기한)를 가질 수 있다. 취소는 하드 삭제(복구 가치 낮음, 재발급으로 대체).
- **FR-4** 익명 사용자는 유효 토큰으로 대시보드 스냅샷을 읽을 수 있다(비인증 GET). 응답은 정적 가젯만 포함하고 데이터 가젯은 위치+타입만 남긴 플레이스홀더 항목으로 치환한다(AQL/filterId/config 미노출).
- **FR-5** 익명 응답은 소유자 PII(ownerId, sharedUserIds), version, 관리 메타를 포함하지 않는다. 이름·설명·정화된 layout만.
- **FR-6** 프론트 공유 모달: 링크 생성·복사, 임베드 코드 복사, 발급 링크 목록·취소. **PRIVATE/TEAM 대시보드에서 링크 생성 시 "링크가 있는 누구나 읽을 수 있습니다" 경고 표시**(G2).
- **FR-7** 프론트 익명 공유 뷰 라우트(`/dashboards/shared/{token}`): 헤더·인증 없이 읽기 전용 렌더. `?embed=1` 시 크롬 최소화. 데이터는 **raw fetch**로 조회(apiFetch 금지 — 401 로그인 리다이렉트/인증 헤더 부착 회피, memory auth-pre-session-401-raw-fetch 선례). 가젯 렌더는 **FR-DB-02의 정화된 렌더러 재사용**(text plain·link http/https 화이트리스트 XSS 이월).
- **FR-8** 공유 토큰 발급/조회/취소는 **소유자 전용**. SYSTEM_ADMIN 오버라이드는 MVP 범위 밖(G5). 대시보드당 토큰 상한 `MAX_SHARE_TOKENS`(예: 20)로 무한 발급 차단(G4).
- **FR-9** 토큰 접근은 대시보드 자체 `visibility`와 **독립**(G2). PRIVATE 대시보드라도 유효 토큰이면 익명 읽기 허용 — 이것이 직교 토큰 모델의 의도(소유자가 명시적으로 링크를 만든 자기 데이터).

## 비기능 요구사항 (NFR)

- **NFR-1 (보안·비인증 경로)** 익명 읽기 경로는 BTS 첫 permitAll 데이터 경로다. 중앙 `SecurityConfig`(identity-access)에 `/api/v1/public/**` permitAll 등록 + 이 경로는 인증 필터를 통과시키되 어떤 SecurityContext도 신뢰하지 않는다. GET 전용.
- **NFR-2 (토큰 강도)** 토큰은 CSPRNG 256bit(base64url ≥ 43자). 조회는 SHA-256 해시로 UNIQUE 인덱스 lookup. 원문 미저장.
- **NFR-3 (열거 차단)** 무효/만료/삭제 모두 동일하게 404 + 동일 응답형식. 타이밍 차이 최소화.
- **NFR-4 (iframe)** 임베드 뷰 응답은 외부 프레이밍 허용이 필요. `X-Frame-Options: DENY`(Spring 기본) 대신 임베드 라우트/뷰 한정으로 `frame-ancestors` 완화. 전역 완화 금지. (security-engineer 확정 — 아래 열린 항목 O-1)
- **NFR-5 (BC 격리)** notification BC는 자기 도메인만. identity-access SecurityConfig permitAll 추가는 불가피한 cross-BC 변경 → 같은 PR에서 처리하되 security-engineer 공동검토(FR-WF-01 옵션C 선례: 한 기능이 요구하는 인접 레이어 변경).
- **NFR-6 (rate limit)** 익명 토큰 조회는 무차별 대입 표면. MVP는 토큰 강도(256bit)로 방어하며 rate limit은 후속(인프라)으로 명시.

## API 인터페이스 (REST)

### 관리 (인증, 소유자 전용)
```
POST   /api/v1/dashboards/{id}/shares          201 { id, token(1회), createdAt, expiresAt? }
GET    /api/v1/dashboards/{id}/shares          200 { items: [{ id, createdAt, expiresAt?, lastAccessedAt? }] }  # token 미포함
DELETE /api/v1/dashboards/{id}/shares/{shareId} 204
```
- 권한: 비소유자 403, 존재/접근불가 대시보드 404. 발급 요청 바디에 `expiresAt?`(선택).

### 익명 (permitAll, GET 전용)
```
GET    /api/v1/public/dashboards/{token}       200 { name, description?, layout(정화됨) } / 404
```
- 토큰은 **path** 파라미터(query 아님 — 로그·referrer 노출 축소). 유효 시 200, 그 외 404.
- 정화: layout 배열에서 gadgetType이 static(text_widget/link_list)/미지정이면 원본 유지, 그 외는 `{ i,x,y,w,h, gadgetType, requiresAuth: true }`로 config 제거.

## 데이터 모델 변경

신규 마이그레이션 `Vxxx__dashboard_share_tokens.sql` (+ init_codegen.sql 미러).
```sql
CREATE TABLE dashboard_share_tokens (
    id              UUID PRIMARY KEY,
    dashboard_id    UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    token_hash      BYTEA NOT NULL,               -- SHA-256, 원문 미저장
    created_by      UUID NOT NULL,                -- users.id 논리참조 (FK 없음, BC 격리)
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ,                  -- null = 무기한
    last_accessed_at TIMESTAMPTZ                  -- G1: MVP 미채택(공개 GET write-on-read 증폭 회피). 컬럼은 후속 여지로 두되 갱신 안 함
);
CREATE UNIQUE INDEX ux_dashboard_share_tokens_hash ON dashboard_share_tokens(token_hash);
CREATE INDEX ix_dashboard_share_tokens_dashboard ON dashboard_share_tokens(dashboard_id);
```
- V번호는 마이그레이션 최신 확인 후 확정(머지 직전 재확인 — V번호 충돌 회귀 방지).
- `DashboardShareToken` 도메인 엔티티 + Dashboard aggregate 통한 발급/취소.

## 엣지 케이스

- **EC-1** 만료 경계: `expires_at <= now`면 404. Clock 주입으로 결정적 테스트.
- **EC-2** 정화 누락 방지: 데이터 가젯 config가 익명 응답에 새어나가면 BLOCKER. 정화는 화이트리스트(static만 통과) 방식 — 새 gadgetType 추가 시 기본 차단(fail-closed).
- **EC-3** 삭제된 대시보드 + 유효 토큰 → 404 (부모 deleted_at 필터).
- **EC-4** 비소유자가 남의 대시보드에 토큰 발급 시도 → 403(존재하면) / 404(접근불가).
- **EC-5** 토큰 path에 이상 문자/길이 → 정상적으로 404(파싱 실패도 404로 수렴, 500 금지).
- **EC-6** literal 경로 우선순위: `/api/v1/dashboards/{id}/shares`의 `shares` literal이 `{id}` 파싱과 충돌 않도록 확인(FR-DB-02 gadget-catalog 선례).
- **EC-7** `embed=1` 없어도 익명 뷰는 동작(공유 링크 직접 열람). embed는 크롬 최소화 플래그일 뿐.
- **EC-8** 동일 대시보드 다중 토큰 발급 후 하나만 취소 → 나머지는 유효.
- **EC-9** `GET /shares` 목록 응답에 원문/해시 토큰이 절대 포함되지 않음(메타만) — 유출 회귀 가드.
- **EC-10** `MAX_SHARE_TOKENS` 초과 발급 시도 → 400(도메인 예외). owner가 취소 후 재발급.
- **EC-11** 익명 뷰가 apiFetch를 쓰면 무효 토큰 404에서 로그인 리다이렉트가 걸려 UX 깨짐 → raw fetch로 404를 그대로 표시(FR-7).

## 제약 조건

- `DashboardVisibility` enum 불변(회귀 0). FR-DB-01/02 기존 테스트 그대로 통과.
- notification BC는 이슈/AQL 데이터를 익명 경로에서 fetch하지 않는다(cross-BC 미호출 — 정적 가젯만).
- 완제품 기준: 토큰 해싱·타이밍·열거차단·정화 fail-closed 모두 필수.

## 측정 가능한 완료 기준

1. 소유자가 링크 발급 → 익명 브라우저에서 정적 가젯 렌더 + 데이터 가젯 플레이스홀더 확인(E2E).
2. 취소된/만료된/삭제된 대시보드 토큰 접근 → 404(통합 테스트, Clock 주입).
3. 익명 응답 JSON에 ownerId/sharedUserIds/version/데이터가젯 config 부재 검증(정화 테스트).
4. `DashboardVisibility` 관련 FR-DB-01/02 기존 테스트 전부 green.
5. permitAll 경로가 인증 사용자 데이터를 우회 노출하지 않음(권한 테스트: 다른 대시보드 토큰으로 교차 조회 불가).
6. 백엔드 모듈 test + ktlint + detekt(--rerun-tasks) green, verify-master-plan 통과.

## 열린 항목 (게이트1에서 Maxi 확정)

- **O-1 iframe 크로스오리진 프레이밍 범위.** MVP가 (a) 임베드 라우트 한정 `frame-ancestors` 완화까지 포함할지, (b) 스니펫 복사 + same-origin 프레이밍만 하고 크로스오리진 헤더/리버스프록시 설정은 후속 인프라로 미룰지. 현 인프라에 nginx 헤더 설정 파일 없음 → SPA 서빙 계층 확인 필요. **기본 제안(b)**: MVP는 익명 뷰 + 스니펫 복사 + same-origin 임베드 동작까지, 크로스오리진 프레이밍은 security-engineer가 서빙 계층 확정 후 후속.

## PR 분할 힌트 (bts-plan에서 확정)

FR-DB-01/02 선례(백엔드 PR → 프론트 PR)대로 분할 권장.
- PR1 (backend, D1~D5): 도메인 + 마이그레이션 + 토큰 CRUD + 익명 읽기 + SecurityConfig permitAll + 정화 + 테스트.
- PR2 (frontend, D6/D7): 공유 모달 + 임베드 코드 + 익명 뷰 라우트 + E2E.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 5건(G1 lastAccessedAt write-on-GET 드롭 / G2 PRIVATE도 토큰이면 익명읽기 → 명시+UI경고 / G3 raw fetch+정화렌더러 재사용 / G4 토큰상한 / G5 SYSTEM_ADMIN 없음) 모두 스펙에 반영. Maxi 결정 필요 항목은 O-1(iframe 크로스오리진 범위) 하나 — 게이트1에서 확정.
