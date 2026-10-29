#!/data/data/com.termux/files/usr/bin/sh
# Manual-only, one-shot, fail-closed controller for the cached-app freezer.
set -eu
ACTION="${1:-status}"
DIR="/data/user/0/com.tencent.mm/files/wechat-memory-guard"
AM="/system/bin/am"
CHCON="/data/data/com.termux/files/usr/bin/chcon"
case "$ACTION" in
  status)
    su -c "if test -f '$DIR/events.tsv'; then tail -n 15 '$DIR/events.tsv'; else echo 'No probe events yet'; fi"
    ;;
  request)
    PID="$(su -c 'pidof com.tencent.mm' | awk '{print $1}')"
    [ -n "$PID" ] || { echo 'Main process absent' >&2; exit 1; }
    [ "$(su -c "cat /proc/$PID/comm")" = 'com.tencent.mm' ] || { echo 'PID process identity changed' >&2; exit 1; }
    ADJ="$(su -c "cat /proc/$PID/oom_score_adj")"
    [ "$ADJ" -ge 400 ] || { echo "Refusing request: oom_adj=$ADJ" >&2; exit 1; }
    TOP="$(su -c '/system/bin/dumpsys activity activities' 2>/dev/null | grep -E -m1 'topResumedActivity=|ResumedActivity:' || true)"
    [ -n "$TOP" ] || { echo 'Unable to confirm current foreground app' >&2; exit 1; }
    case "$TOP" in *com.tencent.mm*) echo 'Refusing request: WeChat visible' >&2; exit 1 ;; esac
    AUDIO="$(su -c '/system/bin/dumpsys audio' 2>/dev/null | grep -m1 'mAudioModeOwner: AudioModeInfo:' || true)"
    case "$AUDIO" in *mMode=MODE_NORMAL*) : ;; *) echo 'Refusing request: active or unknown audio mode' >&2; exit 1 ;; esac
    su -c "test -s '$DIR/events.tsv' && test ! -e '$DIR/request'" || { echo 'Missing probe or stale request' >&2; exit 1; }
    OWNER="$(su -c "stat -c '%u:%g' '$DIR/events.tsv'")"
    printf 'Requesting one manual purge on PID=%s (cached adj=%s).\n' "$PID" "$ADJ"
    # Create with the right uid/SELinux MCS label BEFORE atomic rename.
    su -c "printf 'PURGE:$PID:WAKE\n' > '$DIR/request.tmp.$PID' && chown '$OWNER' '$DIR/request.tmp.$PID' && $CHCON --reference='$DIR/events.tsv' '$DIR/request.tmp.$PID' && chmod 600 '$DIR/request.tmp.$PID' && mv '$DIR/request.tmp.$PID' '$DIR/request'"
    # Android 14+ freezes cached process threads. Wake only once to deliver
    # the already queued request. No sticky freezer or OOM setting changes.
    su -c "$AM unfreeze com.tencent.mm"
    sleep 7
    su -c "tail -n 8 '$DIR/events.tsv'"
    ;;
  cancel)
    su -c "rm -f '$DIR/request'"
    echo 'Pending manual request cancelled'
    ;;
  *)
    echo "Usage: $0 {status|request|cancel}" >&2
    exit 2 ;;
esac
