#!/data/data/com.termux/files/usr/bin/sh
# No persistence, hooks, or network access. Only manual root-guarded request.
set -eu
ACTION="${1:-status}"
DIR="/data/user/0/com.tencent.mm/files/wechat-memory-guard"
case "$ACTION" in
  status)
    su -c "if test -f '$DIR/events.tsv'; then tail -n 15 '$DIR/events.tsv'; else echo 'No probe events yet (is module enabled and in scope?)'; fi"
    ;;
  request)
    PID="$(su -c 'pidof com.tencent.mm' | awk '{print $1}')"
    [ -n "$PID" ] || { echo "WeChat main process not found" >&2; exit 1; }
    echo "Manual PURGE request for PID $PID. Will be accepted only if app has been backgrounded >=120s, oom_adj>=400 and no active audio mode."
    su -c "test -d '$DIR' && printf 'PURGE:$PID\n' > '$DIR/request' && chmod 600 '$DIR/request'"
    echo "Request placed; see: sh scripts/termux-control.sh status"
    ;;
  cancel)
    su -c "rm -f '$DIR/request'"
    echo "Pending request cancelled"
    ;;
  *)
    echo "Usage: $0 {status|request|cancel}" >&2
    exit 2 ;;
esac
