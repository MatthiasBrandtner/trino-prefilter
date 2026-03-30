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

package io.trino.sql.planner.optimizations;

import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.Logical;
import io.trino.sql.planner.ExpressionSymbolInliner;
import io.trino.sql.planner.NodeAndMappings;
import io.trino.sql.planner.PlanCopier;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.SymbolsExtractor;
import io.trino.sql.planner.optimizations.MatchRecognizePatternNfaAnalyzer.PatternNFA;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.JoinType;
import io.trino.sql.planner.plan.PatternRecognitionNode;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.SimplePlanRewriter;
import io.trino.sql.planner.rowpattern.ExpressionAndValuePointers;
import io.trino.sql.planner.rowpattern.ScalarValuePointer;
import io.trino.sql.planner.rowpattern.ValuePointer;
import io.trino.sql.planner.rowpattern.ir.IrLabel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.trino.SystemSessionProperties.getEnablePrefilterRewrite;
import static io.trino.spi.function.OperatorType.ADD;
import static io.trino.spi.function.OperatorType.SUBTRACT;
import static io.trino.sql.ir.IrUtils.combineConjuncts;
import static io.trino.sql.ir.IrUtils.extractConjuncts;
import static io.trino.sql.planner.optimizations.MatchRecognizePatternNfaAnalyzer.analyzePattern;
import static io.trino.sql.planner.plan.ChildReplacer.replaceChildren;
import static java.util.Objects.requireNonNull;

public class MatchRecognizePrefilterOptimizer
        implements PlanOptimizer
{
    private final Metadata metadata;

    public MatchRecognizePrefilterOptimizer(PlannerContext plannerContext)
    {
        this.metadata = requireNonNull(plannerContext, "plannerContext is null").getMetadata();
    }

    @Override
    public PlanNode optimize(PlanNode plan, Context context)
    {
        // prefilter is opt-in via session property and needs a valid configured subsequence
        String status = getEnablePrefilterRewrite(context.session());
        if (status == null) {
            return plan;
        }

        List<IrLabel> subsequence = new ArrayList<>();
        for (String raw : status.split(",")) {
            String label = raw.trim();
            subsequence.add(new IrLabel(label));
        }

        // TODO: label.size == 1
        if (subsequence.size() < 2) {
            return plan;
        }

        // duplicates aren't allowed in the subsequence
        Set<IrLabel> set = new HashSet<>(subsequence);
        if (set.size() < subsequence.size()) {
            return plan;
        }

        // run the plan rewriter with the configured subsequence.
        return SimplePlanRewriter.rewriteWith(new Rewriter(context, metadata, subsequence), plan);
    }

    // one DEFINE conjunct together with information needed for rewrite.
    static record LabeledCondition(
            Expression expression,
            Set<IrLabel> referencedLabels,
            IrLabel baseLabel,
            Map<Symbol, ScalarValuePointer> pointers) {}

    // result of splitting DEFINE conditions into independent and dependent parts.
    record ExtractedConditions(
            Map<IrLabel, List<LabeledCondition>> independent,
            List<LabeledCondition> dependent) {}

    // pattern boundary window, e.g. (last.orderKey - first.orderKey <= w).
    record PatternWindow(long windowSize, ResolvedFunction subtractFunction, Constant windowSizeExpression) {}

    private static final class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private final Context context;
        private final Metadata metadata;
        private final List<IrLabel> subsequence;

        private Rewriter(Context context, Metadata metadata, List<IrLabel> subsequence)
        {
            this.context = context;
            this.metadata = metadata;
            this.subsequence = subsequence;
        }

        @Override
        public PlanNode visitPatternRecognition(PatternRecognitionNode node, RewriteContext<Void> rewriteContext)
        {
            // Check for physical navigation (PREV/NEXT)
            if (usesPhysicalNavigation(node.getVariableDefinitions())) {
                return node;
            }

            // build PatternNFA as described in the paper and validate subsequence order
            PatternNFA patternNFA = analyzePattern(node.getPattern());

            // get a list of all label paths from START to FINISH in the pattern NFA
            List<List<IrLabel>> specialPatterns = patternNFA.specialPatterns();

            // the subsequence needs to be valid and in correct order for all special patterns
            for (List<IrLabel> specialPattern : specialPatterns) {
                int i = 0;
                int j = 0;
                while (i < subsequence.size() && j < specialPattern.size()) {
                    if (subsequence.get(i).equals(specialPattern.get(j))) {
                        i++;
                    }
                    j++;
                }
                if (i != subsequence.size()) {
                    return node;
                }
            }

            // depth first traversal of pattern NFA gives us the order of labels in the pattern
            Set<IrLabel> firstLabels = new HashSet<>();
            Set<IrLabel> lastLabels = new HashSet<>();
            for (List<IrLabel> specialPattern : specialPatterns) {
                if (specialPattern.isEmpty()) {
                    continue;
                }
                firstLabels.add(specialPattern.get(0));
                lastLabels.add(specialPattern.get(specialPattern.size() - 1));
            }
            IrLabel firstPatternLabel = null;
            IrLabel lastPatternLabel = null;
            if (firstLabels.size() == 1) {
                firstPatternLabel = firstLabels.iterator().next();
            }
            if (lastLabels.size() == 1) {
                lastPatternLabel = lastLabels.iterator().next();
            }

            // t is the primary ORDER BY key
            Symbol t = node.getOrderingScheme()
                    .map(ordering -> ordering.orderBy().get(0))
                    .orElse(null);
            if (t == null) {
                return node;
            }

            // input relation (T in Def. 3.7)
            PlanNode inputRelation = node.getSource();

            // independent conditions are keyed by base label (which indicates branch)
            Map<IrLabel, List<LabeledCondition>> independent = new HashMap<>();
            List<LabeledCondition> dependent = new ArrayList<>();

            /* ExpressionAndValuePointers is how Trino stores expressions of the DEFINE clause
             * The expression at this point is an intermediate representation with symbols replacing the labeled column references
             * The expressions are linked to an "assignment" which links the symbols in the expression to value pointers
             * The (scalar) value pointer contains the information which label was originally used in the expression
             * (and information on physical/logical navigation, which was used to filter PREV/NEXT)
             */
            for (Map.Entry<IrLabel, ExpressionAndValuePointers> entry : node.getVariableDefinitions().entrySet()) {
                // base label of this DEFINE clause, e.g. the A in "DEFINE A AS ...".
                IrLabel baseLabel = entry.getKey();

                ExpressionAndValuePointers definition = entry.getValue();

                // Map symbols to scalar value pointers for lookup
                Map<Symbol, ScalarValuePointer> scalarPointers = new HashMap<>();
                for (ExpressionAndValuePointers.Assignment assignment : definition.getAssignments()) {
                    if (assignment.valuePointer() instanceof ScalarValuePointer scp) {
                        scalarPointers.put(assignment.symbol(), scp);
                    }
                }

                // move through every conjunct (separated by AND) in the DEFINE expression and classify as independent or dependent
                for (Expression conjunct : extractConjuncts(definition.getExpression())) {
                    // all symbols referenced by this conjunct.
                    Set<Symbol> usedSymbols = new HashSet<>(SymbolsExtractor.extractUnique(conjunct));
                    Set<IrLabel> referencedLabels = new HashSet<>();

                    // base label is always considered referenced, as construction like "DEFINE A AS B.value > 5" shouldn't be classified as independent
                    referencedLabels.add(baseLabel);
                    Map<Symbol, ScalarValuePointer> usedPointers = new HashMap<>();

                    // resolve each symbol to its pointer and collect referenced labels.
                    for (Symbol symbol : usedSymbols) {
                        ScalarValuePointer scp = scalarPointers.get(symbol);
                        usedPointers.put(symbol, scp);
                        referencedLabels.addAll(scp.getLogicalIndexPointer().getLabels());
                    }

                    LabeledCondition lc = new LabeledCondition(conjunct, referencedLabels, baseLabel, usedPointers);

                    if (lc.referencedLabels().size() == 1) {
                        independent.putIfAbsent(baseLabel, new ArrayList<>());
                        independent.get(baseLabel).add(lc);
                    }
                    else {
                        dependent.add(lc);
                    }
                }
            }
            ExtractedConditions conditions = new ExtractedConditions(independent, dependent);
            PatternWindow patternWindow = null;

            for (LabeledCondition condition : conditions.dependent()) {
                // expected shape: (x.orderKey - y.orderKey) <= constant.
                if (condition.expression() instanceof Comparison comparison &&
                        comparison.operator() == Comparison.Operator.LESS_THAN_OR_EQUAL &&
                        comparison.left() instanceof Call subtract &&
                        subtract.function().name().getFunctionName().equals("$operator$subtract") &&
                        comparison.right() instanceof Constant w &&
                        w.value() instanceof Number wval &&
                        wval.longValue() > 0) {
                    Symbol left = Symbol.from(subtract.arguments().get(0));
                    Symbol right = Symbol.from(subtract.arguments().get(1));
                    ScalarValuePointer lp = condition.pointers().get(left);
                    ScalarValuePointer rp = condition.pointers().get(right);
                    if (lp.getInputSymbol().equals(t) && rp.getInputSymbol().equals(t)) {
                        if (patternWindow == null || wval.longValue() > patternWindow.windowSize()) {
                            patternWindow = new PatternWindow(wval.longValue(), subtract.function(), w);
                        }
                    }
                }
            }

            // if there is no pattern window condition or no consistent pattern boundaries, we can't derive range conditions
            IrLabel firstSubsequenceLabel = subsequence.get(0);
            IrLabel lastSubsequenceLabel = subsequence.get(subsequence.size() - 1);

            if (patternWindow == null && (!(firstSubsequenceLabel.equals(firstPatternLabel) && lastSubsequenceLabel.equals(lastPatternLabel)))) {
                return node;
            }

            /*
             * Step 1: FilterNodes for independent conditions:
             * for each label in the subsequence build a separate branch which will filter the input relation T on independent conditions for that label
             * these branches will later be joined
             * copying the source for each branch was necessary to avoid conflicting node IDs or symbols
             * The generated PlanNode is stored in a map by label - in hindsight this should have been indexed by position to allow duplicate labels in the subsequence
             */
            Map<IrLabel, PlanNode> branchNodes = new HashMap<>();
            // keep track of which input relation symbols belong to which branch symbols (= columns of the table)
            Map<IrLabel, Map<Symbol, Symbol>> labelMappings = new HashMap<>();
            for (IrLabel label : subsequence) {
                // copy source so every label branch has isolated node ids and symbols.
                NodeAndMappings copied = PlanCopier.copyPlan(
                        inputRelation,
                        inputRelation.getOutputSymbols(),
                        context.symbolAllocator(),
                        context.idAllocator());
                PlanNode copiedInput = copied.getNode();
                Map<Symbol, Symbol> branchmap = new HashMap<>();
                for (int i = 0; i < inputRelation.getOutputSymbols().size(); i++) {
                    branchmap.put(inputRelation.getOutputSymbols().get(i), copied.getFields().get(i));
                }

                PlanNode filtered;
                if (independent.get(label) == null) {
                    filtered = copiedInput;
                }
                else {
                    List<Expression> predicates = new ArrayList<>();
                    // replace symbols in independent conditions with symbols of current branch.
                    for (LabeledCondition condition : independent.get(label)) {
                        Map<Symbol, Expression> replacements = new HashMap<>();
                        for (Map.Entry<Symbol, ScalarValuePointer> entry : condition.pointers().entrySet()) {
                            ScalarValuePointer scp = entry.getValue(); // get pointer to input relation column
                            Symbol branchSymbol = branchmap.get(scp.getInputSymbol()); // get corresponding symbol from branch
                            replacements.put(entry.getKey(), branchSymbol.toSymbolReference());
                        }
                        // inlineSymbols replaces symbol references in an expression
                        predicates.add(ExpressionSymbolInliner.inlineSymbols(replacements, condition.expression()));
                    }
                    filtered = new FilterNode(context.idAllocator().getNextId(), copiedInput, combineConjuncts(predicates));
                }
                branchNodes.put(label, filtered);
                labelMappings.put(label, branchmap);
            }

            /*
             * Step 2: ProjectNodes to rename columns of first and last label branches to t1 and tk
             */
            Symbol t1 = new Symbol(t.type(), "t1");
            Symbol tk = new Symbol(t.type(), "tk");

            Symbol firstT = labelMappings.get(firstSubsequenceLabel).get(t);
            Symbol lastT = labelMappings.get(lastSubsequenceLabel).get(t);

            Assignments.Builder first = Assignments.builder();
            for (Symbol col : branchNodes.get(firstSubsequenceLabel).getOutputSymbols()) {
                if (!col.equals(firstT)) {
                    first.put(col, col.toSymbolReference());
                }
                else {
                    first.put(t1, firstT.toSymbolReference());
                }
            }

            Assignments.Builder last = Assignments.builder();
            for (Symbol col : branchNodes.get(lastSubsequenceLabel).getOutputSymbols()) {
                if (!col.equals(lastT)) {
                    last.put(col, col.toSymbolReference());
                }
                else {
                    last.put(tk, lastT.toSymbolReference());
                }
            }

            branchNodes.put(firstSubsequenceLabel, new ProjectNode(
                    context.idAllocator().getNextId(),
                    branchNodes.get(firstSubsequenceLabel),
                    first.build()));

            branchNodes.put(lastSubsequenceLabel, new ProjectNode(
                    context.idAllocator().getNextId(),
                    branchNodes.get(lastSubsequenceLabel),
                    last.build()));

            /*
             * Step 3: Join label branches with dependent conditions as join filters:
             */
            Map<IrLabel, Symbol> projectMap = Map.of(firstSubsequenceLabel, t1, lastSubsequenceLabel, tk);
            PlanNode joinChain = branchNodes.get(firstSubsequenceLabel);

            for (int position = 1; position < subsequence.size(); position++) {
                IrLabel currentLabel = subsequence.get(position);
                // join current branch into the join chain
                PlanNode right = branchNodes.get(currentLabel);

                Set<IrLabel> prefix = new HashSet<>(subsequence.subList(0, position + 1));

                // collect all dependent conditions that can be applied at this point
                List<Expression> predicates = new ArrayList<>();
                for (LabeledCondition condition : dependent) {
                    // condition is active once all referenced labels are in the join prefix
                    if (!prefix.containsAll(condition.referencedLabels()) ||
                            !condition.referencedLabels().contains(currentLabel) ||
                            !labelMappings.keySet().containsAll(condition.referencedLabels())) {
                        continue;
                    }

                    Map<Symbol, Expression> replacements = new HashMap<>();

                    // rewrite dependent condition to branch symbols (including t1/tk aliases when applicable)
                    for (Map.Entry<Symbol, ScalarValuePointer> entry : condition.pointers().entrySet()) {
                        ScalarValuePointer svp = entry.getValue();
                        Set<IrLabel> labels = svp.getLogicalIndexPointer().getLabels();
                        IrLabel targetLabel = labels.iterator().next();

                        Map<Symbol, Symbol> targetMap = labelMappings.get(targetLabel);

                        Symbol rewritten;
                        // t1/tk alias for first/last label order-key references.
                        if (svp.getInputSymbol().equals(t) && projectMap.containsKey(targetLabel)) {
                            rewritten = projectMap.get(targetLabel);
                        }
                        else {
                            rewritten = targetMap.get(svp.getInputSymbol());
                        }

                        replacements.put(entry.getKey(), rewritten.toSymbolReference());
                    }
                    predicates.add(ExpressionSymbolInliner.inlineSymbols(replacements, condition.expression()));
                }
                Expression joinFilter = predicates.isEmpty() ? null : combineConjuncts(predicates);

                joinChain = new JoinNode(
                        context.idAllocator().getNextId(),
                        JoinType.INNER,
                        joinChain,
                        right,
                        List.of(),
                        joinChain.getOutputSymbols(),
                        right.getOutputSymbols(),
                        false,
                        Optional.ofNullable(joinFilter),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        Optional.empty());
            }

            /*
             * Step 4: Apply f(t1, tk) ProjectNode
             */
            Expression tsExpr;
            Expression teExpr;

            // whole-pattern case: direct [t1, tk] when boundaries are known and subsequence covers them.
            if (firstPatternLabel != null && lastPatternLabel != null &&
                    firstSubsequenceLabel.equals(firstPatternLabel) &&
                    lastSubsequenceLabel.equals(lastPatternLabel)) {
                tsExpr = t1.toSymbolReference();
                teExpr = tk.toSymbolReference();
            }

            if (firstPatternLabel != null && firstSubsequenceLabel.equals(firstPatternLabel)) {
                tsExpr = t1.toSymbolReference();
            }
            else {
                // ts = t1 - w
                ResolvedFunction subtractFunction = metadata.resolveOperator(SUBTRACT, List.of(tk.type(), patternWindow.windowSizeExpression().type()));
                tsExpr = new Call(subtractFunction, List.of(t1.toSymbolReference(), patternWindow.windowSizeExpression()));
            }

            if (lastPatternLabel != null && lastSubsequenceLabel.equals(lastPatternLabel)) {
                teExpr = tk.toSymbolReference();
            }
            else {
                // te = t1 + w
                ResolvedFunction addFunction = metadata.resolveOperator(ADD, List.of(t1.type(), patternWindow.windowSizeExpression().type()));
                teExpr = new Call(addFunction, List.of(tk.toSymbolReference(), patternWindow.windowSizeExpression()));
            }

            Symbol ts = new Symbol(t.type(), "ts");
            Symbol te = new Symbol(t.type(), "te");

            Assignments range = Assignments.builder().put(ts, tsExpr).put(te, teExpr).build();

            PlanNode ranges = new ProjectNode(
                    context.idAllocator().getNextId(),
                    joinChain,
                    range);

            /*
             * Step 5: Join prefilter with input relation T on ts <= t <= te
             */
            Expression filter = Logical.and(
                    new Comparison(
                            Comparison.Operator.LESS_THAN_OR_EQUAL,
                            ts.toSymbolReference(),
                            t.toSymbolReference()),
                    new Comparison(
                            Comparison.Operator.LESS_THAN_OR_EQUAL,
                            t.toSymbolReference(),
                            te.toSymbolReference()));

            PlanNode rangeJoin = new JoinNode(
                    context.idAllocator().getNextId(),
                    JoinType.INNER,
                    inputRelation,
                    ranges,
                    List.of(),
                    inputRelation.getOutputSymbols(),
                    ranges.getOutputSymbols(),
                    false,
                    Optional.ofNullable(filter),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of(),
                    Optional.empty());

            /*
             * Step 6: Deduplicate input rows
             */
            PlanNode deduplicate = new AggregationNode(
                    context.idAllocator().getNextId(),
                    rangeJoin,
                    Map.of(),
                    AggregationNode.singleGroupingSet(inputRelation.getOutputSymbols()),
                    List.of(),
                    AggregationNode.Step.SINGLE,
                    Optional.empty());

            return replaceChildren(node, List.of(deduplicate));
        }
    }

    // loops through DEFINE expressions to check if any of them uses physical navigation which are introduced through PREV/NEXT (The offset 0 would be permitted as it has no effect).
    private static boolean usesPhysicalNavigation(Map<IrLabel, ExpressionAndValuePointers> variableDefinitions)
    {
        for (ExpressionAndValuePointers definition : variableDefinitions.values()) {
            for (ExpressionAndValuePointers.Assignment assignment : definition.getAssignments()) {
                ValuePointer valuePointer = assignment.valuePointer();
                if (valuePointer instanceof ScalarValuePointer svp &&
                        svp.getLogicalIndexPointer().getPhysicalOffset() != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
