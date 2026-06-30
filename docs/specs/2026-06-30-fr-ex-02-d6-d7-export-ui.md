# FR-EX-02 D6/D7 — 대용량 비동기 Export 프론트 UI + E2E — 스펙

> slug: fr-ex-02-d6-d7-export-ui · type: ui · 생성: 2026-06-30
> 백엔드 D1~D5 = PR #204 완료. 이 PR = D6(프론트) + D7(E2E). 백엔드 변경 0 목표.

## 개요

동기 Export(FR-EX-01, ≤1만건, PR #203)의 대용량 버전. 검색 결과가 1만건을 초과해 동기 Export가
거부될 때(`SEARCH_EXPORT_LIMIT_EXCEEDED`), 사용자에게 **백그라운드(비동기) Export**를 제안하고,
잡 생성 → 진행률 폴링 → 완료 시 다운로드까지 같은 다이얼로그 안에서 처리한다.

**Maxi 결정 (2026-06-30, 게이트 전 사전 결정)**.
1. **진입 = 자동 분기**. 사용자는 형식/컬럼만 선택. 동기 시도 → 1만 초과 감지 시 비동기 제안.
2. **진행률 = 다이얼로그 내 유지**. 잡 생성 후 같은 다이얼로그가 진행률로 전환, 완료 시 다운로드 버튼.

**핵심 제약 — 백엔드 완료 이벤트 없음**. 백엔드는 알림을 보내지 않는다(백엔드 plan §187).
프론트가 **폴링**으로 완료를 감지하고 자체 진행률/완료를 표시한다.

## 백엔드 계약 (소비, PR #204 확정 — 코드 직접 검증)

| 엔드포인트 | 요청 | 응답 |
|---|---|---|
| `POST /api/v1/search/export-jobs` | `ExportRequest{projectKey, query, format?, columns?}` (동기와 동일 DTO) | 202 `{jobId: UUID, status: "PENDING"}` / 400 AQL·검증 오류 |
| `GET /api/v1/search/export-jobs/{id}` | — | 200 `{jobId, status, progress(0~100), rowCount?, format, errorCode?, downloadReady}` (NON_NULL) / 404 타인·없음 |
| `GET /api/v1/search/export-jobs/{id}/download` | — | 200 octet-stream + `Content-Disposition` / 409 `SEARCH_EXPORT_NOT_READY` / 404 타인·없음 |

- status enum: `PENDING → RUNNING → COMPLETED` / `(PENDING|RUNNING) → FAILED`. 종단 = COMPLETED·FAILED.
- `downloadReady` = COMPLETED && resultObjectKey 존재. 다운로드 가능 신호.
- FAILED 시 `errorCode` ∈ {`SEARCH_EXPORT_LIMIT_EXCEEDED`(10만 초과), `SEARCH_EXPORT_STORAGE_ERROR`}.

### 동기 초과 응답 (자동 분기 트리거 — FR-EX-01 ExportExceptionHandler)

```
ApiError(status=400, body=ProblemDetail{
  errorCode: "SEARCH_EXPORT_LIMIT_EXCEEDED",
  detail: "검색 결과(N건)가 동기 Export 상한(10000건)을 초과합니다. ...",
  resultCount: N,   // 실제 건수
  limit: 10000,
})
```

## 사용자 시나리오 (Given-When-Then)

**S1 — 소량(≤1만) 동기 Export (회귀 0, 기존 경로 보존)**.
- Given 검색 결과 200건, When 형식/컬럼 선택 후 "내보내기", Then 즉시 blob 다운로드 + 다이얼로그 닫힘 (FR-EX-01 그대로).

**S2 — 대용량(>1만) 자동 분기 → 비동기 Export (핵심)**.
- Given 검색 결과 5만건, When "내보내기" → 동기 거부(LIMIT_EXCEEDED),
  Then "검색 결과 50,000건은 대용량입니다. 백그라운드로 내보내시겠습니까?" 확인 단계로 전환.
- When "백그라운드 내보내기" 클릭, Then POST export-jobs → 진행률 단계로 전환(진행률 바 + "내보내는 중...").
- When 폴링이 COMPLETED 감지, Then "✓ 완료 (N행)" + "다운로드" 버튼 표시.
- When "다운로드" 클릭, Then `GET .../{id}/download` → blob 다운로드.

**S3 — 비동기 잡 실패**.
- Given 잡 진행 중 10만 초과 또는 저장 오류, When 폴링이 FAILED 감지,
  Then "내보내기에 실패했습니다. (사유: 결과가 10만건을 초과)" 에러 + "닫기"/"다시 시도".

**S4 — 자동 분기 취소**.
- Given 비동기 제안 단계, When "취소", Then 폼 단계로 복귀(형식/컬럼 유지) 또는 다이얼로그 닫기.

## 기능 요구사항 (FR)

- **FR-1**. 동기 Export 시도 결과가 `ApiError` + `body.errorCode === "SEARCH_EXPORT_LIMIT_EXCEEDED"` 이면
  비동기 제안 단계로 전환한다. `resultCount`를 메시지에 표시한다. 그 외 에러는 기존처럼 인라인 표시(폼 유지).
- **FR-2**. 비동기 제안 단계에서 "백그라운드 내보내기" 클릭 시 동기와 동일한 `{projectKey, query, format, columns}`로
  `POST /search/export-jobs` 호출 → 받은 jobId로 진행률 단계 진입.
- **FR-3**. 진행률 단계에서 `GET /search/export-jobs/{id}`를 주기 폴링(간격 1500ms)한다.
  status가 종단(COMPLETED/FAILED)에 도달하면 폴링을 중단한다.
- **FR-4**. 진행률 표시 — progress(0~100) 바 + 상태 라벨. PENDING="대기 중", RUNNING="내보내는 중...".
- **FR-5**. COMPLETED + downloadReady 시 "다운로드" 버튼. 클릭 시 `GET .../{id}/download` → blob → `triggerBlobDownload`.
- **FR-6**. FAILED 시 errorCode를 한국어 사유로 매핑해 표시(LIMIT_EXCEEDED → "결과가 10만건을 초과합니다",
  STORAGE_ERROR → "저장 중 오류가 발생했습니다", 기타 → 일반 메시지).
- **FR-7**. 다이얼로그를 닫으면 폴링이 정리(cleanup)된다(메모리 누수·orphan 폴링 0). 진행 중 잡은 백엔드에서 계속 처리됨(추적만 끊김).
- **FR-8 (API 클라이언트)**. `@/api/search`에 `submitExportJob`/`fetchExportJobStatus`/`downloadExportJobResult` 3종 함수 추가
  (apiFetch 경유, raw fetch 금지 — CSRF/401-refresh 자동 처리).

## 비기능 요구사항 (NFR)

- **NFR-1 (회귀 0)**. FR-EX-01 동기 **성공 경로(S1)** 동작 불변. 기존 ExportDialog.test.tsx의 동기 성공/컬럼/형식 테스트 무변경 통과. 단, 기존 LIMIT_EXCEEDED→alert 단위 테스트(test:225-243)와 E2E S4(export.spec:239-264)는 자동분기 동작으로 **의도적 교체**(양립 불가 — 리뷰 BLOCKER-3/4).
- **NFR-2 (접근성)**. 진행률 바 `role="progressbar"` + `aria-valuenow`. 상태 전환 시 `role="status"`/`role="alert"`로 스크린리더 고지.
- **NFR-3 (폴링 효율)**. 종단 상태 도달 즉시 폴링 중단. 다이얼로그 unmount 시 폴링 정리. 1500ms 간격(과부하 방지).
- **NFR-4 (BC 격리)**. search BC 프론트 관례 따름(기존 `@/api/search` 확장). 백엔드 변경 0.
- **NFR-5 (타입 안전)**. 폴링 응답 Zod 스키마 검증(`exportJobStatusSchema`) — 백엔드 `ExportJobResponse` DTO 1:1.

## 컴포넌트 설계 — ExportDialog 4단계 상태 머신

`ExportForm`을 `phase` 상태로 확장(또는 단계별 하위 컴포넌트 분리는 plan에서 결정).

```
phase: 'form' | 'confirmAsync' | 'tracking' | 'done'

form         ──[제출, 동기 성공]──────────────▶ (blob 다운로드 + onClose)   ← FR-EX-01 경로 보존
form         ──[제출, LIMIT_EXCEEDED]─────────▶ confirmAsync (resultCount 보관)
form         ──[제출, 기타 에러]──────────────▶ form (submitError 표시)
confirmAsync ──[백그라운드 내보내기]──────────▶ tracking (POST → jobId)
confirmAsync ──[취소]────────────────────────▶ form
tracking     ──[폴링 COMPLETED]──────────────▶ done (downloadReady)
tracking     ──[폴링 FAILED]─────────────────▶ done (errorCode)
done         ──[다운로드]────────────────────▶ GET /{id}/download → blob 다운로드
done(실패)   ──[다시 시도]────────────────────▶ form
```

- 폴링 = TanStack Query `useQuery({ queryKey:['export-job', jobId], queryFn, refetchInterval, enabled: jobId!=null })`.
  `refetchInterval`은 종단 상태면 `false` 반환(중단). `enabled`로 jobId 없을 때 비활성.
- 동기 제출은 기존 `useMutation(exportIssues)` 유지. onError에서 LIMIT_EXCEEDED 판별 후 phase 전환.
- 다이얼로그 닫힘 → Radix Content unmount → useQuery 자동 cleanup(orphan 폴링 0, FR-7).

## 엣지 케이스

- **EC1 — TRACKING 중 다이얼로그 닫기**. 폴링 정리. 잡은 백엔드에서 계속 처리. 재오픈 시 form부터(jobId 미보존, MVP 허용 — Maxi "다이얼로그 내 유지" 결정의 자연 귀결).
- **EC2 — 비동기 잡 생성도 실패(AQL 오류 등)**. 동기에서 통과한 쿼리라 드물지만, POST 실패 시 confirmAsync에 에러 표시 + 폼 복귀 가능.
- **EC3 — 빈 결과**. 백엔드가 progress=100 즉시 COMPLETED. 폴링 1회로 done. (단, 빈 결과는 보통 동기 경로로 끝나므로 비동기 진입 안 함.)
- **EC4 — rowCount=null**. COMPLETED 전까지 null. "N행" 표시는 COMPLETED 후 rowCount 사용(null이면 행 수 생략).
- **EC5 — 폴링 중 네트워크 일시 오류**. TanStack Query 기본 재시도. 지속 실패 시 에러 상태 표시(다시 시도).
- **EC6 — downloadReady=false인데 COMPLETED**. 이론상 없음(백엔드 보장). 방어적으로 download 버튼 비활성.

## 제약 조건

- raw fetch 금지 — apiFetch 경유(401 자동 refresh, CSRF). 단 인증 전 401이 아니므로 issue 없음.
- 폴링 응답 Zod 검증 필수(NFR-5). errorCode/rowCount는 nullable로 스키마 정의.
- 컬럼 정의(EXPORT_COLUMNS)·형식은 FR-EX-01 재사용(중복 정의 금지).
- 백엔드 변경 0(이 PR은 순수 프론트 + E2E).

## 측정 가능한 완료 기준

- [ ] S1(동기 ≤1만) 회귀 0 — 기존 ExportDialog.test.tsx 전부 통과.
- [ ] S2(자동 분기 → 비동기 → 완료 → 다운로드) 단위 테스트 통과(MSW).
- [ ] S3(FAILED) 에러 표시 단위 테스트 통과.
- [ ] FR-7 폴링 cleanup 검증(다이얼로그 닫으면 추가 GET 없음).
- [ ] `@/api/search` 3종 함수 + Zod 스키마 단위 테스트.
- [ ] D7 E2E — 대용량 자동 분기 happy path(MSW stateful PENDING→RUNNING→COMPLETED → 다운로드).
- [ ] pnpm lint + typecheck + test + build 통과.

## D7 — E2E 시나리오 (qa-engineer)

- **E2E-1**. `/search`에서 대용량 쿼리 → "내보내기" → (MSW가 동기 400 LIMIT_EXCEEDED) → 비동기 제안 표시 →
  "백그라운드 내보내기" → MSW stateful 잡(PENDING→RUNNING→COMPLETED) 폴링 → "완료" + "다운로드" 표시.
- MSW 핸들러: `POST /search/export-jobs`(202+jobId), `GET /search/export-jobs/:id`(호출 횟수에 따라 상태 진행 — 메모리 msw-mutation-stateful-refetch / msw-derived-behavior-shared-store-e2e),
  `GET /search/export-jobs/:id/download`(blob).
- 다운로드는 실제 파일 저장 검증이 jsdom/Playwright opaque일 수 있음 → 다운로드 트리거(요청 발생) 검증으로 한정(메모리 fr-mv-01 opaque 한계 참고).

## Brainstorming Check

✅ 직접 adversarial sanity check 통과. gap 8건 검토 — 전부 spec 내 해소(Maxi 결정 추가 불요).
- G1 TRACKING 중 닫기 → EC1(폴링 cleanup + MVP 미보존). G2 download 함수 부재 → FR-8(신규 함수).
  G3 FAILED errorCode 매핑 → FR-6. G4 폴링 401 → apiFetch 자동. G5 progress PENDING=0 → FR-4.
  G6 동기 회귀 → NFR-1. G7 비동기 잡 생성 실패 → EC2. G8 폴링 무한 → FR-3(종단 중단)+NFR-3(unmount cleanup).
