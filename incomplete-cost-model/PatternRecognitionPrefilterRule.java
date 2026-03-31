/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.prefilter;

import io.trino.cost.PlanNodeStatsEstimate;
import io.trino.cost.StatsProvider;
import io.trino.cost.SymbolStatsEstimate;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.Logical;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.SymbolsExtractor;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.PatternRecognitionNode;
import io.trino.sql.planner.rowpattern.ExpressionAndValuePointers;
import io.trino.sql.planner.rowpattern.ScalarValuePointer;
import io.trino.sql.planner.rowpattern.ValuePointer;
import io.trino.sql.planner.rowpattern.ir.IrConcatenation;
import io.trino.sql.planner.rowpattern.ir.IrLabel;
import io.trino.sql.planner.rowpattern.ir.IrRowPattern;
import io.trino.sql.prefilter.PrefilterCostEstimator.InputStats;

// import java.nio.file.DirectoryStream.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.trino.sql.planner.plan.Patterns.patternRecognition;

/**
* Sehr eingeschränkter General Case:
* KEIN PREV oder NEXT in DEFINE (Ausnahme: physische Navigation mit Offset 0 ist erlaubt)
* Subsequence A D (für Testzwecke gewählt)
* Keine OR in DEFINE-Ausdrücken
* Nur Bedingungen der Form "Symbol1 - Symbol2 <= constant" werden als Window Condition erkannt [keine Window Propagation]
 */

public class PatternRecognitionPrefilterRule
        implements Rule<PatternRecognitionNode>
{
    private static final Pattern<PatternRecognitionNode> PATTERN = patternRecognition();

    private final io.trino.cost.FilterStatsCalculator filterStatsCalculator;

    public PatternRecognitionPrefilterRule(io.trino.cost.FilterStatsCalculator filterStatsCalculator)
    {
        this.filterStatsCalculator = filterStatsCalculator;
    }

    @Override
    public Pattern<PatternRecognitionNode> getPattern()
    {
        return PATTERN;
    }

    private static final class LabeledCondition
    {
        private final Expression expression;
        private final Set<IrLabel> referencedLabels;

        private LabeledCondition(
                Expression expression,
                Set<IrLabel> referencedLabels)
        {
            this.expression = expression;
            this.referencedLabels = Set.copyOf(referencedLabels);
        }

        Expression getExpression()
        {
            return expression;
        }

        Set<IrLabel> getReferencedLabels()
        {
            return referencedLabels;
        }
    }

    @Override
    public Result apply(PatternRecognitionNode node, Captures captures, Context context)
    {
        // Wenn physische Navigation (PREV, NEXT) verwendet wird, Regel überspringen
        if (usesPhysicalNavigation(node.getVariableDefinitions())) {
            return Result.empty();
        }

        // Symbol Set Search Space
        IrRowPattern pattern = node.getPattern();
        // (1)
        IrLabel firstLabel = firstLabel(pattern);
        IrLabel lastLabel = lastLabel(pattern);

        List<IrLabel> labelSequence = extractLabelSequence(pattern);
        // window size
        Long w = -1L;

        // Primary ORDER BY Key extrahieren
        Symbol primaryOrderBy = node.getOrderingScheme()
                .map(scheme -> scheme.orderBy().get(0)).orElse(null);
        List<LabeledCondition> independentConditions = new ArrayList<>();
        List<LabeledCondition> dependentConditions = new ArrayList<>();

        // Loop über alle DEFINE-Ausdrücke
        for (Map.Entry<IrLabel, ExpressionAndValuePointers> entry : node.getVariableDefinitions().entrySet()) {
            IrLabel currentLabel = entry.getKey();
            ExpressionAndValuePointers eavp = entry.getValue();

            // Ausdruckszerlegung in Konjunktionen
            List<Expression> conjuncts = flattenAnd(eavp.getExpression());

            for (Expression conjunct : conjuncts) {
                // Verwendete Symbole im Ausdruck extrahieren
                Set<Symbol> usedSymbols = new HashSet<>(SymbolsExtractor.extractUnique(conjunct));

                // Labels extrahieren, die von den verwendeten Symbolen referenziert werden
                Set<IrLabel> referenced = new HashSet<>();

                referenced.add(currentLabel); // Eigenes Label immer hinzufügen
                for (ExpressionAndValuePointers.Assignment a : eavp.getAssignments()) {
                    if (usedSymbols.contains(a.symbol()) &&
                            a.valuePointer() instanceof ScalarValuePointer scalar) {
                        referenced.addAll(scalar.getLogicalIndexPointer().getLabels());
                    }
                }

                // Condition klassifizieren
                LabeledCondition labeledCondition = new LabeledCondition(conjunct, referenced);
                if (referenced.size() == 1) {
                    independentConditions.add(labeledCondition);
                }
                else {
                    dependentConditions.add(labeledCondition);
                       // ---- Pattern Window Check ----
                    if (primaryOrderBy == null || firstLabel == null || lastLabel == null) {
                        continue;
                    }
                    Call subtract = null;
                    Comparison.Operator operator = null;
                    if (conjunct instanceof Comparison cmp && cmp.operator() == Comparison.Operator.LESS_THAN_OR_EQUAL && cmp.left() instanceof Call s) {
                        subtract = s;
                    }
                    else if (conjunct instanceof Call lteCall && lteCall.function().name().getFunctionName().equals("$operator$less_than_equal") && lteCall.arguments().get(0) instanceof Call s) {
                        subtract = s;
                    }

                    if (subtract == null) {
                        continue;
                    }
                    Constant constant = null;
                    Expression rightSide = (conjunct instanceof Comparison cmp2) ? cmp2.right() : ((Call) conjunct).arguments().get(1);
                    if (rightSide instanceof Constant c) {
                        constant = c;
                    }
                    else if (rightSide instanceof Call cast && cast.function().name().getFunctionName().equals("$operator$cast") && cast.arguments().get(0) instanceof Constant c) {
                        constant = c;
                    }
                    if (constant == null) {
                        continue;
                    }

                    if (!(constant.value() instanceof Number)) {
                        continue;
                    }

                    long windowSize = ((Number) constant.value()).longValue();
                    if (windowSize < 0) {
                        continue;
                    }

                    if (!subtract.function().name().getFunctionName().equals("$operator$subtract")) {
                        continue;
                    }

                    Expression leftExpr = subtract.arguments().get(0);
                    Expression rightExpr = subtract.arguments().get(1);

                    Set<Symbol> leftSymbols = new HashSet<>(SymbolsExtractor.extractAll(leftExpr));
                    Set<Symbol> rightSymbols = new HashSet<>(SymbolsExtractor.extractAll(rightExpr));

                    if (leftSymbols.size() != 1 || rightSymbols.size() != 1) {
                        continue;
                    }

                    Symbol leftSymbol = leftSymbols.iterator().next();
                    Symbol rightSymbol = rightSymbols.iterator().next();

                    IrLabel leftLabel = null;
                    IrLabel rightLabel = null;

                    for (ExpressionAndValuePointers.Assignment a : eavp.getAssignments()) {
                        Symbol s = a.symbol();

                        boolean isLeft = s.equals(leftSymbol);
                        boolean isRight = s.equals(rightSymbol);

                        if (!isLeft && !isRight) {
                            continue;
                        }

                        if (!(a.valuePointer() instanceof ScalarValuePointer pointer)) {
                            leftLabel = null;
                            rightLabel = null;
                            break;
                        }

                        // muss auf PRIMARY order by key gehen
                        if (!pointer.getInputSymbol().equals(primaryOrderBy)) {
                            leftLabel = null;
                            rightLabel = null;
                            break;
                        }

                        Set<IrLabel> labels = pointer.getLogicalIndexPointer().getLabels();
                        IrLabel label = labels.iterator().next();

                        if (isLeft) {
                            leftLabel = label;
                        }
                        else {
                            rightLabel = label;
                        }
                    }

                    if (leftLabel == null || rightLabel == null) {
                        continue;
                    }

                    if (!leftLabel.equals(lastLabel) || !rightLabel.equals(firstLabel)) {
                        continue;
                    }
                    // set window size
                    w = Math.max(w, windowSize);
                }
            }
        }

        // (2) Symbol Set Search Space
        List<IrLabel> distinctLabels = new ArrayList<>();
        Set<IrLabel> seenLabels = new HashSet<>();
        for (IrLabel label : labelSequence) {
            if (seenLabels.add(label)) {
                distinctLabels.add(label);
            }
        }
        // Symbol Sets als geordnete Listen (für stabile Ausgabe); Duplikate via seenSets vermeiden
        List<List<IrLabel>> candidateSubsequences = new ArrayList<>();
        Set<Set<IrLabel>> seenSets = new HashSet<>();
        if (firstLabel != null && lastLabel != null) {
            if (w >= 0) {
                // Window Condition vorhanden → alle Teilmengen von x mit Größe 1–3
                allSubsets(distinctLabels, 2, 3, candidateSubsequences, seenSets);
            }
            else {
                // Kein Window → nur {firstLabel, lastLabel}
                List<IrLabel> base = new ArrayList<>();
                base.add(firstLabel);
                if (!firstLabel.equals(lastLabel)) {
                    base.add(lastLabel);
                }
                Set<IrLabel> baseSet = new HashSet<>(base);
                if (seenSets.add(baseSet)) {
                    candidateSubsequences.add(base);
                }
            }
        }
        // Für die nachgelagerte Condition-Prüfung: Label-Sets der Kandidaten
        List<Set<IrLabel>> candidateLabelSets = new ArrayList<>();
        for (List<IrLabel> subseq : candidateSubsequences) {
            candidateLabelSets.add(new HashSet<>(subseq));
        }
        // Fallback
        Set<IrLabel> subsequence = candidateLabelSets.isEmpty() ? Set.of(firstLabel, lastLabel) : candidateLabelSets.get(candidateLabelSets.size() - 1);
        System.out.println("=== Candidate Subsequences (Symbol Set Search Space) ===");
        for (List<IrLabel> subseq : candidateSubsequences) {
            System.out.println("  " + subseq);
        }
        // (3) Selektivitäten pro Label aus Independent Conditions schätzen
        Map<IrLabel, Double> labelSelectivities = new HashMap<>();
        PlanNodeStatsEstimate sourceStats = context.getStatsProvider().getStats(node.getSource());
        double totalRows = sourceStats.getOutputRowCount();
        if (primaryOrderBy != null && !Double.isNaN(totalRows) && totalRows > 0) {
            // Conditions pro Label gruppieren
            Map<IrLabel, List<Expression>> conditionsByLabel = new HashMap<>();
            for (LabeledCondition ic : independentConditions) {
                IrLabel label = ic.getReferencedLabels().iterator().next();
                conditionsByLabel
                        .computeIfAbsent(label, k -> new ArrayList<>())
                        .add(ic.getExpression());
            }
            for (Map.Entry<IrLabel, List<Expression>> entry : conditionsByLabel.entrySet()) {
                IrLabel label = entry.getKey();
                List<Expression> exprs = entry.getValue();
                // Mehrere Conditions zu einer AND-Expression zusammenfassen
                Expression combined = exprs.size() == 1 ? exprs.get(0) : new Logical(Logical.Operator.AND, exprs);
                // FilterStatsCalculator gibt gefilterte Stats zurück
                PlanNodeStatsEstimate filteredStats = filterStatsCalculator.filterStats(sourceStats, combined, context.getSession());
                double filteredRows = filteredStats.getOutputRowCount();
                // sel = gefilterte Zeilen / Gesamtzeilen; Fallback 1.0 bei NaN
                double sel = (!Double.isNaN(filteredRows) && filteredRows >= 0) ? Math.min(1.0, filteredRows / totalRows) : 1.0;
                labelSelectivities.put(label, sel);
            }
            // Labels ohne Independent Condition bekommen sel = 1.0 (unklar → konservativ)
            for (IrLabel label : distinctLabels) {
                labelSelectivities.putIfAbsent(label, 1.0);
            }
        }
        // (4) Selektivitäten der Dependent Conditions zwischen Label-Paaren
        Map<Set<IrLabel>, Double> dependentSelMap = new HashMap<>();
        for (LabeledCondition dc : dependentConditions) {
            double sel = estimateDependentSelectivity(dc.getExpression(), sourceStats);
            dependentSelMap.put(dc.getReferencedLabels(), sel);
        }
        // (5) InputStats aufbauen
        InputStats inputStats = (primaryOrderBy != null)
                ? buildInputStats(node, primaryOrderBy, labelSelectivities, context.getStatsProvider())
                : null;
        // (6) Kostenmodell: bestes Symbol Set wählen
        List<IrLabel> bestSymbolSet = null;
        if (inputStats != null && !candidateSubsequences.isEmpty() && w >= 0) {
            bestSymbolSet = PrefilterCostEstimator.chooseBestSymbolSet(
                    candidateSubsequences, w, dependentSelMap, inputStats);
        }
        System.out.println("=== Cost Model Result ===");
        if (bestSymbolSet == null) {
            System.out.println("  No rewrite beneficial — keeping original plan");
        }
        else {
            System.out.println("  Best symbol set: " + bestSymbolSet);
        }
        System.out.println("=== Independent Conditions ===");
        for (LabeledCondition c : independentConditions) {
            boolean inSubsequence = candidateLabelSets.stream()
                    .anyMatch(s -> s.containsAll(c.getReferencedLabels()));
            String tag = inSubsequence ? " [IN SUBSEQ]" : " [OUTSIDE SUBSEQ]";
            System.out.println("  " + c.getExpression()
                    + "   labels=" + c.getReferencedLabels()
                    + tag);
        }
        System.out.println("=== Dependent Conditions ===");
        for (LabeledCondition c : dependentConditions) {
            boolean inSubsequence = subsequence.containsAll(c.getReferencedLabels());
            String tag = inSubsequence ? " [IN SUBSEQ]" : " [OUTSIDE SUBSEQ]";
            System.out.println("  " + c.getExpression()
                    + "   labels=" + c.getReferencedLabels()
                    + tag);
        }

        System.out.println("Inferred pattern window size: " + (w >= 0 ? w : "undefined"));

        return Result.empty();
    }

    public static boolean usesPhysicalNavigation(
            Map<IrLabel, ExpressionAndValuePointers> variableDefinitions)
    {
        for (ExpressionAndValuePointers eavp : variableDefinitions.values()) {
            for (ExpressionAndValuePointers.Assignment assignment : eavp.getAssignments()) {
                ValuePointer pointer = assignment.valuePointer();

                if (pointer instanceof ScalarValuePointer scalar) {
                    if (scalar.getLogicalIndexPointer().getPhysicalOffset() != 0) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static List<Expression> flattenAnd(Expression expression)
    {
        if (expression instanceof Logical logical && logical.operator() == Logical.Operator.AND) {
            List<Expression> result = new ArrayList<>();
            for (Expression term : logical.terms()) {
                result.addAll(flattenAnd(term));
            }
            return result;
        }
        return List.of(expression);
    }

    static IrLabel firstLabel(IrRowPattern p)
    {
        if (p instanceof IrLabel l) {
            return l;
        }
        if (p instanceof IrConcatenation c) {
            return firstLabel(c.getPatterns().get(0));
        }
        return null;
    }

    static IrLabel lastLabel(IrRowPattern p)
    {
        if (p instanceof IrLabel l) {
            return l;
        }
        if (p instanceof IrConcatenation c) {
            List<IrRowPattern> ps = c.getPatterns();
            return lastLabel(ps.get(ps.size() - 1));
        }
        return null;
    }

    /**
     * Erzeugt alle Teilmengen von {@code labels} mit minSize <= |S| <= maxSize
     * und fügt sie (dedupliziert via seenSets) in {@code result} ein.
     * Die Reihenfolge innerhalb jeder Teilmenge folgt der Reihenfolge in {@code labels}.
     */
    private static void allSubsets(
            List<IrLabel> labels,
            int minSize,
            int maxSize,
            List<List<IrLabel>> result,
            Set<Set<IrLabel>> seenSets)
    {
        int n = labels.size();
        // Iteriere über alle 2^n Bitmasken
        for (int mask = 1; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) < minSize || Integer.bitCount(mask) > maxSize) {
                continue;
            }
            List<IrLabel> subset = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(labels.get(i));
                }
            }
            Set<IrLabel> subsetAsSet = new HashSet<>(subset);
            if (seenSets.add(subsetAsSet)) {
                result.add(subset);
            }
        }
    }

    /**
     * Nur IrLabel- und IrConcatenation-Knoten werden traversiert; andere
     * Knoten (z.B. Quantifier) werden ignoriert, da wir hier nur den
     * eingeschränkten General Case (keine Quantifier, keine Alternation) abdecken.
     */
    static List<IrLabel> extractLabelSequence(IrRowPattern p)
    {
        List<IrLabel> result = new ArrayList<>();
        collectLabels(p, result);
        return result;
    }

    private static void collectLabels(IrRowPattern p, List<IrLabel> result)
    {
        if (p instanceof IrLabel l) {
            result.add(l);
        }
        else if (p instanceof IrConcatenation c) {
            for (IrRowPattern child : c.getPatterns()) {
                collectLabels(child, result);
            }
        }
        // Andere Knoten (Quantifier, Alternation, …) werden im eingeschränkten
        // General Case nicht erwartet und stillschweigend ignoriert.
    }

    private static InputStats buildInputStats(PatternRecognitionNode node, Symbol primaryOrderBy, Map<IrLabel, Double> labelSelectivities, StatsProvider statsProvider)
    {
        PlanNodeStatsEstimate stats = statsProvider.getStats(node.getSource());
        double rowCount = stats.getOutputRowCount();
        Map<Symbol, SymbolStatsEstimate> symbolStats = stats.getSymbolStatistics();
        SymbolStatsEstimate orderKeyStats = symbolStats.getOrDefault(
                primaryOrderBy, SymbolStatsEstimate.unknown());
        double orderKeyMin = orderKeyStats.getLowValue();
        double orderKeyMax = orderKeyStats.getHighValue();
        // Falls keine Stats verfügbar (NaN): konservativer Fallback
        // range = rowCount ist pessimistisch: sel(window) = w/rowCount → klein
        if (Double.isNaN(orderKeyMin) || Double.isNaN(orderKeyMax)) {
            orderKeyMin = 0;
            orderKeyMax = rowCount;
        }
        return new InputStats(rowCount, orderKeyMin, orderKeyMax, labelSelectivities);
    }

    /**
     * Schätzt die Selektivität einer Dependent Condition (Join-Bedingung).
     *
     * Da Dependent Conditions Join-Conditions sind (sie referenzieren zwei Labels),
     * kann der FilterStatsCalculator sie nicht direkt auswerten.
     * Wir unterscheiden drei Muster:
     *
     * (a) BETWEEN mit erkennbarer konstanter Breite (z.B. "B.x BETWEEN A.x-d AND A.x+d"):
     *     sel ≈ (2·d) / (colMax - colMin)  — Anteil des Wertebereichs der Spalte
     *     der durch das BETWEEN-Fenster abgedeckt wird.
     *
     * (b) Window Condition (lastLabel.time - firstLabel.time <= w):
     *     Wird bereits in PrefilterCostEstimator.estimateJoinCardinality() berücksichtigt.
     *     Hier geben wir 1.0 zurück, damit keine Doppelzählung entsteht.
     *
     * (c) Sonstige Dependent Conditions:
     *     Empirischer Fallback 0.3 — konservativer Schätzwert für räumliche Bedingungen.
     *
     * @param expression  der Dependent-Condition-Ausdruck
     * @param sourceStats Stats der Quellrelation (für Spalten-Ranges)
     */
    private static double estimateDependentSelectivity(
            Expression expression,
            PlanNodeStatsEstimate sourceStats)
    {
        // Muster (b): Window Condition — Comparison mit Subtract auf linker Seite
        // Diese wird in estimateJoinCardinality bereits über sel(window) = w/range erfasst.
        // Rückgabe 1.0 verhindert Doppelzählung.
        if (expression instanceof Comparison cmp
                && cmp.operator() == Comparison.Operator.LESS_THAN_OR_EQUAL
                && cmp.left() instanceof Call call
                && call.function().name().getFunctionName().equals("$operator$subtract")) {
            return 1.0;
        }
        // Muster (a): BETWEEN — in Trino als zwei Comparisons nach Rewriting vorhanden,
        // oder als Call auf "$operator$between". Erkenne konstante Breite über
        // den rechten Operanden der Subtract-Calls.
        //
        // "B.x BETWEEN A.x - d AND A.x + d" erscheint nach Normalisierung als:
        //   A.x - d <= B.x  AND  B.x <= A.x + d
        // → zwei LabeledConditions mit je einem Comparison.
        // Wir schätzen hier nur den einzelnen Comparison-Fall.
        if (expression instanceof Comparison cmp) {
            Expression left = cmp.left();
            Expression right = cmp.right();
            // Prüfe ob eine Seite ein Subtract/Add mit Konstante ist
            Double rangeWidth = extractConstantOffsetWidth(left, right);
            if (rangeWidth != null && rangeWidth > 0) {
                // Spalten-Range aus Stats der referenzierten Symbole ermitteln
                Set<Symbol> symbols = new HashSet<>(SymbolsExtractor.extractAll(expression));
                double minColRange = Double.MAX_VALUE;
                for (Symbol sym : symbols) {
                    SymbolStatsEstimate symStats = sourceStats.getSymbolStatistics()
                            .getOrDefault(sym, SymbolStatsEstimate.unknown());
                    double colRange = symStats.getHighValue() - symStats.getLowValue();
                    if (!Double.isNaN(colRange) && colRange > 0) {
                        minColRange = Math.min(minColRange, colRange);
                    }
                }
                if (minColRange < Double.MAX_VALUE) {
                    // sel ≈ rangeWidth / colRange, max 1.0
                    return Math.min(1.0, rangeWidth / minColRange);
                }
            }
        }
        // Muster (c): Fallback für räumliche und sonstige Join-Conditions
        return 0.3;
    }

    /**
     * Versucht die Breite eines konstanten Offsets zu extrahieren.
     * Erkennt Muster wie: "expr - constant" oder "expr + constant"
     * und gibt die absolute Breite (2·constant für symmetrische BETWEEN) zurück.
     * Gibt null zurück wenn kein konstanter Offset erkennbar.
     */
    private static Double extractConstantOffsetWidth(Expression left, Expression right)
    {
        // Suche nach Call($operator$subtract, [_, Constant]) oder
        // Call($operator$add, [_, Constant])
        Double offset = extractConstantFromArithmetic(left);
        if (offset == null) {
            offset = extractConstantFromArithmetic(right);
        }
        // Für symmetrisches BETWEEN: Breite = 2 · offset
        return offset != null ? 2 * offset : null;
    }

    private static Double extractConstantFromArithmetic(Expression expr)
    {
        if (!(expr instanceof Call call)) {
            return null;
        }
        String fn = call.function().name().getFunctionName();
        if (!fn.equals("$operator$subtract") && !fn.equals("$operator$add")) {
            return null;
        }
        if (call.arguments().size() != 2) {
            return null;
        }
        Expression second = call.arguments().get(1);
        if (second instanceof Constant c && c.value() instanceof Number n) {
            return Math.abs(n.doubleValue());
        }
        return null;
    }
}
