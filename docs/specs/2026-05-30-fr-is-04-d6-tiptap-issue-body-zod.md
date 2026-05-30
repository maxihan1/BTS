# FR-IS-04 D6 프론트 — 이슈 본문(Markdown) + 우선순위/라벨/환경/영향도 — 스펙

> slug: fr-is-04-d6-tiptap-issue-body-zod
> type: ui / frontend-engineer / BC=issue-tracking
> 작성: 2026-05-30
> 백엔드: PR #43(be8780e) 머지 완료. 이 스펙은 그 계약의 프론트 노출.

## 에디터 방식 결정 (Maxi 확정)

- **본문 에디터 = GitHub 스타일 Write/Preview 탭.** TipTap 미사용(slug의 "tiptap"은 잔존 명칭, 실제 미도입).
  - 근거: 백엔드가 PR #43에서 **Markdown 원본 저장 + `descriptionHtml` 렌더(OWASP 정화)** = GitHub 모델로 확정. 프론트도 Markdown-native가 계약 정합. TipTap WYSIWYG는 MD↔HTML round-trip 손실 위험만 추가.
  - **의존성 0개 추가** → 게이트1 라이브러리 승인 불필요.
  - Jira Cloud식 ADF/WYSIWYG는 백엔드 ADF 재설계 선행이 필요해 D6 범위 밖(별도 FR).

## 사용자 시나리오 (Given-When-Then)

### S1 — 본문 표시 (Preview)
- **Given** 본문이 있는 이슈 상세를 연다 (단건 GET → `descriptionHtml` 채워짐)
- **When** Preview 탭(기본)을 본다
- **Then** `descriptionHtml`(백엔드 정화본)이 렌더된다. 본문이 null이면 "본문 없음" placeholder.

### S2 — 본문 편집/저장
- **Given** Preview 탭에서 "편집" 진입 → Write 탭
- **When** raw Markdown을 textarea에 입력하고 "저장"
- **Then** `PATCH {description, expectedVersion}` 전송 → 성공 시 캐시 무효화 → Preview가 새 `descriptionHtml`로 갱신.

### S3 — 본문 클리어
- **Given** 본문이 있는 이슈
- **When** Write에서 전체 삭제 후 저장 (빈 문자열)
- **Then** `PATCH {description: "", expectedVersion}` 전송(3-state 클리어 sentinel) → 본문 null → Preview "본문 없음".

### S4 — 우선순위 변경
- **Given** 메타패널 우선순위 셀렉터(현재값 표시, 1=Highest..5=Lowest)
- **When** 다른 우선순위 선택
- **Then** `PATCH {priority, expectedVersion}` 즉시 전송(typeChange 패턴) → 성공 시 셀렉터·배지 갱신.

### S5 — 영향도 설정
- **Given** 메타패널 영향도 셀렉터(1=High,2=Med,3=Low + 초기 "미지정")
- **When** 영향도 선택
- **Then** `PATCH {impact, expectedVersion}` 전송 → 갱신.
- **제약**: 백엔드 `impact`는 `null=무변경`만 있고 클리어 sentinel 부재 → **한번 설정하면 다시 "미지정"으로 못 비움**. "미지정"은 초기 표시값일 뿐 전송 불가(선택 시 무시 또는 disabled).

### S6 — 라벨 추가/삭제 (칩)
- **Given** 메타패널 라벨 칩 입력
- **When** 라벨 입력 후 Enter/콤마 → 칩 추가, 칩 x → 삭제, 저장
- **Then** `PATCH {labels: [...], expectedVersion}` 전송. 빈 배열이면 전체 제거(3-state).
- 클라 검증: 라벨당 ≤50자, 목록 ≤20개, 공백/중복 제거(백엔드 정규화와 정합).

### S7 — 환경 입력
- **Given** 메타패널 환경 텍스트 입력(≤1000자)
- **When** 입력 후 저장
- **Then** `PATCH {environment, expectedVersion}` 전송. 빈 문자열이면 클리어(3-state).

### S8 — 낙관적 잠금 충돌 (OCC, 모든 변경 공통)
- **Given** 다른 탭/사용자가 먼저 수정해 version이 올라감
- **When** 내가 저장
- **Then** 409 VERSION_CONFLICT → toast 안내 + 최신 데이터 재조회(typeChange 패턴 재사용).

## 기능 요구사항 (FR)

- **FR1** `issueResponseSchema`에 8필드 추가: `description`(string|null), `descriptionHtml`(string|null), `priority`(int 1~5), `priorityName`(string), `labels`(string[]), `environment`(string|null), `impact`(int 1~3|null), `impactName`(string|null).
- **FR2** `UpdateIssueInput`에 5필드 추가(전부 optional): `description?`, `priority?`, `labels?`, `environment?`, `impact?`. merge-patch 시맨틱 — 미전달=무변경.
- **FR3** 본문 영역: Write/Preview 탭. Preview=`descriptionHtml` 렌더, Write=raw `description` textarea. `issues.$key.tsx:279-282` 자리표시자 교체.
- **FR4** 메타패널: 우선순위 셀렉터, 영향도 셀렉터, 환경 텍스트, 라벨 칩 추가(IssueTypeSelect/IssueStateTransition 패턴 재사용).
- **FR5** 각 필드 변경은 mutation으로 `updateIssue(key, {필드, expectedVersion})` 호출 + 성공 시 캐시 무효화/setQueryData.
- **FR6** i18n: 신규 라벨/안내 문자열을 `i18n/ko`의 `issueDetailStrings`에 추가.

## 비기능 요구사항 (NFR)

- **NFR1** 표시는 항상 `descriptionHtml`(정화본). raw `description`은 Write 편집 textarea에서만 사용 → stored XSS 방지.
- **NFR2** `descriptionHtml` 렌더는 백엔드 정화 신뢰 하에 `dangerouslySetInnerHTML` 사용(프론트 추가 정화 없음). 정화 책임은 백엔드 OWASP Sanitizer(코드리뷰 명시 포인트).
- **NFR3** 저장 진행 중 중복 클릭 방지(mutation.isPending → disabled).
- **NFR4** WCAG AA: 모든 셀렉터/입력/버튼 min-h-[44px], aria-label.
- **NFR5** TypeScript strict 통과, Zod 스키마가 backend DTO와 1:1(계약갭 방지).

## API 인터페이스 (REST) — 백엔드 기존, 신규 없음

- `GET /api/v1/issues/{key}` → IssueResponse(descriptionHtml 단건만 채워짐). `fetchIssue` 사용 → 정합.
- `PATCH /api/v1/issues/{key}` → UpdateIssueRequest merge-patch 3-state. 계약 표는 plan `## 도메인 정리` 참조.

## 데이터 모델 변경

- 없음(프론트 전용). 백엔드 V006 마이그레이션은 PR #43에서 완료.

## 엣지 케이스

- **EC1** description=null → Preview "본문 없음", Write 빈 textarea.
- **EC2** description 미변경 저장 → 같은 값 전송 또는 본문 mutation 미발화(불필요 PATCH 회피).
- **EC3** impact 클리어 불가(백엔드 sentinel 부재) → "미지정"은 초기값만, 설정 후 되돌리기 disabled.
- **EC4** labels 중복/공백/초과 → 클라 정규화(백엔드 검증과 동일 규칙) + 초과 시 입력 차단.
- **EC5** descriptionHtml이 목록 응답엔 null → 목록 화면에서 본문 표시 금지(단건 GET만 표시).
- **EC6** 409 VERSION_CONFLICT → 모든 필드 mutation onError에서 재조회 + toast.
- **EC7** priorityName/impactName은 표시용(셀렉터 옵션 라벨은 한글 매핑 또는 영문 displayName 그대로 — i18n 결정).

## 제약 조건

- BC: issue-tracking 단일. 다른 BC import 금지.
- 화면 파일(IssueMetaPanel/issues.$key)은 PR #39 D6·PR #41 전이UI와 동일 → 패턴 일관성 유지.
- MSW(목 서버) mutation 핸들러는 stateful 오버라이드 영속(메모리 msw-mutation-stateful-refetch) — refetch 후 롤백 방지.

## 측정 가능한 완료 기준

- [ ] `issueResponseSchema` 8필드 + `UpdateIssueInput` 5필드 추가, issues.test.ts 통과.
- [ ] 본문 Write/Preview 탭 동작(표시=descriptionHtml, 편집=description textarea, 저장/클리어).
- [ ] 우선순위/영향도 셀렉터 + 환경 입력 + 라벨 칩 동작 + 각 PATCH.
- [ ] OCC 409 처리(typeChange 패턴 재사용).
- [ ] lint 0 / typecheck 0 / vitest 전부 green / build OK.
- [ ] (D7 후속) Playwright E2E는 별 PR.

## 저장 인터랙션 모델 (Sanity Check 보강 — gap1)

선례 기반 결정(typeChange/transition = "셀렉터 즉시 PATCH" 확립):
- **우선순위·영향도 셀렉터**: 선택 즉시 PATCH(typeChange 패턴). 별도 저장 버튼 없음.
- **본문(Write 탭)**: 명시적 "저장" 버튼(제목 편집 패턴). 편집 모드 ↔ Preview 토글.
- **환경 텍스트**: 명시적 "저장" 버튼(빈 입력 가능 = 클리어). textarea 편집 후 저장.
- **라벨 칩**: 칩 추가/삭제는 로컬 상태 → "저장" 버튼으로 일괄 PATCH(중간 상태 누적 후 1회 전송). 빈 배열 저장 = 전체 제거.
- 근거: 셀렉터는 단일 선택이라 즉시 전송이 자연스럽고, 텍스트/칩은 다중 편집이라 일괄 저장이 불필요 PATCH·OCC 충돌을 줄임.

## 읽기/편집 표시 (Sanity Check 보강 — gap2)

메타패널 각 필드는 **현재값 읽기 표시 + 편집 컨트롤**을 함께 노출(기존 유형 패널이 아이콘+이름 표시 후 셀렉터 두는 패턴 일관):
- 우선순위: `priorityName` 표시 + 셀렉터.
- 영향도: `impactName`(없으면 "미지정") 표시 + 셀렉터.
- 라벨: 현재 칩 목록 표시 + 추가/삭제 + 저장.
- 환경: 현재 텍스트 표시 + 편집 + 저장.

## Brainstorming Check

✅ 통과 (직접 sanity review 1회). gap 2건 발견·보강 — (1) 메타필드별 저장 인터랙션 모델(셀렉터=즉시 PATCH, 본문/환경/라벨=명시 저장), (2) 읽기값 표시 + 편집 컨트롤 병기. Maxi 결정 필요 항목 없음(전부 typeChange/transition 선례로 자연 해소). 핵심 위험은 계약갭(Zod↔DTO) + impact 클리어 불가 제약 + descriptionHtml 신뢰 렌더 — 전부 스펙 NFR/EC에 명시.
