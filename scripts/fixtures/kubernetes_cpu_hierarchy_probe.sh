#!/bin/bash
# Fixed hosted-node diagnostic. Never an Agent command or local privilege launcher.
set -euo pipefail
name=${1:?}; mode=${2:?}; nonce=${3:?}
[[ $nonce =~ ^[0-9a-f]{32}$ ]] || exit 2
umask 077
[[ $name =~ ^rr-cpu-[0-9a-f]{32}$ ]] || exit 2
root=/sys/fs/cgroup
owned=$root/$name
marker=/tmp/$name
# A global cgroup-v2 root has no cpu.max. Require the finite, private test-node root.
[[ $(stat -f -c %T "$root") == cgroup2fs ]]
[[ $(cat "$root/cpu.max") == '200000 100000' ]]
[[ $(cat "$root/memory.max") == 2147483648 ]]
[[ $(cat /proc/1/cgroup) == '0::/' || $(cat /proc/1/cgroup) == '0::/init.scope' ]]
for controller in cpu memory pids; do
    [[ " $(cat "$root/cgroup.subtree_control") " == *" $controller "* ]]
done
remove_group() {
    while [[ -d $1 ]]; do
        rmdir "$1" 2>/dev/null && break
        (( SECONDS < cleanup_deadline )) || return 2
        sleep 0.05
    done
}
cleanup() {
    local cleanup_deadline=$((SECONDS + 6))
    if [[ ! -e $marker ]]; then [[ ! -e $owned ]]; return; fi
    [[ -d $marker && ! -L $marker && -f $marker/nonce && ! -L $marker/nonce ]]
    [[ $(cat "$marker/nonce") == "$nonce" ]]
    if [[ -d $owned ]]; then
        # An exclusive marker binds this invocation to the created inode even if
        # quota/controller setup failed. Desired limits are not ownership proof.
        [[ ! -L $owned && -f $marker/inode && ! -L $marker/inode ]]
        [[ $(stat -c '%d:%i' "$owned") == "$(cat "$marker/inode")" ]]
        echo 1 > "$owned/cgroup.kill"
        for case in equal stricter; do
            for leaf in child noise; do remove_group "$owned/$case/$leaf"; done
            remove_group "$owned/$case"
        done
        remove_group "$owned/control"
        remove_group "$owned"
    fi
    [[ ! -e $owned ]]
    rm -f -- "$marker/inode" "$marker/nonce"
    rmdir "$marker"
}
if [[ $mode == cleanup ]]; then cleanup; echo CLEANUP; exit; fi
[[ $mode == run && ! -e $owned && ! -e $marker ]]
for tool in setpriv taskset timeout stat; do command -v "$tool" >/dev/null; done
# From the first mutation onward, an independent outer timeout and cleanup call
# remain active. Refuse deletion if a killed setup never established the inode.
trap 'cleanup' EXIT
trap 'exit 2' TERM INT
mkdir -m 700 "$marker"
printf '%s\n' "$nonce" > "$marker/nonce"
mkdir "$owned"
stat -c '%d:%i' "$owned" > "$marker/inode"
# No ancestor controller, quota or process placement is changed.
echo '50000 100000' > "$owned/cpu.max"
echo 67108864 > "$owned/memory.max"
echo 16 > "$owned/pids.max"
[[ $(cat "$owned/cpu.max") == '50000 100000' ]]
[[ $(cat "$owned/memory.max") == 67108864 && $(cat "$owned/pids.max") == 16 ]]
echo '+cpu +memory +pids' > "$owned/cgroup.subtree_control"
mkdir "$owned/control"
stats() {
    local usage='' periods='' throttled='' duration='' key value
    while read -r key value; do
        case $key in usage_usec) usage=$value;; nr_periods) periods=$value;;
          nr_throttled) throttled=$value;; throttled_usec) duration=$value;; esac
    done < "$1/cpu.stat"
    [[ $usage =~ ^[0-9]+$ && $periods =~ ^[0-9]+$ && $throttled =~ ^[0-9]+$ && $duration =~ ^[0-9]+$ ]]
    echo "$usage $periods $throttled $duration"
}
measure() {
# The worker receives only its own path and fixed role. It retains UID 0 but has
# zero effective/permitted/inheritable/bounding/ambient capabilities and NNP=1.
# Expansion occurs only in the capability-dropped worker shell.
# shellcheck disable=SC2016
worker='set -euo pipefail
path=$1; role=$2
for field in CapInh CapPrm CapEff CapBnd CapAmb; do
  found=0
  while read -r key value rest; do
    if [[ $key == "$field:" ]]; then [[ $value == 0000000000000000 ]]; found=1; fi
  done </proc/self/status
  [[ $found == 1 ]]
done
while read -r key value rest; do
  if [[ $key == NoNewPrivs: ]]; then [[ $value == 1 ]]; nnp=1; fi
done </proc/self/status
[[ ${nnp:-0} == 1 && $UID == 0 ]]
read -r uptime rest </proc/uptime
[[ $uptime =~ ^[0-9]+\.[0-9]{2}$ ]]; start=${uptime/./}
before=0
while read -r key value; do [[ $key != nr_throttled ]] || before=$value; done <"$path/cpu.stat"
now=$start; own=$before
while (( now - start < 1000 )); do
  for ((i=0;i<5000;i++)); do :; done
  while read -r key value; do [[ $key != nr_throttled ]] || own=$value; done <"$path/cpu.stat"
  read -r uptime rest </proc/uptime; now=${uptime/./}
  if [[ $role == child ]] && (( own > before )); then break; fi
done
# Quantized monotonic samples bound elapsed time strictly below one extra tick.
# Preserve strict ten-second decoder proof; never report a same-tick zero.
if [[ $role == child ]]; then echo "WORKER $before $own $(( (now-start+1)*10000 ))"; fi'
while read -r key value rest; do
    if [[ $key == Cpus_allowed_list: ]]; then cpu=${value%%[-,]*}; fi
done </proc/self/status
[[ ${cpu:-} =~ ^[0-9]+$ ]]
for case in equal stricter; do
    parent=$owned/$case
    mkdir "$parent"; echo '10000 100000' > "$parent/cpu.max"
    echo '+cpu' > "$parent/cgroup.subtree_control"
    mkdir "$parent/child" "$parent/noise"
    quota=10000; [[ $case == equal ]] || quota=8000
    echo "$quota 100000" > "$parent/child/cpu.max"
    echo '1000 100000' > "$parent/noise/cpu.max"
    echo "CASE $case"
    echo "PARENT $(cat "$parent/cpu.max")"
    echo "CHILD $(cat "$parent/child/cpu.max")"
    echo "NOISE $(cat "$parent/noise/cpu.max")"
    echo "PARENT_BEFORE $(stats "$parent")"
    echo "CHILD_BEFORE $(stats "$parent/child")"
    echo "NOISE_BEFORE $(stats "$parent/noise")"
    # Both workers use one allowed CPU, with identical fixed sibling contention.
    (echo "$BASHPID" > "$parent/noise/cgroup.procs"; exec setpriv --no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all taskset -c "$cpu" bash -c "$worker" probe "$parent/noise" noise) >/dev/null &
    noise=$!
    (echo "$BASHPID" > "$parent/child/cgroup.procs"; exec setpriv --no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all taskset -c "$cpu" bash -c "$worker" probe "$parent/child" child) &
    child=$!
    wait "$child"
    # Kill only this created sibling subtree, never a reused numeric PID.
    echo 1 > "$parent/noise/cgroup.kill"
    wait "$noise" || [[ $? == 137 ]]
    echo "PARENT_AFTER $(stats "$parent")"
    echo "CHILD_AFTER $(stats "$parent/child")"
    echo "NOISE_AFTER $(stats "$parent/noise")"
done
}
# Only the tiny setup/cleanup supervisor stays outside; all measurement work is
# charged to the bounded owned subtree, including its shell controller.
(trap - EXIT TERM INT; echo "$BASHPID" > "$owned/control/cgroup.procs"; measure)
cleanup
trap - EXIT
echo CLEANUP
