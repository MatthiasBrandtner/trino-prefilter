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

import java.util.ArrayList;
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

        //Subsequence A D (gewählt für Testzwecke)
        Set<IrLabel> subsequence = Set.of(
                new IrLabel("A"),
                new IrLabel("D"));

        // Pattern extrahieren
        IrRowPattern pattern = node.getPattern();
        IrLabel firstLabel = firstLabel(pattern);
        IrLabel lastLabel = lastLabel(pattern);

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

                    if (!(conjunct instanceof Comparison cmp)) {
                        continue;
                    }
                    if (cmp.operator() != Comparison.Operator.LESS_THAN_OR_EQUAL) {
                        continue;
                    }

                    if (!(cmp.left() instanceof Call subtract)) {
                        continue;
                    }
                    if (!(cmp.right() instanceof Constant constant)) {
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

        System.out.println("=== Independent Conditions ===");
        for (LabeledCondition c : independentConditions) {

            boolean inSubsequence = subsequence.containsAll(c.getReferencedLabels());
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
}
