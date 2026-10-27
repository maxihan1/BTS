// 전환 정의 생성·수정 다이얼로그 — 이름 · 출발/도착 상태 · 종류 · 전환 화면
import * as React from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Combobox } from '@/components/ui/combobox'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import type { WorkflowView } from '@/api/workflows'
import type { TransitionDefinitionInput, TransitionKind } from '@/api/workflows-admin'
import { SCREEN_FIELDS, type DraftScreenField } from '@/api/workflows-draft.types'

interface TransitionFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 편성된 상태 — 출발·도착 후보 */
  states: WorkflowView['states']
  /** 수정 대상. 없으면 생성이다 */
  editing: WorkflowView['transitions'][number] | null
  /**
   * 생성 시 출발·도착을 미리 채운다 (FR-WF-07 D8).
   *
   * 다이어그램에서 노드 핸들을 끌어 놓으면 사용자는 **출발과 도착을 이미 지정한 것**이다.
   * 그것을 다시 고르게 하면 방금 한 조작이 없던 일이 된다. `editing` 이 있을 때는 무시한다 —
   * 수정은 기존 값이 정본이다.
   */
  prefill?: { from: string; to: string } | null
  /** 수정 대상의 지금 전환 화면 (FR-WF-01 D10). 생성이면 비어 있다 */
  editingScreen?: DraftScreenField[]
  onSubmit: (input: TransitionDefinitionInput, screen: DraftScreenField[]) => void
  submitting?: boolean
}

const NO_SCREEN: DraftScreenField[] = []

/**
 * 종류별 출발 상태 필요 여부 — 백엔드 규칙과 1:1.
 *
 * ★ `NORMAL`·`GLOBAL` 은 이제 폼에서 고를 수 있다. 종전에는 고르는 컨트롤이 없어 생성이
 * 항상 `NORMAL` 이었고, `GLOBAL` 전환을 **만드는 경로 자체가 없었다** — `TransitionListPanel`
 * 의 `GLOBAL` 배지와 이 함수의 비-`NORMAL` 갈래는 수정 경로로만 도달하는 죽은 분기였다.
 */
function needsFromState(kind: TransitionKind): boolean {
  return kind === 'NORMAL'
}

/**
 * 사용자가 고를 수 있는 전환 종류 — `INITIAL` 은 없다.
 *
 * ★ `INITIAL` 은 워크플로우당 하나뿐이고 V207 백필이 심는다. 그것을 다른 종류로 바꾸면
 * **이슈 생성 진입점이 사라진다.** 고를 수 있는데 골라서는 안 되는 값을 목록에 두지 않는다 —
 * 이 폼이 출발 상태 입력을 감추는 것과 같은 판단이다.
 */
const SELECTABLE_KINDS = [
  {
    value: 'NORMAL',
    label: labels.transitionForm.kindNormal,
    description: labels.transitionForm.kindNormalDescription,
  },
  {
    value: 'GLOBAL',
    label: labels.transitionForm.kindGlobal,
    description: labels.transitionForm.kindGlobalDescription,
  },
] as const satisfies readonly { value: TransitionKind; label: string; description: string }[]

/**
 * 전환 화면 (FR-WF-01 D10) — 전환 때 물을 필드와 그 필수 여부.
 * 필드 순서는 고정이라(해결 결과 → 담당자 → 코멘트) 끌어 바꾸지 않는다.
 */
function TransitionScreenFieldset({
  screen,
  onScreenChange,
}: {
  screen: DraftScreenField[]
  onScreenChange: React.Dispatch<React.SetStateAction<DraftScreenField[]>>
}): React.JSX.Element {
  /** 필드를 화면에 넣거나 뺀다. 빼면 필수도 함께 사라진다 */
  const toggleField = (field: DraftScreenField['field'], included: boolean): void => {
    onScreenChange((current) =>
      included ? [...current, { field, required: false }] : current.filter((f) => f.field !== field),
    )
  }
  const toggleRequired = (field: DraftScreenField['field'], required: boolean): void => {
    onScreenChange((current) => current.map((f) => (f.field === field ? { ...f, required } : f)))
  }

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium">{labels.transitionForm.screen}</legend>
      <span className="text-xs text-(--text-subtle)">{labels.transitionForm.screenDescription}</span>
      {SCREEN_FIELDS.map((field) => {
        const entry = screen.find((f) => f.field === field)
        const fieldLabel = labels.transitionForm.screenFields[field]
        return (
          <div key={field} className="flex items-center gap-4">
            <Label className="flex w-28 items-center gap-2 font-normal">
              <Checkbox
                checked={entry !== undefined}
                onCheckedChange={(checked) => toggleField(field, checked === true)}
              />
              {fieldLabel}
            </Label>
            <Label className="flex items-center gap-2 font-normal text-(--text-subtle)">
              <Checkbox
                aria-label={labels.transitionForm.screenRequired(fieldLabel)}
                checked={entry?.required ?? false}
                disabled={entry === undefined}
                onCheckedChange={(checked) => toggleRequired(field, checked === true)}
              />
              {labels.transitionForm.screenRequiredShort}
            </Label>
          </div>
        )
      })}
    </fieldset>
  )
}

/**
 * 전환을 만들거나 고친다.
 *
 * ★ `GLOBAL`·`INITIAL` 은 출발 상태 입력을 **감춘다**. 보이면 사용자가 고를 수 있고, 실으면
 * 백엔드가 400 이다 — 고를 수 있는데 항상 실패하는 입력을 두지 않는다.
 *
 * ★★ 수정은 **표현 전체 교체**다(PUT). 그래서 폼이 열릴 때 기존 값을 전부 채운다 —
 * 안 채우면 안 건드린 필드가 빈 값으로 덮인다.
 */
function TransitionFormDialog({
  open,
  onOpenChange,
  states,
  editing,
  prefill = null,
  editingScreen = NO_SCREEN,
  onSubmit,
  submitting = false,
}: TransitionFormDialogProps): React.JSX.Element {
  const [name, setName] = React.useState('')
  const [screen, setScreen] = React.useState<DraftScreenField[]>(NO_SCREEN)
  const [kind, setKind] = React.useState<TransitionKind>('NORMAL')
  const [fromKey, setFromKey] = React.useState<string | null>(null)
  const [toKey, setToKey] = React.useState<string | null>(null)
  // 다이얼로그 안쪽을 popover 컨테이너로 쓴다 — 이유는 StatusPickerDialog 와 같다.
  const [portalHost, setPortalHost] = React.useState<HTMLElement | null>(null)

  React.useEffect(() => {
    if (!open) {
      return
    }
    setName(editing?.name ?? '')
    setKind(editing?.kind ?? 'NORMAL')
    // 수정이면 기존 값이 정본이고, 생성이면 다이어그램에서 끌어 온 출발·도착을 먼저 쓴다.
    setFromKey(editing?.fromStateKey ?? prefill?.from ?? null)
    setToKey(editing?.toStateKey ?? prefill?.to ?? states[0]?.key ?? null)
    setScreen(editing === null ? NO_SCREEN : editingScreen)
  }, [open, editing, prefill, states, editingScreen])

  const stateOptions = states.map((s) => ({ value: s.key, label: s.name }))
  const title = editing === null ? labels.dialog.transitionCreate : labels.dialog.transitionEdit
  const canSubmit =
    name.trim().length > 0 && toKey !== null && (!needsFromState(kind) || fromKey !== null) && !submitting

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" ref={setPortalHost}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{labels.transitionForm.description}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="transition-name">{labels.transitionForm.name}</Label>
            <Input
              id="transition-name"
              aria-label={labels.transitionForm.name}
              placeholder={labels.transitionNamePlaceholder}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          {/*
            ★`INITIAL` 을 수정할 때는 종류 자체를 감춘다. 라디오에 `INITIAL` 항목이 없으므로
              그대로 두면 「아무것도 선택되지 않은 라디오 그룹」이 되고, 사용자가 무심코
              하나를 고르는 순간 이슈 생성 진입점이 사라진다.
          */}
          {editing?.kind === 'INITIAL' ? null : (
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium">{labels.transitionForm.kind}</legend>
              <RadioGroup
                value={kind}
                onValueChange={(next) => setKind(next as TransitionKind)}
                aria-label={labels.transitionForm.kind}
                className="flex flex-col gap-2"
              >
                {SELECTABLE_KINDS.map((option) => {
                  const itemId = `transition-kind-${option.value.toLowerCase()}`
                  const labelId = `${itemId}-label`
                  const descriptionId = `${itemId}-description`
                  return (
                    <div key={option.value} className="flex items-start gap-2">
                      {/*
                        ★이름을 `aria-labelledby` 로 건다. `RadioGroupItem` 의 내용은 표시용
                          원뿐이라 그대로 두면 **이름 없는 라디오**가 되고 스크린리더는
                          「라디오 버튼」이라고만 읽는다 — `board/CreateBoardForm.tsx` 선례.
                          설명을 `aria-describedby` 로 잇는 것도 같은 이유다. 이름만으로는
                          두 종류를 가를 수 없다.
                      */}
                      <RadioGroupItem
                        id={itemId}
                        value={option.value}
                        aria-labelledby={labelId}
                        aria-describedby={descriptionId}
                        className="mt-1"
                      />
                      <div className="flex flex-col">
                        <Label id={labelId} htmlFor={itemId}>
                          {option.label}
                        </Label>
                        <span id={descriptionId} className="text-xs text-(--text-subtle)">
                          {option.description}
                        </span>
                      </div>
                    </div>
                  )
                })}
              </RadioGroup>
            </fieldset>
          )}

          {needsFromState(kind) ? (
            <div className="flex flex-col gap-2">
              <Label htmlFor="transition-from">{labels.transitionForm.fromState}</Label>
              <Combobox
                id="transition-from"
                options={stateOptions}
                value={fromKey}
                onChange={setFromKey}
                ariaLabel={labels.transitionForm.fromState}
                placeholder={labels.statusSearchPlaceholder}
                emptyText={labels.statusPanel.empty}
                triggerPlaceholder={labels.statusSearchPlaceholder}
                container={portalHost}
              />
            </div>
          ) : null}

          <div className="flex flex-col gap-2">
            <Label htmlFor="transition-to">{labels.transitionForm.toState}</Label>
            <Combobox
              id="transition-to"
              options={stateOptions}
              value={toKey}
              onChange={setToKey}
              ariaLabel={labels.transitionForm.toState}
              placeholder={labels.statusSearchPlaceholder}
              emptyText={labels.statusPanel.empty}
              triggerPlaceholder={labels.statusSearchPlaceholder}
              container={portalHost}
            />
          </div>

          {/* 시작 전환에는 전환 화면을 감춘다 — 이슈 생성은 생성 폼이 화면이고, 백엔드가 400 이다. */}
          {kind === 'INITIAL' ? null : <TransitionScreenFieldset screen={screen} onScreenChange={setScreen} />}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {labels.dialog.cancel}
          </Button>
          <Button
            disabled={!canSubmit}
            onClick={() => {
              if (toKey === null) {
                return
              }
              // 화면 필드는 고정 순서로 싣는다 — 체크한 순서가 저장 순서를 흔들지 않게.
              const ordered = SCREEN_FIELDS.flatMap((field) => screen.filter((f) => f.field === field))
              onSubmit(
                {
                  name: name.trim(),
                  kind,
                  toStatusKey: toKey,
                  fromStatusKey: needsFromState(kind) ? fromKey : null,
                },
                ordered,
              )
              onOpenChange(false)
            }}
          >
            {labels.transitionForm.submit}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { TransitionFormDialog }
export type { TransitionFormDialogProps }
