// 보드 설정 — 카드 레이아웃 탭 본문 (뷰별 최대 3개 · 부채 177 Task 16 · J17·J18)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { BoardDetail } from '@/api/boards'
import { ApiError } from '@/api/client'
import { CARD_LAYOUT_VIEW_SCOPES, replaceCardLayout } from '@/api/board-settings'
import type { CardLayout, CardLayoutViewScope } from '@/api/board-settings'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Skeleton } from '@/components/ui/skeleton'

/** 한 뷰에 실을 수 있는 추가 필드 수 — 백엔드 `MAX_FIELDS_PER_VIEW` 미러 (J17). */
const MAX_FIELDS_PER_VIEW = 3

/**
 * 카드에 얹을 수 있는 **표준** 필드 — 백엔드 `CardLayoutFieldKey` 미러(열거 순서까지 같다).
 *
 * ★**요약(`summary`)은 없다.** J19 의 1층이라 항상 최상단이고 토글 대상이 아니다(R2) —
 * 여기 넣으면 사용자가 요약을 끌 수 있게 되고, 서버는 그 키를 400 으로 거절한다.
 */
const STANDARD_FIELDS = [
  { key: 'EPIC', label: '에픽' },
  { key: 'PRIORITY', label: '우선순위' },
  { key: 'ASSIGNEE', label: '담당자' },
  { key: 'LABELS', label: '라벨' },
  { key: 'ESTIMATE', label: '추정치' },
  { key: 'ISSUE_TYPE', label: '이슈 종류' },
] as const

/**
 * 커스텀 필드 키 접두사 — 백엔드 `CardLayoutSettingsService.CUSTOM_FIELD_PREFIX` 미러.
 *
 * ★**`custom_field_definitions.key` 에는 접두사가 없다.** 표준 카탈로그와 커스텀 필드를 한
 * 배열에 담기 위한 표식이라 **보낼 때 붙이고 카드에 그릴 때 뗀다**(그리는 쪽은 Task 20).
 * 그래서 이 화면이 아는 커스텀 필드 키는 언제나 `cf_` 가 붙은 전송 형태다.
 */
const CUSTOM_FIELD_PREFIX = 'cf_'

/**
 * 화면 문구.
 *
 * `i18n/board-labels.ts` 가 아니라 이 파일이 소유한다 — `board-labels.ts` 는 이 task 의
 * 허용 파일 밖이다(부채 177 Task 16). `BacklogEpicPanel` 이 같은 이유로 쓴 관용구다.
 * ★`saveRetry` 와 `customRetry` 는 **서로 substring 이 아니어야 한다** — 서버가 통째로
 * 죽으면 둘이 함께 뜨고, 그때 Playwright `getByRole('button', { name })` 이 둘을 잡는다.
 */
const labels = {
  heading: '카드에 표시할 필드',
  description: '요약은 항상 카드 맨 위에 보입니다. 뷰마다 최대 3개를 더 고를 수 있습니다.',
  viewGroupLabel: '카드 레이아웃 뷰',
  viewBoard: '보드',
  viewBacklog: '백로그',
  standardHeading: '표준 필드',
  customHeading: '커스텀 필드',
  customEmpty: '이 프로젝트에는 커스텀 필드가 없습니다.',
  customLoadError: '커스텀 필드를 불러오지 못했습니다.',
  customRetry: '커스텀 필드 다시 불러오기',
  limitReached: '뷰당 3개까지입니다. 다른 필드를 고르려면 먼저 하나를 해제하세요.',
  saveFailed: '카드 레이아웃을 저장하지 못했습니다.',
  saveForbidden: '카드 레이아웃을 저장할 권한이 없습니다.',
  saveRetry: '다시 시도',
}

/** 뷰 라벨. 사용자에게 보이는 이름은 스코프 이름(`BOARD`)이 아니다. */
const VIEW_LABELS: Record<CardLayoutViewScope, string> = {
  BOARD: labels.viewBoard,
  BACKLOG: labels.viewBacklog,
}

/**
 * 이 보드에서 편집할 수 있는 뷰 (R3).
 *
 * ★**칸반에는 백로그 스코프 자체가 없다** — 보내면 서버가 400 이다. 「토글을 그릴까」와
 * 「어느 뷰로 보낼까」를 이 함수 **하나**가 낸다. 둘을 따로 분기하면 두 목록이 서로를 검사하지
 * 않는 상태가 되어, 한쪽만 고친 날 칸반이 조용히 `BACKLOG` 를 보낸다.
 *
 * @param boardType 보드 종류.
 * @returns 편집 가능한 뷰. 첫 원소가 기본 뷰다(항상 하나 이상이다).
 */
function editableViews(
  boardType: BoardDetail['boardType'],
): readonly [CardLayoutViewScope, ...CardLayoutViewScope[]] {
  return boardType === 'SCRUM' ? CARD_LAYOUT_VIEW_SCOPES : ['BOARD']
}

/** 한 번의 저장 시도. 실패 되돌림과 재시도가 같은 값을 쓴다. */
interface CardLayoutSave {
  /** 저장할 뷰. */
  view: CardLayoutViewScope
  /** 그 뷰의 필드 키 목록 — 통째 교체다. */
  fields: readonly string[]
  /** 실패했을 때 되돌릴 직전 값. */
  previous: readonly string[]
}

/** 후보 한 줄. */
interface FieldCandidate {
  /** 전송 형태의 필드 키. 커스텀 필드는 `cf_` 가 붙어 있다. */
  key: string
  /** 화면에 보이는 이름. */
  label: string
}

/** ViewToggle props */
interface ViewToggleProps {
  /** 고를 수 있는 뷰. **둘 이상일 때만** 이 컴포넌트를 그린다(칸반은 하나뿐이다). */
  views: readonly CardLayoutViewScope[]
  /** 지금 편집 중인 뷰. */
  value: CardLayoutViewScope
  /** 뷰 전환. */
  onChange: (next: CardLayoutViewScope) => void
  /** 컨트롤 id 접두사 — 한 화면에 이 패널이 둘일 수 있으므로 `useId` 값을 받는다. */
  idPrefix: string
}

/**
 * 편집할 뷰를 고르는 토글 (J18).
 *
 * ★**Radix Tabs 가 아니라 RadioGroup 이다.** 이 화면은 이미 설정 5탭 안에 있어, 여기 Tabs 를
 * 겹치면 `getByRole('tab')`·`getByRole('tabpanel')` 이 바깥 탭바와 섞인다 — 설정 화면의 기존
 * 단언(`T-BS-9`·`T-BS-11`)과 E2E 셀렉터가 그 즉시 흔들린다. 「보드/백로그 중 하나를 고른다」는
 * 의미상으로도 라디오다.
 */
function ViewToggle({ views, value, onChange, idPrefix }: ViewToggleProps): JSX.Element {
  return (
    <RadioGroup
      aria-label={labels.viewGroupLabel}
      value={value}
      onValueChange={(next) => {
        onChange(next as CardLayoutViewScope)
      }}
      className="flex flex-row gap-4"
    >
      {views.map((scope) => (
        <div key={scope} className="flex items-center gap-2">
          <RadioGroupItem id={`${idPrefix}-view-${scope}`} value={scope} />
          <label
            htmlFor={`${idPrefix}-view-${scope}`}
            className="text-foreground flex min-h-11 cursor-pointer items-center text-sm md:min-h-8"
          >
            {VIEW_LABELS[scope]}
          </label>
        </div>
      ))}
    </RadioGroup>
  )
}

/** FieldCandidateList props */
interface FieldCandidateListProps {
  /** 후보 목록. 표준·커스텀 두 목록이 같은 렌더를 쓴다. */
  candidates: readonly FieldCandidate[]
  /** 지금 고른 필드 키. */
  selected: readonly string[]
  /** 편집 자체가 잠겼는가 — 권한 없음(S7) 또는 저장 중(E8 잠금). */
  locked: boolean
  /** 상한 3개에 닿았는가. */
  atLimit: boolean
  /** 컨트롤 id 접두사. */
  idPrefix: string
  /** 후보 토글. `checked` 는 **누른 뒤**의 상태다. */
  onToggle: (key: string, checked: boolean) => void
}

/**
 * 후보 체크박스 목록.
 *
 * 표준 필드와 커스텀 필드가 **같은 렌더를 공유한다** — 둘을 따로 그리면 잠금 규칙(상한·권한·
 * 저장 중)이 두 벌이 되고, 한쪽만 고친 날 커스텀 필드로 상한을 넘길 수 있게 된다.
 */
function FieldCandidateList({
  candidates,
  selected,
  locked,
  atLimit,
  idPrefix,
  onToggle,
}: FieldCandidateListProps): JSX.Element {
  return (
    <ul className="mt-1">
      {candidates.map((candidate) => {
        const checked = selected.includes(candidate.key)
        const controlId = `${idPrefix}-${candidate.key}`
        return (
          <li key={candidate.key} className="flex items-center gap-2">
            <Checkbox
              id={controlId}
              checked={checked}
              // 상한에 닿아도 **고른 것은 잠그지 않는다** — 잠그면 아무것도 바꿀 수 없는
              // 막다른 골목이 된다.
              disabled={locked || (atLimit && !checked)}
              onCheckedChange={(next) => {
                onToggle(candidate.key, next === true)
              }}
            />
            {/* 행 전체를 라벨로 만들어 터치 타깃을 44px 로 넓힌다(체크박스 자체는 16px).
                데스크톱은 목록 밀도를 위해 낮춘다 — `BacklogEpicPanel` 과 같은 결. */}
            <label
              htmlFor={controlId}
              className="text-foreground flex min-h-11 flex-1 cursor-pointer items-center text-sm md:min-h-8"
            >
              {candidate.label}
            </label>
          </li>
        )
      })}
    </ul>
  )
}

/** CardLayoutPanel props */
export interface CardLayoutPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회 API 를 만들지 않았다. */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 후보가 잠긴다 — 목록 자체는 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Card layout 탭** 본문 (J17·J18).
 *
 * 카드에 얹을 추가 필드를 **뷰마다 최대 3개**까지 고른다. 요약은 J19 의 1층이라 후보에 없다.
 *
 * ### 저장은 고르는 즉시 일어난다
 * 「저장」 버튼을 따로 두지 않는다. 뷰를 바꿔 가며 고르는 화면이라 버튼을 두면 「보드에서
 * 고르고 백로그로 옮겼더니 앞의 것이 사라졌다」가 생긴다. 지라도 필드를 고르는 즉시 저장한다.
 *
 * ### 저장 중에는 후보 전체가 잠긴다
 * 연속 토글을 허용하면 두 요청이 같은 낡은 목록에서 파생돼 앞 변경이 조용히 사라진다 —
 * `#452` 가 드래그에 건 잠금(스펙 E8)과 **같은 처방**이다. 응답이 오면 저절로 풀린다.
 *
 * ### ★저장된 구성을 읽어 오는 경로가 아직 없다 (계획 결함 · 보고 대상)
 * 이 화면은 빈 구성에서 시작해 **PATCH 응답**으로만 상태를 채운다. 스펙 N1 은 「보드 조회
 * 응답에 설정을 실어 추가 왕복을 만들지 않는다」고 적었지만, `BoardDetailResponse` 에
 * `cardLayout` 을 싣는 task 가 계획에 없다(T8 은 PATCH 뿐이고 T26 은 카드의 커스텀 필드 값이다).
 * 그래서 지금은 **새로고침하면 이미 저장된 구성이 체크되지 않는다.** 화면이 스스로 GET 을
 * 만들지 않는 이유는 N1 이 금지했기 때문이다 — 백엔드가 보드 응답에 실어야 닫힌다.
 */
export function CardLayoutPanel({ board, canConfigure }: CardLayoutPanelProps): JSX.Element {
  const domId = useId()
  const views = editableViews(board.boardType)
  const [requestedView, setRequestedView] = useState<CardLayoutViewScope>(views[0])
  // 보드 종류가 바뀌어도 없는 뷰를 편집하지 않는다 — 판정을 [editableViews] 한 곳에 모은다.
  const view: CardLayoutViewScope = views.includes(requestedView) ? requestedView : views[0]

  const [layout, setLayout] = useState<CardLayout>({})
  const [failure, setFailure] = useState<{ save: CardLayoutSave; status: number | null } | null>(
    null,
  )
  const customFieldsQuery = useCustomFields(board.projectKey)

  const mutation = useMutation<CardLayout, unknown, CardLayoutSave>({
    mutationFn: (save) => replaceCardLayout(board.boardId, save.view, save.fields),
    onSuccess: (saved) => {
      // 응답은 요청 echo 가 아니라 **저장 후 다시 읽은 전체 구성**이다. 자기가 보낸 값을
      // 화면 상태로 삼으면 저장이 안 돼도 성공처럼 보이고, 다른 뷰의 최신 값도 못 받는다.
      setLayout(saved)
      setFailure(null)
    },
    onError: (error, save) => {
      // 낙관 반영을 되돌린다. 그대로 두면 사용자는 저장된 줄 안다.
      setLayout((prev) => ({ ...prev, [save.view]: [...save.previous] }))
      // ★오류 **본문**을 읽지 않는다. 탭마다 봉투가 달라(부채 177 Task 29 가 통일 예정)
      //   본문 구조에 기대면 통일되는 날 조용히 어긋난다. 상태 코드로만 가른다.
      setFailure({ save, status: error instanceof ApiError ? error.status : null })
    },
  })

  const selected: readonly string[] = layout[view] ?? []
  const atLimit = selected.length >= MAX_FIELDS_PER_VIEW
  const locked = !canConfigure || mutation.isPending

  function submit(save: CardLayoutSave): void {
    setLayout((prev) => ({ ...prev, [save.view]: [...save.fields] }))
    setFailure(null)
    mutation.mutate(save)
  }

  function handleToggle(key: string, checked: boolean): void {
    // 고른 순서가 곧 카드에서의 자리(`position`)다 — 뒤에 붙인다.
    const fields = checked ? [...selected, key] : selected.filter((entry) => entry !== key)
    submit({ view, fields, previous: selected })
  }

  const customFields: FieldCandidate[] = (customFieldsQuery.data ?? []).map((field) => ({
    key: `${CUSTOM_FIELD_PREFIX}${field.key}`,
    label: field.name,
  }))

  return (
    <section aria-labelledby={`${domId}-heading`} className="max-w-2xl space-y-4">
      <header className="space-y-1">
        <h2 id={`${domId}-heading`} className="text-sm font-semibold">
          {labels.heading}
        </h2>
        <p className="text-muted-foreground text-sm">{labels.description}</p>
      </header>

      {/* 뷰 토글은 **스크럼에만** 있다. 칸반은 보드 뷰 하나뿐이라 고를 것이 없다(R3) —
          그 판정은 [editableViews] 가 낸 목록의 길이 하나로 끝난다. */}
      {views.length > 1 && (
        <ViewToggle views={views} value={view} onChange={setRequestedView} idPrefix={domId} />
      )}

      {/* 실패는 **화면에 남는다.** 토스트만 띄우면 사용자가 저장된 줄 알고 화면을 떠난다. */}
      {failure !== null && (
        <div
          role="alert"
          className="border-destructive/40 flex items-center gap-3 rounded-md border p-3 text-sm"
        >
          <p className="text-destructive flex-1">
            {failure.status === 403 ? labels.saveForbidden : labels.saveFailed}
          </p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={mutation.isPending}
            onClick={() => {
              submit(failure.save)
            }}
          >
            {labels.saveRetry}
          </Button>
        </div>
      )}

      <div className="ring-foreground/10 space-y-4 rounded-lg p-4 ring-1">
        <div>
          <h3 className="text-muted-foreground text-xs font-semibold">{labels.standardHeading}</h3>
          <FieldCandidateList
            candidates={STANDARD_FIELDS}
            selected={selected}
            locked={locked}
            atLimit={atLimit}
            idPrefix={domId}
            onToggle={handleToggle}
          />
        </div>

        <div>
          <h3 className="text-muted-foreground text-xs font-semibold">{labels.customHeading}</h3>

          {customFieldsQuery.isPending && (
            <div className="mt-2 space-y-2" aria-hidden="true">
              {[0, 1, 2].map((row) => (
                <Skeleton key={row} className="h-5 w-40" />
              ))}
            </div>
          )}

          {customFieldsQuery.isError && (
            // 커스텀 조회가 실패해도 표준 필드는 그대로 고를 수 있다 — 탭 전체를 막지 않는다.
            <div className="mt-2 flex items-center gap-3">
              <p className="text-muted-foreground flex-1 text-sm">{labels.customLoadError}</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  void customFieldsQuery.refetch()
                }}
              >
                {labels.customRetry}
              </Button>
            </div>
          )}

          {customFieldsQuery.data !== undefined &&
            (customFields.length === 0 ? (
              // 안심 문구다 — 「없음」을 회색으로 비워 두면 사용자가 조회 실패로 읽는다
              // (`UnmappedStatesPanel` 의 빈 상태와 같은 온도 · 스펙 §8b).
              <p className="text-muted-foreground mt-2 text-sm">{labels.customEmpty}</p>
            ) : (
              <FieldCandidateList
                candidates={customFields}
                selected={selected}
                locked={locked}
                atLimit={atLimit}
                idPrefix={domId}
                onToggle={handleToggle}
              />
            ))}
        </div>
      </div>

      {atLimit && <p className="text-muted-foreground text-xs">{labels.limitReached}</p>}
    </section>
  )
}
