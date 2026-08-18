<!-- FR-IM-02 D6/D7 Import 매핑 마법사 프론트엔드 스펙 — analyze→필드/사용자/값 매핑→confirm→폴링 -->

# FR-IM-02 D6/D7 — Import 매핑 마법사 프론트엔드 스펙

- 날짜: 2026-07-04
- BC: search-export-import (프론트 `apps/web`)
- 선행: 백엔드 3-PR 완결 — #230 PR-A(필드 매핑) / #233 PR-B(사용자 매핑) / #234 PR-C(값 매핑)
- 선행 ADR: [2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md)
- 미러 선례: FR-IM-01 D6/D7(#229) `ImportForm` / `api/imports.ts` / `use-import-job-polling.ts`

## 배경 / 범위

FR-IM-02 백엔드는 "임의 CSV 헤더 자유 필드매핑 + 전 작성자 사용자매핑 + status/type/priority 값매핑"을
`analyze → map → confirm → (기존 워커 폴링)` 2단계 흐름으로 완결했다. 이 작업은 그 흐름을 소비하는
**프론트 매핑 마법사 UI**다. **백엔드 변경 0** — 기존 REST 계약을 소비하는 프론트 코드만 추가한다.

**Maxi 결정 (게이트 전 확인).**
1. **두 모드 병존** — 기존 즉시-업로드 `ImportForm`("바로 가져오기")과 새 매핑 마법사("매핑하며
   가져오기")를 설정 페이지에서 탭/토글로 병존. 근거: `analyze` 경로는 첨부 zip 파라미터가 없어
   (컨트롤러 `@RequestParam file/projectKey/format`뿐), 마법사가 폼을 대체하면 JSON 첨부 Import UI가
   사라진다. 두 경로는 상호 보완.
2. **DESIGN.md 관례로 진행** — design-shotgun 스킵. 기존 설정 페이지 + DESIGN.md 토큰으로 레이아웃 확정.

## 사용자 시나리오 (Given-When-Then)

### S1. 마법사 진입 + CSV 필드 매핑 → 실행
- **Given** 사용자가 `/projects/ATLAS/settings/import`에서 "매핑하며 가져오기" 탭을 연다.
- **When** 형식=CSV·임의 헤더 CSV 파일을 골라 [분석]을 누른다.
- **Then** `POST /analyze`로 `jobId`·소스 필드(CSV 헤더)·샘플 행(최대 5)·대상 필드 카탈로그 11종을 받아
  **필드 매핑 단계**로 진입한다. 각 소스 헤더에 대상 필드 드롭다운(카탈로그 + "매핑 안 함")이 나오고,
  이름이 유사한 헤더는 초기 추천이 채워진다.
- **When** summary(제목)에 한 헤더를 매핑하고 [다음]을 누른다.
- **Then** `POST /{jobId}/mapping/validate`가 `valid=true`면 다음 단계로, 아니면 인라인 error(예:
  SUMMARY_NOT_MAPPED)를 보여 [다음]을 막는다.

### S2. 사용자 매핑 (전 작성자 → BTS 유저)
- **Given** 필드 매핑에서 reporter/assignee 컬럼을 매핑한 상태.
- **When** [다음]으로 **사용자 매핑 단계**에 들어간다.
- **Then** `POST /{jobId}/mapping/users`가 원본 전량 스캔으로 등장한 작성자 식별자(이메일)와 BTS 추천
  사용자(`suggestedUserId`/`suggestedDisplayName`)를 반환한다. 각 식별자에 대해 추천 사용자가 기본
  선택되고, 사용자가 다른 BTS 유저로 바꾸거나 "미매핑(이메일 폴백)"으로 둘 수 있다.
- **When** 등장 작성자가 하나도 없으면(수집 결과 빈 목록) 이 단계는 자동으로 건너뛴다.

### S3. 값 매핑 (status/type/priority)
- **Given** status/type/priority 중 하나 이상을 매핑한 상태.
- **When** **값 매핑 단계**에 들어간다.
- **Then** `POST /{jobId}/mapping/values`가 대상 필드별 등장 소스 값 + 추천 대상 값을 반환한다. 각 소스
  값에 대상 값을 지정한다. **STATUS는 관대**(임의 비공백 허용, apply-time best-effort), **TYPE/PRIORITY는
  엄격**(canonical 불일치 시 confirm에서 422). 매핑할 값이 없으면 단계 자동 스킵.

### S4. 검토 → 검증만 실행 → (매핑 보존) 실제 실행
- **Given** 필드/사용자/값 매핑을 마친 상태.
- **When** **검토 단계**에서 [검증만 실행](dryRun=true)을 누른다.
- **Then** `POST /{jobId}/mapping`(dryRun=true)이 job을 PENDING으로 전환하고, 기존 폴링
  (`GET /{jobId}`, `useImportJobPolling`)으로 진행률→완료를 추적한다. 완료 시 성공/실패 건수 + 실패행
  에러 로그 다운로드를 노출한다(FR-IM-01 done 단계 재사용, 라벨="검증 완료").
- **When** dry-run 완료 후 [이 매핑으로 실제 가져오기]를 누른다.
- **Then** (Maxi 결정 = 매핑 보존 → 재분석 재적용) confirm은 job을 단발 소진하므로, 메모리에 보존한
  **파일 + 필드/사용자/값 매핑 상태**로 `POST /analyze`를 다시 호출해 새 jobId를 얻고, 같은 파일이라
  동일한 소스 필드가 나오므로 저장된 매핑(키=소스 필드/식별자/값)을 그대로 재적용한 뒤
  `confirm(dryRun=false)`로 실제 실행 → 폴링. 사용자에겐 원클릭.
- **When** [가져오기 실행](dryRun=false)을 처음부터 눌렀다면 재분석 없이 바로 실제 실행.

### S5. JSON 마법사 (필드 매핑 스킵)
- **Given** 형식=JSON Jira export 파일.
- **When** [분석] 후.
- **Then** JSON은 canonical 고정 구조라 **필드 매핑 단계를 건너뛰고** 사용자 매핑 → 값 매핑 → 검토로
  진행한다(`validate`는 JSON에서 항상 valid=true, 서비스 `computeValidationResult` §JSON 분기). 필드
  매핑 payload는 빈 배열로 전송한다.

### S6. 상태 충돌 / 소유권
- **Given** 다른 세션이 같은 job을 이미 확정(PENDING 전환)했거나, 타인 소유 jobId.
- **When** 매핑 API를 호출.
- **Then** 409 IMPORT_MAPPING_STATE_CONFLICT(이미 대기 상태 아님) 또는 404(타인/미존재)를 사용자
  메시지로 안내하고 처음(업로드)으로 되돌린다.

## 기능 요구사항 (FR)

- **FR-1 모드 토글.** 설정 Import 페이지 상단에 "바로 가져오기"(기존 `ImportForm` 무변경) / "매핑하며
  가져오기"(신규 마법사) 두 모드. 기본 모드는 "바로 가져오기"(하위호환·기존 E2E 무회귀).
- **FR-2 업로드/분석 단계.** 형식(CSV/JSON) + 파일 선택 → [분석] → `POST /analyze` → jobId/소스필드/
  샘플행/카탈로그 확보. 파일 미선택 시 [분석] 비활성.
- **FR-3 필드 매핑 단계 (CSV).** 소스 헤더별 대상 필드 `<Select>`(카탈로그 11종 + "매핑 안 함"=IGNORE).
  **초기 추천은 프론트 순수 휴리스틱**(소스 헤더 정규화 이름이 카탈로그 key/label과 일치 시 프리필) —
  백엔드 analyze는 필드 매핑 추천을 주지 않으므로(사용자/값 매핑과 달리 비권위적 best-effort, G3).
  샘플 행 미리보기 표(sampleRows를 sourceFields 순서로 정렬). summary 필수·중복 대상·미지 소스/대상·
  모호 소스는 error로 [다음] 차단, 미매핑 필드는 warning(비차단). JSON은 이 단계 스킵.
- **FR-4 사용자 매핑 단계.** `POST /{jobId}/mapping/users` 결과의 소스 식별자별 추천 사용자 기본 선택 +
  BTS 유저 검색(`fetchUsers(query)` = `GET /api/v1/users?query=`)으로 재지정 + "미매핑" 옵션. 수집 결과
  빈 목록이면 단계 자동 스킵.
- **FR-5 값 매핑 단계.** `POST /{jobId}/mapping/values` 결과의 대상 필드별 소스 값에 대상 값 지정.
  STATUS 관대 / TYPE·PRIORITY 엄격(canonical). 추천 대상 값 프리필. 전 필드 빈 목록이면 스킵.
- **FR-6 검토·확정 단계.** 매핑 요약 표시 + [검증만 실행](dryRun=true)/[가져오기 실행](dryRun=false)
  → `POST /{jobId}/mapping` → PENDING → 폴링 진입.
- **FR-7 진행률/완료 + dry-run 재적용.** 기존 `useImportJobPolling(jobId)` + 진행률 바 + 완료(성공/실패
  건수·에러 로그 다운로드) 재사용. dry-run 완료 화면에 [이 매핑으로 실제 가져오기] 노출 → **보존한
  파일+매핑으로 재-analyze → 매핑 자동 재적용 → confirm(dryRun=false)**(G1/S4). 실제(non-dry-run) 완료
  화면엔 재적용 버튼 없음("처음으로"만).
- **FR-8 마법사 네비게이션 + 동적 stepper.** 상단 진행 stepper + [이전]/[다음]. 뒤로 이동 시 매핑 상태
  보존. **사용자/값 단계 존재 여부는 해당 collect 호출 후에야 판명**(G4) → 수집 결과 빈 목록이면 자동
  진행(다음 단계로 skip), stepper는 판명된 활성 단계만 강조. jobId·파일·매핑 상태는 마법사 컴포넌트
  내부 state.
- **FR-9 하위 단계 stale 재검증.** (G2) 필드 매핑이 바뀐 뒤 사용자/값 단계에 재진입하면, 그 단계는
  **collect를 재호출**해 최신 소스 식별자/값을 다시 수집한다. 사용자 override(targetUserId)·값
  override(targetValue)는 **소스 키(sourceIdentifier/sourceValue)로 보존**해 여전히 등장하는 키에만
  재적용, 사라진 키는 폐기. 필드 매핑 미변경 시 재호출 생략 가능(캐시).
- **FR-10 에러 처리.** 매핑 API 422(IMPORT_MAPPING_INVALID/USER/VALUE)·409(STATE_CONFLICT)·404·401을
  각 단계 인라인 `role=alert`로 안내. 422 errors[]는 필드별로 매핑 UI에 귀속 표시.

## 비기능 요구사항 (NFR)

- **NFR-1 계약 정합.** 모든 Zod 스키마는 백엔드 DTO 정본(`ImportAnalysisResponse`/`MappingValidationResponse`/
  `UserCollectionResponse`/`ValueCollectionResponse`/`ImportJobResponse`)과 1:1. `@JsonInclude(NON_NULL)`
  필드(`field?`/`suggestedUserId?`/`suggestedDisplayName?`/`suggestedTargetValue?`/`totalRows?`/`errorCode?`)는
  `.nullish()`. (교훈 frontend-zod-backend-dto-contract-gap — DTO invent 금지, spec/코드 grep.)
- **NFR-2 apiFetch 사용.** 모든 호출은 `apiFetch`(analyze는 FormData→Content-Type 자동). 인증 후 경로라
  raw fetch 불필요(로그인/MFA 아님).
- **NFR-3 접근성.** stepper·진행률 바·에러 `role`/`aria-*`. 셀렉트 라벨 연결.
- **NFR-4 테스트.** 단위(마법사 상태머신·순수 변환·스키마 파싱) + E2E(S1 CSV 전체 흐름 / S5 JSON 스킵 /
  S6 상태충돌·에러). MSW stateful 핸들러로 analyze→mapping→confirm→폴링 시뮬(공유 store, 시나리오 토글
  localStorage — 교훈 e2e-msw-scenario-toggle-localstorage-flag).
- **NFR-5 무회귀.** 기존 "바로 가져오기" `ImportForm`·라우트·E2E 무변경. 라우트 카운트 등 정본 동기화.

## API 인터페이스 (REST) — 백엔드 정본(무변경, 소비만)

모든 매핑 엔드포인트: 401(미인증)·404(타인/미존재 job, 존재 은닉)·409 IMPORT_MAPPING_STATE_CONFLICT
(AWAITING_MAPPING 아님) 공통.

### `POST /api/v1/imports/analyze` (multipart) → 200 `ImportAnalysisResponse`
- req: `file`(part), `projectKey`, `format`("CSV"|"JSON")
- res: `{ jobId: uuid, status: "AWAITING_MAPPING", format, sourceFields: [{name}], sampleRows: string[][],
  targetFields: [{key, label, required, multi}] }` (sampleRows JSON은 항상 [])
- 에러: 401 / 400(projectKey 공백·패턴) / 403 IMPORT_ACCESS_DENIED / 413 IMPORT_FILE_TOO_LARGE /
  400 IMPORT_UNSUPPORTED_FORMAT

### `POST /api/v1/imports/{jobId}/mapping/validate` → 200 `MappingValidationResponse`
- req: `{ fieldMappings: [{sourceField, targetField}] }`
- res: `{ valid: boolean, errors: [{code, message, field?}], warnings: [{code, message, field?}] }`
- codes(error): SUMMARY_NOT_MAPPED(field=null) · DUPLICATE_TARGET · UNKNOWN_TARGET · UNKNOWN_SOURCE ·
  AMBIGUOUS_SOURCE / (warning): SOURCE_FIELD_IGNORED. JSON은 항상 valid=true.

### `POST /api/v1/imports/{jobId}/mapping/users` → 200 `UserCollectionResponse`
- req: `{ fieldMappings: [...] }` (validate와 동일 계약, JSON은 스킵)
- res: `{ users: [{sourceIdentifier, suggestedUserId?, suggestedDisplayName?}] }`
- 에러: +422 IMPORT_MAPPING_INVALID(CSV 필드 매핑 선검증 실패)

### `POST /api/v1/imports/{jobId}/mapping/values` → 200 `ValueCollectionResponse`
- req: `{ fieldMappings: [...] }`
- res: `{ fields: [{targetField: "STATUS"|"TYPE"|"PRIORITY", values: [{sourceValue, suggestedTargetValue?}]}] }`
- 에러: +422 IMPORT_MAPPING_INVALID

### `POST /api/v1/imports/{jobId}/mapping` (confirm) → 200 `ImportJobResponse`
- req: `{ fieldMappings: [...], dryRun?: boolean, userMappings?: [{sourceIdentifier, targetUserId?}],
  valueMappings?: [{targetField: "STATUS"|"TYPE"|"PRIORITY", sourceValue, targetValue}] }`
- res: `ImportJobResponse`(status="PENDING") → 이후 `GET /{jobId}` 폴링(기존)
- 에러: +400(valueMappings.targetField 미지 상수명) / 422 IMPORT_MAPPING_INVALID / 422
  IMPORT_USER_MAPPING_INVALID(DUPLICATE_SOURCE_IDENTIFIER·TARGET_USER_NOT_FOUND) / 422
  IMPORT_VALUE_MAPPING_INVALID(TARGET_VALUE_NOT_FOUND·DUPLICATE_VALUE_MAPPING)

### 대상 필드 카탈로그 11종 (`TargetField`)
summary(제목,required,단일) · description(설명) · type(유형) · priority(우선순위) · reporter(보고자) ·
assignee(담당자) · labels(라벨,multi) · component(컴포넌트,multi) · status(상태) · fixVersion(수정 버전,multi) ·
affectsVersion(영향 버전,multi) + 센티널 IGNORE(키="IGNORE", "매핑 안 함"). 카탈로그는 analyze 응답에
매번 포함되므로 별도 API 없이 응답으로 렌더.

## 데이터 모델 변경

- **없음.** 프론트 전용. 백엔드 스키마/DTO/마이그레이션 변경 0.

## 엣지 케이스

- **EC1 미지 소스/대상.** 오래된 카탈로그 캐시로 UNKNOWN_TARGET/UNKNOWN_SOURCE → error 인라인. (카탈로그는
  응답으로 받으므로 실무상 드묾.)
- **EC2 대소문자 중복 헤더(Status/STATUS).** AMBIGUOUS_SOURCE error — 둘 중 하나만 매핑하도록 안내.
- **EC3 summary 미매핑.** SUMMARY_NOT_MAPPED(field=null) → 전역 error, [다음] 차단.
- **EC4 사용자 매핑 중복 소스.** 정규화 후 겹치는 식별자 매핑 → confirm 422 DUPLICATE_SOURCE_IDENTIFIER.
  UI는 수집 결과가 이미 정규화·dedup되어 있어 실무상 없으나, 방어적으로 처리.
- **EC5 TARGET_USER_NOT_FOUND.** 추천 없는 식별자를 임의 유저로 지정했다 그 유저가 사라진 극단 → 422.
- **EC6 값 매핑 엄격/관대.** TYPE/PRIORITY 미존재 대상 값 → 422 TARGET_VALUE_NOT_FOUND. STATUS 공백만
  거부. (중복 (필드,소스값) → DUPLICATE_VALUE_MAPPING.)
- **EC7 dry-run 소진 → 재적용.** (G1, Maxi 결정) confirm(dryRun=true)은 job을 PENDING으로 소진한다.
  [이 매핑으로 실제 가져오기]는 같은 job 재확정(409)이 아니라, 보존한 파일+매핑으로 **재-analyze(새
  jobId) → 매핑 자동 재적용 → confirm(dryRun=false)**로 처리. 재-analyze 중 실패(파일 재읽기/권한 변화
  등) 시 에러 노출 후 처음으로. 실제(non-dry-run) 완료엔 재적용 버튼 없음.
- **EC8 프로젝트 전환 leak.** 마법사도 `key={projectKey}` remount로 jobId/매핑 state leak 차단
  (교훈 react-usestate-stale-key-prop, 기존 페이지 선례).
- **EC9 JSON 필드 매핑.** JSON은 필드 매핑 단계 스킵·fieldMappings=[] 전송(validate 항상 valid).
- **EC10 대량 소스 필드/작성자.** 수십 개 헤더·수백 작성자 스크롤 UX. 값 매핑 소스 값도 다수 가능 —
  가상화는 범위 밖(YAGNI), 스크롤 컨테이너로 처리.
- **EC11 빈/오형식 analyze.** (DR-6) analyze가 sourceFields 빈 목록 반환(빈 CSV·헤더만·오형식) 시 빈
  필드 매핑 표가 아니라 명확한 에러 메시지 + 업로드 단계 복귀. 400 계열 에러도 업로드 단계 인라인 표시.
- **EC12 async 로딩.** (DR-1) analyze/collect/confirm 진행 중 disabled + 진행 라벨. 중복 제출 차단.

## 제약 조건

- BC 격리: search-export-import 프론트만. 백엔드 무변경.
- 하위호환: 기존 "바로 가져오기" 폼·`POST /imports`·라우트 무변경.
- 완제품 품질: 절대 규칙(DEVELOPMENT.md §1)·TDD·에러 처리·접근성 충족.

## 측정 가능한 완료 기준

- [ ] 설정 Import 페이지에 두 모드 토글, 기본 "바로 가져오기"(기존 무회귀).
- [ ] 마법사: 업로드/분석 → (CSV)필드 매핑 → 사용자 매핑 → 값 매핑 → 검토·확정 → 폴링/완료.
- [ ] CSV 필드 매핑 validate error/warning 인라인, summary 필수 [다음] 차단.
- [ ] 사용자/값 매핑 단계 수집 결과 빈 목록 시 자동 스킵. JSON은 필드 매핑 스킵.
- [ ] confirm(dryRun true/false) → PENDING → 기존 폴링 재사용 완료 화면.
- [ ] dry-run 완료 후 [이 매핑으로 실제 가져오기] → 재-analyze + 매핑 재적용 + 실제 confirm(G1).
- [ ] 하위 단계 stale 재검증(collect 재호출·override 키 보존, G2)·동적 stepper 스킵(G4).
- [ ] 422/409/404/401 인라인 에러 처리(단계 귀속).
- [ ] Zod 스키마 백엔드 DTO 1:1(NON_NULL `.nullish()`). 단위 + E2E(S1/S5/S6) 통과.
- [ ] `pnpm verify`(lint+typecheck+test+build) + `verify-master-plan.sh` 통과.

## Brainstorming Check

✅ 통과 (1회 iteration). sanity check로 gap 4건 발견 후 반영.
- **G1 (Maxi 결정)** dry-run 소진 → 매핑 보존·재-analyze·재적용 흐름 채택(S4/FR-7/EC7).
- **G2** 필드 매핑 변경 시 사용자/값 단계 stale → collect 재호출·override 키 보존(FR-9).
- **G3** 필드 매핑 초기 추천은 프론트 순수 휴리스틱(백엔드 무추천)·비권위적(FR-3).
- **G4** 동적 stepper — 사용자/값 단계 존재는 collect 후 판명·빈 목록 자동 스킵(FR-8).
