package io.trino.sql.planner.optimizations;

import io.trino.sql.ir.Expression;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.rowpattern.ir.IrLabel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BucketizedPrefilterBuilder
{
    public record LabeledCondition(
            Expression expression,
            Set<IrLabel> referencedLabels)
    {
        public LabeledCondition
        {
            referencedLabels = Set.copyOf(referencedLabels);
        }

        public boolean isIndependent()
        {
            return referencedLabels.size() == 1;
        }

        public IrLabel onlyLabel()
        {
            if (!isIndependent()) {
                throw new IllegalStateException("Condition is not independent");
            }
            return referencedLabels.iterator().next();
        }

        static LabeledCondition fromOptimizerCondition(MatchRecognizePrefilterOptimizer.LabeledCondition condition)
        {
            return new LabeledCondition(condition.expression(), condition.referencedLabels());
        }
    }

    public record BucketizedPrefilterInput(
            Symbol primaryOrderBy,
            IrLabel firstLabel,
            IrLabel lastLabel,
            Set<IrLabel> subsequence,
            List<LabeledCondition> independentConditions,
            List<LabeledCondition> dependentConditions,
            Optional<Long> windowSize)
    {
        public BucketizedPrefilterInput
        {
            subsequence = Set.copyOf(subsequence);
            independentConditions = List.copyOf(independentConditions);
            dependentConditions = List.copyOf(dependentConditions);
        }
    }

    public record BucketWindow(
            long startBucketId,
            long endBucketId)
    {
        public BucketWindow
        {
            if (endBucketId < startBucketId) {
                throw new IllegalArgumentException("endBucketId must be >= startBucketId");
            }
        }
    }

    public record LabelWindowPair(
            IrLabel leftLabel,
            IrLabel rightLabel,
            BucketWindow leftWindow,
            BucketWindow rightWindow)
    {
        public LabelWindowPair
        {
            if (leftLabel == null || rightLabel == null) {
                throw new IllegalArgumentException("labels must not be null");
            }
            if (leftWindow == null || rightWindow == null) {
                throw new IllegalArgumentException("windows must not be null");
            }
        }
    }

    public record BucketizedPrefilterPlanSketch(
            BucketLayout layout,
            long effectiveBucketWidth,
            Symbol primaryOrderBy,
            IrLabel firstLabel,
            IrLabel lastLabel,
            Set<IrLabel> subsequence,
            List<IrLabel> orderedRelevantLabels,
            Map<IrLabel, List<LabeledCondition>> independentConditionsByLabel,
            Set<IrLabel> bucketRelevantLabels,
            Map<IrLabel, Set<BucketWindow>> bucketWindowsByLabel,
            Set<LabelWindowPair> labelWindowPairs,
            List<LabeledCondition> dependentConditionsInSubsequence,
            List<LabeledCondition> dependentConditionsOutsideSubsequence,
            Set<BucketWindow> candidateWindows)
    {
        public BucketizedPrefilterPlanSketch
        {
            subsequence = Set.copyOf(subsequence);
            orderedRelevantLabels = List.copyOf(orderedRelevantLabels);
            independentConditionsByLabel = Map.copyOf(independentConditionsByLabel);
            bucketRelevantLabels = Set.copyOf(bucketRelevantLabels);
            bucketWindowsByLabel = Map.copyOf(bucketWindowsByLabel);
            labelWindowPairs = Set.copyOf(labelWindowPairs);
            dependentConditionsInSubsequence = List.copyOf(dependentConditionsInSubsequence);
            dependentConditionsOutsideSubsequence = List.copyOf(dependentConditionsOutsideSubsequence);
            candidateWindows = Set.copyOf(candidateWindows);
        }
    }

    private final BucketizedPrefilterConfig config;

    public BucketizedPrefilterBuilder(BucketizedPrefilterConfig config)
    {
        this.config = config;
    }

    public BucketizedPrefilterConfig getConfig()
    {
        return config;
    }

    /**
     * Direkte Brücke zum neuen MatchRecognizePrefilterOptimizer.
     */
    public BucketizedPrefilterPlanSketch buildSketch(
            Symbol primaryOrderBy,
            IrLabel firstLabel,
            IrLabel lastLabel,
            List<IrLabel> subsequence,
            Map<IrLabel, List<MatchRecognizePrefilterOptimizer.LabeledCondition>> independent,
            List<MatchRecognizePrefilterOptimizer.LabeledCondition> dependent,
            Optional<MatchRecognizePrefilterOptimizer.PatternWindow> patternWindow)
    {
        List<LabeledCondition> independentConditions = new ArrayList<>();
        for (List<MatchRecognizePrefilterOptimizer.LabeledCondition> conditions : independent.values()) {
            for (MatchRecognizePrefilterOptimizer.LabeledCondition condition : conditions) {
                independentConditions.add(LabeledCondition.fromOptimizerCondition(condition));
            }
        }

        List<LabeledCondition> dependentConditions = new ArrayList<>();
        for (MatchRecognizePrefilterOptimizer.LabeledCondition condition : dependent) {
            dependentConditions.add(LabeledCondition.fromOptimizerCondition(condition));
        }

        BucketizedPrefilterInput input = new BucketizedPrefilterInput(
                primaryOrderBy,
                firstLabel,
                lastLabel,
                new LinkedHashSet<>(subsequence),
                independentConditions,
                dependentConditions,
                patternWindow.map(MatchRecognizePrefilterOptimizer.PatternWindow::windowSize));

        return buildSketch(input);
    }

    public BucketizedPrefilterPlanSketch buildSketch(BucketizedPrefilterInput input)
    {
        long effectiveWidth = input.windowSize().orElse(config.bucketWidth());

        if (effectiveWidth <= 0) {
            throw new IllegalArgumentException("effective bucket width must be > 0");
        }

        BucketLayout layout = new BucketLayout(0L, effectiveWidth);

        Map<IrLabel, List<LabeledCondition>> independentByLabel = groupIndependentConditions(input.independentConditions());

        Set<IrLabel> bucketRelevantLabels = determineBucketRelevantLabels(
                input.subsequence(),
                independentByLabel);

        List<IrLabel> orderedRelevantLabels = determineOrderedRelevantLabels(
                input.firstLabel(),
                input.lastLabel(),
                input.subsequence(),
                bucketRelevantLabels);

        Map<IrLabel, Set<BucketWindow>> bucketWindowsByLabel = buildInitialWindowsByLabel(
                orderedRelevantLabels,
                effectiveWidth,
                layout);

        Set<LabelWindowPair> labelWindowPairs = buildInitialLabelWindowPairs(
                input.firstLabel(),
                input.lastLabel(),
                bucketWindowsByLabel);

        List<LabeledCondition> dependentInside = new ArrayList<>();
        List<LabeledCondition> dependentOutside = new ArrayList<>();

        for (LabeledCondition condition : input.dependentConditions()) {
            if (input.subsequence().containsAll(condition.referencedLabels())) {
                dependentInside.add(condition);
            }
            else {
                dependentOutside.add(condition);
            }
        }

        Set<BucketWindow> candidateWindows = buildInitialCandidateWindows(
                input.firstLabel(),
                input.lastLabel(),
                input.subsequence(),
                effectiveWidth,
                layout);

        return new BucketizedPrefilterPlanSketch(
                layout,
                effectiveWidth,
                input.primaryOrderBy(),
                input.firstLabel(),
                input.lastLabel(),
                input.subsequence(),
                orderedRelevantLabels,
                independentByLabel,
                bucketRelevantLabels,
                bucketWindowsByLabel,
                labelWindowPairs,
                dependentInside,
                dependentOutside,
                candidateWindows);
    }

    public void printSketch(BucketizedPrefilterPlanSketch sketch)
    {
        System.out.println("=== Bucketized Prefilter Sketch ===");
        System.out.println("primaryOrderBy = " + sketch.primaryOrderBy());
        System.out.println("firstLabel = " + sketch.firstLabel());
        System.out.println("lastLabel = " + sketch.lastLabel());
        System.out.println("subsequence = " + sketch.subsequence());
        System.out.println("effectiveBucketWidth = " + sketch.effectiveBucketWidth());
        System.out.println("bucketLayout = " + sketch.layout());

        System.out.println("--- Ordered relevant labels ---");
        for (IrLabel label : sketch.orderedRelevantLabels()) {
            System.out.println("  " + label);
        }

        System.out.println("--- Independent conditions by label ---");
        for (Map.Entry<IrLabel, List<LabeledCondition>> entry : sketch.independentConditionsByLabel().entrySet()) {
            System.out.println("label " + entry.getKey() + ":");
            for (LabeledCondition condition : entry.getValue()) {
                System.out.println("  " + condition.expression());
            }
        }

        System.out.println("--- Bucket relevant labels ---");
        for (IrLabel label : sketch.bucketRelevantLabels()) {
            System.out.println("  " + label);
        }

        System.out.println("--- Initial bucket windows by label ---");
        for (Map.Entry<IrLabel, Set<BucketWindow>> entry : sketch.bucketWindowsByLabel().entrySet()) {
            System.out.println("label " + entry.getKey() + ":");
            for (BucketWindow window : entry.getValue()) {
                long start = sketch.layout().bucketStart(window.startBucketId());
                long endExclusive = sketch.layout().bucketEnd(window.endBucketId());
                System.out.println("  buckets [" + window.startBucketId() + ", " + window.endBucketId() + "]"
                        + " => time [" + start + ", " + endExclusive + ")");
            }
        }

        System.out.println("--- Initial label window pairs ---");
        for (LabelWindowPair pair : sketch.labelWindowPairs()) {
            System.out.println("  "
                    + pair.leftLabel() + " -> " + pair.rightLabel()
                    + " | left buckets [" + pair.leftWindow().startBucketId() + ", " + pair.leftWindow().endBucketId() + "]"
                    + " | right buckets [" + pair.rightWindow().startBucketId() + ", " + pair.rightWindow().endBucketId() + "]");
        }

        System.out.println("--- Dependent conditions inside subsequence ---");
        for (LabeledCondition condition : sketch.dependentConditionsInSubsequence()) {
            System.out.println("  " + condition.expression() + " labels=" + condition.referencedLabels());
        }

        System.out.println("--- Dependent conditions outside subsequence ---");
        for (LabeledCondition condition : sketch.dependentConditionsOutsideSubsequence()) {
            System.out.println("  " + condition.expression() + " labels=" + condition.referencedLabels());
        }

        System.out.println("--- Candidate bucket windows ---");
        for (BucketWindow window : sketch.candidateWindows()) {
            long start = sketch.layout().bucketStart(window.startBucketId());
            long endExclusive = sketch.layout().bucketEnd(window.endBucketId());
            System.out.println("  buckets [" + window.startBucketId() + ", " + window.endBucketId() + "]"
                    + " => time [" + start + ", " + endExclusive + ")");
        }
    }

    private static Map<IrLabel, List<LabeledCondition>> groupIndependentConditions(List<LabeledCondition> conditions)
    {
        Map<IrLabel, List<LabeledCondition>> grouped = new LinkedHashMap<>();

        for (LabeledCondition condition : conditions) {
            if (!condition.isIndependent()) {
                continue;
            }

            IrLabel onlyLabel = condition.onlyLabel();
            grouped.computeIfAbsent(onlyLabel, ignored -> new ArrayList<>()).add(condition);
        }

        return grouped;
    }

    private static Set<IrLabel> determineBucketRelevantLabels(
            Set<IrLabel> subsequence,
            Map<IrLabel, List<LabeledCondition>> independentConditionsByLabel)
    {
        Set<IrLabel> relevant = new LinkedHashSet<>();

        for (IrLabel label : subsequence) {
            if (independentConditionsByLabel.containsKey(label)) {
                relevant.add(label);
            }
        }

        return relevant;
    }

    private static List<IrLabel> determineOrderedRelevantLabels(
            IrLabel firstLabel,
            IrLabel lastLabel,
            Set<IrLabel> subsequence,
            Set<IrLabel> bucketRelevantLabels)
    {
        List<IrLabel> ordered = new ArrayList<>();

        if (firstLabel != null && bucketRelevantLabels.contains(firstLabel)) {
            ordered.add(firstLabel);
        }

        for (IrLabel label : subsequence) {
            if (bucketRelevantLabels.contains(label) && !ordered.contains(label)) {
                ordered.add(label);
            }
        }

        if (lastLabel != null && bucketRelevantLabels.contains(lastLabel) && !ordered.contains(lastLabel)) {
            ordered.add(lastLabel);
        }

        return ordered;
    }

    private static Map<IrLabel, Set<BucketWindow>> buildInitialWindowsByLabel(
            List<IrLabel> orderedRelevantLabels,
            long effectiveWidth,
            BucketLayout layout)
    {
        Map<IrLabel, Set<BucketWindow>> windowsByLabel = new LinkedHashMap<>();

        long startBucketId = layout.bucketId(0L);
        long endBucketId = layout.bucketId(Math.max(0L, effectiveWidth - 1));
        BucketWindow initialWindow = new BucketWindow(startBucketId, endBucketId);

        for (IrLabel label : orderedRelevantLabels) {
            Set<BucketWindow> windows = new LinkedHashSet<>();
            windows.add(initialWindow);
            windowsByLabel.put(label, windows);
        }

        return windowsByLabel;
    }

    private static Set<LabelWindowPair> buildInitialLabelWindowPairs(
            IrLabel firstLabel,
            IrLabel lastLabel,
            Map<IrLabel, Set<BucketWindow>> bucketWindowsByLabel)
    {
        Set<LabelWindowPair> pairs = new LinkedHashSet<>();

        if (firstLabel == null || lastLabel == null) {
            return pairs;
        }

        Set<BucketWindow> leftWindows = bucketWindowsByLabel.get(firstLabel);
        Set<BucketWindow> rightWindows = bucketWindowsByLabel.get(lastLabel);

        if (leftWindows == null || rightWindows == null) {
            return pairs;
        }

        for (BucketWindow leftWindow : leftWindows) {
            for (BucketWindow rightWindow : rightWindows) {
                pairs.add(new LabelWindowPair(firstLabel, lastLabel, leftWindow, rightWindow));
            }
        }

        return pairs;
    }

    private static Set<BucketWindow> buildInitialCandidateWindows(
            IrLabel firstLabel,
            IrLabel lastLabel,
            Set<IrLabel> subsequence,
            long effectiveWidth,
            BucketLayout layout)
    {
        Set<BucketWindow> windows = new LinkedHashSet<>();

        if (firstLabel == null || lastLabel == null || subsequence.isEmpty()) {
            return windows;
        }

        long startBucketId = layout.bucketId(0L);
        long endBucketId = layout.bucketId(Math.max(0L, effectiveWidth - 1));

        windows.add(new BucketWindow(startBucketId, endBucketId));
        return windows;
    }
}