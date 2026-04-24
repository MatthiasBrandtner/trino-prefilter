#!/usr/bin/env bash
set -euo pipefail

SERVER="http://localhost:8080"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CLI_JAR="${REPO_ROOT}/client/trino-cli/target/trino-cli-478-executable.jar"
PREFILTER_PROPERTY="enable_prefilter_rewrite"

rm -f /tmp/mr_prefilter_bench_*.out /tmp/mr_prefilter_graph_*_raw.out /tmp/mr_prefilter_plan_*.dot

run()
{
    local name="$1"
    local sql_file="$2"
    local session_value="$3"
    local sql_one_line
    sql_one_line="$(tr '\n' ' ' < "${sql_file}")"

    local svg_file="${SCRIPT_DIR}/mr_prefilter_plan_${name}.svg"
    local start_ms end_ms elapsed_ms
    local query_output rows
    local graph_output
    
    echo "${name}"

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
    echo "${name}: ${elapsed_ms} ms, rows=${rows}"

    graph_output="$(java -jar "${CLI_JAR}" \
        --server "${SERVER}" \
        --progress=false \
        --output-format CSV_HEADER_UNQUOTED \
        --execute "SET SESSION ${PREFILTER_PROPERTY} = '${session_value}'; EXPLAIN (TYPE LOGICAL, FORMAT GRAPHVIZ) ${sql_one_line}" \
        2>&1)"

    printf '%s\n' "${graph_output}" | awk '/^digraph /,0' | dot -Tsvg > "${svg_file}"
    echo "svg (${name}): ${svg_file}"
}

run prefilter_off "${SCRIPT_DIR}/test_query_benchmark1.sql" ""
run prefilter_fp "${SCRIPT_DIR}/test_query_benchmark1.sql" "F,P"
run prefilter_fo "${SCRIPT_DIR}/test_query_benchmark1.sql" "F,O"
run prefilter_op "${SCRIPT_DIR}/test_query_benchmark1.sql" "O,P"
run manual_prefilter_fp "${SCRIPT_DIR}/test_query_manual_prefilter_benchmark1_fp.sql" ""
run manual_prefilter_fo "${SCRIPT_DIR}/test_query_manual_prefilter_benchmark1_fo.sql" ""
run manual_prefilter_op "${SCRIPT_DIR}/test_query_manual_prefilter_benchmark1_op.sql" ""
