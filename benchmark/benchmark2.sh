#!/usr/bin/env bash
set -euo pipefail

SERVER="http://localhost:8080"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CLI_JAR="${REPO_ROOT}/client/trino-cli/target/trino-cli-478-executable.jar"
PREFILTER_PROPERTY="enable_prefilter_rewrite"

AUTO_SQL="${SCRIPT_DIR}/test_query.sql"
RESULTS_CSV="${SCRIPT_DIR}/mr_prefilter_sizes.csv"
REPETITIONS=1
ROW_LIMIT=500

rm -f "${RESULTS_CSV}"

render_sql()
{
    local sql_file="$1"
    sed "s/__ROW_LIMIT__/${ROW_LIMIT}/g" "${sql_file}" | tr '\n' ' '
}

run()
{
    local name="$1"
    local sql_file="$2"
    local session_value="$3"

    local sql_one_line
    local start_ms end_ms elapsed_ms
    local query_output rows

    sql_one_line="$(render_sql "${sql_file}")"

    start_ms="$(date +%s%3N)"

    query_output="$(java -jar "${CLI_JAR}" \
        --server "${SERVER}" \
        --progress=false \
        --output-format CSV_HEADER_UNQUOTED \
        --execute "SET SESSION ${PREFILTER_PROPERTY} = '${session_value}'; ${sql_one_line}" \
        2>&1)"

    end_ms="$(date +%s%3N)"
    elapsed_ms=$((end_ms - start_ms))

    rows="$(printf '%s\n' "${query_output}" | awk '/^match_count$/ {getline; print; exit}')"
    printf '%s,%s\n' "${elapsed_ms}" "${rows}"
}

echo "variant,row_limit,avg_elapsed_ms,avg_rows" > "${RESULTS_CSV}"

for variant in prefilter_off prefilter_fp prefilter_fo prefilter_op; do
    elapsed_sum=0
    rows_sum=0

    case "${variant}" in
        prefilter_off)
            session_value=""
            ;;
        prefilter_fp)
            session_value="F,P"
            ;;
        prefilter_fo)
            session_value="F,O"
            ;;
        prefilter_op)
            session_value="O,P"
            ;;
    esac

    for _ in $(seq 1 "${REPETITIONS}"); do
        IFS=, read -r elapsed_ms rows < <(run "${variant}" "${AUTO_SQL}" "${session_value}")
        elapsed_sum=$((elapsed_sum + elapsed_ms))
        rows_sum=$((rows_sum + rows))
    done

    avg_elapsed_ms="$(awk -v sum="${elapsed_sum}" -v n="${REPETITIONS}" 'BEGIN { printf "%.2f", sum / n }')"
    avg_rows="$(awk -v sum="${rows_sum}" -v n="${REPETITIONS}" 'BEGIN { printf "%.2f", sum / n }')"

    echo "${variant}, limit=${ROW_LIMIT}, avg=${avg_elapsed_ms} ms, rows=${avg_rows}"
    printf '%s,%s,%s,%s\n' "${variant}" "${ROW_LIMIT}" "${avg_elapsed_ms}" "${avg_rows}" >> "${RESULTS_CSV}"
done

echo "results: ${RESULTS_CSV}"
