package de.tum.cit.hestia.learninggoalhub.goal;

import com.opencsv.CSVWriter;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Writes a course's goals as one CSV row per goal, in Topic tree order: each topic, then its
 * subtree depth-first, then the goals outside the tree. {@code parent_ids}, {@code tree_depth} and
 * {@code tier} come from the {@code CONTRIBUTES_TO} edges (child → parent), so the tree can be
 * rebuilt from the file and reads as the client draws it. Legacy GAP goals and legacy edge types
 * are left out, as in the client.
 */
@Component
public class LearningGoalCsvWriter {

    static final String[] HEADER = {
            "id",
            "parent_ids",
            "tree_depth",
            "tier",
            "role",
            "origin",
            "created_by",
            "learning_goal",
            "short_label",
            "kind",
            "bloom",
            "solo",
            "status",
            "hierarchy_module",
            "hierarchy_session",
            "hierarchy_exercise",
            "sources",
            "quotes",
            "grounded"
    };

    /** A node's tier in the Topic tree, as the client labels it. */
    enum Tier { TOPIC, SKILL, SUB_SKILL, KNOWLEDGE }

    private record Placement(int depth, Tier tier) {
    }

    /** Bloom levels that make a goal without a role skill-tier (legacy data), as in the client. */
    private static final Set<BloomLevel> DOING_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    /** Topic, skill, sub-skill, knowledge: the client draws no deeper. */
    private static final int MAX_DEPTH = 3;

    /** Quotes span several lines themselves, so a line of their own separates them. */
    static final String QUOTE_SEPARATOR = "\n---\n";

    /** Lets Excel detect UTF-8, so umlauts and other non-ASCII text open intact. */
    static final char BOM = '﻿';

    private static final Comparator<LearningGoal> LECTURE_ORDER = Comparator
            .comparing(LearningGoal::getLectureOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(LearningGoal::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public void write(Writer writer,
                      List<LearningGoal> goals,
                      List<GoalSource> sources,
                      List<GoalRelationship> relationships) throws IOException {
        List<LearningGoal> exported = goals.stream()
                .filter(g -> g.getOrigin() != GoalOrigin.GAP)
                .toList();
        Map<Long, LearningGoal> byId = exported.stream()
                .collect(Collectors.toMap(LearningGoal::getId, g -> g));
        Map<Long, List<GoalSource>> sourcesByGoal = sources.stream()
                .collect(Collectors.groupingBy(s -> s.getGoal().getId()));

        Map<Long, List<Long>> parentIds = new HashMap<>();
        Map<Long, List<LearningGoal>> children = new HashMap<>();
        for (GoalRelationship r : relationships) {
            Long child = r.getSource().getId();
            Long parent = r.getTarget().getId();
            if (r.getType() != RelationshipType.CONTRIBUTES_TO
                    || !byId.containsKey(child) || !byId.containsKey(parent)) {
                continue;
            }
            // Extraction and Topic tree synthesis can both store the same edge.
            List<Long> parents = parentIds.computeIfAbsent(child, k -> new ArrayList<>());
            if (!parents.contains(parent)) {
                parents.add(parent);
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(byId.get(child));
            }
        }

        // Insertion order is the row order; a null placement marks a goal outside the tree.
        Map<Long, Placement> placements = new LinkedHashMap<>();
        exported.stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .sorted(LECTURE_ORDER)
                .forEach(topic -> walk(topic, 0, null, children, placements));
        exported.stream()
                .filter(g -> !placements.containsKey(g.getId()))
                .sorted(LECTURE_ORDER)
                .forEach(g -> placements.put(g.getId(), null));

        writer.write(BOM);
        try (CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(HEADER);
            for (Map.Entry<Long, Placement> e : placements.entrySet()) {
                LearningGoal g = byId.get(e.getKey());
                csv.writeNext(neutralizeFormulas(toRow(
                        g,
                        e.getValue(),
                        parentIds.getOrDefault(g.getId(), List.of()),
                        sourcesByGoal.getOrDefault(g.getId(), List.of()))));
            }
            csv.flushQuietly();
        }
    }

    /**
     * Depth-first, first visit wins: a goal with two parents is listed once, under the first.
     * Knowledge takes no children and the tree stops at {@link #MAX_DEPTH}, as in the client, so
     * goals hanging below either end up outside the tree.
     */
    private void walk(LearningGoal goal, int depth, Tier parentTier,
                      Map<Long, List<LearningGoal>> children, Map<Long, Placement> placements) {
        if (placements.containsKey(goal.getId())) {
            return;
        }
        List<LearningGoal> goalChildren = depth < MAX_DEPTH
                ? children.getOrDefault(goal.getId(), List.of())
                : List.of();
        Tier tier = tierOf(goal, depth, parentTier, goalChildren);
        placements.put(goal.getId(), new Placement(depth, tier));
        if (tier == Tier.KNOWLEDGE) {
            return;
        }
        goalChildren.stream()
                .sorted(LECTURE_ORDER)
                .forEach(child -> walk(child, depth + 1, tier, children, placements));
    }

    /**
     * The server marks skills and sub-skills alike with role SKILL, so the position decides. Mirrors
     * {@code buildTopicForest} in the client's {@code lib/goals.ts}; change both together.
     */
    private static Tier tierOf(LearningGoal goal, int depth, Tier parentTier, List<LearningGoal> children) {
        boolean userCreated = goal.getCreationProvenance() == GoalCreationProvenance.USER_CREATED;
        if (depth == 0) {
            return Tier.TOPIC;
        }
        if (depth == 1 && (children.stream().anyMatch(c -> c.getRole() == GoalRole.SKILL)
                || (userCreated && goal.getRole() == GoalRole.SKILL))) {
            return Tier.SKILL;
        }
        if ((depth == 1 && (!children.isEmpty() || isSkillTier(goal) || userCreated))
                || (depth == 2 && parentTier == Tier.SKILL && isSkillTier(goal))) {
            return Tier.SUB_SKILL;
        }
        return Tier.KNOWLEDGE;
    }

    private static boolean isSkillTier(LearningGoal goal) {
        return goal.getRole() != null
                ? goal.getRole() == GoalRole.SKILL
                : DOING_BLOOM.contains(goal.getBloomLevel());
    }

    private String[] toRow(LearningGoal goal, Placement placement, List<Long> parentIds,
                           List<GoalSource> goalSources) {
        HierarchyPath path = HierarchyPath.from(goal.getHierarchyNode());
        String sources = goalSources.stream()
                .map(s -> s.getPage() == null
                        ? s.getDocument().getFilename()
                        : s.getDocument().getFilename() + " p." + s.getPage())
                .collect(Collectors.joining("; "));
        String quotes = goalSources.stream()
                .map(GoalSource::getSnippet)
                .filter(snippet -> snippet != null && !snippet.isBlank())
                .collect(Collectors.joining(QUOTE_SEPARATOR));
        String grounded = goalSources.isEmpty()
                ? ""
                : String.valueOf(goalSources.stream().allMatch(GoalSource::isGrounded));
        return new String[]{
                String.valueOf(goal.getId()),
                parentIds.stream().map(String::valueOf).collect(Collectors.joining("; ")),
                placement == null ? "" : String.valueOf(placement.depth()),
                placement == null ? "" : placement.tier().name(),
                nameOrEmpty(goal.getRole()),
                nameOrEmpty(goal.getOrigin()),
                nameOrEmpty(goal.getCreationProvenance()),
                goal.getText(),
                orEmpty(goal.getShortLabel()),
                nameOrEmpty(goal.getKind()),
                nameOrEmpty(goal.getBloomLevel()),
                nameOrEmpty(goal.getSoloLevel()),
                goal.getStatus().name(),
                orEmpty(path.module()),
                orEmpty(path.session()),
                orEmpty(path.exercise()),
                sources,
                quotes,
                grounded
        };
    }

    /**
     * Goal text, hierarchy labels and filenames come from uploads and model output. A spreadsheet
     * treats a cell that starts with one of these characters as a formula, so such a cell gets a
     * leading apostrophe and opens as plain text (OWASP's CSV-injection guidance).
     */
    static String[] neutralizeFormulas(String[] row) {
        String[] safe = new String[row.length];
        for (int i = 0; i < row.length; i++) {
            String cell = row[i];
            safe[i] = cell != null && !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0
                    ? "'" + cell
                    : cell;
        }
        return safe;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nameOrEmpty(Enum<?> value) {
        return value == null ? "" : value.name();
    }
}
