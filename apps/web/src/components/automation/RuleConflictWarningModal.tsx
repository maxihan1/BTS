// 자동화 규칙 저장 후 검출된 충돌을 표시하는 1회성 경고 모달 (FR-AT-04 D6)
import type { JSX } from 'react'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import type { ConflictType, RuleConflict } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (WebhookTokenModal.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: '규칙이 저장되었습니다',
  warningHeading: '다음 충돌이 감지되었습니다. 저장은 그대로 유지됩니다.',
  close: '확인',
} as const

/** 충돌 종류 4종 → 한국어 배지 라벨 — backend `ConflictType` 1:1 대응. */
const CONFLICT_TYPE_LABELS: Record<ConflictType, string> = {
  CYCLE: '순환 참조',
  FIELD_CONFLICT: '필드 충돌',
  PRIORITY_AMBIGUITY: '우선순위 모호',
  PERMISSION_MISSING: '권한 부족',
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** RuleConflictWarningModal props */
export interface RuleConflictWarningModalProps {
  /** 규칙 저장 응답에 동봉된 충돌 목록. null 또는 빈 배열이면 모달을 렌더하지 않는다 */
  readonly conflicts: RuleConflict[] | null
  /** 닫힘 콜백 — 호출부는 이 시점에 `conflicts` state를 null로 되돌린다 */
  readonly onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 충돌 1건을 렌더한다. `ruleIds`는 UUID 원문을 노출하지 않고 "관련 규칙 N개"로 축약한다
 * (design 리뷰 보강 5 — 이름 조회를 하지 않으므로 UUID 나열은 사용자에게 무의미하다).
 */
function ConflictItem({ conflict }: { conflict: RuleConflict }): JSX.Element {
  return (
    <li className="rounded-md border border-warning bg-warning/10 p-3">
      <span className="inline-block rounded bg-warning px-2 py-0.5 text-xs font-semibold text-warning-foreground">
        {CONFLICT_TYPE_LABELS[conflict.type]}
      </span>
      <p className="mt-1 text-sm text-warning-text">{conflict.detail}</p>
      <p className="mt-1 text-xs text-warning-text">관련 규칙 {conflict.ruleIds.length}개</p>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleConflictWarningModal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 규칙 저장 후 검출된 충돌을 표시하는 1회성 정보 모달.
 *
 * 충돌은 `severity=WARNING`(soft) 뿐이므로 저장 자체를 막지 않는다 — 이 모달은 저장이 이미
 * 성공했음을 먼저 알린 뒤, 감지된 충돌을 참고 정보로만 보여준다(design 리뷰 보강 4).
 * "확인" 클릭·바깥 클릭·Esc로 닫히면 `onClose`를 호출한다. 실제 목록 소멸은 호출부가
 * `conflicts` state를 null로 되돌리는 시점에 일어난다(WebhookTokenModal.tsx와 동일 패턴).
 */
export function RuleConflictWarningModal({
  conflicts,
  onClose,
}: RuleConflictWarningModalProps): JSX.Element | null {
  // null(GET 응답) 또는 빈 배열(충돌 없는 create/patch)이면 렌더하지 않는다 — 유일 호출부
  // emitConflicts가 length>0에서만 전달하므로 빈 배열은 도달 불가하지만, spec E1("빈 배열 →
  // 미표시")을 모달 계층에도 이중 보장한다(방어적, code-review SUGGESTION).
  if (conflicts === null || conflicts.length === 0) return null

  function handleOpenChange(open: boolean): void {
    if (!open) onClose()
  }

  return (
    <Dialog open onOpenChange={handleOpenChange}>
      <DialogContent
        data-testid="rule-conflict-warning-modal"
        className="max-w-md"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>{labels.title}</DialogTitle>
        </DialogHeader>

        {/* role="alert" — 경고 소제목 + 충돌 목록을 하나의 경고 본문으로 묶어 스크린리더가
            중복 announce하지 않도록 한다(제목/목록에 각각 role을 주지 않음). Description 대신
            plain <p>로 두어 role 중복을 피하고, 설명 부재는 aria-describedby={undefined}로 억제한다. */}
        <div role="alert">
          <p className="mt-2 text-sm text-warning-text">
            {labels.warningHeading}
          </p>

          <ul className="mt-4 flex flex-col gap-2">
            {conflicts.map((conflict, index) => (
              // key: 충돌 항목에 안정적인 id가 없다 — type+position(index)으로 안정적 구분 (AutomationRuleList.tsx 선례).
              <ConflictItem key={`${conflict.type}-${index}`} conflict={conflict} />
            ))}
          </ul>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button size="sm" data-testid="rule-conflict-close-button">
              {labels.close}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
