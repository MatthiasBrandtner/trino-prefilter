WITH base AS (
    SELECT
        o.*,
        date_diff('day', DATE '1970-01-01', o.o_orderdate) AS t
    FROM tpch.tiny.orders o
    WHERE o.o_orderkey <= 3000
),
ranges AS (
    SELECT
        F.t AS t_s,
        O.t + 365 AS t_e
    FROM base AS F, base AS O
    WHERE F.t <= O.t
      AND F.o_orderstatus = 'F'
      AND O.o_orderstatus = 'O'
      AND O.o_totalprice BETWEEN F.o_totalprice - 20000 AND F.o_totalprice + 20000
      AND O.o_custkey BETWEEN F.o_custkey - 250 AND F.o_custkey + 250
),
manual_prefilter AS (
    SELECT DISTINCT b.*
    FROM base AS b, ranges AS r
    WHERE b.t BETWEEN r.t_s AND r.t_e
)
SELECT count(*) AS match_count
FROM (
    SELECT *
    FROM manual_prefilter
    MATCH_RECOGNIZE (
        ORDER BY t, o_orderkey
        MEASURES
            F.o_orderkey AS FID,
            O.o_orderkey AS OID,
            P.o_orderkey AS PID,
            count(Z.o_orderkey) AS GAP
        ONE ROW PER MATCH
        AFTER MATCH SKIP TO NEXT ROW
        PATTERN (F Z* O Z* P)
        DEFINE
            F AS F.o_orderstatus = 'F',
            O AS O.o_orderstatus = 'O'
                 AND O.o_totalprice BETWEEN F.o_totalprice - 20000 AND F.o_totalprice + 20000
                 AND O.o_custkey BETWEEN F.o_custkey - 250 AND F.o_custkey + 250,
            P AS P.o_orderstatus = 'P'
                 AND P.o_totalprice BETWEEN F.o_totalprice - 20000 AND F.o_totalprice + 20000
                 AND P.o_custkey BETWEEN F.o_custkey - 250 AND F.o_custkey + 250
                 AND P.t - F.t <= 365
    )
);
