<!-- FR-DB-02 가젯 시스템 D6/D7 프론트 UI + E2E 스펙 — 가젯 컴포넌트 6종 + 카탈로그 모달 + 설정 폼 + 데이터 fetch -->

# FR-DB-02 D6/D7 — 가젯 시스템 프론트 UI + E2E 스펙 (PR2)

- 날짜: 2026-06-30
- BC: notification-dashboard (apps/web 프론트 + 카탈로그 소폭 보강)
- 관련 ADR: [docs/decisions/2026-06-29-fr-db-02-gadget-system.md](../decisions/2026-06-29-fr-db-02-gadget-system.md)
- 선행 spec(PR1 백엔드): [docs/specs/2026-06-29-fr-db-02-gadgets.md](../specs/2026-06-29-fr-db-02-gadgets.md), 백엔드 #205
- 선행 인프라: FR-DB-01 대시보드(#178), FR-SR-02/03 AQL·저장필터(#190/#196)

## 0. 범위 (이 PR = PR2)

FR-DB-01 대시보드(그리드 컨테이너) 위에 **가젯**을 배치·표시하는 프론트 UI와 E2E를 구현한다. 백엔드 저장·검증·카탈로그 API는 PR1(#205)에서 완료됐다. 가젯은 `dashboards.layout` JSONB 항목(`{i,x,y,w,h,gadgetType,config}`)으로 저장된다.

**PR2 포함**: 가젯 컴포넌트 MVP 6종 + 카탈로그(가젯 추가) 모달 + 가젯별 설정 폼 + 클라이언트측 config 검증(백엔드 1:1 미러) + 데이터 fetch(기존 BC API 재사용) + DashboardTile/Grid·api/dashboards 확장 + MSW 카탈로그 핸들러 + E2E. **+ same-BC 백엔드 소폭 보강**: `assigned_to_me` 카탈로그 config에 `projectKey` 필드 추가(프로젝트 지정형, recently_created 패턴 계승).

**PR2 제외**: enabled=false 6종(pie/bar/created_vs_resolved/sprint_burndown/activity_stream/comments_recent) — 카탈로그에 표시되나 추가 불가(백엔드 400). 집계 API(issue-tracking BC). 전역(프로젝트 무관) 가젯.

**데이터 소스 결정(Maxi 확정 2026-06-30, plan 참조)**: 이슈 기반 가젯은 **프로젝트 지정형**. AQL은 projectKey 필수 + assignee/created 미지원이므로, assigned_to_me/recently_created는 이슈 목록 API(`fetchIssues`, 기본 정렬 created_at DESC + assignee 필터)로 fetch한다.

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 (카탈로그에서 가젯 추가)**. Given 소유자가 대시보드 상세를 편집 모드로 연 상태에서, When "가젯 추가" 버튼 → 카탈로그 모달에서 enabled 가젯 1종 선택 → 설정 입력 → 추가하면, Then 그리드에 새 가젯 타일이 추가되고(로컬 상태), "저장" 시 layout에 `{...,gadgetType,config}`로 PATCH 200·version+1.
- **S2 (이슈 목록 가젯 렌더)**. Given assigned_to_me 가젯(projectKey=ATLAS)이 배치된 대시보드를, When 조회하면, Then 가젯 타일이 `fetchIssues({projectKey:'ATLAS', filter:{assigneeIds:[내 userId]}})` 결과(이슈 키·제목 목록, maxItems 제한)를 렌더. recently_created는 동 프로젝트 최근 생성순.
- **S3 (저장필터 가젯 렌더)**. Given filter_result 가젯(filterId=X)을, When 조회, Then `fetchFilter(X)` → SavedFilter{projectKey,aqlQuery} → `searchAql`로 이슈 목록 렌더. issue_count는 `page.totalElements` 숫자만 크게.
- **S4 (정적 가젯)**. Given text_widget(markdown)·link_list(links) 가젯을, When 조회, Then 데이터 fetch 없이 config를 렌더(markdown→텍스트, links→링크 목록).
- **S5 (클라이언트측 검증)**. Given 설정 폼에서 text_widget markdown 공란/10001자 또는 link_list url이 http(s)가 아니면, When 추가/저장 시도, Then 클라이언트가 즉시 인라인 에러로 차단(백엔드 400 도달 전). 백엔드 400(NOTIF_DASHBOARD_INVALID)이 와도 일반 토스트로 표시.
- **S6 (enabled=false 게이팅)**. Given 카탈로그 모달이 12종을 표시하면, When 사용자가 pie_chart(enabled=false)를 보면, Then "준비 중"으로 비활성(선택 불가)·추가 버튼 disabled. enabled=true 6종만 선택 가능.
- **S7 (비편집자 읽기전용)**. Given bob 소유 ORG 대시보드를 alice가 열면, When 본다면, Then 가젯은 렌더되나 "가젯 추가"·설정·삭제 UI 부재(canEditDashboard=false).
- **S8 (가젯 데이터 로딩/에러/빈)**. Given 데이터 가젯이 fetch 중/실패/0건이면, Then 각각 로딩 스켈레톤·에러 메시지(재시도)·빈 상태 안내를 타일 내부에 표시(전체 페이지 깨짐 없음).
- **S9 (FR-DB-01 호환·legacy 타일)**. Given gadgetType 없는 기존 타일(`{i,x,y,w,h,title}`)이 섞인 대시보드를, When 조회/저장, Then 회귀 없이 placeholder 타일로 렌더(읽기), 저장 시 그대로 보존(Gap A).

## 2. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-1 | **카탈로그 조회**: `GET /api/v1/dashboards/gadget-catalog` 호출 api 함수 + Zod 스키마(백엔드 `GadgetCatalogResponse` 1:1: `{data:{gadgets:[{type,category,label,enabled,configFields:[{key,type,required,minLength?,maxLength?,min?,max?,enumValues?,itemSchema?,minItems?,maxItems?}],requireAtLeastOne}]}}`). ConfigFieldDto @JsonInclude(NON_NULL) → optional 필드 `.optional()`. TanStack Query 캐시(가젯 메타는 정적). |
| FR-2 | **가젯 추가 모달**: "가젯 추가" → radix Dialog(기존 PostActionFormDialog 패턴). 카탈로그 12종을 category별 그룹 표시. enabled=true만 선택 가능, false는 "준비 중" 비활성(S6). 선택 시 가젯별 설정 폼 표시. |
| FR-3 | **가젯 설정 폼**: 선택 가젯의 configFields(+requireAtLeastOne) 기반. 타입별 입력 — STRING(text/textarea), INT(number, min/max), UUID(filterId 선택/입력), URL, ARRAY(link_list 동적 행 추가/삭제), ENUM(select). 프로젝트 지정형 가젯(assigned_to_me·recently_created)은 projectKey 입력 필수(클라). 클라측 검증=백엔드 config 스키마 미러. |
| FR-4 | **layout 항목 확장**: `DashboardTile` 인터페이스에 `gadgetType?: string`, `config?: Record<string,unknown>` 추가. `parseLayout`/`serializeLayout`(lib/dashboard-layout.ts)가 두 필드를 보존(왕복 무손실). gadgetType 없으면 legacy 타일(기존 동작·placeholder, Gap A). |
| FR-5 | **가젯 컴포넌트 6종 렌더**: 타일 본문(현 placeholder 위치)에 gadgetType별 컴포넌트 분기. 데이터 가젯(assigned_to_me·recently_created·filter_result·issue_count)은 TanStack Query로 fetch + 로딩/에러/빈 상태(S8). 정적 가젯(text_widget·link_list)은 config 직접 렌더. |
| FR-6 | **데이터 fetch 전략(§4)**: assigned_to_me=`fetchIssues({projectKey,filter:{assigneeIds:[useAuthUser().userId]}})`. recently_created=`fetchIssues({projectKey,page:0,size:maxItems})`(기본 created_at DESC). filter_result=filterId→`fetchFilter`→`searchAql`. issue_count=동일 경로 `totalElements`. maxItems 클램프(1~50). (filter_result/issue_count는 MVP filterId 경로만 — Gap 1.) |
| FR-7 | **MVP 노출 게이팅**: 카탈로그 enabled 플래그로 추가 가능 가젯 결정(하드코딩 금지 — 백엔드 enabled가 단일 진실원천). enabled=true 6종만 추가. |
| FR-8 | **MSW 카탈로그 핸들러**: `GET /api/v1/dashboards/gadget-catalog` 핸들러 추가(백엔드 응답 1:1, enabled 플래그 정확). dashboard PATCH는 기존 stateful 핸들러가 gadgetType/config 포함 layout 왕복 보존. fetchIssues/searchAql/fetchFilter는 기존 MSW 재사용(가젯 데이터). |
| FR-9 | **같은-BC 백엔드 보강**: `GadgetType.ASSIGNED_TO_ME` 카탈로그 config에 `projectKey`(STRING, optional, maxLength 100) 추가(recently_created 동일 패턴). 카탈로그=단일 진실원천이므로 폼 정합 위해 백엔드 노출. GadgetTypeTest + 카탈로그 통합 테스트 보강. |
| FR-10 | **기존 E2E 무회귀**: 기존 dashboard.spec.ts(S1~S8) 그대로 통과. 가젯 추가/렌더/정적가젯 E2E 신규 시나리오 추가(memory `ui-pr-defer-e2e-regression-latent`). |

## 3. 가젯 컴포넌트별 명세 (MVP 6종)

| 가젯 | 본문 렌더 | 설정 폼 | 데이터 |
|---|---|---|---|
| assigned_to_me | 이슈 키·제목 목록(최대 maxItems) | projectKey(필수), maxItems(1~50) | fetchIssues + assignee 필터 |
| recently_created | 최근 생성 이슈 목록 | projectKey(필수), maxItems(1~50) | fetchIssues 기본 정렬 |
| filter_result | 저장필터 결과 이슈 목록 | **filterId 필수(MVP)**, maxItems | fetchFilter→searchAql |
| issue_count | 큰 숫자(건수) + 라벨 | **filterId 필수(MVP)** | totalElements |

> **Gap 1 해소(sanity check)**. filter_result/issue_count는 **MVP에서 filterId 경로만** 지원한다. aql 직접 입력 위젯은 (a) 저장필터(filterId)와 기능 중복이고 (b) config에 projectKey가 없어 searchAql 호출 불가(projectKey 출처 부재)라 후속으로 미룬다. filterId 경로는 `fetchFilter(id)`가 SavedFilter{projectKey, aqlQuery}를 주므로 projectKey 출처가 명확하다. 따라서 백엔드 보강은 assigned_to_me projectKey 1건으로 최소화(FR-9). 백엔드 config의 aql 필드는 그대로 두되(스키마 무변경) 프론트가 노출하지 않는다.

> **Gap 2 해소(sanity check)**. 이슈 목록 렌더는 두 응답 형식을 받는다 — `fetchIssues`(IssueResponse: key, summary, assigneeId, ...) vs `searchAql`(AqlSearchHit: key, summary, ...). 공통 필드(`key`, `summary`)로 정규화한 경량 뷰 타입(`GadgetIssueRow {key, summary}`)으로 통일해 단일 이슈목록 렌더 컴포넌트가 처리한다. plan에서 정규화 매핑 확정.
| text_widget | 마크다운 렌더(또는 안전 텍스트) | markdown(1~10000, textarea) | 정적 |
| link_list | 링크 목록(label→url) | links 동적 행(label 1~100, url http/https ≤2000), 1~20 | 정적 |

- 이슈 목록 렌더는 키 클릭 시 이슈 상세로 SPA 이동(기존 라우트 재사용).
- text_widget 마크다운: 기존 마크다운 렌더러 재사용 여부 확인(없으면 안전 plain text + 줄바꿈, XSS 차단). plan서 확정.

## 4. 데이터 fetch 전략 (상세)

- **projectKey 필수 제약**(실측): searchAql·fetchIssues 모두 projectKey 필수. assigned_to_me/recently_created는 가젯 config projectKey 사용. filter_result/issue_count는 filterId→SavedFilter.projectKey 사용(MVP filterId 경로만, Gap 1).
- **currentUserId**: `useAuthUser()?.userId`(authStore). null이면 가젯이 "로그인 필요" 표시(이론상 비도달).
- **TanStack Query 키**: 가젯별 + config 해시로 키 구성(다른 config=다른 캐시). staleTime 적정(대시보드 위젯은 즉시성보다 안정).
- **에러 격리**: 한 가젯 fetch 실패가 다른 가젯/페이지를 깨지 않음(가젯별 ErrorBoundary 또는 query error 분기).

## 5. 카탈로그 모달 + 설정 폼 UX

- 모달 열기: 편집 모드 + "가젯 추가" 버튼(canEdit 게이팅).
- 1단계: 가젯 종류 선택(category 그룹, enabled 배지). 2단계: 설정 입력. (PostActionFormDialog 다단계 패턴 참고.)
- 추가 시 그리드 빈 위치에 기본 크기(w/h) 타일 배치(기존 handleAddTile 확장).
- 저장은 기존 handleSave(serializeLayout + version PATCH, OCC 409 로컬 보존) 재사용.

## 6. NFR / 제약

- **계약 drift 0**(memory `frontend-zod-backend-dto-contract-gap`): 카탈로그/config/layout Zod는 백엔드 DTO·GadgetType 1:1. MSW도 동일. spec서 백엔드 파일 grep 대조.
- **BC 격리**: 가젯 데이터는 기존 BC API(issue/search/filter) 재사용. notification 프론트가 직접 쿼리 신설 0. 백엔드 보강은 same-BC(notification) 한정.
- **보안**: link_list url http/https 화이트리스트(클라+백엔드). 외부 링크 `rel="noopener noreferrer"`. text_widget 마크다운 XSS 차단(렌더러 sanitize 또는 plain).
- **무회귀**: 기존 dashboard 단위/E2E 테스트 green. legacy 타일 호환(Gap A).
- **절대 규칙**(DEVELOPMENT.md §1): TS strict non-null, 명시 에러, 파일 헤더 한 줄 주석(한국어), ktlint/lint/typecheck 통과.

## 7. 엣지 케이스

- EC1. 가젯 50개 초과 추가 시도 → 클라 차단 + 백엔드 400(MAX_GADGETS). "최대 50개" 안내.
- EC2. layout 64KB 초과(대형 markdown 다수) → 백엔드 400. 클라 사전 경고 가능(plan).
- EC3. filter_result filterId·aql 둘 다 공란 → 클라 검증 차단(requireAtLeastOne).
- EC4. 삭제된 filterId 참조 → fetchFilter 404 → 가젯 "필터를 찾을 수 없음" 에러 상태(페이지 무손상).
- EC5. projectKey 미입력(프로젝트 지정형) → 클라 필수 검증 차단.
- EC6. enabled=false 가젯이 이미 저장돼 있던 대시보드(이론상 비도달, 백엔드가 막음) 조회 → "지원되지 않는 가젯" 안전 표시.
- EC7. link_list url에 javascript:/data: → 클라 차단(http/https만).
- EC8. text_widget 10001자 → 클라 차단.
- EC9. assigned_to_me에서 useAuthUser null → 가젯 안내(비도달).
- EC10. OCC 409(동시 편집) → 기존 패턴(로컬 tiles 보존, invalidate 금지).
- EC11. 빈 대시보드(가젯 0) → 기존 빈 상태 + "가젯 추가" 안내.

## 8. 측정 가능한 완료 기준

1. 카탈로그 api+Zod(백엔드 1:1) + MSW 핸들러. enabled=true 6종만 추가 가능(게이팅 테스트).
2. 가젯 컴포넌트 6종 렌더(데이터 4 + 정적 2) + 로딩/에러/빈 상태 단위 테스트.
3. 설정 폼 6종 + 클라측 config 검증(백엔드 스키마 미러) — 정상/위반(EC3/EC5/EC7/EC8) 단위 테스트.
4. layout 왕복: parseLayout/serializeLayout가 gadgetType/config 무손실 보존 + legacy 타일 호환(Gap A) 단위 테스트.
5. 데이터 fetch 4종 경로(assigned_to_me/recently_created=fetchIssues, filter_result/issue_count=fetchFilter→searchAql) 단위/통합.
6. 백엔드 보강: assigned_to_me projectKey 카탈로그 노출 + GadgetTypeTest green + notification 모듈 `:test ktlintCheck detekt` clean.
7. E2E: 가젯 추가(S1)·이슈가젯 렌더(S2)·정적가젯(S4)·enabled 게이팅(S6)·읽기전용(S7) + 기존 dashboard.spec.ts 무회귀. RGL 드래그 제약은 기존대로 처리.
8. `pnpm verify`(lint+typecheck+test+build) green. 계약 drift grep 대조(카탈로그/layout Zod ↔ 백엔드).

## 9. 문서 동기화 대상

- product `notification-dashboard.md §3.2` — D6/D7 체크 + 프론트 deviation(프로젝트 지정형, 정적 markdown 렌더 방식) 기록.
- ADR §결과 enabled 플래그 게이팅 실현 + assigned_to_me projectKey 보강 메모.
- glossary "가젯(Gadget)" 독립 항목(머지 단계, Maxi 승인).
- `bash scripts/verify-master-plan.sh` 통과.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, gap 2건 발견 후 보강 — PR1 spec 선례).
1. **Gap 1 — filter_result/issue_count aql 직접 경로 projectKey 출처 부재** → MVP filterId 경로만으로 단순화(저장필터 중복·projectKey 출처 명확·백엔드 보강 최소화). §3 보강.
2. **Gap 2 — 이슈 목록 응답 형식 불일치**(fetchIssues IssueResponse ↔ searchAql AqlSearchHit) → 공통 필드 정규화 뷰 타입 `GadgetIssueRow{key,summary}`. §3 보강.

Maxi 결정 필요 gap: 없음(데이터 소스·범위는 도메인 단계서 확정). 단 (a) 프로젝트 지정형 + (b) same-BC 백엔드 보강(assigned_to_me projectKey) + (c) filter_result MVP filterId 한정은 게이트1 재확인 대상.
잔여 plan-영역 확정: text_widget 마크다운 렌더러 유무, projectKey 입력 UX(드롭다운 vs 텍스트), 가젯 기본 타일 크기, GadgetIssueRow 정규화 매핑, 가젯 데이터 query 키/staleTime.
