# ADR: FR-DB-03 대시보드 공유 (URL 토큰 + iframe 임베드)

> 날짜: 2026-07-02
> 상태: 채택 (Accepted)
> 관련 FR: FR-DB-03 (notification-dashboard BC §3.3)
> Plan: docs/plans/2026-07-02-fr-db-03-dashboard-share.md
> 선행: FR-DB-01(대시보드 CRUD·visibility) / FR-DB-02(가젯 layout 임베드)

## 맥락 (Context)

FR-DB-01은 대시보드를 PRIVATE/TEAM/ORG로 공유하되 모두 **인증 사용자**를 전제했다.
SDD 14.1.2의 PUBLIC(비로그인·URL 토큰)과 14.5의 iframe 임베드(`?embed=true&token=...`,
"토큰 기반 익명 접근, 임원 보고용")는 FR-DB-03으로 미뤄져 있었다.

현재 `DashboardController`의 모든 읽기 경로는 `currentActorId()`로 로그인 사용자를 요구한다
(익명 읽기 경로 부재). FR-DB-02 가젯 데이터는 프론트가 로그인 상태로 issue-tracking API
(`/issues`, `/search/aql`)를 직접 호출한다 — 익명 뷰어는 세션이 없어 이 데이터를 가져올 수 없다.

해결이 필요했던 두 갈림길.
1. PUBLIC을 visibility enum으로 모델링할지, 직교 토큰으로 모델링할지.
2. 익명/임베드 뷰에서 가젯 데이터를 어디까지 노출할지 (백엔드·보안 범위 결정).

## 결정 (Decision)

### D1. 공유 모델 — 직교 공유 토큰 (Maxi 확정 2026-07-02)
`DashboardVisibility`는 PRIVATE/TEAM/ORG **그대로 유지**한다(PUBLIC enum 값 추가 안 함).
공개 공유는 대시보드에 종속된 **공유 토큰**(`dashboard_share_tokens`)을 발급하는 방식으로
모델링한다. 구글 독스 "링크가 있는 사람" 패턴 — 대시보드의 자체 visibility를 바꾸지 않고
링크(토큰)를 발급/취소한다.

- **DashboardShareToken** (신규 자식 엔티티): 한 대시보드에 0개 이상. Dashboard aggregate를
  통해서만 생성/취소.
- 토큰은 **불투명(opaque) 랜덤 토큰** — 서버가 발급하고 원문은 발급 응답에서 1회만 노출.
  DB에는 **SHA-256 해시만** 저장한다 (FR-MF-05 신뢰 디바이스 선례 계승, 유출 시 원문 복원 불가).
- 취소 = 하드 삭제 또는 revoked_at 무효화(spec에서 확정). 개별 링크 취소 가능.

PUBLIC enum 값을 추가하는 대안은 기각. 개별 링크 취소/다중 링크 발급이 어렵고,
product D3의 `dashboard_share_tokens` 테이블과 중복 모델이 된다.

### D2. 익명 뷰 데이터 범위 — 정적 가젯만 (MVP, Maxi 확정 2026-07-02)
익명/임베드 뷰는 **layout + 정적 가젯(`text_widget`, `link_list`)만** 렌더한다.
데이터 가젯(assigned_to_me / recently_created / filter_result / issue_count / 차트 등)은
익명 뷰에서 "로그인 필요" 플레이스홀더로 표시하고 실제 데이터를 fetch하지 않는다.

이유. 익명 뷰어는 세션이 없어 issue-tracking 등 타 BC의 인증 API를 호출할 수 없다.
"owner 대리 자격으로 토큰이 이슈 데이터를 노출"하는 대안은 링크 소지자에게 owner의 이슈
데이터가 그대로 노출되는 큰 보안 표면 + cross-BC 토큰 전달을 요구해 MVP에서 기각.
BC 격리 유지 + 보안 표면 최소화. (FR-DB-02가 가시 가젯을 6종으로 신중히 스코프한 것과 동일 결.)

### D3. 데이터 모델 — `dashboard_share_tokens` (db-engineer)
- `dashboard_share_tokens(id, dashboard_id, token_hash, created_by, created_at, expires_at?,
  revoked_at?, ...)` — 구체 컬럼은 D3 spec/마이그레이션에서 확정.
- `token_hash`는 SHA-256(UNIQUE 조회 인덱스). 원문 토큰 미저장.
- `dashboard_id` FK ON DELETE CASCADE (부모 하드 삭제 대비 안전망, DashboardShare 선례).
- 대시보드 소프트 삭제(deleted_at) 시 익명 조회는 부모 deleted_at IS NULL 필터로 차단.

### D4. 익명 읽기 엔드포인트 — 별도 공개 경로 (backend + security-engineer)
- 관리 엔드포인트. `POST /api/v1/dashboards/{id}/share`(토큰 발급, owner 인증) +
  목록/취소(`GET`/`DELETE`) — 기존 인증 컨트롤러 확장.
- 익명 조회 엔드포인트. 토큰으로 대시보드 스냅샷(정적 가젯 한정)을 반환하는 **비인증 경로**
  (경로/시큐리티 필터 화이트리스트는 D2 security-engineer 공동검토에서 확정).
- iframe 임베드. 임베드 라우트에 한해 `X-Frame-Options`/CSP `frame-ancestors` 완화 필요 —
  전역이 아니라 임베드 응답 한정으로 스코프 (security-engineer 검토).

## 결과 (Consequences)

- 신규 마이그레이션 (`dashboard_share_tokens`) + init_codegen.sql 미러.
- 기존 `DashboardVisibility` enum 불변 — FR-DB-01/02 회귀 0.
- 신규 **비인증(anonymous) 읽기 경로**가 처음 도입됨 → SecurityFilterChain 화이트리스트 +
  토큰 검증 미들웨어가 보안 핵심. 계정 열거/토큰 probe/타이밍 공격 방어를 spec에서 명시.
- 익명 뷰 데이터 = 정적 가젯 한정. 데이터 가젯 익명 노출은 후속 FR 여지로 남김.
- 새 용어 후보: 공유 토큰(Dashboard Share Token). Maxi 승인 후 머지 단계에서 glossary/domain
  노트 동기화.
- 기존 결정 충돌: 없음. FR-DB-01 ADR의 "PUBLIC(URL 토큰)은 FR-DB-03 범위"를 본 ADR이 구체화.
