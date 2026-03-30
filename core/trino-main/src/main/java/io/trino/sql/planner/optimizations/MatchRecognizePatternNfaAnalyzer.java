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

import io.trino.sql.planner.rowpattern.ir.IrAlternation;
import io.trino.sql.planner.rowpattern.ir.IrConcatenation;
import io.trino.sql.planner.rowpattern.ir.IrLabel;
import io.trino.sql.planner.rowpattern.ir.IrQuantified;
import io.trino.sql.planner.rowpattern.ir.IrQuantifier;
import io.trino.sql.planner.rowpattern.ir.IrRowPattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NFA helper for MATCH_RECOGNIZE prefilter.
 * Supports labels, concatenation, alternation, Kleene star, and Kleene plus.
 * Main flow: build graph, DFS START->FINISH paths, emit special patterns.
 */
final class MatchRecognizePatternNfaAnalyzer
{
    private MatchRecognizePatternNfaAnalyzer() {}

    // Guard against path explosion: abort enumeration when limit is exceeded.
    private static final int MAX_SPECIAL_PATTERNS = 1000;

    // Result of the NFA analysis run.
    record PatternNFA(List<List<IrLabel>> specialPatterns)
    {}

    private enum NfaNodeKind
    {
        START,
        FINISH,
        LABEL,
        SPLIT_ALT,
        SPLIT_STAR,
        SPLIT_PLUS
    }

    private enum NfaEdgeRole
    {
        NORMAL,
        LOOP
    }

    private record NfaNode(NfaNodeKind nodeKind, Optional<IrLabel> label) {}

    private record NfaEdge(int toNodeId, NfaEdgeRole role) {}

    private record NfaFragment(int entryNodeId, Set<Integer> exitNodeIds) {}

    private record NfaGraph(
            int startNodeId,
            int finishNodeId,
            Map<Integer, NfaNode> nodesById,
            Map<Integer, List<NfaEdge>> outgoingEdges) {}

    static PatternNFA analyzePattern(IrRowPattern pattern)
    {
        // Build NFA for the supported IR subset.
        NfaGraph graph = new GraphBuilder().build(pattern);

        // DFS from START to FINISH and collect label paths
        List<List<IrLabel>> paths = new ArrayList<>();
        if (!dfsPaths(graph, graph.startNodeId(), new ArrayList<>(), new HashMap<>(), paths)) {
            throw new UnsupportedOperationException("special pattern enumeration limit exceeded");
        }

        // Deduplicate while keeping DFS order for deterministic output.
        return new PatternNFA(List.copyOf(new LinkedHashSet<>(paths)));
    }

    static Optional<List<List<IrLabel>>> enumerateSpecialPatterns(IrRowPattern pattern)
    {
        try {
            return Optional.of(analyzePattern(pattern).specialPatterns());
        }
        catch (UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    private static boolean dfsPaths(
            NfaGraph graph,
            int currentNodeId,
            List<IrLabel> currentPath,
            Map<Integer, Integer> cycleSplitVisits,
            List<List<IrLabel>> allPaths)
    {
        // Stop when there are too many paths
        if (allPaths.size() > MAX_SPECIAL_PATTERNS) {
            return false;
        }

        NfaNode node = graph.nodesById().get(currentNodeId);

        boolean pathExtended = false;
        if (node.nodeKind() == NfaNodeKind.LABEL && node.label().isPresent()) {
            currentPath.add(node.label().get());
            pathExtended = true;
        }

        // Count visits of Kleene split nodes on current DFS stack.
        boolean isCycleSplit = isCycleSplitNode(node.nodeKind());
        if (isCycleSplit) {
            cycleSplitVisits.put(currentNodeId, cycleSplitVisits.getOrDefault(currentNodeId, 0) + 1);
        }

        if (currentNodeId == graph.finishNodeId()) {
            allPaths.add(List.copyOf(currentPath));
        }
        else {
            int splitVisits = cycleSplitVisits.getOrDefault(currentNodeId, 0);
            for (NfaEdge edge : graph.outgoingEdges().getOrDefault(currentNodeId, List.of())) {
                // On second visit of Split(*)/Split(+), stop following LOOP edges.
                if (isCycleSplit &&
                        splitVisits >= 2 &&
                        edge.role() == NfaEdgeRole.LOOP) {
                    continue;
                }
                if (!dfsPaths(graph, edge.toNodeId(), currentPath, cycleSplitVisits, allPaths)) {
                    return false;
                }
            }
        }

        if (isCycleSplit) {
            int remaining = cycleSplitVisits.getOrDefault(currentNodeId, 0) - 1;
            if (remaining <= 0) {
                cycleSplitVisits.remove(currentNodeId);
            }
            else {
                cycleSplitVisits.put(currentNodeId, remaining);
            }
        }
        if (pathExtended) {
            currentPath.remove(currentPath.size() - 1);
        }

        return true;
    }

    private static boolean isCycleSplitNode(NfaNodeKind nodeKind)
    {
        return nodeKind == NfaNodeKind.SPLIT_STAR || nodeKind == NfaNodeKind.SPLIT_PLUS;
    }

    private static final class GraphBuilder
    {
        private int nextNodeId;
        private final Map<Integer, NfaNode> nodesById = new HashMap<>();
        private final Map<Integer, List<NfaEdge>> outgoingEdges = new HashMap<>();

        private NfaGraph build(IrRowPattern pattern)
        {
            // Wrap fragment with explicit START and FINISH nodes.
            int startNode = createNode(NfaNodeKind.START, Optional.empty());
            int finishNode = createNode(NfaNodeKind.FINISH, Optional.empty());

            NfaFragment fragment = buildFragment(pattern);
            addEdge(startNode, fragment.entryNodeId(), NfaEdgeRole.NORMAL);
            for (int exitNodeId : fragment.exitNodeIds()) {
                addEdge(exitNodeId, finishNode, NfaEdgeRole.NORMAL);
            }

            return new NfaGraph(
                    startNode,
                    finishNode,
                    nodesById,
                    outgoingEdges);
        }

        private NfaFragment buildFragment(IrRowPattern pattern)
        {
            // Recursive translation from pattern IR to NFA fragment.
            if (pattern instanceof IrLabel label) {
                return buildLabel(label);
            }
            if (pattern instanceof IrConcatenation concatenation) {
                return buildConcatenation(concatenation.getPatterns());
            }
            if (pattern instanceof IrAlternation alternation) {
                return buildAlternation(alternation.getPatterns());
            }
            if (pattern instanceof IrQuantified quantified) {
                return buildQuantified(quantified.getPattern(), quantified.getQuantifier());
            }
            else {
                throw new UnsupportedOperationException("unsupported pattern node: " + pattern.getClass().getName());
            }
        }

        private NfaFragment buildLabel(IrLabel label)
        {
            // Single-label fragment: entry and exit are the same label node.
            int labelNode = createNode(NfaNodeKind.LABEL, Optional.of(label));
            return new NfaFragment(labelNode, Set.of(labelNode));
        }

        private NfaFragment buildConcatenation(List<IrRowPattern> patterns)
        {
            // Chain fragments linearly in pattern order
            NfaFragment current = buildFragment(patterns.get(0));
            for (int index = 1; index < patterns.size(); index++) {
                NfaFragment next = buildFragment(patterns.get(index));
                for (int exitNodeId : current.exitNodeIds()) {
                    addEdge(exitNodeId, next.entryNodeId(), NfaEdgeRole.NORMAL);
                }
                current = new NfaFragment(current.entryNodeId(), next.exitNodeIds());
            }
            return current;
        }

        private NfaFragment buildAlternation(List<IrRowPattern> patterns)
        {
            // Split node fans out to each alternation branch.
            int split = createNode(NfaNodeKind.SPLIT_ALT, Optional.empty());
            Set<Integer> exits = new LinkedHashSet<>();
            for (IrRowPattern branchPattern : patterns) {
                NfaFragment branch = buildFragment(branchPattern);
                addEdge(split, branch.entryNodeId(), NfaEdgeRole.NORMAL);
                exits.addAll(branch.exitNodeIds());
            }
            return new NfaFragment(split, exits);
        }

        private NfaFragment buildQuantified(IrRowPattern pattern, IrQuantifier quantifier)
        {
            int atLeast = quantifier.getAtLeast();
            Optional<Integer> atMost = quantifier.getAtMost();
            if (!atMost.isPresent()) {
                if (atLeast == 0) {
                    NfaFragment body = buildFragment(pattern);
                    return buildKleeneStar(body);
                }
                if (atLeast == 1) {
                    NfaFragment body = buildFragment(pattern);
                    return buildKleenePlus(body);
                }
            }
            throw new UnsupportedOperationException("only Kleene * and + are supported");
        }

        private NfaFragment buildKleeneStar(NfaFragment body)
        {
            // Split(*) can stop immediately or loop into body.
            int split = createNode(NfaNodeKind.SPLIT_STAR, Optional.empty());

            addEdge(split, body.entryNodeId(), NfaEdgeRole.LOOP);
            for (int exitNodeId : body.exitNodeIds()) {
                addEdge(exitNodeId, split, NfaEdgeRole.NORMAL);
            }

            return new NfaFragment(split, Set.of(split));
        }

        private NfaFragment buildKleenePlus(NfaFragment body)
        {
            // Split(+) starts looping only after one body traversal.
            int split = createNode(NfaNodeKind.SPLIT_PLUS, Optional.empty());

            for (int exitNodeId : body.exitNodeIds()) {
                addEdge(exitNodeId, split, NfaEdgeRole.NORMAL);
            }
            addEdge(split, body.entryNodeId(), NfaEdgeRole.LOOP);

            return new NfaFragment(body.entryNodeId(), Set.of(split));
        }

        private int createNode(NfaNodeKind nodeKind, Optional<IrLabel> label)
        {
            int nodeId = nextNodeId++;
            nodesById.put(nodeId, new NfaNode(nodeKind, label));
            return nodeId;
        }

        private void addEdge(int fromNodeId, int toNodeId, NfaEdgeRole role)
        {
            outgoingEdges.computeIfAbsent(fromNodeId, ignored -> new ArrayList<>())
                    .add(new NfaEdge(toNodeId, role));
        }
    }
}
