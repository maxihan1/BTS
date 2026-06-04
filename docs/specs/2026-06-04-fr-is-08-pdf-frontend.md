# FR-IS-08 D6/D7 — 이슈 PDF 출력 프론트엔드 — 스펙

> slug: fr-is-08-pdf-frontend · BC: issue-tracking (apps/web) · type: ui
> 생성: 2026-06-04 · 백엔드 의존: GET /api/v1/issues/{key}/pdf (PR #71 머지됨)

## 개요

이슈 상세 페이지에서 현재 이슈를 PDF로 다운로드하는 버튼을 추가한다. 백엔드가 반환하는
`application/pdf`(`Content-Disposition: attachment; filename="{key}.pdf"`)를 받아 브라우저
다운로드를 트리거한다. 코드베이스 **첫 바이너리(blob) 다운로드** 패턴을 확립한다.

## 사용자 시나리오 (Given-When-Then)

**S1 — 정상 다운로드**
- Given 이슈 상세 페이지(`/issues/ATLAS-42`)가 로드돼 있다.
- When breadcrumb 행 우측의 "PDF" 다운로드 버튼을 클릭한다.
- Then `{key}.pdf` 파일이 브라우저로 다운로드된다. 클릭 중 버튼은 비활성+로딩 표시.

**S2 — 다운로드 중 중복 클릭 방지**
- Given 다운로드 진행 중.
- When 버튼을 다시 클릭.
- Then 비활성 상태라 중복 요청 안 감.

**S3 — 다운로드 실패**
- Given 백엔드가 5xx/네트워크 오류 반환.
- When 버튼 클릭.
- Then 에러 토스트(sonner) 표시. 버튼은 다시 활성. 페이지 깨지지 않음.

**S4 — 본문 없는 이슈**
- Given description 없는 이슈.
- When 버튼 클릭.
- Then 백엔드가 메타만 있는 유효 PDF 반환 → 정상 다운로드(프론트는 본문 유무 무관).

## 기능 요구사항 (FR)

- **FR1** `src/api/issues.ts`에 `downloadIssuePdf(key): Promise<Blob>` 추가. `apiFetch`로 GET 호출,
  `res.ok` 아니면 `ApiError`, ok면 `res.blob()` 반환. Zod 파싱 안 함(바이너리).
- **FR2** 이슈 상세 페이지 breadcrumb 행 우측에 PDF 다운로드 버튼(secondary `Button` + lucide 아이콘 +
  "PDF" 라벨 + aria-label). 클릭 시 blob 다운로드 트리거.
- **FR3** 다운로드 트리거 = `URL.createObjectURL(blob)` → 임시 `<a download="{key}.pdf">` 클릭 → `revokeObjectURL`.
- **FR4** 로딩 상태: 다운로드 중 버튼 `disabled` + 로딩 표시. 완료/실패 시 해제.
- **FR5** 실패 시 sonner `toast.error` + i18n 문자열(`issueDetailStrings`에 추가).

## 비기능 요구사항 (NFR)

- **NFR1** GET 요청이라 CSRF 토큰 불요(`apiFetch`가 Authorization+credentials 자동 처리).
- **NFR2** blob URL 메모리 누수 방지 — 다운로드 후 `revokeObjectURL` 필수.
- **NFR3** BC 격리 — apps/web 내부 완결. issues.ts 관례(ApiError) 준수.
- **NFR4** 접근성 — 버튼 aria-label, 키보드 포커스 가능.

## API 인터페이스 (프론트)

```ts
// src/api/issues.ts
export async function downloadIssuePdf(key: string): Promise<Blob> {
  const res = await apiFetch(`/api/v1/issues/${key}/pdf`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return res.blob()
}
```

## 데이터 모델 변경

- **없음**. 백엔드 변경 0. 프론트 신규 API 함수 + UI만.

## 기술 설계

### blob 다운로드 헬퍼 (코드베이스 첫 패턴)
- 다운로드 트리거 로직은 작은 헬퍼(`triggerBlobDownload(blob, filename)`)로 분리 — 재사용 + 테스트 용이.
  위치: `src/lib/download.ts`(신규) 또는 컴포넌트 인접. jsdom/vitest에서 `URL.createObjectURL` mock 필요.
- 버튼 클릭 핸들러: `useState` 로딩 + `downloadIssuePdf` 호출 + `triggerBlobDownload` + try/catch 토스트.
  (React Query mutation으로 감싸도 되나, 캐시 무효화 대상이 없어 단순 async 핸들러로 충분.)

### 버튼 배치
- breadcrumb 행(`<nav aria-label="이동 경로">` 인접)을 flex로 만들어 우측에 버튼 정렬.
- 대안(plan에서 확정 가능): 우측 메타패널 액션 영역. 기본은 breadcrumb 행 우측(문서 수준 export 액션).

### i18n
- `src/i18n/ko.ts`의 `issueDetailStrings`에 `pdfDownloadButton`(예: "PDF"), `pdfDownloadAriaLabel`,
  `pdfDownloadError`(실패 토스트) 추가.

## 엣지 케이스 (Phase B 새너티 — 인라인)

1. **다운로드 중 중복 클릭** → disabled로 차단(S2).
2. **실패 응답이 JSON 아닐 때** → `res.json().catch(() => ({}))`로 안전 처리, 토스트는 일반 메시지.
3. **blob URL 누수** → revokeObjectURL 호출(NFR2). 테스트로 호출 검증.
4. **이슈 로드 전 클릭 불가** → 버튼은 이슈 로드 성공 레이아웃에만 렌더(issue 존재 보장).
5. **filename** → 앵커 `download="{key}.pdf"`로 명시(백엔드 Content-Disposition과 일치).
6. **MSW 환경** → jsdom에 `URL.createObjectURL`/`revokeObjectURL` 미구현 → 테스트에서 mock. E2E는 실제 브라우저라 동작.
7. **본문 없는 이슈** → 프론트 무관, 정상 다운로드(S4).
8. **vitest 단위에서 blob 다운로드** → createObjectURL mock + 앵커 click spy로 검증.

## 제약 조건

- issues.ts API 관례(ApiError, apiFetch) 준수. 새 에러 체계 만들지 않음.
- 단위 테스트 + E2E 모두 작성(D6 단위 + D7 E2E). UI PR이 기존 E2E 셀렉터 깨뜨리지 않게 확인([[ui-pr-defer-e2e-regression-latent]]).

## 측정 가능한 완료 기준

- [ ] `downloadIssuePdf` 단위 테스트(200→blob, 5xx→ApiError).
- [ ] PDF 버튼 클릭 시 createObjectURL+앵커 click+revokeObjectURL 호출(단위, mock).
- [ ] 다운로드 중 버튼 disabled, 실패 시 toast.error(단위).
- [ ] E2E(Playwright): 버튼 클릭 → download 이벤트 발생 + 파일명 `{key}.pdf` 검증. MSW 핸들러 추가.
- [ ] 기존 이슈 상세 E2E 회귀 0(셀렉터 strict mode 확인).
- [ ] typecheck(tsconfig.app) + eslint + vitest 그린.

## Brainstorming Check
✅ 통과 (인라인 새너티 — 엣지 8건 망라. office-hours/design-shotgun은 단일 버튼 추가에 부적합하여 직접 작성, 배치는 기존 상세 페이지 패턴 따름).
