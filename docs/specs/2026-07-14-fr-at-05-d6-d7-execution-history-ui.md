<!-- FR-AT-05 D6/D7 실행 이력 UI 스펙 — Radix Dialog 모달(목록+trace+replay), 백엔드 #270 계약 위 순수 프론트 -->

# FR-AT-05 D6/D7 — 자동화 규칙 실행 이력 UI 스펙

> 날짜. 2026-07-14 | BC. automation | type. ui | 선행. #270(백엔드 D1~D5) | 배치 결정. Radix Dialog 모달(Maxi 확정)

## 개요

자동화 규칙이 실제로 언제·어떤 트리거로·어떤 결과로 실행됐는지 조회하는 UI. 진입점은 automation 설정 페이지(`/projects/$projectKey/settings/automation`)의 각 룰 행에 추가하는 **"이력"** 버튼. 클릭 시 Radix Dialog 모달이 열려 해당 룰의 실행 이력 목록을 최신순으로 보여주고, 각 실행을 펼치면 액션별 결과(trace)와 원본 트리거(triggerEvent)를 확인하고 재실행(replay)할 수 있다.

**순수 프론트엔드.** 백엔드(엔드포인트·DTO·권한·에러코드)는 #270로 완결. 신규 백엔드/스키마/ADR 0.

## 사용자 시나리오 (Given-When-Then)

### S1. 실행 이력 조회
- **Given** 관리자가 프로젝트 자동화 설정 페이지에서 룰 목록을 본다
- **When** 특정 룰 행의 "이력" 버튼을 클릭한다
- **Then** Dialog가 열리고 그 룰의 실행 이력이 최신순으로 표시된다(각 행 = status 배지·트리거 타입·이슈 키·시작 시각·`성공/전체` 액션 수)

### S2. 단계별 trace 펼침
- **Given** 실행 이력 목록이 열려 있다
- **When** 실행 1건의 행을 클릭한다
- **Then** 그 행이 인라인 펼쳐져 액션별 결과(position·actionType·성공✓/실패✗·error 코드)와 원본 triggerEvent(JSON)가 표시된다

### S3. 재실행 (replay)
- **Given** trace가 펼쳐져 있고 replay 대상 실행이 보인다
- **When** "재실행" 버튼을 누르고 확인 다이얼로그에서 확정한다
- **Then** 저장된 원본 트리거로 실제 재실행되어 새 실행 이력이 목록 맨 위에 추가되고 성공 토스트가 뜬다

### S4. 재실행 불가(소프트 삭제된 룰)
- **Given** replay 대상 룰이 이미 소프트 삭제됐다
- **When** 재실행을 확정한다
- **Then** 409 응답 → "재실행 대상 자동화 룰을 더 이상 사용할 수 없습니다" 토스트, 목록 불변

### S5. 빈 이력
- **Given** 아직 한 번도 실행되지 않은 룰
- **When** "이력" 버튼을 클릭한다
- **Then** "실행 이력이 없습니다" 빈 상태가 표시된다

### S6. 더 보기(페이지네이션)
- **Given** 실행 이력이 기본 조회 건수(50)를 초과한다
- **When** 목록 하단 "더 보기"를 누른다
- **Then** keyset 커서(`before`=마지막 행 startedAt)로 다음 페이지가 조회돼 목록에 이어 붙는다

### S7. 이슈별 필터
- **Given** 실행 이력 목록이 열려 있다
- **When** issueKey 필터 입력에 이슈 키를 넣는다
- **Then** 해당 이슈에 대한 실행만 조회된다(SDD "이 이슈에 영향을 준 자동화" 대응)

## 기능 요구사항 (FR)

- **FR1** automation 설정 페이지의 각 룰 행에 "이력" 버튼 추가(기존 활성/수정/삭제와 형제, aria-label `${rule.name} 실행 이력`).
- **FR2** "이력" 버튼 클릭 → Radix Dialog 모달로 해당 룰(ruleId)의 실행 이력 목록을 최신순 조회·표시.
- **FR3** 목록 행 요약. status 배지(SUCCESS/PARTIAL/FAILED/SKIPPED, 색+텍스트) · 트리거 타입 · 이슈 키(없으면 "이슈 없음") · 시작 시각(상대+절대 tooltip) · `successCount/actionCount 성공`. replay로 생성된 실행이면 "재실행됨" 표식(replayedFrom 존재).
- **FR4** 목록 행 클릭 → 인라인 펼침. 액션별 결과 목록(position 순, actionType·✓/✗·실패 시 error 코드) + 원본 triggerEvent JSON(스크롤 가능한 pretty-print `<pre>`).
- **FR5** 펼친 trace에 "재실행" 버튼. 클릭 → **인라인 2단계 확인**("실제 이슈 변경이 발생합니다 [확정][취소]", 중첩 Dialog 회피 — B-gap2) → 확정 시 `POST .../executions/{id}/replay`.
- **FR6** replay 성공 → 응답으로 받은 새 실행을 목록 맨 위에 추가(stateful) + **새 실행 trace 자동 펼침**(B-gap1, ADR "고치고 재시도 → 결과 바로 확인" 흐름) + `sonner` 성공 토스트. 재실행 진행 중 버튼 비활성(중복 방지).
- **FR7** 페이지네이션. limit 50, 마지막 페이지 개수가 limit과 같으면 "더 보기" 노출 → `before` 커서(마지막 행 startedAt)로 다음 페이지 append.
- **FR8** issueKey 필터 입력(선택). exact match이므로 **Enter/제출 시 적용**(키 입력마다 재조회 아님) + 필터 변경 시 누적 목록·커서 **리셋**(B-gap3).
- **FR10** triggerType 표시 = **한국어 라벨 맵**(ISSUE_CREATED→"이슈 생성" 등 5종) + 미지 값은 원문 fallback(응답이 String이라 미래 값 견고, B-gap4).
- **FR9** 빈 상태("실행 이력이 없습니다") · 로딩 상태 · 에러 상태(403/404/기타) 각각 처리.

## 비기능 요구사항 (NFR)

- **NFR1(접근성).** status는 색상 단독 금지 — 색 + 텍스트 라벨 병기. Dialog는 role/aria 준수(기존 automation 모달 관례). "이력" 버튼 aria-label 명시.
- **NFR2(성능).** keyset 커서 페이지네이션(offset 아님) · 기본 50건 · triggerEvent JSON은 max-height 스크롤로 대형 payload 억제.
- **NFR3(보안).** 백엔드가 MANAGE_AUTOMATION 강제(목록 403·단건/replay 404 존재숨김). 프론트는 이미 MANAGE_AUTOMATION 게이팅된 설정 페이지 내부이므로 별도 버튼 게이팅 불요(에러 상태만 방어).
- **NFR4(완제품).** 단위 + E2E 커버. 빈 catch 금지, 에러코드 기반 분기.

## API 인터페이스 (REST) — #270 확정, 프론트는 소비만

봉투 없음(bare DTO), `apiGet(path, zodSchema)` / `apiFetch`+`throwIfNotOk`+`.parse` 관례(automation-rules.ts 동형).

| # | Method · Path | 응답 | 쿼리 | 에러 |
|---|---|---|---|---|
| 1 | `GET /api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` | `RuleExecutionSummary[]` (bare 배열) | `issueKey?`·`limit`(50)·`before?`(ISO Instant) | 403 `AUTOMATION_ACCESS_DENIED` · 401 · 400 `AUTOMATION_MALFORMED_REQUEST` |
| 2 | `GET /api/v1/automation/executions/{id}` | bare `RuleExecutionDetail` | — | 404 `AUTOMATION_EXECUTION_NOT_FOUND` · 401 |
| 3 | `POST /api/v1/automation/executions/{id}/replay` | 새 실행 `RuleExecutionDetail` | — | 404 · 409 `AUTOMATION_RULE_UNAVAILABLE` · 401 · 403 |

**참고.** 목록(요약)은 `outcomes`/`triggerEvent`를 담지 않는다 → trace 펼침 시 #2(단건)로 상세를 별도 조회한다. replay 응답(#3)은 이미 detail 형태라 새 실행의 trace를 즉시 반환한다.

### DTO → Zod 계약 (신규 `api/automation-executions.types.ts`)

```
ruleExecutionStatusSchema = z.enum(['SUCCESS','PARTIAL','FAILED','SKIPPED'])

actionOutcomeSchema = {
  position: z.number().int(),
  actionType: z.string(),
  success: z.boolean(),
  error: z.string().nullable(),        // ← nullable
}

ruleExecutionSummarySchema = {
  id: z.string().uuid(),
  ruleId: z.string().uuid(),
  triggerType: z.string(),
  issueKey: z.string().nullable(),      // ← nullable
  status: ruleExecutionStatusSchema,
  actionCount: z.number().int(),
  successCount: z.number().int(),
  startedAt: z.string().datetime(),
  finishedAt: z.string().datetime(),
  replayedFrom: z.string().uuid().nullable(),  // ← nullable
}

ruleExecutionDetailSchema = ruleExecutionSummary + {
  projectKey: z.string(),
  triggerEvent: z.unknown(),            // ← 임의 JSON(JsonNode)
  outcomes: z.array(actionOutcomeSchema),
}

목록 응답 = z.array(ruleExecutionSummarySchema)  // bare 배열
```

**Zod 함정([[frontend-zod-backend-dto-contract-gap]]·[[zod-v4-uuid-fixture-strictness]]).** issueKey·replayedFrom·error는 `.nullable()` 필수(누락 시 실제 파싱에서 조용히 깨짐). triggerEvent는 `z.unknown()`. fixture UUID는 RFC4122 v4 형식.

## 데이터 모델 변경

없음(순수 UI). triggerType은 백엔드 status/type 문자열을 그대로 표시(자동화 룰의 5종 triggerType과 동일 enum이나, 응답은 String이므로 Zod는 `z.string()`으로 느슨히 받아 미래 값 추가에 견고).

## 엣지 케이스

- **EC1.** issueKey null(이슈 무관 실행, 예 SCHEDULED) → "이슈 없음" 표시.
- **EC2.** status SKIPPED(조건 불충족) → 회색 배지 + "조건 불충족", actionCount 0 가능.
- **EC3.** outcomes[].error는 코드 문자열(사용자 메시지 아님) → 디버깅 도구이므로 코드 그대로 노출.
- **EC4.** triggerEvent 빈 객체 `{}` / 대형 중첩 JSON → pretty-print + max-height 스크롤.
- **EC5.** replay 409(소프트 삭제 룰) → 토스트, 목록 불변(S4).
- **EC6.** replay 진행 중 Dialog 닫힘 → react-query mutation 계속(크래시 없음), 재오픈 시 최신 목록 반영.
- **EC7.** replay 중복 클릭 → 진행 중 버튼 disabled.
- **EC8.** 페이지네이션 마지막 페이지(len < limit) → "더 보기" 숨김.
- **EC9.** trace 펼침 중 단건 조회 404 → 인라인 에러("실행 이력을 찾을 수 없습니다"), 목록 유지.
- **EC10.** 목록 403 → Dialog 내 권한 에러 상태.
- **EC11.** replayedFrom 존재 → "재실행됨" 표식(원본에서 파생된 실행 구분).
- **EC12.** actionCount/successCount 표기 — PARTIAL이면 `1/3 성공`처럼 부분 성공 가시화.
- **EC13.** MSW 신규 handlers.ts는 `mocks/handlers.ts` 전역 배열에 반드시 등록([[msw-global-handler-registration-gap]] — 미등록 시 단위는 통과, E2E만 누출).

## 제약 조건

- **한 PR = 한 BC(automation).** 신규 cross-BC 호출 없음.
- **automation UI 관례 미러.** Radix Dialog 프리미티브(`import { Dialog as DialogPrimitive } from 'radix-ui'`), `sonner` 토스트, bare DTO, `apiGet`/`apiFetch`, 파일 구조(`api/*.types.ts`+`api/*.ts`+`api/use*.ts`+`components/automation/*`+`mocks/*-handlers.ts`+`*-fixtures.ts`).
- **라우터 변경 없음.** Dialog 배치라 router.ts 무변경(전용 라우트 안 씀).
- **날짜 표시.** 기존 프로젝트 날짜 유틸 재사용(있으면). ISO Instant → 상대/절대.

## 측정 가능한 완료 기준

- [ ] "이력" 버튼 → Dialog로 실행 이력 목록 조회(S1) — 단위 + E2E.
- [ ] 행 클릭 → trace 인라인 펼침(액션별 결과 + triggerEvent JSON)(S2) — 단위.
- [ ] 재실행 확인 → replay 성공 → 새 실행 목록 맨 위 추가 + 토스트(S3) — 단위 + E2E(MSW stateful).
- [ ] replay 409 소프트삭제 룰 → 토스트, 목록 불변(S4) — 단위(MSW 시나리오 토글).
- [ ] 빈 이력 상태(S5) · 더 보기 페이지네이션(S6) · issueKey 필터(S7) — 단위.
- [ ] Zod nullable 3필드 + triggerEvent unknown 계약 준수 — 타입 테스트.
- [ ] MSW 핸들러 handlers.ts 전역 등록 확인 — setupServer 단위 + E2E 둘 다 green.
- [ ] `pnpm verify`(lint + typecheck + test + build) 통과 · 기존 E2E 회귀 없음.

## Brainstorming Check

✅ 통과 (1회 gap 분석, Maxi 결정 불요 4건 기본값 해소).

발견 gap + 해소.
- **B-gap1(replay 후 UX).** 새 실행을 목록 추가만으론 디버깅 흐름 미완 → replay 성공 시 새 실행 trace 자동 펼침(FR6).
- **B-gap2(중첩 Dialog).** 이력 Dialog 안에 확인 Dialog 중첩은 Radix focus-trap 꼬임 위험 → 인라인 2단계 확인(FR5).
- **B-gap3(필터 재조회).** exact match issueKey를 키 입력마다 재조회하면 낭비/깜빡임 → Enter/제출 적용 + 커서 리셋(FR8).
- **B-gap4(triggerType 표시).** 원문 enum 문자열 노출은 비친화 → 한국어 라벨 맵 + 원문 fallback(FR10).

추가 확인(gap 아님, 설계 확정).
- 목록=요약(outcomes/triggerEvent 없음) → trace 펼침 시 단건(#2) 별도 조회, react-query queryKey per executionId 캐시.
- replay 응답(#3)은 detail → 목록 행(summary)으로 매핑 시 actionCount=outcomes.size·successCount=성공 개수(백엔드 SummaryResponse.from 동형).
- status 색: SUCCESS/PARTIAL/FAILED/SKIPPED = green/amber/red/gray(DESIGN.md 토큰), 색+텍스트 병기(NFR1).
- 시작 시각·소요(finishedAt-startedAt) 표시는 기존 프로젝트 날짜 유틸 재사용.
