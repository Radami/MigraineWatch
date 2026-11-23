#!/usr/bin/env bash
#
# Runs the iOS side of MigraineWatch on a Mac, from another machine, over SSH.
#
# Each command first mirrors this working tree to the Mac, uncommitted changes included, then
# runs the step there. The Mac's own checkout is never touched: the mirror lives in a folder
# of its own, and signing secrets and build output are never copied.
#
# Usage: scripts/ios-remote.sh <command> [sim|device]
#
#   sync              mirror the working tree to the Mac, and nothing else
#   test              run the shared suites on the iOS simulator
#   sim               build, install and launch the app on the simulator
#   device            build, install and launch the app on the iPhone plugged into the Mac
#   shot [sim|device] copy a screenshot back to build/ios-remote/screenshots
#   logs [sim|device] stream the app's log lines until Ctrl+C; on the iPhone it relaunches it
#
# Settings, from the environment:
#
#   MAC_HOST       the SSH host of the Mac (default: mac, an alias in ~/.ssh/config)
#   MAC_DIR        the mirror's folder on the Mac, under its home (default: Projects/MigraineWatch-remote)
#   IOS_SIMULATOR  the simulator to use (default: iPhone 17 Pro)
#   IOS_DEVICE     the iPhone's name or UDID (default: the one physical device connected)

set -euo pipefail

MAC_HOST="${MAC_HOST:-mac}"
MAC_DIR="${MAC_DIR:-Projects/MigraineWatch-remote}"
IOS_SIMULATOR="${IOS_SIMULATOR:-iPhone 17 Pro}"
IOS_DEVICE="${IOS_DEVICE:-}"

readonly SCHEME="MigraineWatch"
readonly APP_NAME="MigraineWatch.app"
# The app's own lines: logged from its binary or its Kotlin framework, not the system's.
readonly APP_LOG_PREDICATE='process == "MigraineWatch" AND senderImagePath CONTAINS "MigraineWatch"'
readonly BUNDLE_ID="com.radami.migrainewatch"
readonly CONFIGURATION="Debug"
readonly IOS_DIR="iosApp"
readonly PROJECT="$IOS_DIR/MigraineWatch.xcodeproj"
readonly DERIVED_DATA="build/ios-remote/derived-data"
readonly MAC_SCREENSHOT="build/ios-remote/screenshot.png"
readonly LOCAL_SCREENSHOTS="build/ios-remote/screenshots"
readonly LOGIN_KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

# The first argument that marks a run on the Mac itself, as the local side asks for.
readonly ON_MAC="on-mac"

readonly TARGET_SIMULATOR="sim"
readonly TARGET_DEVICE="device"

# Never copied: build output and caches (the Mac keeps its own, so builds stay incremental),
# git, IDE state, files generated on the Mac, and anything holding a secret or a local path.
readonly SYNC_EXCLUDES=(
    build/ .gradle/ .kotlin/ .git/ .idea/ .claude/ captures/ play-assets/ xcuserdata/ .DS_Store
    local.properties keystore.properties '*.jks' '*.keystore'
    "/$PROJECT" "/$IOS_DIR/MigraineWatch/Info.plist"
)

usage() {
    sed -n '9,23s/^# \{0,1\}//p' "$0" >&2
    exit 2
}

fail() {
    echo "ios-remote: $*" >&2
    exit 1
}

# --- On this machine ---------------------------------------------------------------------------

sync_to_mac() {
    local excludes=()
    local pattern
    for pattern in "${SYNC_EXCLUDES[@]}"; do
        excludes+=(--exclude "$pattern")
    done

    # rsync creates only the last folder of the destination, so make the rest first.
    ssh "$MAC_HOST" mkdir -p "$MAC_DIR"
    rsync -az --delete "${excludes[@]}" ./ "$MAC_HOST:$MAC_DIR/"
}

# Runs this script on the Mac, in the mirror. A login shell, because a plain SSH command skips
# the profile that sets JAVA_HOME, ANDROID_HOME and Homebrew's PATH.
run_on_mac() {
    local remote
    remote="cd $(printf %q "$MAC_DIR")"
    remote+=" && IOS_SIMULATOR=$(printf %q "$IOS_SIMULATOR") IOS_DEVICE=$(printf %q "$IOS_DEVICE")"
    remote+=" scripts/ios-remote.sh $ON_MAC $(printf '%q ' "$@")"

    # A terminal when there is one, so Ctrl+C reaches a log stream on the Mac and stops it.
    local tty=()
    if [[ -t 0 && -t 1 ]]; then
        tty=(-t)
    fi

    ssh "${tty[@]}" "$MAC_HOST" "bash -lc $(printf %q "$remote")"
}

fetch_screenshot() {
    local target="$1"
    run_on_mac shot "$target"

    mkdir -p "$LOCAL_SCREENSHOTS"
    local local_file
    local_file="$LOCAL_SCREENSHOTS/$target-$(date +%Y%m%d-%H%M%S).png"
    scp -q "$MAC_HOST:$MAC_DIR/$MAC_SCREENSHOT" "$local_file"
    echo "$local_file"
}

run_locally() {
    local command="${1:-}"
    local target="${2:-$TARGET_SIMULATOR}"

    case "$command" in
        sync) sync_to_mac ;;
        test | sim | device) sync_to_mac && run_on_mac "$command" ;;
        shot) fetch_screenshot "$target" ;;
        logs) run_on_mac logs "$target" ;;
        *) usage ;;
    esac
}

# --- On the Mac --------------------------------------------------------------------------------

require_target() {
    case "$1" in
        "$TARGET_SIMULATOR" | "$TARGET_DEVICE") ;;
        *) fail "unknown target '$1': use $TARGET_SIMULATOR or $TARGET_DEVICE" ;;
    esac
}

# The xcodeproj isn't checked in; regenerate it so it follows project.yml.
generate_project() {
    (cd "$IOS_DIR" && xcodegen generate --quiet)
}

app_path() {
    local sdk="$1"
    echo "$DERIVED_DATA/Build/Products/$CONFIGURATION-$sdk/$APP_NAME"
}

# The UDID of IOS_DEVICE, matched by name or UDID; with none set, of the one iPhone connected.
device_udid() {
    xcrun devicectl list devices --quiet --json-output - | python3 -c '
import json, sys

wanted = sys.argv[1]
devices = json.load(sys.stdin)["result"]["devices"]
physical = [
    d for d in devices
    if d["hardwareProperties"].get("reality") == "physical"
    and d["connectionProperties"].get("tunnelState") == "connected"
]
matches = [
    d for d in physical
    if not wanted or wanted in (d["deviceProperties"]["name"], d["hardwareProperties"]["udid"])
]

if len(matches) != 1:
    names = ", ".join(d["deviceProperties"]["name"] for d in physical) or "none"
    sys.exit(f"ios-remote: need exactly one matching iPhone; connected: {names}. Set IOS_DEVICE.")

print(matches[0]["hardwareProperties"]["udid"])
' "$IOS_DEVICE"
}

# The UDID of IOS_SIMULATOR, matched by name or UDID. xcodebuild given a name alone assumes the
# newest iOS, and one name can exist on several: prefer the one booted, then the newest iOS.
simulator_udid() {
    xcrun simctl list devices available --json | python3 -c '
import json, re, sys

wanted = sys.argv[1]
runtimes = json.load(sys.stdin)["devices"]

def version(runtime):
    return [int(part) for part in re.findall(r"\d+", runtime.rsplit(".", 1)[-1])]

matches = [
    (device["state"] == "Booted", version(runtime), device["udid"])
    for runtime, devices in runtimes.items() if "iOS" in runtime
    for device in devices
    if wanted in (device["name"], device["udid"])
]

if not matches:
    sys.exit(f"ios-remote: no simulator named {wanted!r}. Set IOS_SIMULATOR; xcrun simctl list lists them.")

print(max(matches)[2])
' "$IOS_SIMULATOR"
}

# Signing reads the certificate from the login keychain, which an SSH session can find locked.
# Unlocking it takes the Mac password, so that stays with the user.
require_unlocked_keychain() {
    if security show-keychain-info "$LOGIN_KEYCHAIN" >/dev/null 2>&1; then
        return
    fi

    fail "the Mac's login keychain is locked, so the app can't be signed. Unlock it with
  ssh -t $MAC_HOST security unlock-keychain
and run this again."
}

mac_test() {
    ./gradlew :shared:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
}

# The app that shows simulators: DeviceHub from Xcode 27, Simulator before it. Without either,
# the simulator still runs, with no window.
show_simulator_window() {
    local developer
    developer="$(xcode-select -p)"

    local app
    for app in "$developer/../Applications/DeviceHub.app" "$developer/Applications/Simulator.app"; do
        if [[ -d "$app" ]]; then
            open "$app"
            return
        fi
    done

    echo "ios-remote: found no app to show the simulator in; use shot to see it." >&2
}

mac_simulator() {
    local udid
    udid="$(simulator_udid)"

    generate_project
    xcodebuild -quiet -project "$PROJECT" -scheme "$SCHEME" -configuration "$CONFIGURATION" \
        -destination "platform=iOS Simulator,id=$udid" \
        -derivedDataPath "$DERIVED_DATA" build

    # Boot it if needed and wait until it's ready, then show it on the Mac's screen.
    xcrun simctl bootstatus "$udid" -b >/dev/null
    show_simulator_window

    xcrun simctl install "$udid" "$(app_path iphonesimulator)"
    xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE_ID"
}

mac_device() {
    local udid
    udid="$(device_udid)"
    require_unlocked_keychain

    # Provisioning updates renew the Personal Team profile, which lasts a week.
    generate_project
    xcodebuild -quiet -project "$PROJECT" -scheme "$SCHEME" -configuration "$CONFIGURATION" \
        -destination "platform=iOS,id=$udid" -allowProvisioningUpdates \
        -derivedDataPath "$DERIVED_DATA" build

    xcrun devicectl device install app --quiet --device "$udid" "$(app_path iphoneos)"
    xcrun devicectl device process launch --quiet --terminate-existing --device "$udid" "$BUNDLE_ID"
}

mac_screenshot() {
    local target="$1"
    require_target "$target"
    mkdir -p "$(dirname "$MAC_SCREENSHOT")"

    local udid
    if [[ "$target" == "$TARGET_SIMULATOR" ]]; then
        udid="$(simulator_udid)"
        xcrun simctl io "$udid" screenshot "$MAC_SCREENSHOT" >/dev/null
        return
    fi

    udid="$(device_udid)"
    xcrun devicectl device capture screenshot --quiet --device "$udid" --destination "$MAC_SCREENSHOT"
}

mac_logs() {
    local target="$1"
    require_target "$target"

    local udid
    if [[ "$target" == "$TARGET_SIMULATOR" ]]; then
        udid="$(simulator_udid)"
        xcrun simctl spawn "$udid" log stream --style compact --predicate "$APP_LOG_PREDICATE"
        return
    fi

    # The iPhone's log can't be streamed on its own; launching with a console attached can.
    udid="$(device_udid)"
    xcrun devicectl device process launch --console --terminate-existing --device "$udid" "$BUNDLE_ID"
}

run_on_this_mac() {
    local command="${1:-}"
    local target="${2:-$TARGET_SIMULATOR}"

    case "$command" in
        test) mac_test ;;
        sim) mac_simulator ;;
        device) mac_device ;;
        shot) mac_screenshot "$target" ;;
        logs) mac_logs "$target" ;;
        *) usage ;;
    esac
}

# --- Entry -------------------------------------------------------------------------------------

cd "$(dirname "$0")/.."

if [[ "${1:-}" == "$ON_MAC" ]]; then
    shift
    run_on_this_mac "$@"
    exit
fi

run_locally "$@"
