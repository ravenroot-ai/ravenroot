#!/usr/bin/env bash
set -u -o pipefail

if [[ $# -ne 7 ]]; then
  echo "usage: $0 CHECKOUT MODE CONDITION START_INDEX COUNT COMMIT OUTPUT_DIR" >&2
  exit 2
fi

checkout=$1
mode=$2
condition=$3
start_index=$4
count=$5
commit=$6
output_dir=$7

case "$mode" in
  base|candidate|candidate-siblings) ;;
  *) echo "invalid mode: $mode" >&2; exit 2 ;;
esac
case "$condition" in
  isolated|contended) ;;
  *) echo "invalid condition: $condition" >&2; exit 2 ;;
esac
[[ "$start_index" =~ ^[0-9]+$ ]] || { echo "invalid start index" >&2; exit 2; }
[[ "$count" =~ ^[1-9][0-9]*$ ]] || { echo "invalid count" >&2; exit 2; }
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "invalid commit" >&2; exit 2; }
[[ -d "$checkout/.git" || -f "$checkout/.git" ]] || { echo "checkout is not a Git worktree" >&2; exit 2; }

actual_commit=$(git -C "$checkout" rev-parse HEAD)
[[ "$actual_commit" == "$commit" ]] || {
  echo "checkout HEAD $actual_commit does not equal declared commit $commit" >&2
  exit 2
}

reactor="$checkout/ravenroot"
module="$reactor/ravenroot-sandbox-supervisor-testkit"
classes="$module/target/test-classes"
validator="$module/src/test/scripts/validate-cpu-measurement.py"
[[ -f "$reactor/pom.xml" && -d "$classes" && -f "$validator" ]] || {
  echo "measurement requires the validated checkout and completed test-compile" >&2
  exit 2
}

mkdir -p "$output_dir/logs" "$output_dir/load" "$output_dir/reports" "$output_dir/markers"
records="$output_dir/samples.jsonl"
touch "$records"
module_tree=$(git -C "$checkout" rev-parse "$commit:ravenroot/ravenroot-sandbox-supervisor-testkit")
printf 'commit\t%s\nmoduleTree\t%s\nmode\t%s\ncondition\t%s\n' \
  "$commit" "$module_tree" "$mode" "$condition" >"$output_dir/source-identity.tsv"

subject_paths=(
  ravenroot/ravenroot-sandbox-supervisor-testkit/src/main/java/ai/ravenroot/testkit/sandbox/SandboxSupervisorContract.java
  ravenroot/ravenroot-sandbox-supervisor-testkit/src/test/java/ai/ravenroot/testkit/sandbox/NonCompliantOnCpu.java
  ravenroot/ravenroot-sandbox-supervisor-testkit/src/test/java/ai/ravenroot/testkit/sandbox/NonCompliantSupervisorRedControlTest.java
  ravenroot/ravenroot-sandbox-supervisor-testkit/src/test/java/ai/ravenroot/testkit/sandbox/RealisticFakeSupervisor.java
)
for subject in "${subject_paths[@]}"; do
  declared_blob=$(git -C "$checkout" rev-parse "$commit:$subject")
  worktree_blob=$(git -C "$checkout" hash-object "$checkout/$subject")
  printf '%s\t%s\t%s\n' "$subject" "$declared_blob" "$worktree_blob" \
    >>"$output_dir/source-identity.tsv"
  [[ "$declared_blob" == "$worktree_blob" ]] || {
    echo "subject file differs from declared commit: $subject" >&2
    exit 2
  }
done

worker_pids=()
heartbeat_files=()
start_file="$output_dir/load/start"
stop_file="$output_dir/load/stop"
worker_stop_failed=0

stop_workers() {
  if [[ ${#worker_pids[@]} -eq 0 ]]; then
    return
  fi
  touch "$stop_file"
  local index pid code
  for index in "${!worker_pids[@]}"; do
    pid=${worker_pids[index]}
    wait "$pid"
    code=$?
    if (( code != 0 )); then
      echo "contention worker $index exited $code" >&2
      worker_stop_failed=1
    fi
  done
  worker_pids=()
}
trap stop_workers EXIT

heartbeat_iteration() {
  local file=$1
  awk 'NF == 3 && $2 ~ /^[0-9]+$/ { print $2; exit }' "$file" 2>/dev/null || true
}

heartbeat_snapshot() {
  local result="" status=0 index pid file value
  for index in "${!heartbeat_files[@]}"; do
    pid=${worker_pids[index]}
    file=${heartbeat_files[index]}
    kill -0 "$pid" 2>/dev/null || status=1
    value=$(heartbeat_iteration "$file")
    [[ "$value" =~ ^[0-9]+$ ]] || { value=0; status=1; }
    result+="$value,"
  done
  printf '%s' "${result%,}"
  return "$status"
}

if [[ "$condition" == contended ]]; then
  processors=$(getconf _NPROCESSORS_ONLN)
  worker_count=$((processors * 2))
  (( worker_count >= 2 )) || worker_count=2
  rm -f "$start_file" "$stop_file"
  for ((worker=1; worker<=worker_count; worker++)); do
    heartbeat="$output_dir/load/worker-$worker.heartbeat"
    log="$output_dir/load/worker-$worker.log"
    heartbeat_files+=("$heartbeat")
    java -cp "$classes" ai.ravenroot.testkit.sandbox.CpuContentionWorker \
      "$start_file" "$stop_file" "$heartbeat" >"$log" 2>&1 &
    worker_pids+=("$!")
  done

  ready_deadline=$((SECONDS + 30))
  while :; do
    ready=0
    for index in "${!worker_pids[@]}"; do
      kill -0 "${worker_pids[index]}" 2>/dev/null || {
        echo "contention worker $index exited before start" >&2
        exit 1
      }
      [[ -f "${heartbeat_files[index]}" ]] && ((ready+=1))
    done
    (( ready == worker_count )) && break
    (( SECONDS < ready_deadline )) || { echo "contention workers did not become ready" >&2; exit 1; }
    sleep 0.1
  done
  touch "$start_file"
  progress_deadline=$((SECONDS + 30))
  while :; do
    progressing=0
    for index in "${!heartbeat_files[@]}"; do
      iteration=$(heartbeat_iteration "${heartbeat_files[index]}")
      [[ "${iteration:-0}" =~ ^[0-9]+$ ]] && (( iteration > 0 )) && ((progressing+=1))
    done
    (( progressing == worker_count )) && break
    (( SECONDS < progress_deadline )) || { echo "contention workers did not report progress" >&2; exit 1; }
    sleep 0.1
  done
fi

{
  uname -a
  java -version
  getconf _NPROCESSORS_ONLN
  [[ -r /sys/fs/cgroup/cpu.max ]] && cat /sys/fs/cgroup/cpu.max
  [[ -r /proc/pressure/cpu ]] && cat /proc/pressure/cpu
} >"$output_dir/host-before.txt" 2>&1

phase_failed=0
last_index=$((start_index + count - 1))
for ((sample=start_index; sample<=last_index; sample++)); do
  sample_id=$(printf '%03d' "$sample")
  scheduled_at=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  jq -cn --arg commit "$commit" --arg mode "$mode" --arg condition "$condition" \
    --arg sample "$sample_id" --arg scheduledAt "$scheduled_at" \
    '{commit:$commit,mode:$mode,condition:$condition,sample:$sample,scheduledAt:$scheduledAt}' \
    >>"$output_dir/scheduled.jsonl"
  calibration='null'
  calibration_load_valid=true
  calibration_before=""
  calibration_after=""
  if [[ "$mode" == base ]]; then
    calibration_log="$output_dir/logs/$condition-$sample_id-calibration.log"
    if [[ "$condition" == contended ]]; then
      calibration_before=$(heartbeat_snapshot) || calibration_load_valid=false
    fi
    external_start=$(python3 -c 'import time; print(time.monotonic_ns())')
    timeout 12s java -cp "$classes" ai.ravenroot.testkit.sandbox.CpuServiceCalibration \
      >"$calibration_log" 2>&1
    calibration_code=$?
    external_end=$(python3 -c 'import time; print(time.monotonic_ns())')
    if [[ "$condition" == contended ]]; then
      calibration_after=$(heartbeat_snapshot) || calibration_load_valid=false
      IFS=',' read -r -a calibration_before_values <<<"$calibration_before"
      IFS=',' read -r -a calibration_after_values <<<"$calibration_after"
      if (( ${#calibration_before_values[@]} != ${#worker_pids[@]}
            || ${#calibration_after_values[@]} != ${#worker_pids[@]} )); then
        calibration_load_valid=false
      else
        for index in "${!calibration_before_values[@]}"; do
          if (( calibration_after_values[index] <= calibration_before_values[index] )); then
            calibration_load_valid=false
          fi
        done
      fi
    fi
    external_wall_millis=$(((external_end - external_start) / 1000000))
    calibration_line=$(sed -n 's/^RAVENROOT_CPU_CALIBRATION=//p' "$calibration_log" | tail -1)
    if (( calibration_code == 0 )) && [[ -n "$calibration_line" ]]; then
      calibration=$(jq -cn --argjson value "$calibration_line" \
        --argjson external "$external_wall_millis" \
        --arg heartbeatBefore "$calibration_before" --arg heartbeatAfter "$calibration_after" \
        --argjson loadValid "$calibration_load_valid" \
        '$value + {externalWallMillis:$external,loadHeartbeatBefore:$heartbeatBefore,
          loadHeartbeatAfter:$heartbeatAfter,loadValid:$loadValid,
          runnable:($value.supported and $value.cpuMillis >= 4000 and $external <= 6000 and $loadValid)}')
    else
      calibration=$(jq -cn --argjson code "$calibration_code" \
        --argjson external "$external_wall_millis" \
        --arg heartbeatBefore "$calibration_before" --arg heartbeatAfter "$calibration_after" \
        --argjson loadValid "$calibration_load_valid" \
        '{supported:false,runnable:false,harnessExit:$code,externalWallMillis:$external,
          loadHeartbeatBefore:$heartbeatBefore,loadHeartbeatAfter:$heartbeatAfter,loadValid:$loadValid}')
    fi
  fi

  before=""
  before_ok=0
  if [[ "$condition" == contended ]]; then
    before=$(heartbeat_snapshot) || before_ok=$?
  fi
  outer_started_at=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  outer_start=$(python3 -c 'import time; print(time.monotonic_ns())')
  target_log="$output_dir/logs/$condition-$sample_id-outer.log"
  suffix="issue138-$mode-$condition-$sample_id"
  if [[ "$mode" == candidate-siblings ]]; then
    selector=NonCompliantSupervisorRedControlTest
    safety=480s
  else
    selector='NonCompliantSupervisorRedControlTest#aSupervisorNotEnforcingCpuFailsOnlyTheCpuTest'
    safety=70s
  fi
  timeout "$safety" mvn -B -q -o -f "$reactor/pom.xml" \
    -pl ravenroot-sandbox-supervisor-testkit -Dtest="$selector" \
    -Dsurefire.rerunFailingTestsCount=0 -DforkCount=1 -DreuseForks=false \
    -Dsurefire.reportNameSuffix="$suffix" \
    -Dravenroot.cpu.measurement.sample="$sample_id" \
    -Dravenroot.cpu.measurement.condition="$condition" test \
    >"$target_log" 2>&1
  outer_code=$?
  outer_end=$(python3 -c 'import time; print(time.monotonic_ns())')
  after=""
  after_ok=0
  if [[ "$condition" == contended ]]; then
    after=$(heartbeat_snapshot) || after_ok=$?
  fi
  outer_ended_at=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
  outer_elapsed_millis=$(((outer_end - outer_start) / 1000000))
  timed_out=false
  (( outer_code == 124 )) && timed_out=true

  load_valid=true
  if [[ "$condition" == contended ]]; then
    if (( before_ok != 0 || after_ok != 0 )); then
      load_valid=false
    else
      IFS=',' read -r -a before_values <<<"$before"
      IFS=',' read -r -a after_values <<<"$after"
      if (( ${#before_values[@]} != ${#worker_pids[@]}
            || ${#after_values[@]} != ${#worker_pids[@]} )); then
        load_valid=false
      else
        for index in "${!before_values[@]}"; do
          if (( after_values[index] <= before_values[index] )); then
            load_valid=false
          fi
        done
      fi
    fi
  fi

  marker_file="$output_dir/markers/$condition-$sample_id-outer.jsonl"
  if [[ "$mode" == candidate-siblings ]]; then
    sed -n 's/^.*RAVENROOT_RED_CONTROL=//p' "$target_log" >"$marker_file"
  elif [[ "$mode" == candidate ]]; then
    sed -n 's/^.*RAVENROOT_CPU_RED_CONTROL=//p' "$target_log" >"$marker_file"
  else
    : >"$marker_file"
  fi

  report_matches=("$module"/target/surefire-reports/TEST-*"$suffix".xml)
  report_text_matches=("$module"/target/surefire-reports/*"$suffix".txt)
  if (( ${#report_matches[@]} != 1 || ${#report_text_matches[@]} != 1 )) \
      || [[ ! -f "${report_matches[0]}" || ! -f "${report_text_matches[0]}" ]]; then
    report_valid=false
    report_summary='{"valid":false,"reason":"missing or ambiguous uniquely named outer Surefire report"}'
  else
    report_copy="$output_dir/reports/$condition-$sample_id-outer.xml"
    cp "${report_matches[0]}" "$report_copy"
    cp "${report_text_matches[0]}" "$output_dir/reports/$condition-$sample_id-outer.txt"
    python3 "$validator" "$mode" "$report_copy" "$marker_file" \
      "$outer_code" "$sample_id" "$condition" \
      >"$output_dir/markers/$condition-$sample_id-outer-validation.json"
    validation_code=$?
    report_summary=$(tail -1 "$output_dir/markers/$condition-$sample_id-outer-validation.json")
    report_valid=true
    (( validation_code == 0 )) || report_valid=false
  fi

  auxiliary='null'
  auxiliary_scheduled=false
  auxiliary_exit='null'
  auxiliary_before=""
  auxiliary_after=""
  auxiliary_load_valid=true
  auxiliary_elapsed_millis='null'
  if [[ "$mode" == base ]] && { (( sample == 1 )) || (( sample == 10 )) || (( sample == 20 )); }; then
    auxiliary_scheduled=true
    auxiliary_log="$output_dir/logs/$condition-$sample_id-auxiliary.log"
    auxiliary_suffix="issue138-auxiliary-$condition-$sample_id"
    if [[ "$condition" == contended ]]; then
      auxiliary_before=$(heartbeat_snapshot) || auxiliary_load_valid=false
    fi
    auxiliary_start=$(python3 -c 'import time; print(time.monotonic_ns())')
    timeout 70s mvn -B -q -o -f "$reactor/pom.xml" \
      -pl ravenroot-sandbox-supervisor-testkit -Dtest=CpuRedControlMeasurement \
      -Dsurefire.rerunFailingTestsCount=0 -DforkCount=1 -DreuseForks=false \
      -Dsurefire.reportNameSuffix="$auxiliary_suffix" \
      -Dravenroot.cpu.measurement.mode=base-real-auxiliary \
      -Dravenroot.cpu.measurement.sample="$sample_id" \
      -Dravenroot.cpu.measurement.condition="$condition" \
      -Dravenroot.cpu.measurement.commit="$commit" test \
      >"$auxiliary_log" 2>&1
    auxiliary_exit=$?
    auxiliary_end=$(python3 -c 'import time; print(time.monotonic_ns())')
    auxiliary_elapsed_millis=$(((auxiliary_end - auxiliary_start) / 1000000))
    if [[ "$condition" == contended ]]; then
      auxiliary_after=$(heartbeat_snapshot) || auxiliary_load_valid=false
      IFS=',' read -r -a auxiliary_before_values <<<"$auxiliary_before"
      IFS=',' read -r -a auxiliary_after_values <<<"$auxiliary_after"
      if (( ${#auxiliary_before_values[@]} != ${#worker_pids[@]}
            || ${#auxiliary_after_values[@]} != ${#worker_pids[@]} )); then
        auxiliary_load_valid=false
      else
        for index in "${!auxiliary_before_values[@]}"; do
          if (( auxiliary_after_values[index] <= auxiliary_before_values[index] )); then
            auxiliary_load_valid=false
          fi
        done
      fi
    fi
    auxiliary_marker="$output_dir/markers/$condition-$sample_id-auxiliary.jsonl"
    sed -n 's/^.*RAVENROOT_CPU_AUXILIARY=//p' "$auxiliary_log" >"$auxiliary_marker"
    auxiliary_reports=("$module"/target/surefire-reports/TEST-*"$auxiliary_suffix".xml)
    auxiliary_text_reports=("$module"/target/surefire-reports/*"$auxiliary_suffix".txt)
    if (( ${#auxiliary_reports[@]} == 1 && ${#auxiliary_text_reports[@]} == 1 )) \
        && [[ -f "${auxiliary_reports[0]}" && -f "${auxiliary_text_reports[0]}" ]]; then
      auxiliary_report="$output_dir/reports/$condition-$sample_id-auxiliary.xml"
      cp "${auxiliary_reports[0]}" "$auxiliary_report"
      cp "${auxiliary_text_reports[0]}" "$output_dir/reports/$condition-$sample_id-auxiliary.txt"
      python3 "$validator" auxiliary "$auxiliary_report" "$auxiliary_marker" \
        "$auxiliary_exit" "$sample_id" "$condition" \
        >"$output_dir/markers/$condition-$sample_id-auxiliary-validation.json"
      auxiliary_validation_code=$?
      auxiliary=$(tail -1 "$output_dir/markers/$condition-$sample_id-auxiliary-validation.json")
      if (( auxiliary_validation_code != 0 )) || [[ "$auxiliary_load_valid" != true ]]; then
        phase_failed=1
      fi
    else
      auxiliary='{"valid":false,"reason":"missing or ambiguous auxiliary Surefire report"}'
      phase_failed=1
    fi
  fi

  jq -cn \
    --arg commit "$commit" --arg moduleTree "$module_tree" --arg mode "$mode" \
    --arg condition "$condition" --arg sample "$sample_id" --arg scheduledAt "$scheduled_at" \
    --arg startedAt "$outer_started_at" --arg endedAt "$outer_ended_at" \
    --argjson elapsedMillis "$outer_elapsed_millis" --argjson safetyTimedOut "$timed_out" \
    --argjson rerunCount 0 --argjson calibration "$calibration" \
    --argjson outerExit "$outer_code" --argjson outer "$report_summary" \
    --argjson auxiliaryScheduled "$auxiliary_scheduled" \
    --argjson auxiliaryExit "$auxiliary_exit" --argjson auxiliary "$auxiliary" \
    --argjson auxiliaryElapsedMillis "$auxiliary_elapsed_millis" \
    --arg auxiliaryHeartbeatBefore "$auxiliary_before" \
    --arg auxiliaryHeartbeatAfter "$auxiliary_after" \
    --argjson auxiliaryLoadValid "$auxiliary_load_valid" \
    --arg heartbeatBefore "$before" --arg heartbeatAfter "$after" \
    --argjson loadValid "$load_valid" \
    '{commit:$commit,moduleTree:$moduleTree,mode:$mode,condition:$condition,sample:$sample,
      scheduledAt:$scheduledAt,startedAt:$startedAt,endedAt:$endedAt,
      elapsedMillis:$elapsedMillis,safetyTimedOut:$safetyTimedOut,rerunCount:$rerunCount,
      calibration:$calibration,outerExit:$outerExit,outer:$outer,
      auxiliaryScheduled:$auxiliaryScheduled,auxiliaryExit:$auxiliaryExit,auxiliary:$auxiliary,
      auxiliaryElapsedMillis:$auxiliaryElapsedMillis,
      auxiliaryHeartbeatBefore:$auxiliaryHeartbeatBefore,
      auxiliaryHeartbeatAfter:$auxiliaryHeartbeatAfter,auxiliaryLoadValid:$auxiliaryLoadValid,
      loadHeartbeatBefore:$heartbeatBefore,loadHeartbeatAfter:$heartbeatAfter,
      loadValid:$loadValid}' >>"$records"

  if [[ "$report_valid" != true || "$load_valid" != true || "$timed_out" == true ]]; then
    phase_failed=1
  fi
done

{
  [[ -r /sys/fs/cgroup/cpu.max ]] && cat /sys/fs/cgroup/cpu.max
  [[ -r /proc/pressure/cpu ]] && cat /proc/pressure/cpu
} >"$output_dir/host-after.txt" 2>&1

stop_workers
trap - EXIT
(( worker_stop_failed == 0 )) || phase_failed=1
exit "$phase_failed"
