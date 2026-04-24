# Prefilter-Kostenmodell

<p>In PrefilterCostEstimator.java wurde das einfache Kostenmodell für den Basic Join-Prefilter nach "Efficient Pattern Matching over Streams" (Microsoft Research, 2022) Section 4 — Cost Model implementiert.
<br>
Das Kostenmodell wurde leider nicht vollständig implementiert und getestet. </p>

# Berechnungen und Annahmen:

<p> Das Kostenmodell basiert auf folgende Berechnungen und Annahmen:
<br>
Kosten für einen Rewrite: <br> C(rewrite) = C(prefilter) + C(MATCH_RECOGNIZE) <br>
Kosten für den Prefilter: <br> C(prefilter) = C(bucket_generation) + C(filtering) <br>
Kosten für MATCH_RECOGNIZE: <br> C(MATCH_RECOGNIZE) = ω * |prefilterOutput| <br>
Skalenkalibrierungsparameter ω nach 4.3: <br> ω = 5 in Trino <br>
prefilterOutput: Anzahl der Zeilen, die nach dem Filter exisiteren und in MATCH_RECOGNIZE verwendet werden: <br> |T| · min(1, |join| / β), wobei β = (orderKeyMax - orderKeyMin) / w  (theoretische Bucket-Anzahl) <br>
Kosten für Bucket Generation (pressimistische Annahme für den Basic Join-Prefilter): <br> C(bucket_generation) = C(scan) + C(join) + C(bucket) <br>
C(scan) = Σ_{s ∈ X} CPU · |T| · sel(C_s) (Kosten für unabhängige Filterung pro Label) <br>
C(join) = CPU · |T|^k · Π sel(C_s) · sel(window) · Π sel(dependent) (Kosten für Self-Join der gefilterten Relationen) <br>
C(bucket) = CPU · |T| () (Kosten für Bucket-Identifikation) <br>
Kosten für Filtern von Zeilen: <br> C(filtering) = CPU · |prefilterOutput| <br>
Originale Kosten: <br> C(original) = ω * |T| (MATCH_RECOGNIZE auf gesamte Tabelle) </p>

# Funktionsweise

<p> 
Schritt 1 - Eingabegrößen werden vom StatsProvider geholt <br>
Schritt 2 - Für jedes Label werden die Independent Conditions an FilterStatsCalculator übergeben <br>
Schritt 3 - Für jede Subsequenz wird C(rewrite) berechnet <br>
  Dazu werden Scan-Kosten, Join-Kardinalität, Bucket-Identifikation, Prefilter-Output, C(filtering), C(prefilter) und C(MATCH_RECOGNIZE) berechnet <br>
Schritt 4 - C(original) wird berechnet <br>
Schritt 5 - Beide Kosten werden verglichen und die günstigste Ausführung wird benutzt (Theorie)
</p>

# Probleme

<ol>
  <li> Testen mit Trino-Dev-Server nicht möglich, aufgrund von fehlendem ANALYZE zur Schätzung von Selektivitäten: Schätzt daher immer negativ</li>
  <li> Einbindung in aktuellste Version nicht vorhanden</li>
  <li> Kostenmodell bisher nur für speziellen Fall, also nur Konkatenationen möglich</li>
</ol>
