# SP Row Pattern Recognition in Data Processing Systems

In diesem Projekt findet sich eine Umsetzung des Prefilters aus dem Paper High-Performance Row Pattern Recognition Using Joins [1].
Dieser reduziert die Input-Tabelle vor Aufruf eines MATCH RECOGNIZE operators mittels Joins.

Die Umsetzung ist ein "proof of concept", da sie in vielerlei Hinsicht im Vergleich zu der im Paper eingeschränkt ist (siehe unten).

---

## Integration in Trino:
Es wurden 2 neue Klassen im Optimizer ergänzt (eine für den Prefilter und eine für den PatternNFA):

    core/trino-main/src/main/java/io/trino/sql/planner/optimizations/MatchRecognizePrefilterOptimizer.java
    core/trino-main/src/main/java/io/trino/sql/planner/optimizations/MatchRecognizePatternNfaAnalyzer.java

Es wurde weiterhin in 2 Klassen kleine Änderungen vorgenommen:

	core/trino-main/src/main/java/io/trino/SystemSessionProperties.java
	core/trino-main/src/main/java/io/trino/sql/planner/PlanOptimizers.java


## Ausführung:
Der Prefilter kann mit folgender Session-Property aktiviert werden:

     SET SESSION enable_prefilter_rewrite = 'A,D';

Wobei 'A,D' mit einer beliebigen Subsequence des Patterns ersetzt werden kann (unter Beachtung der Einschränkungen).

## Einschränkungen:
   * Für den Input gelten folgende Einschränkungen:
     * Die Input-Tabelle darf keine Duplikate enthalten (da diese bei der finalen Deduplikation entfernt werden würden)
   
   * Für die Query gelten folgende Einschränkungen:
     * Sie muss entweder eine Pattern-Window Condition beinhalten ODER die Subsequence muss das erste und letzte Pattern Symbol beinhalten
     * Sie darf keine Physical Navigation Function enthalten (PREV/NEXT)
     
   * Für die Subsequence gelten folgende Einschränkungen:
     * Sie muss mindestens 2 Zeichen beinhalten
     * Sie darf keine Duplikate beinhalten
     * Sie muss in der angegeben Reihenfolge im Pattern vorkommen
     * Das Pattern *darf* Alternation oder die Quantifier * und + beinhalten, aber die Subsequence muss für jede Dekomposition des Pattern gültig sein

### Beispiele:
* Für das Pattern (R Z* B Z* M) wären 'R,M', 'B,M', 'R,B,M' alles gültige subsequences
* Für das Pattern (A Z* B Z* A) wären 'A,B' und 'B,A' gültig, allerdings 'A,B,A' und 'B' ungültig.

Im Falle eines ungültigen Patterns oder einer ungültigen Query wird der Prefilter nicht angewendet.
