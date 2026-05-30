# FR-IS-04 — 이슈 본문(Markdown) + 우선순위/라벨/환경/영향도 — 스펙

> slug: fr-is-04-markdown | BC: issue-tracking | 선행: FR-IS-01 (완료)
> 작성: 2026-05-30 | 도메인 결정: docs/plans/2026-05-30-fr-is-04-markdown.md §도메인 정리

## 계약 검증 결과 (grep 실증, frontend-zod-backend-dto-contract-gap 방지)

현재 상태 — 5개 필드 모두 신규(부분 구현 없음).
- `issues`(V001~V005): `summary VARCHAR(255)`만. description/priority/labels/environment/impact 없음.
- `Issue.kt`: id/key/projectId/summary/reporterId/currentStateKey/version/deletedAt/createdAt/updatedAt/typeId. 신규 필드 없음.
- `IssueResponse.kt`: 12필드(key/id/projectKey/summary/currentStateKey/reporterId/version/createdAt/updatedAt/typeId/typeKey/typeName).
- `IssueController` PATCH: `summary` + `typeId` + `expectedVersion`(OCC)만. RFC 7396 merge-patch(null=무변경).
- 프론트 `issueResponseSchema`(Zod): 위 12필드와 1:1.
- flexmark/jsoup/owasp sanitizer 의존성: 없음 → 신규 추가.

## 사용자 시나리오 (Given-When-Then)

**S1 — 본문 작성/표시 (해피)**
- Given: 보고자가 이슈 상세를 연다(현재 본문 없음).
- When: 본문 에디터에 Markdown(`## 재현 절차\n1. ...`)을 입력하고 저장한다.
- Then: 저장 후 본문이 렌더된 HTML로 표시된다. 버전 +1. updatedAt 갱신.

**S2 — 우선순위 변경**
- Given: 이슈 상세(priority 기본 Medium=3).
- When: 우선순위 셀렉터에서 "Highest"(=1) 선택.
- Then: priority=1 저장. 배지가 Highest 색으로 표시(SDD 21 `--color-priority-highest`).

**S3 — 라벨 부착**
- Given: 이슈 상세.
- When: 라벨 입력에 `backend`, `regression` 추가.
- Then: `labels=['backend','regression']` 저장. 칩으로 표시. (자동완성은 FR-IS-09 후속 — 이번엔 자유 입력.)

**S4 — 환경/영향도 입력**
- Given: 이슈 상세.
- When: 환경="Chrome 120 / macOS 14", 영향도="High"(=1) 입력.
- Then: environment/impact 저장.

**S5 — XSS 차단 (보안 핵심)**
- Given: 공격자가 본문에 `<script>alert(1)</script>`, `[x](javascript:alert(1))`, `<img src=x onerror=alert(1)>` 등을 입력.
- When: 저장 후 본문을 조회/렌더한다.
- Then: 서버가 flexmark 렌더 + sanitize로 위험 태그/속성/스킴을 제거. 응답 `descriptionHtml`에 실행 가능한 스크립트 0건. 원본 `description`(Markdown)은 그대로 보존(편집용).

**S6 — 부분 수정 (RFC 7396 merge-patch)**
- Given: 본문/우선순위/라벨이 채워진 이슈.
- When: PATCH로 priority만 보낸다(나머지 필드 생략).
- Then: priority만 변경, 나머지 무변경. (null=무변경. 기존 summary/typeId 규약 일치.)

**S7 — 필드 비우기**
- Given: 본문/환경이 채워진 이슈.
- When: description="" (빈 문자열), labels=[] 전송.
- Then: 본문 클리어, 라벨 전체 제거. (""·[]=명시적 클리어, null과 구분.)

## 기능 요구사항 (FR)

- FR1. `issues`에 description(TEXT, nullable), priority(SMALLINT NOT NULL DEFAULT 3), labels(TEXT[] NOT NULL DEFAULT '{}'), environment(TEXT, nullable), impact(SMALLINT, nullable) 추가. labels에 GIN 인덱스.
- FR2. Issue Aggregate에 5필드 추가. create()는 기본값(priority=3, labels=[], 나머지 null) 허용. 신규 필드는 생성/수정 경로 모두 반영.
- FR3. IssueResponse에 description(원본 Markdown), descriptionHtml(서버 렌더+sanitize), priority(Int), priorityName(String, 매핑), labels(List<String>), environment(String?), impact(Int?), impactName(String?) 노출.
- FR4. PATCH /issues/{key} merge-patch 확장 — description/priority/labels/environment/impact 각각 null=무변경. description/environment ""=클리어, labels []=전체 제거.
- FR5. priority 이름 매핑(1=Highest,2=High,3=Medium,4=Low,5=Lowest), impact 이름 매핑(1=High,2=Medium,3=Low) 공유 레이어 제공(자동화/검색 SDD 08/09/10 재사용 대비).
- FR6. Markdown 렌더 — flexmark(MD→HTML) + HTML sanitizer(허용 태그/속성 화이트리스트). javascript:/data: 스킴, on* 핸들러, script/style/iframe 제거.
- FR7. 프론트 — 이슈 상세에 본문 에디터(TipTap issue-body variant, Markdown 직렬화) + 우선순위 셀렉터 + 라벨 입력(자유, 칩) + 환경 텍스트 + 영향도 셀렉터. Zod 스키마 DTO 1:1 동기.

## 비기능 요구사항 (NFR)

- NFR1 (보안). XSS 페이로드 10종(script 주입/이벤트 핸들러/javascript: 스킴/data: 이미지/HTML 엔티티 우회/중첩 태그 등) 전부 차단. 백엔드 단위 테스트로 검증(D5). CSRF ADR "서버 측 입력 sanitization" 준수.
- NFR2 (성능). 본문 렌더는 읽기 시 수행(render-on-read). 이슈 단건 GET p95 200ms 유지(SDD NFR). 렌더 결과 캐싱은 후속(Deferred) — 본문 평균 크기에서 flexmark 렌더 비용 측정 후 판단.
- NFR3 (정합). IssueResponse ↔ issueResponseSchema 필드 1:1. drift 0 (계약갭 회귀 방지).
- NFR4 (입력 한계). description 최대 길이 제한(예 32KB), label 개수/길이 제한(예 라벨당 ≤50자, 공백 불가, 이슈당 ≤20개), environment ≤255자. 초과 시 400 + RFC 7807.

## API 인터페이스 (REST)

```
GET  /api/v1/issues/{key}
  → 200 { data: IssueResponse(+description, descriptionHtml, priority, priorityName,
                              labels, environment, impact, impactName) }

PATCH /api/v1/issues/{key}
  body(merge-patch, 모든 필드 optional):
    { summary?, typeId?, description?, priority?, labels?, environment?, impact?, expectedVersion }
  검증: priority∈1..5, impact∈1..3, label 형식/개수, description/environment 길이
  → 200 IssueResponse / 400 검증 / 404 / 409 VERSION_CONFLICT
```

## 데이터 모델 변경

- V006(issue-tracking namespace): `ALTER TABLE issues ADD COLUMN description TEXT, ADD COLUMN priority SMALLINT NOT NULL DEFAULT 3, ADD COLUMN labels TEXT[] NOT NULL DEFAULT '{}', ADD COLUMN environment TEXT, ADD COLUMN impact SMALLINT; CREATE INDEX ... USING GIN (labels);`
- priority CHECK(1..5), impact CHECK(1..3) 제약.
- 기존 행 backfill — priority=3(Medium), labels='{}' (DEFAULT로 자동).

## 엣지 케이스

- E1. description=null(미설정) → descriptionHtml=null 또는 빈 문자열. 프론트 "본문 없음" placeholder.
- E2. 잘못된 Markdown(미완성 코드블록 등) → flexmark가 안전하게 렌더(예외 없이). 깨진 입력도 sanitize 통과.
- E3. priority 범위 밖(0, 6) → 400. impact 범위 밖 → 400.
- E4. label 공백 포함/빈 문자열/중복 → 검증(공백 불가, 빈 제거, 중복 dedup) 또는 400. (정책 D2에서 확정.)
- E5. 매우 큰 본문(>32KB) → 400.
- E6. labels 부분 unique/대소문자 — Jira는 라벨 대소문자 구분. 그대로 보존.
- E7. OCC — 다른 필드 동시 수정 시 expectedVersion 불일치 → 409.

## 제약 조건

- 절대 규칙(DEVELOPMENT.md §1) 준수 — 트랜잭션 경계(이슈+히스토리 한 트랜잭션), 소프트 삭제 이슈는 수정 404.
- BC 격리 — issue-tracking 단일. 다른 BC 직접 import 금지.
- CSRF ADR 준수 — 서버 측 sanitization.
- merge-patch 규약 — 기존 summary/typeId null=무변경과 일관.

## 측정 가능한 완료 기준

- [ ] V006 마이그레이션 적용 + 기존 이슈 backfill 확인(Testcontainers).
- [ ] XSS 페이로드 10종 전부 차단(단위 테스트 green).
- [ ] PATCH 각 필드 merge-patch(null=무변경, ""/[]=클리어) 동작(통합 테스트).
- [ ] IssueResponse ↔ Zod 1:1, 프론트 typecheck/lint/test green.
- [ ] 이슈 상세에서 본문 작성→렌더 표시, 우선순위/라벨/환경/영향도 입력→저장 E2E.
- [ ] priority/impact 이름 매핑 노출.

## 미해결(plan-eng-review에서 확정)

- Q1. descriptionHtml을 응답에 포함(render-on-read) — 확정 제안. 캐싱은 Deferred.
- Q2. impact 스케일 1~3(High/Med/Low) — 제안. (우선순위 1~5와 다른 스케일.)
- Q3. label 검증 정책(개수/길이/공백/중복) 구체값 — D2 스펙에서 확정.
- Q4. sanitizer 라이브러리 선택 — OWASP Java HTML Sanitizer vs jsoup allowlist. D4에서 확정.

## 범위 경계 (Brainstorming Check — 인접 FR과 명확히 분리)

본문 작업에 딸려 보이는 기능들은 전부 별도 FR. FR-IS-04는 아래를 **하지 않음**.

- **@멘션 알림 → FR-MN-01**(§4.1.1, 별도). FR-IS-04는 본문을 Markdown 텍스트로 저장/렌더만. `@username` 파싱·알림 발사 안 함.
- **변경 이력 감사 → FR-HS-01**(§5.1.1, 별도, 미구현 확인). priority/labels 등 필드 수정 이력 기록 안 함. (FR-IS-01의 트랜잭션 경계는 유지하되 history 엔티티는 FR-HS 도입 후.)
- **본문 FTS 검색(search_vector) → search-export-import BC**(별도). FR-IS-04는 `description` 컬럼 추가만, FTS 인덱싱은 검색 FR.
- **본문 템플릿 → FR-TM-01**(별도). **라벨 자동완성 → FR-IS-09**(별도). FR-IS-04 라벨은 자유 입력.
- **목록 뷰 필드**: GET /issues(목록)는 보드/필터용으로 priority/labels 포함, descriptionHtml(무거움)은 단건 GET만. 목록 응답 비대화 방지.
- **TipTap Markdown 직렬화(D6 구현 리스크)**: tiptap 미설치 확인 → D6에서 tiptap + Markdown 직렬화 확장 추가 필요. TipTap 기본은 HTML/JSON이라 Markdown 직렬화 확장 명시(저장 형식=Markdown 원본).

## Brainstorming Check ✅ 통과

범위 경계 6건 명확화(멘션/이력/검색/템플릿/라벨자동완성/목록필드) + TipTap Markdown 직렬화 리스크 식별. Maxi 결정 필요 gap 없음 — 미해결 Q1~Q4는 plan-eng-review에서 확정. office-hours/design-shotgun 스킵(정의된 FR + 기존 화면 확장, 메모리 패턴).
