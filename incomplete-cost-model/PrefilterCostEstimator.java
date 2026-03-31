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
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.trino.sql.prefilter;
import io.trino.cost.PlanNodeStatsEstimate;
import io.trino.cost.StatsProvider;
import io.trino.cost.SymbolStatsEstimate;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.rowpattern.ir.IrLabel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kostenmodell für den Basic Join Prefilter nach:
 *   "Efficient Pattern Matching over Streams" (Microsoft Research, 2022)
 *   Section 4 — Cost Model
 *
 * Gesamtformel:
 *   C(rewrite, X) = C(prefilter, X) + ω · C(MATCH_RECOGNIZE, prefilterOutput)
 *   C(original)   = ω · |T|
 *
 * Nur wenn C(rewrite, X) < C(original) für mindestens ein Symbol Set X
 * wird der Rewrite durchgeführt.
 */
public final class PrefilterCostEstimator
{
    private PrefilterCostEstimator() {}

    private static final double OMEGA = 5.0;
    private static final double CPU_COST_PER_ROW = 0.25;

    // key-information
    public static final class InputStats
    {
        final double rowCount;
        final double orderKeyMin;
        final double orderKeyMax;
        final Map<IrLabel, Double> labelSelectivity;

        public InputStats(double rowCount, double orderKeyMin, double orderKeyMax, Map<IrLabel, Double> labelSelectivity)
        {
            this.rowCount = rowCount;
            this.orderKeyMin = orderKeyMin;
            this.orderKeyMax = orderKeyMax;
            this.labelSelectivity = labelSelectivity;
        }
    }

    public static InputStats estimateInputStats(PlanNode sourceNode, Symbol primaryOrderBy, Map<IrLabel, Double> labelSelectivities, StatsProvider statsProvider)
    {
        PlanNodeStatsEstimate stats = statsProvider.getStats(sourceNode);
        double rowCount = stats.getOutputRowCount();
        SymbolStatsEstimate orderKeyStats = stats.getSymbolStatistics(primaryOrderBy);
        double orderKeyMin = orderKeyStats.getLowValue();
        double orderKeyMax = orderKeyStats.getHighValue();

        return new InputStats(rowCount, orderKeyMin, orderKeyMax, labelSelectivities);
    }

    public static double estimateScanCost(List<IrLabel> symbolSet, InputStats inputStats)
    {
        double cost = 0.0;
        for (IrLabel label : symbolSet) {
            double sel = inputStats.labelSelectivity.getOrDefault(label, 1.0);
            cost += CPU_COST_PER_ROW * inputStats.rowCount * sel;
        }
        return cost;
    }

    public static double estimateJoinCardinality(List<IrLabel> symbolSet, long w, Map<Set<IrLabel>, Double> dependentSelMap, InputStats inputStats)
    {
        int k = symbolSet.size();

        if (k == 1) {
            double sel = inputStats.labelSelectivity.getOrDefault(symbolSet.get(0), 1.0);
            return inputStats.rowCount * sel;
        }
        double base = Math.pow(inputStats.rowCount, k);
        for (IrLabel label : symbolSet) {
            base *= inputStats.labelSelectivity.getOrDefault(label, 1.0);
        }
        double range = inputStats.orderKeyMax - inputStats.orderKeyMin;
        double windowSel = (range > 0 && w >= 0) ? Math.min(1.0, (double) w / range) : 1.0;
        base *= windowSel;
        for (int i = 0; i < k - 1; i++) {
            for (int j = i + 1; j < k; j++) {
                Set<IrLabel> pair = Set.of(symbolSet.get(i), symbolSet.get(j));
                Double depSel = dependentSelMap.get(pair);
                if (depSel != null) {
                    base *= depSel;
                }
            }
        }
        return base;
    }

    public static double estimateJoinCost(double joinCardinality)
    {
        return CPU_COST_PER_ROW * joinCardinality;
    }

    public static double estimateBucketCount(long w, InputStats inputStats)
    {
        if (w <= 0) {
            return Double.NaN;
        }
        double range = inputStats.orderKeyMax - inputStats.orderKeyMin;
        return range / w;
    }

    public static double estimateBucketIdentificationCost(InputStats inputStats)
    {
        return CPU_COST_PER_ROW * inputStats.rowCount;
    }

    public static double estimatePrefilterOutputSize(double joinCardinality, double bucketCount, InputStats inputStats)
    {
        if (Double.isNaN(bucketCount) || bucketCount <= 0) {
            return inputStats.rowCount;
        }
        double fractionCovered = Math.min(1.0, joinCardinality / bucketCount);
        return inputStats.rowCount * fractionCovered;
    }

    public static PrefilterCostResult estimatePrefilterCost(List<IrLabel> symbolSet, long w, Map<Set<IrLabel>, Double> dependentSelMap, InputStats inputStats)
    {
        double cScan = estimateScanCost(symbolSet, inputStats);
        double joinCard = estimateJoinCardinality(symbolSet, w, dependentSelMap, inputStats);
        double cJoin = estimateJoinCost(joinCard);
        double beta = estimateBucketCount(w, inputStats);
        double cBucketId = estimateBucketIdentificationCost(inputStats);
        double prefilterOutput = estimatePrefilterOutputSize(joinCard, beta, inputStats);
        double cFiltering = CPU_COST_PER_ROW * prefilterOutput;
        double totalPrefilterCost = cScan + cJoin + cBucketId + cFiltering;

        return new PrefilterCostResult(totalPrefilterCost, prefilterOutput);
    }

    public static double estimateRewriteCost(List<IrLabel> symbolSet, long w, Map<Set<IrLabel>, Double> dependentSelMap, InputStats inputStats)
    {
        PrefilterCostResult prefilter = estimatePrefilterCost(symbolSet, w, dependentSelMap, inputStats);
        double cMatchRecognize = prefilter.prefilterOutputSize;
        return prefilter.prefilterCost + OMEGA * cMatchRecognize;
    }

    public static double estimateOriginalCost(InputStats inputStats)
    {
        return OMEGA * inputStats.rowCount;
    }

    public static List<IrLabel> chooseBestSymbolSet(List<List<IrLabel>> candidateSymbolSets, long w, Map<Set<IrLabel>, Double> dependentSelMap, InputStats inputStats)
    {
        double originalCost = estimateOriginalCost(inputStats);
        double bestCost = originalCost;
        List<IrLabel> bestSymbolSet = null;

        for (List<IrLabel> symbolSet : candidateSymbolSets) {
            double rewriteCost = estimateRewriteCost(symbolSet, w, dependentSelMap, inputStats);
            System.out.printf("  Symbol Set %s -> C(rewrite = %.2f%n", symbolSet, rewriteCost);
            if (rewriteCost < bestCost) {
                bestCost = rewriteCost;
                bestSymbolSet = symbolSet;
            }
        }
        System.out.printf(" C(original)=%.2f best=%s C(best)=%.2f%n", originalCost, bestSymbolSet, bestCost);
        return bestSymbolSet;
    }

    public static final class PrefilterCostResult
    {
        final double prefilterCost;
        final double prefilterOutputSize;

        public PrefilterCostResult(double prefilterCost, double prefilterOutputSize)
        {
            this.prefilterCost = prefilterCost;
            this.prefilterOutputSize = prefilterOutputSize;
        }
    }
}
