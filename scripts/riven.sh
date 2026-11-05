#!/usr/bin/env bash
# Riven TV — ADB helper for the Thomson 65OATH7000 (or any Google TV / Android TV).
#
# Everything here is reversible: apps are *disabled* or removed for user 0 only,
# never wiped from the system partition. Every change is logged to
# scripts/state/changes.log, and `./riven.sh restore` undoes all of it.
#
# Usage:
#   ./riven.sh setup <tv-ip>            everything safe in one go: connect, info, backup, install,
#                                       make Home, screensaver, snappier animations
#   ./riven.sh connect <tv-ip>          adb connect over the network (port 5555)
#   ./riven.sh pair <tv-ip:port>        pair with Android 11+ "Wireless debugging"
#   ./riven.sh info                     model, Android version, chipset, launcher
#   ./riven.sh backup                   save the full package list before changing anything
#   ./riven.sh scan                     show which bloat on the lists is present on this TV
#   ./riven.sh install <apk>            install / update the Riven launcher
#   ./riven.sh set-home                 make Riven the Home app
#   ./riven.sh debloat [--apply]        disable bloat (dry run unless --apply)
#   ./riven.sh replace-stock [--apply]  disable the Google TV home screen (Riven takes over Home)
#   ./riven.sh screensaver              make Riven Ambient the TV's screensaver
#   ./riven.sh speed                    snappier system animations (0.5x), remembered for restore
#   ./riven.sh restore                  re-enable everything this script ever disabled
#
# Set ADB_SERIAL=<ip:5555> if more than one device is connected.

set -euo pipefail
cd "$(dirname "$0")"
STATE=state; mkdir -p "$STATE"
LOG="$STATE/changes.log"
RIVEN_PKG=com.riven.tv
RIVEN_HOME="$RIVEN_PKG/.MainActivity"

# Use the SDK's adb if adb isn't on PATH.
command -v adb >/dev/null || PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
RIVEN_APK="../Riven.apk"   # ADB installs skip Play Protect, so the full edition is fine
adb_() { if [[ -n "${ADB_SERIAL:-}" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi; }
sh_()  { adb_ shell "$@" </dev/null | tr -d '\r'; }
need_adb() { command -v adb >/dev/null || { echo "adb not found. Install it:  brew install --cask android-platform-tools"; exit 1; }; }
need_device() { [[ "$(adb_ get-state 2>/dev/null)" == "device" ]] || { echo "No TV connected. Run: ./riven.sh connect <tv-ip>"; exit 1; }; }
installed() { sh_ pm list packages | sed 's/^package://' | sort; }
enabled()   { sh_ pm list packages -e | sed 's/^package://' | sort; }
list_pkgs() { grep -hv '^\s*#' "$@" | awk '{print $1}' | sed '/^$/d' | sort -u; }

disable_list() {  # $1=apply|dry  $2..=list files
  local mode=$1; shift
  local present; present=$(enabled)
  local n=0
  while read -r pkg; do
    grep -qx "$pkg" <<<"$present" || continue
    n=$((n + 1))
    if [[ $mode == apply ]]; then
      if sh_ pm disable-user --user 0 "$pkg" | grep -q "new state: disabled"; then
        echo "disabled  $pkg"; echo "$pkg" >> "$LOG"
      else
        echo "skipped   $pkg (system refused)"
      fi
    else
      echo "would disable  $pkg"
    fi
  done < <(list_pkgs "$@")
  [[ $n -eq 0 ]] && echo "Nothing from this list is enabled on the TV."
  [[ $mode == dry && $n -gt 0 ]] && echo -e "\nDry run. Re-run with --apply to do it." || true
}

cmd=${1:-help}; shift || true
case "$cmd" in
  setup)
    need_adb; ip=${1:?tv ip}
    "$0" connect "$ip"
    for i in 1 2 3 4 5 6; do [[ "$(adb -s "${ip%:*}:${ip##*:}" get-state 2>/dev/null)" == device ]] && break
      [[ "$(adb get-state 2>/dev/null)" == device ]] && break; echo "Waiting for you to press Allow on the TV..."; sleep 5; done
    need_device
    "$0" info; "$0" backup; "$0" install "$RIVEN_APK"; "$0" set-home; "$0" homekey; "$0" screensaver; "$0" speed
    echo; echo "Done. Next (optional, read the warnings first):"
    echo "  ./riven.sh scan       ./riven.sh debloat       ./riven.sh replace-stock" ;;

  connect)
    need_adb; ip=${1:?tv ip}
    [[ $ip == *:* ]] || ip="$ip:5555"
    adb connect "$ip"
    echo "If the TV shows 'Allow USB debugging?', tick 'Always allow' and press OK."
    adb devices ;;

  pair)
    need_adb; adb pair "${1:?ip:port from the Wireless debugging screen}" ;;

  info)
    need_adb; need_device
    echo "Model    : $(sh_ getprop ro.product.model)  ($(sh_ getprop ro.product.manufacturer))"
    echo "Android  : $(sh_ getprop ro.build.version.release)  (SDK $(sh_ getprop ro.build.version.sdk))"
    echo "Chipset  : $(sh_ getprop ro.board.platform)  $(sh_ getprop ro.hardware)"
    echo "Display  : $(sh_ wm size | tail -1)  $(sh_ wm density | tail -1)"
    echo "Home app : $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
    if installed | grep -qx com.google.android.apps.tv.launcherx; then echo "Platform : Google TV"; else echo "Platform : Android TV"; fi ;;

  backup)
    need_adb; need_device
    f="$STATE/packages-$(date +%Y%m%d-%H%M%S).txt"
    { echo "# all"; installed; echo "# enabled"; enabled; } > "$f"
    echo "Saved $(installed | wc -l | tr -d ' ') packages to scripts/$f" ;;

  scan)
    need_adb; need_device
    present=$(installed)
    for f in packages/*.txt; do
      echo "== $(basename "$f" .txt)"
      list_pkgs "$f" | while read -r p; do grep -qx "$p" <<<"$present" && echo "   $p"; done || true
    done
    echo -e "\nOEM / unknown apps on this TV (review these by hand):"
    installed | grep -v -E '^(android|com\.android\.|com\.google\.android\.(gms|gsf|tv\.|apps\.tv\.launcherx|tungsten))' \
      | grep -v -x -f <(list_pkgs packages/*.txt) | sed 's/^/   /' || true ;;

  install)
    need_adb; need_device
    adb_ install -r "${1:?path to riven apk}"
    echo "Installed. Next: ./riven.sh set-home" ;;

  set-home)
    need_adb; need_device
    sh_ cmd package set-home-activity "$RIVEN_HOME" || true
    sh_ input keyevent KEYCODE_HOME
    echo "Pressed Home. If the TV still opens Google TV, run: ./riven.sh replace-stock --apply" ;;

  debloat)
    need_adb; need_device
    [[ ${1:-} == --apply ]] && mode=apply || mode=dry
    compgen -G "$STATE/packages-*.txt" >/dev/null || { echo "Run ./riven.sh backup first."; exit 1; }
    disable_list "$mode" packages/google-extras.txt packages/streaming-extras.txt packages/oem-extras.txt ;;

  replace-stock)
    need_adb; need_device
    installed | grep -qx "$RIVEN_PKG" || { echo "Install Riven first (./riven.sh install app.apk)."; exit 1; }
    if [[ ${1:-} != --apply ]]; then
      cat <<'EOF'
This disables the Google TV home screen so the Home button lands on Riven:
  com.google.android.apps.tv.launcherx      (Google TV home)
  com.google.android.tungsten.setupwraith   (re-enables the home above if left on)
  com.smartdevice.setupwraith               (Thomson setup wizard, also claims Home)

!! THOMSON: the setup wizard owns your saved Wi-Fi network. BEFORE --apply, on the TV:
   Settings > Network > your Wi-Fi > Forget, then join it again. Otherwise the TV can
   lose Wi-Fi (and this ADB connection) when the wizard is disabled.

Known side effects reported by others:
  - Google TV "For you" / profiles / ambient mode go away (that's the point)
  - the remote's dedicated YouTube button may stop working
  - a few devices have boot-looped; that's why you ran `backup` and why
    ./riven.sh restore exists. Keep the TV on Wi-Fi with ADB enabled.

Re-run with --apply to proceed.
EOF
      exit 0
    fi
    disable_list apply packages/stock-launcher.txt
    sh_ cmd package set-home-activity "$RIVEN_HOME" || true
    sh_ input keyevent KEYCODE_HOME
    echo "Done. Press Home on the remote to check." ;;

  screensaver)
    need_adb; need_device
    old=$(sh_ settings get secure screensaver_components)
    [[ -f $STATE/screensaver.prev ]] || echo "$old" > "$STATE/screensaver.prev"
    sh_ settings put secure screensaver_components com.riven.tv/.RivenDream
    sh_ settings put secure screensaver_enabled 1
    echo "Screensaver: $(sh_ settings get secure screensaver_components)  (was: $old)"
    echo "Pick when it starts in Riven › Customize › Screensaver, or the TV's own sleep timer." ;;

  homekey)
    # Riven's accessibility service answers the Home button even if the stock home stays installed.
    need_adb; need_device
    cur=$(sh_ settings get secure enabled_accessibility_services); [[ $cur == null ]] && cur=""
    [[ -f $STATE/a11y.prev ]] || echo "$cur" > "$STATE/a11y.prev"
    svc=com.riven.tv/com.riven.tv.RivenKeyService
    [[ $cur == *"$svc"* ]] || sh_ settings put secure enabled_accessibility_services "${cur:+$cur:}$svc"
    sh_ settings put secure accessibility_enabled 1
    echo "Home button now opens Riven (accessibility service on)." ;;

  speed)
    need_adb; need_device
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      [[ -f $STATE/$k.prev ]] || sh_ settings get global $k > "$STATE/$k.prev"
      sh_ settings put global $k 0.5
    done
    echo "System animations now 0.5x (snappier). ./riven.sh restore puts them back." ;;

  restore)
    need_adb; need_device
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      if [[ -f $STATE/$k.prev ]]; then
        v=$(cat "$STATE/$k.prev"); [[ $v == null || -z $v ]] && v=1.0
        sh_ settings put global $k "$v"; rm "$STATE/$k.prev"; echo "restored  $k = $v"
      fi
    done
    if [[ -f $STATE/a11y.prev ]]; then
      prev=$(cat "$STATE/a11y.prev")
      if [[ -n $prev ]]; then sh_ settings put secure enabled_accessibility_services "$prev"
      else sh_ settings delete secure enabled_accessibility_services >/dev/null; fi
      rm "$STATE/a11y.prev"; echo "restored  accessibility services"
    fi
    if [[ -f $STATE/screensaver.prev ]]; then
      prev=$(cat "$STATE/screensaver.prev")
      if [[ -n $prev && $prev != null ]]; then sh_ settings put secure screensaver_components "$prev"
      else sh_ settings delete secure screensaver_components >/dev/null; fi
      rm "$STATE/screensaver.prev"; echo "restored  screensaver"
    fi
    [[ -s $LOG ]] || { echo "Nothing else to restore."; exit 0; }
    { tail -r "$LOG" 2>/dev/null || tac "$LOG"; } | awk '!seen[$0]++' | while read -r p; do
      sh_ pm enable --user 0 "$p" >/dev/null 2>&1 || sh_ cmd package install-existing "$p" >/dev/null 2>&1 || true
      echo "restored  $p"
    done
    mv "$LOG" "$LOG.restored-$(date +%s)"
    echo "All changes undone. Reboot the TV: adb reboot" ;;

  *) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
