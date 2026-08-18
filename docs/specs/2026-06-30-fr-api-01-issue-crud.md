<!-- FR-API-01 이슈 CRUD REST API 표준화 스펙 — cursor 병행 페이지네이션 + 응답 envelope + OpenAPI 3.1 + contract test -->

# FR-API-01 — 이슈 CRUD REST API 표준화 스펙

- 날짜: 2026-06-30
- BC: issue-tracking (구현) / search-export-import §5.1 (논리 FR)
- 관련 ADR: [docs/decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md](../decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md)
- 선행: SDD 11장 §11.1 · §11.3 · §11.8
- 환경: Spring Boot 3.3.5, Kotlin 2.0.10, jOOQ, 모듈별 독립 부팅(issue-tracking 자체 `@SpringBootApplication`)

## 0. 범위 해석 (중요 — 게이트1 재확인 대상)

Maxi 확정 3선택: ① cursor **병행**(offset 유지·프론트 무회귀) ② OpenAPI **전역**+Swagger UI ③ 적용범위 **전 목록 API**.

①(무회귀)과 ③(전 목록)은 직접 충돌한다 — unpaged 목록을 envelope으로 바꾸면 그 목록을 List로 소비하는 프론트가 회귀하기 때문이다.
**충돌 시 ①(무회귀)을 우선**으로 해석한다:

- **cursor + envelope 실적용** = 실제 페이지네이션 목록(이슈 목록 `GET /issues`, changelog `GET /issues/{key}/changelog`)에 **신규 모드로 병행 추가**.
- **전 목록 API** = **OpenAPI 문서화(annotation) 대상**은 issue-tracking 목록 API 전반. 단 unpaged 목록의 **응답 형태는 변경하지 않는다**(무회귀).
- 이 해석을 게이트1에서 Maxi가 재확인한다(다른 의도면 ③을 프론트 동반 수정 별도 FR로 분리).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 외부 통합 클라이언트가 cursor로 이슈를 순회
- **Given** 외부 스크립트가 `GET /api/v1/issues?projectKey=ATLAS&cursor=&limit=50`(첫 호출, cursor 비움)을 호출하고
- **When** 서버가 created_at 내림차순 50건과 다음 cursor를 반환하면
- **Then** 응답은 `{ "data": [...50건...], "meta": { "page": { "next": "<opaque>", "limit": 50 } } }` 이고, 클라이언트는 `next`를 그대로 다음 호출 `?cursor=<opaque>`에 넣어 끊김 없이 순회한다. 마지막 페이지에서 `next`는 `null`.

### S2. 기존 프론트는 offset으로 그대로 동작 (무회귀)
- **Given** apps/web이 `GET /api/v1/issues?projectKey=ATLAS&page=0&size=20`을 호출하고
- **When** 서버가 기존과 동일하게 Spring `Page<IssueResponse>`(`{content, pageable, totalElements, ...}`)를 반환하면
- **Then** 프론트 코드·MSW·테스트는 단 한 줄도 바뀌지 않는다.

### S3. 개발자가 Swagger UI로 API를 탐색
- **Given** issue-tracking 앱이 떠 있고
- **When** 개발자가 `/swagger-ui`(또는 `/api/v1/docs`)에 접속하면
- **Then** 이슈 CRUD/목록/전환 엔드포인트가 요청·응답 스키마, 에러 응답(RFC 7807)과 함께 OpenAPI 3.1로 문서화돼 보인다. `/v3/api-docs`(또는 `/api/v1/openapi.json`)는 기계 판독용 스펙을 반환한다.

### S4. 잘못된 cursor는 명확히 거부
- **Given** 클라이언트가 위변조되었거나 형식이 깨진 `?cursor=GARBAGE`를 보내면
- **When** 서버가 토큰 디코딩에 실패하면
- **Then** 400 Bad Request + RFC 7807 ProblemDetail(`errorCode: "ISSUE_INVALID_CURSOR"`)을 반환한다(500 아님).

### S5. cursor와 page 동시 지정은 모순 → 거부
- **Given** 클라이언트가 `?cursor=X&page=2`를 동시에 보내면
- **When** 서버가 두 페이지네이션 모드의 동시 지정을 감지하면
- **Then** 400 Bad Request + ProblemDetail(`errorCode: "ISSUE_PAGINATION_MODE_CONFLICT"`)을 반환한다.

## 2. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-1 | `GET /api/v1/issues`에 **cursor 모드**를 추가한다. `?cursor=<token>&limit=N`(또는 `cursor` 빈 문자열=첫 페이지) 지정 시 cursor 모드. 응답은 §3 envelope. 기존 `?page&size`(또는 무파라미터)는 offset 모드로 기존 `Page<IssueResponse>` 응답 **그대로 유지**. |
| FR-2 | **cursor 토큰**은 opaque Base64URL 문자열. keyset 정렬 `(created_at DESC, id DESC)`의 마지막 행 `(created_at, id)`를 인코딩. 포맷 버전 prefix(`v1:`) 포함. 디코딩 실패/형식 오류 시 400(S4). 클라이언트는 내부 구조에 의존하지 않는다. |
| FR-3 | cursor seek 쿼리는 `WHERE (created_at, id) < (:cursorCreatedAt, :cursorId)` keyset 조건. 기존 `BROWSE` 권한 게이트 + visibility 보안 술어 + 필터(status/assignee/label/component)를 **그대로 상속**(별도 보안 경로 신설 0). `limit+1` fetch로 다음 페이지 존재 여부 판정. |
| FR-4 | `GET /api/v1/issues/{key}/changelog`에 동일한 cursor 모드를 병행 추가(정렬 키는 changelog의 안정 정렬 = `(occurred_at DESC, id DESC)` 등 실측 확정). offset 모드 유지. |
| FR-5 | **응답 envelope**(cursor 경로 한정): `{ "data": [...], "meta": { "page": { "next": "<cursor|null>", "limit": N } } }`. 단건 응답은 기존 `DataResponse`(`{data}`) 유지. |
| FR-6 | **에러 포맷**: 이슈 CRUD 경로 ProblemDetail이 SDD 11.3 필드(`type`/`title`/`status`/`detail`/`instance`/`errorCode`)를 일관 충족하도록 검증·보강. `type`은 이슈 API 한정으로 절대 URI(`https://bts.example.com/problems/<type-token>`) 정렬 — 기존 25개+ 핸들러 `problem()` 헬퍼 컨벤션과 일치(예: `invalid-cursor`). `<type-token>`은 errorCode가 아닌 kebab-case 토큰. 신규 에러코드 `ISSUE_INVALID_CURSOR`(400), `ISSUE_PAGINATION_MODE_CONFLICT`(400). **errorCode는 기존 issue-tracking 핸들러 컨벤션 SCREAMING_SNAKE_CASE를 따른다**(devex 리뷰 CONCERN-3 — 실제 코드는 `ISSUE_NOT_FOUND` 형식, SDD 예시의 소문자 dot은 미반영 drift). |
| FR-7 | **OpenAPI 3.1**: `springdoc-openapi-starter-webmvc-ui` 2.6.x를 issue-tracking 모듈에 통합. `/swagger-ui`(별칭 `/api/v1/docs`) + `/v3/api-docs`(별칭 `/api/v1/openapi.json`) 게시. 이슈 CRUD/목록/전환 엔드포인트에 `@Operation`/`@ApiResponse`/스키마 annotation 부여. OpenAPI 버전 3.1 명시. **보안 스킴**: JWT Bearer(`securitySchemes.bearerAuth: http/bearer/JWT`)를 전역 정의하고 인증 필요 엔드포인트에 적용(이슈 API 전체 인증 필요). |
| FR-8 | **전 목록 API 문서화**: issue-tracking 목록 API(watchers/components/versions/worklogs/links/templates/custom-fields/attachments 등)에 OpenAPI annotation을 부여한다. **응답 형태는 변경하지 않는다**(무회귀). |
| FR-9 | **contract test**: 생성된 OpenAPI 스펙과 실제 응답이 일치하는지 검증(이슈 CRUD/목록 + cursor envelope 스키마). cursor 토큰 round-trip + keyset 정렬 안정성(동률 키 tie-break) 단위 테스트 포함. |
| FR-10 | **bulk ops(FR-IS-05)**: 신규 도메인/엔드포인트 추가 0. 기존 bulk 엔드포인트에 OpenAPI annotation + 에러/응답 표준 정합 검증만. |

## 3. API 인터페이스 (REST)

### 3.1 이슈 목록 — cursor 모드 (신규)
```
GET /api/v1/issues?projectKey=ATLAS&cursor=<token|empty>&limit=50
  &status=...&assignee=...&label=...&component=...   # 기존 필터 그대로

200 OK
{
  "data": [ { ...IssueResponse... }, ... ],
  "meta": { "page": { "next": "v1:base64url...", "limit": 50 } }
}
```
- `limit` 기본 20, 최대 100(기존 pageSize 제약 재사용). 초과 시 400.
- `next == null` → 마지막 페이지.

### 3.2 이슈 목록 — offset 모드 (기존, 불변)
```
GET /api/v1/issues?projectKey=ATLAS&page=0&size=20
200 OK  → Spring Page<IssueResponse> (content/pageable/totalElements ...)  # 변경 없음
```

### 3.3 changelog — cursor 모드 (신규) / offset (기존 유지)
```
GET /api/v1/issues/{key}/changelog?cursor=<token>&limit=N   → envelope
GET /api/v1/issues/{key}/changelog?page=0&size=20           → 기존 Page (불변)
```

### 3.4 OpenAPI 문서
```
GET /swagger-ui            (별칭 /api/v1/docs)        → Swagger UI HTML
GET /v3/api-docs           (별칭 /api/v1/openapi.json) → OpenAPI 3.1 JSON
```

### 3.5 오류 (RFC 7807)
```
400 invalid cursor
{ "type":"https://bts.example.com/problems/invalid-cursor", "title":"Invalid Cursor",
  "status":400, "detail":"cursor 토큰을 해석할 수 없습니다.", "instance":"/api/v1/issues",
  "errorCode":"ISSUE_INVALID_CURSOR" }
```

## 4. 데이터 모델 변경

- **테이블/컬럼 변경 없음** (도메인 신설 0).
- cursor keyset seek 성능: `issues(created_at DESC, id DESC)` 정렬을 커버하는 인덱스 존재 여부를 db-engineer가 실측. 없으면 보조 인덱스 추가 검토(마이그레이션 V번호 머지 직전 재확인). 있으면 추가 0.

## 5. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| NFR-1 | **무회귀**: offset/Page 응답·프론트(apps/web)·기존 백엔드 테스트 영향 0. apps/web 디렉토리 미변경(BC 격리·백엔드 단독 PR). |
| NFR-2 | **성능**: cursor는 keyset seek라 깊은 페이지에서 offset 대비 인덱스 효율 우위(OFFSET N 스캔 회피). |
| NFR-3 | **보안**: cursor 토큰은 정렬 키(created_at,id)만 담아 민감정보 비노출. BROWSE 권한·visibility 술어를 기존 경로 그대로 상속(우회 0). 위변조 토큰은 400(서버 오류·스택 노출 0). **HMAC 서명은 불필요** — cursor 조작으로 가능한 건 임의 위치 seek뿐이고, 실제 데이터 노출은 어차피 BROWSE+visibility 술어가 막으므로(본인이 볼 수 있는 데이터만 보임) 토큰 무결성 서명은 과설계. 디코딩 검증(형식/범위)만으로 충분. |
| NFR-4 | **계약 안정성**: OpenAPI 스펙은 contract test로 응답과 동기화. 스펙 drift 시 테스트 실패. |
| NFR-5 | **일관성**: 절대 규칙(DEVELOPMENT.md §1) — `!!` 금지, 트랜잭션 경계, ktlint/detekt 통과. cursor 인코딩은 SQL injection 무관(바인드 파라미터). |

## 6. 엣지 케이스

- 위변조/형식오류 cursor → 400 `ISSUE_INVALID_CURSOR` (S4)
- `cursor` + `page` 동시 지정 → 400 `ISSUE_PAGINATION_MODE_CONFLICT` (S5)
- `limit > 100` → 400 (기존 제약 재사용)
- 빈 결과 → `{ "data": [], "meta": { "page": { "next": null, "limit": N } } }`
- 마지막 페이지 → `next: null`
- 동시 INSERT 중 순회 → keyset이라 페이지 경계 중복/누락 없음(offset의 약점 해소)
- cursor 토큰이 가리키는 행이 그 사이 소프트삭제됨 → keyset 비교는 (created_at,id) 값 기반이라 정상 동작(삭제 행은 visibility/soft-delete 술어로 자연 제외)
- `cursor=`(빈 문자열) → 첫 페이지로 해석(cursor 모드 진입)

## 7. 제약 조건

- BC 격리: issue-tracking 단독. cross-BC 변경 0. apps/web 미변경.
- 기존 offset API 제거 금지(무회귀). cursor는 순수 추가.
- 신규 라이브러리 springdoc-openapi 2.6.x 도입은 Maxi 확인 대상(DEVELOPMENT.md §외부 의존성) — 본 spec에서 사전 합의 기록, plan에서 정확 버전 고정.
- OpenAPI 버전은 3.1로 명시(springdoc `springdoc.api-docs.version=OPENAPI_3_1`).
- **springdoc 통합 범위 = issue-tracking 모듈 한정.** BTS는 모듈별 독립 부팅 구조라 통합 단일 앱이 없다. 따라서 "전역"은 issue-tracking 앱 컨텍스트 전역(이 모듈의 모든 컨트롤러)을 의미하며, 타 모듈(identity-access 등)의 OpenAPI 통합은 본 FR 범위 밖(후속). 게이트1에서 Maxi 재확인.

## 8. 측정 가능한 완료 기준

- [ ] cursor 모드: `?cursor=&limit=N` → envelope 응답 + `next` round-trip으로 전체 순회(중복/누락 0) 통합 테스트 통과
- [ ] offset 모드: 기존 `?page&size` Page 응답 + 기존 테스트 전부 그대로 통과(무회귀 증명)
- [ ] cursor 토큰 인코딩/디코딩 round-trip + keyset 동률 tie-break 단위 테스트 통과
- [ ] 위변조 cursor·모드 충돌 → 400 ProblemDetail 검증 테스트 통과
- [ ] changelog cursor 모드 동일 검증 통과
- [ ] springdoc 통합 후 issue-tracking 앱 부팅 + `/v3/api-docs` 200 + OpenAPI 3.1 + `/swagger-ui` 200
- [ ] 이슈 CRUD/목록/전환 + 전 목록 API OpenAPI annotation 반영
- [ ] contract test(OpenAPI 스펙 ↔ 실제 cursor/CRUD 응답) 통과
- [ ] 백엔드 clean 빌드 + ktlint + detekt 그린, apps/web 미변경 확인

## Brainstorming Check

✅ 통과 (1회 iteration). 직접 adversarial sanity check로 gap 3건 발견 후 보강:
1. cursor 위변조 방어 수준 — HMAC 불필요 근거 명시(NFR-3): BROWSE+visibility가 실제 노출 차단하므로 토큰 서명 과설계
2. OpenAPI securityScheme(JWT Bearer) 표기 추가(FR-7)
3. springdoc 통합 범위 = issue-tracking 모듈 한정 명시(§7, 모듈별 독립 부팅 구조 반영)

Maxi 결정 필요 gap: 없음. 단 범위 해석(§0 — ①무회귀 vs ③전 목록 충돌→무회귀 우선)은 게이트1 재확인 대상.
잔여 plan-영역 확정 항목: changelog cursor 정렬 키 실측, cursor tie-break id vs key 선택, contract test 구현 방식.
