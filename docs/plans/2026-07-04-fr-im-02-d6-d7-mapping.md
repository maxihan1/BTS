# FR-IM-02 D6/D7 — Import 매핑 마법사 프론트엔드

> slug: fr-im-02-d6-d7-mapping
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-04

## Brief

FR-IM-02 D6/D7 — Import 매핑 마법사 프론트엔드. 백엔드 3-PR(#230 PR-A 필드매핑 / #233 PR-B 사용자매핑 / #234 PR-C 값매핑) 완결, 프론트 UI만 남음.
FR-IM-01 D6/D7(#229) ImportForm(즉시 업로드 폼)을 analyze→필드/사용자/값 매핑→confirm→실행 마법사로 확장.
classify: type=ui, agent=frontend-engineer.

## 도메인 정리

- **BC**: search-export-import (프론트는 `apps/web` SPA, 백엔드 API 소비)
- **영향 엔티티**: 없음 (신규 도메인/엔티티 0 — 확정된 DTO 소비만)
- **새 용어**: 없음. 필드 매핑 / 사용자 매핑 / 값 매핑 / `AWAITING_MAPPING` 모두 ADR·백엔드 3-PR에서 확정
- **기존 결정 충돌**: 없음
- **관련 ADR**: [2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md) — FR-IM-02 전체 아키텍처(D1~D5). "프론트 D6/D7 다단계 마법사(필드/사용자/값) + E2E"를 명시적 후속으로 규정. **신규 ADR 불필요**(이 PR은 확정된 흐름을 UI로 구현).
- **grill-with-docs 스킵**: 순수 UI 미러 작업(백엔드 완결·신규 용어 0). 도메인 정착 확인으로 갈음.
- **핵심 흐름 (ADR D1/D5)**: `analyze(업로드+persist→AWAITING_MAPPING)` → `필드매핑` → `(그 매핑 기준) distinct 사용자/값 수집` → `사용자/값 매핑` → `confirm(→PENDING+enqueue)` → 기존 폴링(FR-IM-01 재사용) → COMPLETED/FAILED. 순서 의존: 필드 매핑 먼저, 사용자/값 매핑 나중.

## 스펙

전체 스펙. [docs/specs/2026-07-04-fr-im-02-d6-d7-mapping.md](../specs/2026-07-04-fr-im-02-d6-d7-mapping.md)

핵심 요약.
- **두 모드 병존** (Maxi): 설정 Import 페이지 상단 토글 — "바로 가져오기"(기존 ImportForm 무변경) / "매핑하며 가져오기"(신규 마법사). 근거: analyze 경로는 첨부 zip 미지원.
- **마법사 흐름**: 업로드/분석(POST /analyze) → (CSV)필드 매핑(validate) → 사용자 매핑(collect/users) → 값 매핑(collect/values) → 검토·확정(POST /mapping, dryRun) → 기존 폴링(GET /{jobId}) 재사용. JSON은 필드 매핑 스킵.
- **백엔드 변경 0**. 확정된 REST 계약 소비만. Zod 스키마 백엔드 DTO 1:1(NON_NULL `.nullish()`).
- **dry-run 재적용** (Maxi): confirm 단발 소진 → dry-run 후 "이 매핑으로 실제 가져오기"는 보존 파일+매핑으로 재-analyze 후 실제 confirm.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 반영 — G1 dry-run 재적용(Maxi 결정)·G2 하위단계 stale 재검증·G3 필드추천 프론트 휴리스틱·G4 동적 stepper.

## Plan

> 공통: agent 기본값 `frontend-engineer`. 백엔드 변경 0. 모든 Zod는 백엔드 DTO 정본과 1:1(NON_NULL → `.nullish()`).
> UI 컴포넌트 제약: `ui/`에 tabs·combobox 없음 → 모드 토글=세그먼트 버튼(라디오형), 유저 피커=Input+필터 리스트.
> 각 컴포넌트 파일 L1에 한국어 역할 주석(글로벌 §6). 신규 파일 디렉토리 `apps/web/src/components/import/mapping/`.

### Task 1. Import 매핑 API 클라이언트 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/import-mappings.ts`, `apps/web/src/api/import-mappings.test.ts`]
- depends-on: []

**RED**: `import-mappings.test.ts`
- `importAnalysisResponseSchema` — `{jobId,status:"AWAITING_MAPPING",format,sourceFields:[{name}],sampleRows:string[][],targetFields:[{key,label,required,multi}]}` 파싱. sampleRows 빈 배열 허용.
- `mappingValidationResponseSchema` — `{valid,errors:[{code,message,field?}],warnings:[...]}`. `field` 생략(NON_NULL) 시 `.nullish()` 통과.
- `userCollectionResponseSchema` — `{users:[{sourceIdentifier,suggestedUserId?,suggestedDisplayName?}]}` (NON_NULL nullish).
- `valueCollectionResponseSchema` — `{fields:[{targetField:enum STATUS/TYPE/PRIORITY,values:[{sourceValue,suggestedTargetValue?}]}]}`.
- API 함수가 올바른 경로·메서드 호출(mock fetch): `analyzeImport`(multipart FormData file/projectKey/format)·`validateFieldMapping`·`collectUsers`·`collectValues`·`confirmMapping`. confirm은 기존 `importJobStatusSchema`(api/imports.ts) 재사용(status PENDING 포함하도록 enum 확인 — 아래).
- 에러코드 매핑 `IMPORT_MAPPING_ERROR_CODES`(IMPORT_MAPPING_INVALID/USER/VALUE/STATE_CONFLICT 한국어).

**GREEN**: `import-mappings.ts`
- 5 Zod 스키마 + 5 API 함수(`apiFetch`, FormData는 Content-Type 자동). `throwIfNotOk` 재사용(api/imports.ts 패턴 미러). confirm 응답은 `importJobStatusSchema` 재사용하되 **status enum에 PENDING 이미 포함**(기존 스키마 그대로) — jobId/status 반환.
- 타입은 `z.infer` 추론(interface 중복 금지).

**REFACTOR**: 공통 `throwIfNotOk`를 api/imports.ts에서 import 재사용 검토(중복 시 export). 카탈로그 상수 KDoc.

**검증**: `pnpm --filter web test import-mappings`

### Task 2. 필드 매핑 초기 추천 순수 휴리스틱

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/field-mapping-suggest.ts`, `apps/web/src/components/import/mapping/field-mapping-suggest.test.ts`]
- depends-on: []

**RED**: `field-mapping-suggest.test.ts`
- `suggestFieldMappings(sourceFields, targetFields)`: 소스 헤더 정규화(trim+lowercase)가 카탈로그 key/label과 일치하면 그 target.key, 아니면 IGNORE. 예: "Summary"→summary, "제목"→summary(label), "우선순위"→priority, 미상 "Foo"→IGNORE. 대소문자/공백 무시.

**GREEN**: `field-mapping-suggest.ts`
- 순수 함수. `Record<sourceField, targetKey>` 반환. label 정규화 매칭 포함.

**REFACTOR**: 정규화 헬퍼 추출.

**검증**: `pnpm --filter web test field-mapping-suggest`

### Task 3. FieldMappingStep 컴포넌트 (CSV 필드 매핑)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/FieldMappingStep.tsx`, `apps/web/src/components/import/mapping/FieldMappingStep.test.tsx`]
- depends-on: [1, 2]

**RED**: `FieldMappingStep.test.tsx`
- props(`sourceFields`,`sampleRows`,`targetFields`,`value`,`onChange`,`jobId`) 렌더: 소스 헤더별 `<Select>`(카탈로그+"매핑 안 함"), 초기값=suggest 결과.
- 샘플 행 미리보기 표(sourceFields 순서).
- [다음] 시 `validateFieldMapping` 호출 → errors 있으면 인라인 `role=alert` 표시 + `onNext` 미호출, warnings는 비차단 표시. summary 미매핑 → SUMMARY_NOT_MAPPED 노출.
- valid → `onNext(fieldMappings)` 호출.

**GREEN**: `FieldMappingStep.tsx`
- shadcn `Select` 재사용. validate mutation(react-query `useMutation`). 에러/경고 렌더(ErrorAlert 패턴 미러).

**REFACTOR**: 이슈 코드→필드 귀속 렌더 헬퍼. 매핑 행 컴포넌트 추출.

**검증**: `pnpm --filter web test FieldMappingStep`

### Task 4. UserMappingStep 컴포넌트 (전 작성자 → BTS 유저)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/UserMappingStep.tsx`, `apps/web/src/components/import/mapping/UserMappingStep.test.tsx`]
- depends-on: [1]

**RED**: `UserMappingStep.test.tsx`
- props(`users`(collect 결과),`value`(override map),`onChange`) 렌더: 소스 식별자별 행 — 기본 선택=추천 사용자(suggestedDisplayName), "미매핑(이메일 폴백)" 옵션, 검색 입력(`fetchUsers(query)`)으로 다른 유저 지정.
- override는 `sourceIdentifier`→`targetUserId|null` map. 추천 없으면 기본 미매핑.
- 빈 users면 "매핑할 작성자 없음" 안내(단계 스킵은 컨테이너 책임).

**GREEN**: `UserMappingStep.tsx`
- Input+필터 리스트 경량 피커(콤보박스 부재). `fetchUsers` react-query. 선택 상태 콜백.

**REFACTOR**: UserMappingRow 추출. 검색 debounce(기존 `useDebounce` 재사용).

**검증**: `pnpm --filter web test UserMappingStep`

### Task 5. ValueMappingStep 컴포넌트 (status/type/priority 값)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/ValueMappingStep.tsx`, `apps/web/src/components/import/mapping/ValueMappingStep.test.tsx`]
- depends-on: [1]

**RED**: `ValueMappingStep.test.tsx`
- props(`fields`(collect 결과),`value`(override),`onChange`) 렌더: 대상 필드(상태/유형/우선순위)별 소스 값 행 — 대상 값 입력(추천값 프리필). override 키=`(targetField, sourceValue)`.
- STATUS는 자유 입력 안내, TYPE/PRIORITY는 canonical 힌트(confirm 시 422 가능 문구). 빈 fields면 "매핑할 값 없음".

**GREEN**: `ValueMappingStep.tsx`
- 필드 그룹별 렌더. 대상 값 Input(추천 프리필). 콜백.

**REFACTOR**: ValueMappingGroup/Row 추출.

**검증**: `pnpm --filter web test ValueMappingStep`

### Task 6. ImportMappingWizard 컨테이너 (상태머신·동적 스킵·검토·폴링·dry-run 재적용)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/ImportMappingWizard.tsx`, `apps/web/src/components/import/mapping/ImportMappingWizard.test.tsx`]
- depends-on: [1, 3, 4, 5]

**RED**: `ImportMappingWizard.test.tsx`
- 상태: `step ∈ {upload,fields,users,values,review,tracking,done}` + `file`·`jobId`·`fieldMappings`·`userOverrides`·`valueOverrides`.
- upload: format/file → `analyzeImport` → jobId 저장 → CSV면 fields, JSON이면 users로 진입.
- fields→users 전이 시 `collectUsers` 호출, **빈 목록이면 values로 자동 스킵**(G4). users→values 시 `collectValues`, 빈이면 review 스킵.
- **stale 재검증**(G2): fields 변경 후 users/values 재진입 시 collect 재호출, override는 살아있는 키만 재적용.
- review: 매핑 요약 + [검증만 실행](dryRun=true)/[가져오기 실행](dryRun=false) → `confirmMapping` → tracking.
- tracking: `useImportJobPolling(jobId)` → COMPLETED/FAILED → done.
- done: 성공/실패 건수·에러 로그 다운로드(기존 `downloadImportErrorLog` 재사용). **dry-run 완료면 [이 매핑으로 실제 가져오기]** → 보존 file+매핑으로 `analyzeImport` 재호출→새 jobId→매핑 재적용→`confirmMapping(dryRun=false)`→tracking(G1).
- 상단 stepper 활성 단계 표시. 상태 충돌 409/404/401 인라인.

**GREEN**: `ImportMappingWizard.tsx`
- 단계 오케스트레이션 + collect mutation + 폴링 hook + 재적용 로직. 검토는 내부 서브뷰(요약+버튼).

**REFACTOR**: 검토 서브뷰/스텝퍼 소컴포넌트 추출. 폴링 종단→done `useEffect`는 ImportForm 패턴 미러.

**검증**: `pnpm --filter web test ImportMappingWizard`

### Task 7. 설정 Import 페이지 모드 토글 (바로 가져오기 / 매핑 마법사)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.import.tsx`, `apps/web/src/routes/projects.$projectKey.settings.import.test.tsx`]
- depends-on: [6]

**RED**: 라우트/페이지 테스트
- 페이지 상단 세그먼트 버튼(라디오형) 2개: "바로 가져오기"(기본, 기존 `ImportForm`) / "매핑하며 가져오기"(`ImportMappingWizard`). 기본 렌더=ImportForm(무회귀). 토글 시 마법사 렌더.
- 각 모드에 `key={projectKey}` remount 보존(EC8).

**GREEN**: 라우트 수정 — 모드 state + 조건 렌더. tabs 컴포넌트 없이 버튼 그룹(`Button` variant 토글).

**REFACTOR**: 모드 토글 소컴포넌트. 기존 ImportForm import 유지.

**검증**: `pnpm --filter web test settings.import`

### Task 8. MSW stateful 핸들러 (analyze/validate/collect/confirm)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/import-handlers.ts`]
- depends-on: [1]

**RED/GREEN**: (MSW는 테스트 인프라 — RED는 기존 컴포넌트 테스트가 핸들러 소비로 대체, 여기선 핸들러 추가 + 스키마 parse 자기검증)
- `POST /imports/analyze` → AWAITING_MAPPING + 고정 sourceFields/sampleRows/targetFields(11종 카탈로그, 정본 미러).
- `POST /imports/:id/mapping/validate` → fieldMappings 기반 valid/errors(summary 미매핑 시 SUMMARY_NOT_MAPPED).
- `POST /imports/:id/mapping/users` → 고정 users(추천 有/無 혼합).
- `POST /imports/:id/mapping/values` → 고정 fields(status/type/priority).
- `POST /imports/:id/mapping` → jobId-keyed store에 PENDING 등록(기존 진행 시뮬 재사용) → 기존 `GET /imports/:id` 폴링 연결. dryRun 반영.
- 시나리오 토글 재사용(`LS_KEY_IMPORT_FAIL` + 매핑 검증 실패용 신규 토글 필요 시 추가). 픽스처는 `*Schema.parse`로 drift 가드.

**REFACTOR**: 카탈로그/픽스처 상수 추출. 기존 `importHandlers` 배열에 추가.

**검증**: `pnpm --filter web test import-mappings FieldMappingStep UserMappingStep ValueMappingStep ImportMappingWizard` (핸들러 소비 확인)

### Task 9. E2E — 매핑 마법사 (S1 CSV / S5 JSON / S6 에러)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/import-mapping.spec.ts`]
- depends-on: [7, 8]

**RED/GREEN**: Playwright
- **S1**: 매핑 모드 진입 → CSV 업로드/분석 → 필드 매핑(summary 지정) → 사용자 매핑 → 값 매핑 → 검토 [가져오기 실행] → 진행률→완료(성공/실패 건수).
- **S5**: JSON 업로드/분석 → 필드 매핑 단계 스킵 확인 → 사용자/값 → 완료.
- **S6**: summary 미매핑 시 [다음] 차단(에러 노출) / 상태충돌·실패 토글 시 에러 안내.
- MSW SPA 내부 이동(reload 금지), 시나리오 토글 `addInitScript`(메모리 e2e-msw-scenario-toggle).

**검증**: `pnpm --filter web test:e2e import-mapping`

## Plan 메타

- task 수: 9
- 예상 wave: 3 (Wave1=T1·T2 병렬 → Wave2=T3·T4·T5·T8 병렬 → Wave3=T6 → T7 → T9. 실제 files 교집합 기준 bts-impl 재계산)
- TDD 강제: yes (프론트 vitest RED→GREEN→REFACTOR)
- 추가 검증: typecheck(tsconfig.app.json), lint, vitest, playwright(qa), `pnpm verify`, `verify-master-plan.sh`
- 정본 동기화: **신규 라우트 없음**(기존 `settings.import` 페이지 내부 토글로 마법사 렌더 → router.ts/설정 카운트 변경 0). FR 수 불변(FR-IM-02 이미 등재). D6/D7 완료 시 product/fr-index D박스 마킹만.

## 리뷰 결과 (← /bts-review-plan 채움)
