import { Headphones } from "lucide-react";
import { useImperativeHandle, useRef, useState } from "react";
import { toast } from "sonner";

import { errorKey, media } from "./api";
import { t } from "./strings";
import { Btn } from "../../components/ui";

/** A signed link lasts an hour; one older than this is replaced before it is used. */
const LINK_REFRESH_MS = 55 * 60 * 1000;

/**
 * The meeting's audio, loaded only when somebody asks to hear it.
 *
 * NOT ON PAGE LOAD. Opening a recording to read its summary is not listening
 * to it, and every link minted is a line in the access log. So the player
 * starts as a button, and clicking any timestamp on the page loads it too.
 *
 * `ref.seek(seconds)` jumps there and plays: the transcript and the summary's
 * timestamps both call it. `onTime` reports the position so the transcript can
 * follow along.
 */
export function Player({ ref, recordingId, available, onTime }) {
  const audio = useRef(null);
  const [link, setLink] = useState(null);
  const [loading, setLoading] = useState(false);
  const pendingSeek = useRef(null);
  const mintedAt = useRef(0);

  async function load() {
    if (loading) return null;
    setLoading(true);
    try {
      const { url } = await media(recordingId, "listen");
      mintedAt.current = Date.now();
      setLink(url);
      return url;
    } catch (err) {
      toast.error(t(errorKey(err)));
      return null;
    } finally {
      setLoading(false);
    }
  }

  function playAt(seconds) {
    const el = audio.current;
    if (!el) return;
    el.currentTime = Math.max(0, seconds);
    el.play().catch(() => undefined);
  }

  useImperativeHandle(ref, () => ({
    async seek(seconds) {
      if (!available) return;
      if (!link || Date.now() - mintedAt.current > LINK_REFRESH_MS) {
        pendingSeek.current = seconds;
        await load();
        return;
      }
      playAt(seconds);
    },
  }));

  if (!available) {
    return <p className="text-sm text-ink-muted">{t("player.none")}</p>;
  }

  if (!link) {
    return (
      <Btn onClick={load} loading={loading} className="w-full">
        <Headphones className="size-4" aria-hidden="true" />
        {t("player.load")}
      </Btn>
    );
  }

  return (
    <audio
      ref={audio}
      src={link}
      controls
      preload="metadata"
      className="h-11 w-full"
      onLoadedMetadata={() => {
        if (pendingSeek.current !== null) {
          playAt(pendingSeek.current);
          pendingSeek.current = null;
        }
      }}
      onTimeUpdate={(e) => onTime?.(e.currentTarget.currentTime)}
      onError={() => {
        // Most likely the hour ran out mid-meeting. A fresh link picks up
        // where the listener was.
        if (Date.now() - mintedAt.current > LINK_REFRESH_MS) {
          pendingSeek.current = audio.current?.currentTime ?? 0;
          setLink(null);
          void load();
        } else {
          toast.error(t("player.error"));
        }
      }}
    >
      {t("player.unsupported")}
    </audio>
  );
}
