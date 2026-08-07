// 보드/백로그 카드의 라벨 칩 행 — 최대 3개 + "+N" 오버플로 (FR-UX-14 F14 Task 2)
import type { JSX } from 'react'
import { Badge } from '@/components/ui/badge'
import { cardLabels } from '@/i18n/card-labels'

/** 접히지 않고 그대로 노출되는 라벨 최대 개수 */
const MAX_VISIBLE_LABELS = 3

interface CardLabelChipsProps {
  /** 이슈에 붙은 라벨 이름 목록 */
  labels: readonly string[]
}

/**
 * 보드·백로그 카드가 **공유**하는 라벨 칩 행.
 *
 * 라벨이 4개 이상이면 앞 3개만 칩으로 보여주고 나머지는 "+N" 오버플로 칩 하나로
 * 접는다. 오버플로 칩의 `aria-label`에는 숨은 라벨 전문이 담겨야 한다 — `title`은
 * 터치 화면에서 뜨지 않기 때문이다 (`i18n/card-labels.ts` D6 참고).
 *
 * @param labels 이슈에 붙은 라벨 이름 목록 (빈 배열이면 아무것도 렌더하지 않는다)
 */
export function CardLabelChips({ labels }: CardLabelChipsProps): JSX.Element | null {
  if (labels.length === 0) return null // FR9 — 빈 div도 만들지 않는다

  const visible = labels.slice(0, MAX_VISIBLE_LABELS)
  const hidden = labels.slice(MAX_VISIBLE_LABELS)

  return (
    <div className="flex flex-wrap items-center gap-1">
      {visible.map((label) => (
        <Badge
          key={label}
          variant="neutral"
          className="max-w-[10rem] truncate text-muted-foreground"
        >
          {label}
        </Badge>
      ))}
      {hidden.length > 0 && (
        <Badge
          variant="neutral"
          className="text-muted-foreground"
          aria-label={cardLabels.moreLabelsAriaLabel(hidden)} // D6 — 터치엔 hover가 없다
          title={hidden.join(', ')} // 마우스 보조 (주 경로 아님)
        >
          {`+${hidden.length}`}
        </Badge>
      )}
    </div>
  )
}
