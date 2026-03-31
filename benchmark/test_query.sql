SELECT count(*) AS match_count
FROM (
    SELECT *
    FROM (
        SELECT *
        FROM tpch.tiny.orders
        WHERE o_orderkey <= 3000
    ) t
    MATCH_RECOGNIZE (
        ORDER BY o_orderdate, o_orderkey
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
                 AND O.o_custkey BETWEEN F.o_custkey - 10000 AND F.o_custkey + 10000,
            P AS P.o_orderstatus = 'P'
                 AND P.o_totalprice BETWEEN F.o_totalprice - 20000 AND F.o_totalprice + 20000
                 AND P.o_custkey BETWEEN F.o_custkey - 10000 AND F.o_custkey + 10000
                 AND date_diff('day', F.o_orderdate, P.o_orderdate) <= 3650
    )
);
