import type { ComponentChildren } from 'preact';
import { artUrl } from '../sleeves';
import { imageUrl } from '../images';
import { Pip, Pips } from '../symbols';
import { t } from '../text';

/** A commander, planeswalker or deck in a list. A click shows it in the panel, and a double click makes it the one in use. */
export function Plate({ name, image, colors, selected, pressed, pick, choose, lines, tag }: {
  name: string; image?: string | null; colors: string; selected: boolean; pressed: boolean; pick: () => void; choose: () => void; lines: ComponentChildren[];
  tag?: string;
}) {
  return (
    <button class={selected ? 'cq-cmd in-use' : 'cq-cmd'} aria-pressed={pressed} onClick={pick} onDblClick={() => { if (!selected) choose(); }}>
      <div class="a" style={{ backgroundImage: image ? `url("${artUrl(image)}")` : undefined }} />
      {selected && <span class="cq-chip brass tag">{tag ?? t('lblSelected')}</span>}
      <div class="t">
        <b>{name}</b>
        <span class="l"><span class="pips">{colors ? <Pips colors={colors} /> : <Pip letter="C" />}</span>{lines[0]}</span>
        {lines[1] && <span class="l">{lines[1]}</span>}
      </div>
    </button>
  );
}

export function DeckPanel({ image, title, chips, problem, details, children }: {
  image?: string | null; title: string; chips: ComponentChildren; problem?: string | null; details?: ComponentChildren; children: ComponentChildren;
}) {
  return (
    <aside class="cq-side">
      <div class="cq-cmd-detail">
        <div class="cq-cmd-top">
          {image && <img class="cq-card" alt={title} src={imageUrl(image)} data-image={image} />}
          <div class="cq-cmd-about">
            <h3>{title}</h3>
            <div class="cq-chips">{chips}</div>
            {problem && <p class="cq-warn">{problem}</p>}
          </div>
        </div>
        {details}
      </div>
      <div class="cq-foot">{children}</div>
    </aside>
  );
}
