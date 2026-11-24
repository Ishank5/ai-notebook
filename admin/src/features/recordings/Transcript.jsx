import { FileText, Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { Btn, cn } from "../../components/ui";
import { clock } from "./format";
import { t } from "./strings";

/**
 * What was said, one line per speaker turn, each line a way into the audio.
 *
 * Clicking a line's time plays the meeting from there, which is the point:
 * any claim in the summary can be checked against the actual words in
 * seconds. While the audio plays, the line being spoken is marked, and the
 * panel follows it unless the reader has turned that off to look elsewhere.
 */
export function Transcript({ transcript, names, currentTime, onSeek, loading, onLoad }) {
  const [filter, setFilter] = useState("");
  const [follow, setFollow] = useState(true);
  const activeRef = useRef(null);
  const listRef = useRef(null);

  const turns = useMemo(() => transcript?.turns ?? [], [transcript]);
  const active = useMemo(() => {
    if (!Number.isFinite(currentTime)) return -1;
    let found = -1;
    for (let i = 0; i < turns.length; i++) {
      if (turns[i].t0 <= currentTime + 0.25) found = i;
      else break;
    }
    return found;
  }, [turns, currentTime]);

  /*
   * FOLLOWS INSIDE ITS OWN BOX ONLY. `scrollIntoView` would also scroll the
   * page, dragging a reader away from the summary they were reading every
   * time the speaker changed.
   */
  useEffect(() => {
    const list = listRef.current;
    const line = activeRef.current;
    if (!follow || active < 0 || !list || !line) return;
    // The list is `relative`, so a line's offsetTop is measured from it.
    const top = line.offsetTop;
    const bottom = top + line.offsetHeight;
    if (top < list.scrollTop || bottom > list.scrollTop + list.clientHeight) {
      list.scrollTo({ top: Math.max(0, top - 24), behavior: "smooth" });
    }
  }, [active, follow]);

  if (!transcript) {
    return (
      <Btn variant="outline" onClick={onLoad} loading={loading} className="w-full">
        <FileText className="size-4" aria-hidden="true" />
        {t("transcript.load")}
      </Btn>
    );
  }

  const needle = filter.trim().toLowerCase();
  const visible = turns.map((turn, i) => ({ turn, i })).filter(({ turn }) => !needle || turn.text.toLowerCase().includes(needle));

  return (
    <div className="flex min-h-0 flex-col gap-3">
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-faint" aria-hidden="true" />
          <input
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t("transcript.search")}
            aria-label={t("transcript.search")}
            className="field h-9 pl-10"
          />
        </div>
        <label className="flex shrink-0 items-center gap-1.5 text-xs font-semibold text-ink-muted">
          <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} className="size-4 accent-[var(--color-primary)]" />
          {t("transcript.follow")}
        </label>
      </div>

      <p className="text-xs text-ink-faint">
        {t("transcript.meta", {
          turns: turns.length,
          speakers: transcript.speakers?.length ?? 0,
          language: transcript.language ?? "-",
        })}
      </p>

      {visible.length === 0 && <p className="py-4 text-center text-sm text-ink-muted">{t("transcript.noMatch")}</p>}

      <ol ref={listRef} className="relative -mx-2 flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto px-2">
        {visible.map(({ turn, i }) => {
          const name = names.get(turn.s);
          return (
            <li
              key={i}
              ref={i === active ? activeRef : undefined}
              className={cn("flex gap-2.5 rounded-xl px-2 py-2 text-sm leading-relaxed text-ink", i === active && "bg-primary-tint")}
            >
              <button
                type="button"
                onClick={() => onSeek(turn.t0)}
                className="num mt-0.5 h-fit shrink-0 rounded-full px-1.5 text-[11px] font-bold text-primary hover:bg-primary-tint"
                title={t("transcript.playFrom")}
              >
                {clock(turn.t0)}
              </button>
              <div className="min-w-0">
                <span className="mr-1.5 text-xs font-bold text-ink-muted">
                  {name ? `${name} (${turn.s})` : turn.s}
                </span>
                <span className="whitespace-pre-wrap break-words">{turn.text}</span>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
