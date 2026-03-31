# SP Row Pattern Recognition in Data Processing Systems

In diesem Projekt findet sich eine Umsetzung des Prefilters aus dem Paper High-Performance Row Pattern Recognition Using Joins [1].
Dieser reduziert die Input-Tabelle vor Aufruf eines MATCH RECOGNIZE Operators, sodass der Input verringert wird, aber der Output gleichbleibt.

Die Umsetzung ist ein "proof of concept", da sie in vielerlei Hinsicht im Vergleich zu der im Paper eingeschränkt ist.
Es handelt sich um eine "eingeschränkte" Version des General Case. Das Pattern darf die Quantifier *, + und Alternation beinhalten, allerdings muss eine Subsequence übergeben werden welche für alle Pattern gültig sein muss (siehe Einschränkungen).
 
# Umsetzung
Zunächst wird die Eingabe auf Korrektheit geprüft (kein Vorkommen von PREV/NEXT).

Danach wird die Subsequence und das Pattern mit Hilfe des PatternNFA auf Gültigkeit kontrolliert (anhand der bei Einschränkungen genannten Punkte).

Danach werden Indepedent und Dependent conditions, sowie (falls vorhanden) die Pattern Window Condition extrahiert.

Der Prefilter wird danach (entsprechend Def. 3.7 aus [1]) in folgenden Schritten erzeugt:
* 1. Pro Subsequence Symbol wird eine Filternode als seperater Branch im Plantree erstellt, welches die Input Tabelle über independent conditions filtert.
* 2. Für das erste und letzte Symbol der Subsequence wird im jeweiligen Branch eine Projectnode erstellt, die den primary ORDER BY key zu t1 bzw. tk umbenennt 
* 3. Iterativer Join über alle Branches mit den dependent conditions entsprechender Symbole als Joinfilter
* 4. Anwendung der f(t1, tk) auf die PlanNode mittels ProjectionNode anhander der im Paper genannten Regeln
* 5. Finaler Join mit der Input Tabelle mit ts <= t <= te als Joinfilter
* 6. Deduplikation mittels AggregationNode

---

## Integration in Trino:
Das Github ist ein Trino-Fork bei dem 2 neue Klassen im Optimizer ergänzt wurden (eine für den Prefilter und eine für den PatternNFA):

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
     * Die Input-Tabelle muss Indexe haben 
     
   * Für die Query gelten folgende Einschränkungen:
     * Sie muss entweder eine Pattern-Window Condition beinhalten ODER die Subsequence muss das erste und letzte Pattern Symbol beinhalten
     * Die Pattern Window Condition muss die exakte Form Symbol1 - Symbol2 <= constant haben.
     * Sie darf keine Physical Navigation Function enthalten (PREV/NEXT)
     
   * Für die Subsequence gelten folgende Einschränkungen:
     * Sie muss mindestens 2 Zeichen beinhalten
     * Sie darf keine Duplikate beinhalten (aufgrund fehlerhafter Design-Entscheidung, die nicht zur Abgabe behoben werden konnte)
     * Sie muss in der angegeben Reihenfolge im Pattern vorkommen
     * Das Pattern *darf* Alternation oder die Quantifier * und + beinhalten, aber die Subsequence muss für jede Dekomposition des Pattern gültig sein

### Beispiele für gültige Pattern:
* Für das Pattern (R Z* B Z* M) wären 'R,M', 'B,M', 'R,B,M' alles gültige subsequences
* Für das Pattern (A Z* B Z* A) wären 'A,B' und 'B,A' gültig, allerdings 'A,B,A' und 'B' ungültig.
* Für das Pattern (A Z* B*) wäre 'A,B' ungültig, für das Pattern (A Z* B+) wäre 'A,B' gültig.

Im Falle eines ungültigen Patterns oder einer ungültigen Query wird der Prefilter nicht angewendet und die Query normal durchgeführt.

Ob der Input die Voraussetzungen erfüllt wird **nicht** kontrolliert. Dass der Input keine Duplikate enthält müsste über Verwendung von DISTINCT in der Eingabe sichergestellt werden.

# Benchmark:
Um die Gültigkeit des Prefilters anhand des Query Plans zu zeigen, werden 3 Queries auf tpch.tiny.orders miteinander verglichen.
Ohne Prefilter (angelehnt an Fig. 1 in [1]), mit Prefilter und mit einem manuellem Prefilter als SQL-Rewrite (angelehnt an Fig. 5 in [1]).

Alle 3 Querys nutzen EXPLAIN (TYPE LOGICAL, FORMAT GRAPHVIZ), um eine graphische Ausgabe des Query-Plans (als .svg) zu erstellen.

Damit dies funktioniert muss graphviz installiert werden.

	sudo apt install graphviz

Neben der Visualisierung werden ebenfalls die Laufzeiten und Anzahl der matchenden Rows für die 3 Durchläufe ausgegeben.

Die Benchmark findet sich um Ordner benchmark und kann mittels

	./benchmark.sh

ausgeführt werden (Trino-Server muss dafür gestartet sein und auf localhost:8080 laufen).

# Bucketized Prefilter:
Ein Entwurf des Bucketized Prefilters befindet sich in dem gleichnamigen Ordner, konnte aber zur Abgabe nicht rechtzeitig integriert werden, da er auf einem alten Fork basierte.

# Quelle:
[1] Zhu, Erkang, Silu Huang, and Surajit Chaudhuri. *"High-Performance Row Pattern Recognition Using Joins (Technical Report)."* 2022,
