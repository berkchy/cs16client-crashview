#!/data/data/com.termux/files/usr/bin/bash
# Step-aware live viewer for ALL jobs of a workflow run.
#
# Tracks each job's active step. While a step runs it renders that step's log
# lines as they appear (each unique line only ever printed once -- dedupe via
# a seen-file). When a step completes it prints a DONE banner and moves on;
# when a job finishes all its steps it prints that job's result. On run
# completion prints the summary and, on failure, the compile errors.
# Raw lines are mirrored to ci-watch.log.
#
# Usage:
#   ./ci-watch.sh                 # watch the latest run
#   ./ci-watch.sh <run-id>        # watch a specific run
set -u

REPO="berkchy/cs16client-crashview"
LOG="ci-watch.log"
SEENFILE="ci-watch.seen"
RUN="${1:-}"

if [[ -z "$RUN" ]]; then
  RUN="$(gh run list --repo "$REPO" --limit 1 --json databaseId --jq '.[0].databaseId')"
fi

: > "$LOG"
: > "$SEENFILE"
echo "==> watching run $RUN (all jobs, step-aware)  Ctrl-C to stop"
printf '==> watching run %s at %s\n' "$RUN" "$(date '+%H:%M:%S')" >> "$LOG"

# job -> step we currently have open
declare -A cur_step
# job -> 1 once we announced its "completed" result
declare -A announced_done

# ---- initial snapshot: print current state of all jobs ----
echo "==> initial state:"
gh api "repos/$REPO/actions/runs/$RUN/jobs" 2>/dev/null \
  --jq '.jobs[] | "    " + .name + " -> " + (.status) + (if .conclusion then " (" + .conclusion + ")" else "" end)'
echo

while true; do
  state="$(gh run view "$RUN" --repo "$REPO" --json status,conclusion --jq '{s:.status,c:.conclusion}' 2>/dev/null)"
  status="$(jq -r '.s' <<<"$state")"

  # ---- active step per job (process-sub: same shell, so arrays persist) ----
  declare -A act_step=()
  while IFS=$'\t' read -r job step; do
    [[ -n "$job" ]] && act_step["$job"]="$step"
  done < <(gh api "repos/$REPO/actions/runs/$RUN/jobs" 2>/dev/null \
              --jq '.jobs[] | . as $j |
                    [.steps[]? | select(.status!="completed")][0] |
                    select(. != null) |
                    $j.name + "\t" + .name')

  # ---- run summary for completion ----
  if [[ "$status" == "completed" ]]; then
    conclusion="$(jq -r '.c' <<<"$state")"
    echo
    echo "==> run $RUN $conclusion"
    gh run view "$RUN" --repo "$REPO" --json jobs \
      --jq '.jobs[] | "    " + (.name + " -> " + (.conclusion // "n/a"))'
    if [[ "$conclusion" == "failure" ]]; then
      echo
      echo "==> failed (errors):"
      gh run view "$RUN" --repo "$REPO" --log-failed 2>/dev/null \
        | tr -d '\r' \
        | grep -iE "error:|fatal|undefined|FAILED|FAILURE|ninja: build stopped|no matching" \
        | grep -viE "JAVA_HOME|ANDROID_HOME|GRADLE_USER_HOME|DEVELOCITY" \
        | grep -v "note:" \
        | grep -v "\^" \
        | head -n 100 || true
      # Kotlin / Gradle errors (if any)
      gh run view "$RUN" --repo "$REPO" --log-failed 2>/dev/null \
        | grep -E "e: .*\.kt" \
        | head -n 50 || true
    fi
    exit 0
  fi

  # ---- announce newly-active steps / completed jobs (main shell) ----
  for job in "${!act_step[@]}"; do
    step="${act_step[$job]}"
    if [[ "${cur_step[$job]:-}" != "$step" ]]; then
      echo
      echo "==> [$job] [$step] running ..."
      printf '==> [%s] [%s] running\n' "$job" "$step" >> "$LOG"
      cur_step["$job"]="$step"
      unset "announced_done[$job]"
    fi
  done
  # mark jobs that have no pending steps as finished
  while read -r job concl; do
    [[ -z "$job" ]] && continue
    if [[ -z "${act_step[$job]:-}" && "${announced_done[$job]:-}" != "1" ]]; then
      echo "==> [$job] completed ($concl)"
      printf '==> [%s] completed (%s)\n' "$job" "$concl" >> "$LOG"
      announced_done["$job"]="1"
    fi
  done < <(gh api "repos/$REPO/actions/runs/$RUN/jobs" 2>/dev/null \
              --jq '.jobs[] | select(.status=="completed") | [.name, (.conclusion // "-")] | @tsv')

  # ---- render each job's current active step log lines (dedupe) ----
  gh run view "$RUN" --repo "$REPO" --log 2>/dev/null \
    | tr -d '\r' \
    | while IFS=$'\t' read -r job step raw; do
        [[ -z "$raw" ]] && continue
        if [[ -n "${act_step[$job]:-}" && "${act_step[$job]}" == "$step" ]]; then
          if ! grep -qxF -- "$raw" "$SEENFILE"; then
            printf '%s\n' "$raw"
            printf '%s\n' "$raw" >> "$SEENFILE"
            printf '%s\n' "$raw" >> "$LOG"
          fi
        fi
      done

  sleep 3
done
