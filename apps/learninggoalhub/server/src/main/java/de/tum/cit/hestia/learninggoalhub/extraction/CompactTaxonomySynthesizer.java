package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the competency tree bottom-up, as two partitions of what the course actually teaches.
 *
 * <p>Stage one groups every source-backed skill outcome into sub-skills; stage two groups those
 * sub-skills into terminal skills. Both stages are validated as an EXACT PARTITION: every input
 * index appears under exactly one output node, never omitted, repeated or invented. Coverage is
 * therefore a property of the validator rather than something the prompt asks for politely — a
 * model that drops an outcome fails validation and is retried with the specific violation.
 *
 * <p>This is what keeps the "at most five sub-skills" cap from costing content. The cap bounds the
 * tree's WIDTH; the number of outcomes a sub-skill absorbs is unbounded, so an overfull course is
 * compacted by merging outcomes into broader capabilities, never by dropping them. There is no
 * catch-all node because there is no residue for one to hold.
 *
 * <p>Stage one can also be allowed to name a residue — outcomes it judges to be supporting detail
 * rather than course-level performances — via {@code hestia.extraction.set-aside-outcomes}. That is
 * OFF by default because it is the one decision here that does not reproduce: see
 * {@link SelectionPolicy}. The accounting is the same either way.
 *
 * <p>Stage one writes no learner-facing text. It ELECTS one member of each group to stand for it, so
 * every visible sub-skill in the finished tree is a real extracted outcome that keeps its own source
 * quote, page and taxonomy levels. Only the terminal skills above them are generated, because a
 * course-level competency is an abstraction that no single lecture states.
 */
@Service
public class CompactTaxonomySynthesizer {

    private static final Logger log = LoggerFactory.getLogger(CompactTaxonomySynthesizer.class);

    /** Below this a tree is degenerate rather than compact, whatever the course looks like. */
    static final int MIN_SUB_SKILLS = 6;
    static final int MIN_FULL_COURSE_SUB_SKILLS = 18;
    static final int MAX_SUB_SKILLS = 24;
    static final int MAX_SUB_SKILLS_PER_SKILL = 5;
    /**
     * The longest a terminal skill's text may be.
     *
     * <p>Nothing used to bound it — {@link OutcomeWording} enforces only a MINIMUM, that text says
     * more than its own shortLabel — and asking for an outcome "covering the whole group" against no
     * upper bound produced summaries rather than names: measured on a nineteen-lecture course,
     * terminal text averaged 39 words over sub-skills of 11, the most abstract tier running 3.5x
     * longer than its own children. One of them concatenated its members verb and all
     * ("Understanding hierarchical decomposition ..., understanding hierarchical Archimedes
     * quadrature ..., and understanding application of tree-based data structures"). Twenty words is
     * room for a capability and too little for a list.
     */
    static final int MAX_TERMINAL_TEXT_WORDS = 20;

    /**
     * Every terminal skill needs at least this many sub-skills.
     *
     * <p>This replaces the two constants that used to set the terminal count directly — a preferred
     * six per course and a target width of three. Both were arbitrary, and neither expressed what
     * they were actually protecting: a model free to spread one sub-skill per terminal produces
     * single-child skills that add no structure at all. Saying THAT leaves the count itself to the
     * model, bounded only by arithmetic — at least ceil(n / {@value #MAX_SUB_SKILLS_PER_SKILL})
     * terminals to fit the sub-skills, at most n / 2 for them all to have company.
     */
    static final int MIN_SUB_SKILLS_PER_SKILL = 2;
    /** The one editorial bound left: a tree wider than this stops being reviewable in one sitting. */
    static final int MAX_SKILLS = 10;
    /** Attempts allowed for the naming call. It risks nothing structural, so it is never waived. */
    static final int NAMING_ATTEMPTS = 3;
    /** Shorter tokens carry no topic and would make the anchoring check meaningless. */
    private static final int SIGNIFICANT_TOKEN_LENGTH = 5;
    /**
     * The share of source outcomes that must still become sub-skills. Setting outcomes aside is the
     * point of the stage, but a model that sets aside most of the course has not selected — it has
     * given up, and the resulting tree would misrepresent what is taught.
     */
    static final double MIN_SELECTED_SHARE = 0.5;
    /** Outcomes per sub-skill used to scale the requested count down on a short input. */
    private static final int OUTCOMES_PER_SUB_SKILL = 4;

    public record Candidate(String text, String bloomLevel, String session) {}

    /**
     * One visible sub-skill: the elected source outcome that becomes the node, plus every outcome
     * in its group (the representative included).
     */
    public record PlannedSubSkill(int representative, List<Integer> supporting) {
        public PlannedSubSkill {
            supporting = supporting == null ? List.of() : List.copyOf(supporting);
        }
    }

    public record PlannedSkill(String text, String shortLabel, List<PlannedSubSkill> subSkills) {
        public PlannedSkill {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
        }
    }

    /** One stage-one group: the elected representative index plus every member index. */
    record SubSkillGroup(int representative, List<Integer> supporting) {
        SubSkillGroup {
            supporting = supporting == null ? List.of() : List.copyOf(supporting);
        }
    }

    record SubSkillPartition(List<SubSkillGroup> subSkills, List<Integer> setAside) {
        SubSkillPartition {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
            setAside = setAside == null ? List.of() : List.copyOf(setAside);
        }
    }

    /**
     * What stage one decided: the outcomes that became sub-skills, and the ones it judged to be
     * supporting detail rather than a course-level performance. Both together account for every
     * source outcome exactly once — the accounting is still total, but the residue is now NAMED
     * instead of being force-fitted into whichever group was nearest.
     */
    record SubSkillSelection(List<SubSkillGroup> groups, List<Integer> setAside) {
        SubSkillSelection {
            groups = groups == null ? List.of() : List.copyOf(groups);
            setAside = setAside == null ? List.of() : List.copyOf(setAside);
        }
    }

    /** The finished plan plus the source outcomes that ladder up to no skill. */
    public record Plan(List<PlannedSkill> skills, List<Integer> setAside) {
        public Plan {
            skills = skills == null ? List.of() : List.copyOf(skills);
            setAside = setAside == null ? List.of() : List.copyOf(setAside);
        }
    }

    /** One stage-two node: a terminal skill plus the stage-one sub-skill indices beneath it. */
    record TerminalGroup(String text, String shortLabel, List<Integer> subSkills) {
        TerminalGroup {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
        }
    }

    /** One stage-2a node: the sub-skill indices of a group, before anybody has named it. */
    record TerminalMembers(List<Integer> subSkills) {
        TerminalMembers {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
        }
    }

    record TerminalGrouping(List<TerminalMembers> skills) {
        TerminalGrouping {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    /** One stage-2b name, positionally matched to the group it names. */
    record TerminalName(String text, String shortLabel) {}

    record TerminalNaming(List<TerminalName> skills) {
        TerminalNaming {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    /**
     * The clauses of the stage-one prompt that differ between its two selection policies.
     *
     * <p>Whether the model may leave an outcome out of the tree is by far the least stable decision
     * in the pipeline: measured across five runs of one course it set aside between 1 and 17 of 34
     * outcomes — 3% to 50% — while extraction varied by ~6% and the set of terminal themes not at
     * all. So the residue is no longer a coin flip by default; see
     * {@code hestia.extraction.set-aside-outcomes}. Nothing about the ACCOUNTING changes with it:
     * both policies are validated as an exact partition of every source index.
     */
    private record SelectionPolicy(String opening, String example, String accounting, String conflict,
                                   String residue) {}

    /** Outcomes may be set aside as supporting detail, and the residue is reported upward. */
    private static final SelectionPolicy SELECT = new SelectionPolicy(
            """
            Decide which of this university course's source-backed learning outcomes are the distinct
            SKILLS a graduate carries away, and set the rest aside as supporting detail.""",
            """
            {"subSkills":[{"representative":3,"supporting":[3,7]}],"setAside":[5,11]}""",
            """
            each appears in exactly one supporting array
                or in setAside. Never omit, repeat or invent an index.""",
            """
            they are different sub-skills, or the weaker ones belong in setAside.""",
            """
            setAside is for an outcome that is NOT a course-level performance in its own right:
                a fact, step, method or example that merely supports one; a tooling or presentation
                aside; a notation convention; or wording too vague to assess. Setting such an
                outcome aside is CORRECT and expected — it is reported to the instructor, not
                discarded. Do NOT force it into the nearest group to avoid using setAside, and do
                NOT set aside a genuine performance merely to shorten the list. Most courses set
                aside a minority of their outcomes.""");

    /** Every outcome is grouped. Supporting detail joins the performance it serves. */
    private static final SelectionPolicy PARTITION = new SelectionPolicy(
            """
            Group this university course's source-backed learning outcomes into the distinct SKILLS a
            graduate carries away. Every outcome belongs under exactly one of them.""",
            """
            {"subSkills":[{"representative":3,"supporting":[3,7]}]}""",
            """
            each appears in exactly one supporting
                array. Never omit, repeat or invent an index.""",
            """
            they are different sub-skills, and a weak outcome belongs with
                the performance it supports.""",
            """
            An outcome that is not a course-level performance in its own right — a fact, step,
                method or example that merely supports one; a tooling or presentation aside; a
                notation convention — is neither dropped nor given a sub-skill of its own. Put it in
                the supporting array of the performance it serves, and elect a representative that
                names that performance rather than the detail.""");

    private static final String SUB_SKILL_PROMPT = """
            %s

            Do NOT write any new outcome text. Return only indices:
            %s.
            The zero-based indices refer to the numbered list below and must remain numbers.

            Rules:
              - Return between %d and %d sub-skills.
              - ACCOUNT for every source index below exactly once: %s
              - A sub-skill is ONE performance a student can be asked to carry out. Its supporting
                array holds ONLY the outcomes that are the SAME performance restated — the same
                capability taught again in another lecture, or worded differently. Two or three
                members is normal. If you find yourself putting unrelated performances together
                because they share a lecture or a chapter, that is the error this rule exists to
                prevent: %s
              - representative must be one of that group's own supporting indices, and must read
                well as the name of every member.
              - %s
              - The outcomes below are listed in NO meaningful order: neighbouring entries have
                nothing to do with each other, and an outcome's position carries no information.
                Group purely by capability.
              - Each outcome names the session or document it came from in braces. That label is
                context for telling terse or near-duplicate outcomes apart — use it only to break a
                genuine tie, NEVER as the thing you group by. A reusable capability is normally
                rehearsed across SEVERAL sessions, and one session normally teaches outcomes
                belonging to different capabilities. Grouping the outcomes by their session or
                document is the one clearly wrong answer.
              - Return the sub-skills in any order.
              - Return only structured JSON.

            Source-backed outcomes, in arbitrary order:
            ---
            %s
            ---
            """;

    private static final String TERMINAL_GROUPING_PROMPT = """
            Group the sub-skills of this university course into the terminal skills they build toward.
            This step decides STRUCTURE ONLY. Return indices and no text: the skills are named in a
            separate step, from the contents of the groups you decide here.

            Return {"skills":[{"subSkills":[0,2]}]}. subSkills holds the zero-based sub-skill indices
            shown below and must remain numbers.

            Rules:
              - Return between %d and %d terminal skills.
              - Every terminal skill takes between two and %d sub-skills. A terminal holding a single
                sub-skill adds no structure — it is that sub-skill under a second name — so fold it
                into a related capability rather than returning it alone.
              - How many terminal skills the course has is YOUR judgement within the range above.
                Do not aim for the smallest allowed number: return as many capabilities as the course
                genuinely builds, and as few as that honestly requires.
              - PARTITION: every sub-skill index below must appear under exactly one terminal skill.
                Never omit, repeat or invent an index. Do not create a catch-all, residual, "further
                outcomes" or "advanced topics" skill: every sub-skill specialises a real capability,
                so place it under the closest one rather than in a leftover bucket.
              - A terminal skill is the broad capability a graduate of this course carries away. Each
                of its sub-skills must genuinely specialise it.
              - Group so that each group can be given a name that no other group could take. If two
                groups could only be told apart by their action verb, the split is wrong.
              - The sub-skills below are listed in NO meaningful order, and a sub-skill's position
                carries no information. A terminal skill is a capability the course builds across
                its whole span, so its sub-skills normally come from lectures spread through the
                course. Returning the syllabus back as consecutive blocks — a table of contents
                rather than a set of capabilities — is the one clearly wrong answer.
              - Return the terminal skills in any order.
              - Return only structured JSON.

            Sub-skills, in arbitrary order:
            ---
            %s
            ---
            """;

    /**
     * Stage 2b. Naming is its own call for two reasons. It lets the wording rules be ENFORCED rather
     * than merely retried once and then waived — a rejected name costs one cheap regeneration, not
     * the validated partition above it. And it lets the model see a whole group's outcomes at once
     * while writing its name, instead of inventing a label in the same breath as deciding membership.
     */
    private static final String TERMINAL_NAMING_PROMPT = """
            Name the terminal skills of this university course. Their grouping is already decided and
            must NOT change: write one name for each numbered group below, in the same order.

            Write every text and shortLabel in %s. Return
            {"skills":[{"text":"...","shortLabel":"..."}]} with exactly %d entries, one per group, in
            the order the groups are listed. Keep the JSON property names text and shortLabel exactly
            as written.

            Rules:
              - text names the ONE capability the group builds, in at most %d words; shortLabel is a
                distinct compact 2-6 word action label reusing that same action. A topic-only
                shortLabel is invalid — it must carry the action too.
              - DO NOT ENUMERATE THE GROUP. Its outcomes are listed beneath the name already, so they
                do not need restating: text says what they build toward, not what they contain.
                Stringing topics together with commas or a repeated "and" returns a summary instead
                of a name, and repeating the members' own verb once per member — "understanding X,
                understanding Y, and understanding Z" — is the same mistake.
              - NAME THE GROUP FROM ITS OWN CONTENTS. Every name must reuse the terminology of the
                outcomes listed under it: the objects, methods and topics they actually mention,
                spelled as they spell them. A name a reader could not trace back to the outcomes
                beneath it is wrong, and an invented near-word is worse than a plain one.
              - DO NOT NAME THE COURSE. A label that restates the subject of the whole course —
                "analysing complex functions" in a course on complex analysis — carries no
                information. Name what distinguishes THIS group from the other groups.
              - The names must stay distinguishable WITHOUT their action verb. No two may name the
                same object with a different verb; if two groups would take the same object, you are
                describing them too broadly, so go one level more specific in each.
              - Return only structured JSON.

            Groups:
            ---
            %s
            ---
            """;

    private static final String NAMING_RETRY = """

            Your previous names did not satisfy the naming rules. Regenerate the COMPLETE list of %d
            names, one per group, in the same group order, changing only the wording — the grouping
            is fixed.
            Specific validation failure: %s
            """;

    private static final String RETRY = """

            Your previous response did not account for the listed items correctly. Regenerate the
            COMPLETE response. Every listed index must appear exactly once across the response, with
            no missing, repeated or out-of-range indices, and every generated text and shortLabel
            must satisfy the wording invariant.
            Specific validation failure: %s
            """;

    private final ChatClient chatClient;
    private final String plannerModel;
    private final String partitionModel;
    private final double generationTemperature;
    private final double partitionTemperature;
    private final boolean setAsideOutcomes;

    public CompactTaxonomySynthesizer(
            ChatClient.Builder chatClientBuilder,
            @Value("${hestia.extraction.taxonomy-planner-model:openai-gpt-oss-120b}") String plannerModel,
            @Value("${hestia.extraction.taxonomy-partition-model:openai-gpt-oss-120b}") String partitionModel,
            @Value("${hestia.extraction.temperature:0.2}") double generationTemperature,
            @Value("${hestia.extraction.assignment-temperature:0.0}") double partitionTemperature,
            @Value("${hestia.extraction.set-aside-outcomes:false}") boolean setAsideOutcomes) {
        this.chatClient = chatClientBuilder.build();
        this.plannerModel = plannerModel;
        this.partitionModel = partitionModel;
        this.generationTemperature = generationTemperature;
        this.partitionTemperature = partitionTemperature;
        this.setAsideOutcomes = setAsideOutcomes;
    }

    /**
     * Plans the whole tree. Every candidate ends up under exactly one sub-skill and every sub-skill
     * under exactly one terminal skill, or the call fails — a half-covered tree is never returned.
     */
    public Plan synthesize(List<Candidate> candidates, String languageName, String modelOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return new Plan(List.of(), List.of());
        }
        SubSkillSelection selection = partitionOutcomes(candidates, languageName, modelOverride);
        List<SubSkillGroup> subSkills = selection.groups();
        log.info("Compact taxonomy selected {} sub-skills from {} source outcomes, setting {} aside",
                subSkills.size(), candidates.size(), selection.setAside().size());
        List<List<Integer>> grouping = groupSubSkills(subSkills, candidates, languageName, modelOverride);
        List<TerminalGroup> terminals = nameTerminals(grouping, subSkills, candidates, languageName,
                modelOverride);
        log.info("Compact taxonomy grouped {} sub-skills into {} terminal skills",
                subSkills.size(), terminals.size());
        return new Plan(assemble(terminals, subSkills), selection.setAside());
    }

    /**
     * Stage one: the index-faithful half of the plan. Measured on a real course, both candidate
     * models returned an exact 86/86 partition, but only the planner-class model honoured the
     * requested sub-skill count — DeepSeek returned 38 groups against a range of 18-24, which the
     * validator can only reject. Hence the same default model here; the property still allows an A/B.
     */
    private SubSkillSelection partitionOutcomes(List<Candidate> candidates, String languageName,
                                                String modelOverride) {
        int min = minSubSkills(candidates.size());
        int max = maxSubSkills(candidates.size());
        // Validation, and therefore every index in a retry's failure message, stays in the space the
        // model actually saw. Lecture order is restored only once the grouping is decided.
        List<Integer> order = presentationOrder(candidates.size(), seedOf(candidates));
        List<Candidate> presented = order.stream().map(candidates::get).toList();
        SelectionPolicy policy = setAsideOutcomes ? SELECT : PARTITION;
        String prompt = SUB_SKILL_PROMPT.formatted(policy.opening(), policy.example(), min, max,
                policy.accounting(), policy.conflict(), policy.residue(), numbered(presented));
        String model = effectiveModel(modelOverride, partitionModel);
        try {
            return restoreSubSkillOrder(validateSubSkillPartition(
                    call(prompt, languageName, model, partitionTemperature,
                            new ParameterizedTypeReference<SubSkillPartition>() {}),
                    candidates.size(), languageName, setAsideOutcomes), order);
        } catch (IllegalArgumentException invalid) {
            log.warn("Sub-skill selection was invalid; retrying once: {}", invalid.getMessage());
            return restoreSubSkillOrder(validateSubSkillPartition(
                    call(prompt + RETRY.formatted(invalid.getMessage()), languageName, model, 0.0,
                            new ParameterizedTypeReference<SubSkillPartition>() {}),
                    candidates.size(), languageName, setAsideOutcomes), order);
        }
    }

    /** Stage 2a: structure only, over a short list, so it runs on the planner model. */
    private List<List<Integer>> groupSubSkills(List<SubSkillGroup> subSkills, List<Candidate> candidates,
                                               String languageName, String modelOverride) {
        int min = minTerminals(subSkills.size());
        int max = maxTerminals(subSkills.size());
        // Stage one hands its groups over sorted by source position, so this stage would otherwise
        // read the syllabus in order all over again — the very signal it must not group by.
        List<Integer> order = presentationOrder(subSkills.size(), seedOf(subSkills, candidates));
        List<SubSkillGroup> presented = order.stream().map(subSkills::get).toList();
        String prompt = TERMINAL_GROUPING_PROMPT.formatted(
                min, max, MAX_SUB_SKILLS_PER_SKILL, renderSubSkills(presented, candidates));
        String model = effectiveModel(modelOverride, plannerModel);
        try {
            return restoreTerminalOrder(validateTerminalGrouping(
                    call(prompt, languageName, model, generationTemperature,
                            new ParameterizedTypeReference<TerminalGrouping>() {}),
                    subSkills.size()), order);
        } catch (IllegalArgumentException invalid) {
            log.warn("Terminal grouping was invalid; retrying once: {}", invalid.getMessage());
            return restoreTerminalOrder(validateTerminalGrouping(
                    call(prompt + RETRY.formatted(invalid.getMessage()), languageName, model, 0.0,
                            new ParameterizedTypeReference<TerminalGrouping>() {}),
                    subSkills.size()), order);
        }
    }

    /**
     * Stage 2b: the names, over a grouping that is already fixed.
     *
     * <p>Measured over five runs of one course, this was the least trustworthy output of the whole
     * pipeline: labels were rewritten every run, one run produced two siblings that differed only by
     * their verb ("Interpretieren/Analysieren komplexer Funktionen" — the course's own subject,
     * naming nothing). The old fused call could only ever retry once and then WAIVED the wording
     * rules, because the alternative was discarding a valid partition over a word. Naming on its own
     * removes that trade: a rejection costs one short regeneration and risks nothing structural, so
     * the rules are enforced until they hold rather than downgraded to a warning.
     */
    private List<TerminalGroup> nameTerminals(List<List<Integer>> grouping, List<SubSkillGroup> subSkills,
                                              List<Candidate> candidates, String languageName,
                                              String modelOverride) {
        String prompt = TERMINAL_NAMING_PROMPT.formatted(
                languageName, grouping.size(), MAX_TERMINAL_TEXT_WORDS,
                renderGroups(grouping, subSkills, candidates));
        String model = effectiveModel(modelOverride, plannerModel);
        String failure = null;
        for (int attempt = 0; attempt < NAMING_ATTEMPTS; attempt++) {
            String attemptPrompt = failure == null ? prompt
                    : prompt + NAMING_RETRY.formatted(grouping.size(), failure);
            try {
                return validateTerminalNames(
                        call(attemptPrompt, languageName, model,
                                attempt == 0 ? generationTemperature : 0.0,
                                new ParameterizedTypeReference<TerminalNaming>() {}),
                        grouping, subSkills, candidates, languageName);
            } catch (IllegalArgumentException invalid) {
                failure = invalid.getMessage();
                log.warn("Terminal naming attempt {} of {} was invalid: {}",
                        attempt + 1, NAMING_ATTEMPTS, failure);
            }
        }
        throw new IllegalArgumentException(
                "Terminal naming failed " + NAMING_ATTEMPTS + " times; last failure: " + failure);
    }

    /**
     * The order the model reads its input in, as a presentation index to source index map.
     *
     * <p>Both stages used to receive their input in lecture order and were asked to return their
     * output ordered by earliest input index. Together those told the model that adjacency carries
     * meaning, and it duly grouped by adjacency: on a real thirteen-lecture course four of six
     * terminal skills came out as contiguous lecture runs, which is a table of contents rather than
     * a set of capabilities. Shuffling the presentation removes the signal. Lecture order is not
     * lost — it is restored from the source indices once the grouping has been decided without it.
     *
     * <p>The permutation is seeded from the input text itself, so a course shuffles the same way on
     * every run and two runs of the same course stay comparable.
     */
    static List<Integer> presentationOrder(int size, long seed) {
        List<Integer> order = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            order.add(index);
        }
        Collections.shuffle(order, new Random(seed));
        return List.copyOf(order);
    }

    /** Maps a validated stage-one selection out of presentation space and back into source order. */
    static SubSkillSelection restoreSubSkillOrder(SubSkillSelection selection, List<Integer> order) {
        List<Integer> setAside = new ArrayList<>(selection.setAside().size());
        for (Integer index : selection.setAside()) {
            setAside.add(order.get(index));
        }
        setAside.sort(Integer::compareTo);
        return new SubSkillSelection(restoreSubSkillOrder(selection.groups(), order), setAside);
    }

    /** Maps one validated stage-one partition out of presentation space and back into source order. */
    static List<SubSkillGroup> restoreSubSkillOrder(List<SubSkillGroup> groups, List<Integer> order) {
        List<SubSkillGroup> restored = new ArrayList<>(groups.size());
        for (SubSkillGroup group : groups) {
            List<Integer> members = new ArrayList<>(group.supporting().size());
            for (Integer member : group.supporting()) {
                members.add(order.get(member));
            }
            members.sort(Integer::compareTo);
            restored.add(new SubSkillGroup(order.get(group.representative()), members));
        }
        restored.sort(Comparator.comparingInt(group -> group.supporting().getFirst()));
        return List.copyOf(restored);
    }

    /** The same for stage two, whose members are indices into the stage-one sub-skill list. */
    static List<List<Integer>> restoreTerminalOrder(List<List<Integer>> groups, List<Integer> order) {
        List<List<Integer>> restored = new ArrayList<>(groups.size());
        for (List<Integer> group : groups) {
            List<Integer> members = new ArrayList<>(group.size());
            for (Integer member : group) {
                members.add(order.get(member));
            }
            members.sort(Integer::compareTo);
            restored.add(List.copyOf(members));
        }
        restored.sort(Comparator.comparingInt(List::getFirst));
        return List.copyOf(restored);
    }

    private static long seedOf(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::text).toList().hashCode();
    }

    /** Seeded from the elected texts, so the two stages of one course shuffle independently. */
    private static long seedOf(List<SubSkillGroup> subSkills, List<Candidate> candidates) {
        return subSkills.stream()
                .map(group -> candidates.get(group.representative()).text())
                .toList()
                .hashCode() * 31L + subSkills.size();
    }

    /**
     * Joins both partitions into the tree. Sub-skills sort by their earliest source outcome; terminal
     * skills sort by the MEDIAN of the outcomes beneath them, so one stray early outcome cannot drag
     * a late chapter to the top of the course.
     */
    static List<PlannedSkill> assemble(List<TerminalGroup> terminals, List<SubSkillGroup> subSkills) {
        List<PlannedSkill> plan = new ArrayList<>(terminals.size());
        for (TerminalGroup terminal : terminals) {
            List<PlannedSubSkill> children = terminal.subSkills().stream()
                    .map(subSkills::get)
                    .map(group -> new PlannedSubSkill(group.representative(), group.supporting()))
                    .sorted(Comparator.comparingInt(child -> child.supporting().getFirst()))
                    .toList();
            plan.add(new PlannedSkill(terminal.text(), terminal.shortLabel(), children));
        }
        plan.sort(Comparator.comparingInt(CompactTaxonomySynthesizer::medianSupporting));
        return List.copyOf(plan);
    }

    /** The median source position of everything under a terminal skill. */
    static int medianSupporting(PlannedSkill skill) {
        List<Integer> supporting = skill.subSkills().stream()
                .flatMap(subSkill -> subSkill.supporting().stream())
                .sorted()
                .toList();
        return supporting.isEmpty() ? Integer.MAX_VALUE : supporting.get(supporting.size() / 2);
    }

    /**
     * Stage one is pure structure, so there is no wording rule to apply here at all: the visible
     * text of every sub-skill is an extracted outcome that already passed the wording invariant when
     * it was extracted.
     */
    static SubSkillSelection validateSubSkillPartition(SubSkillPartition partition, int candidateCount,
                                                       String languageName) {
        return validateSubSkillPartition(partition, candidateCount, languageName, true);
    }

    static SubSkillSelection validateSubSkillPartition(SubSkillPartition partition, int candidateCount,
                                                       String languageName, boolean setAsideAllowed) {
        if (partition == null || partition.subSkills().isEmpty()) {
            throw new IllegalArgumentException("Sub-skill selection returned no sub-skills");
        }
        int min = minAcceptedSubSkills(candidateCount);
        int max = maxSubSkills(candidateCount);
        if (partition.subSkills().size() < min || partition.subSkills().size() > max) {
            throw new IllegalArgumentException("Sub-skill selection returned " + partition.subSkills().size()
                    + " sub-skills for " + candidateCount + " outcomes; it must hold between "
                    + min + " and " + max);
        }
        Set<Integer> covered = new HashSet<>();
        List<SubSkillGroup> groups = new ArrayList<>(partition.subSkills().size());
        for (SubSkillGroup group : partition.subSkills()) {
            if (group == null) {
                throw new IllegalArgumentException("Every sub-skill must name a group of outcomes");
            }
            List<Integer> members = exactPartitionMembers(
                    group.supporting(), candidateCount, covered, "source outcome");
            if (!members.contains(group.representative())) {
                throw new IllegalArgumentException("The elected representative ["
                        + group.representative() + "] must be one of its own group's outcomes");
            }
            groups.add(new SubSkillGroup(group.representative(), members));
        }
        int selected = covered.size();
        // Set-aside indices go through the SAME accounting as grouped ones, so an outcome can never
        // be both grouped and set aside, and can never quietly disappear from the response.
        List<Integer> setAside = exactPartitionMembers(
                partition.setAside(), candidateCount, covered, "set-aside outcome", true);
        // A model asked to partition may still volunteer a residue, because the response schema can
        // carry one. Rejecting it here — after the same accounting — is what makes the policy a
        // guarantee rather than a request, and the retry carries the reason.
        if (!setAsideAllowed && !setAside.isEmpty()) {
            throw new IllegalArgumentException("Sub-skill selection set aside " + setAside.size()
                    + " source outcome(s), but setting outcomes aside is disabled; every outcome "
                    + "must be grouped under a sub-skill");
        }
        if (covered.size() != candidateCount) {
            throw new IllegalArgumentException("Sub-skill selection left "
                    + (candidateCount - covered.size()) + " source outcome(s) unaccounted for; every "
                    + "outcome must be either grouped under a sub-skill or listed in setAside");
        }
        if (selected < candidateCount * MIN_SELECTED_SHARE) {
            throw new IllegalArgumentException("Sub-skill selection set aside " + setAside.size()
                    + " of " + candidateCount + " source outcomes; at least half must become sub-skills");
        }
        groups.sort(Comparator.comparingInt(group -> group.supporting().getFirst()));
        return new SubSkillSelection(groups, setAside);
    }

    /** Stage 2a carries no text at all, so structure is the only thing there is to check. */
    static List<List<Integer>> validateTerminalGrouping(TerminalGrouping grouping, int subSkillCount) {
        if (grouping == null || grouping.skills().isEmpty()) {
            throw new IllegalArgumentException("Terminal grouping returned no skills");
        }
        int min = minTerminals(subSkillCount);
        int max = maxTerminals(subSkillCount);
        if (grouping.skills().size() < min || grouping.skills().size() > max) {
            throw new IllegalArgumentException("Terminal grouping returned " + grouping.skills().size()
                    + " terminal skills for " + subSkillCount + " sub-skills; it must hold between "
                    + min + " and " + max);
        }
        Set<Integer> covered = new HashSet<>();
        List<List<Integer>> groups = new ArrayList<>(grouping.skills().size());
        for (TerminalMembers terminal : grouping.skills()) {
            if (terminal == null) {
                throw new IllegalArgumentException("Every terminal skill must name its sub-skills");
            }
            List<Integer> members = exactPartitionMembers(
                    terminal.subSkills(), subSkillCount, covered, "sub-skill");
            if (members.size() > MAX_SUB_SKILLS_PER_SKILL) {
                throw new IllegalArgumentException(
                        "A terminal skill must not hold more than " + MAX_SUB_SKILLS_PER_SKILL + " sub-skills");
            }
            // Enforced rather than merely requested, because it is the only thing the terminal count
            // range now protects. A one-sub-skill terminal is that sub-skill wearing a second name.
            // Skipped for a course with a single sub-skill, where there is nothing to pair it with.
            if (subSkillCount >= MIN_SUB_SKILLS_PER_SKILL && members.size() < MIN_SUB_SKILLS_PER_SKILL) {
                throw new IllegalArgumentException("A terminal skill must hold at least "
                        + MIN_SUB_SKILLS_PER_SKILL + " sub-skills; one holds " + members.size()
                        + ". Fold it into a related capability rather than returning it alone");
            }
            groups.add(members);
        }
        if (covered.size() != subSkillCount) {
            throw new IllegalArgumentException(
                    "Terminal grouping left " + (subSkillCount - covered.size()) + " sub-skill(s) unplaced");
        }
        groups.sort(Comparator.comparingInt(List::getFirst));
        return List.copyOf(groups);
    }

    /**
     * Stage 2b, where the wording rules finally bite. Beyond the shared invariant, a name must earn
     * its place twice over: it has to be ANCHORED — at least one topic word it uses must stem from
     * the outcomes it covers, so the label is drawn from the material rather than supplied — and it
     * has to be DISCRIMINATING, meaning no two siblings may name the same object and differ only in
     * their action. The second failure was observed directly (one run named two of five terminals
     * after the course's own subject), and neither is visible to a rule that reads one name alone.
     *
     * <p>A third rule was tried and REMOVED: requiring a term the other groups do not use. It did
     * stop course-level labels, but it drove the model to the opposite failure — naming a group
     * after whatever rare topic one of its members mentioned ("Anwenden von Computeralgebra" over a
     * group of four, "Verstehen von Visualisierung" over another). Measured on a real tree, most
     * labels are anchored in only one or two of their three to five sub-skills, because the GROUPS
     * are heterogeneous: no name can be both typical of such a group and unique to it, so a naming
     * rule can only choose which way it fails. That makes it a grouping problem, and it is not
     * fixable here.
     */
    static List<TerminalGroup> validateTerminalNames(TerminalNaming naming, List<List<Integer>> groups,
                                                     List<SubSkillGroup> subSkills,
                                                     List<Candidate> candidates, String languageName) {
        if (naming == null || naming.skills().size() != groups.size()) {
            throw new IllegalArgumentException("Naming must return exactly one name per group, in group "
                    + "order; " + groups.size() + " group(s) were listed but "
                    + (naming == null ? 0 : naming.skills().size()) + " name(s) came back");
        }
        List<Set<String>> vocabularies = new ArrayList<>(groups.size());
        for (List<Integer> group : groups) {
            vocabularies.add(groupVocabulary(group, subSkills, candidates));
        }
        List<TerminalGroup> terminals = new ArrayList<>(groups.size());
        List<Set<String>> objects = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            TerminalName name = naming.skills().get(index);
            if (name == null) {
                throw new IllegalArgumentException("Every terminal skill must have non-blank text");
            }
            OutcomeWording.validate(name.text(), name.shortLabel(), languageName, "Every terminal skill");
            int words = name.text().strip().split("\\s+").length;
            if (words > MAX_TERMINAL_TEXT_WORDS) {
                throw new IllegalArgumentException("The terminal skill \"" + name.shortLabel() + "\" is "
                        + words + " words long, so it summarises its group instead of naming it; state the "
                        + "one capability the group builds in at most " + MAX_TERMINAL_TEXT_WORDS
                        + " words and leave the detail to the sub-skills listed beneath it");
            }
            Set<String> object = objectTokens(name.shortLabel(), name.text());
            if (object.isEmpty()) {
                throw new IllegalArgumentException("The shortLabel \"" + name.shortLabel() + "\" names only "
                        + "an action; it must also name what that action is carried out on");
            }
            Set<String> vocabulary = vocabularies.get(index);
            if (object.stream().noneMatch(token -> anchored(token, vocabulary))) {
                throw new IllegalArgumentException("The shortLabel \"" + name.shortLabel() + "\" uses no "
                        + "term from the outcomes it covers; name the group from its own contents");
            }
            objects.add(object);
            terminals.add(new TerminalGroup(name.text().strip(), name.shortLabel().strip(),
                    groups.get(index)));
        }
        for (int left = 0; left < objects.size(); left++) {
            for (int right = left + 1; right < objects.size(); right++) {
                if (objects.get(left).equals(objects.get(right))) {
                    throw new IllegalArgumentException("The terminal skills \""
                            + terminals.get(left).shortLabel() + "\" and \"" + terminals.get(right).shortLabel()
                            + "\" name the same thing and differ only in their action; name what "
                            + "distinguishes each group");
                }
            }
        }
        return List.copyOf(terminals);
    }

    /** Every word the outcomes under one group actually use. */
    private static Set<String> groupVocabulary(List<Integer> members, List<SubSkillGroup> subSkills,
                                               List<Candidate> candidates) {
        Set<String> vocabulary = new HashSet<>();
        for (Integer member : members) {
            SubSkillGroup group = subSkills.get(member);
            for (Integer outcome : group.supporting()) {
                vocabulary.addAll(significantTokens(candidates.get(outcome).text()));
            }
        }
        return vocabulary;
    }

    /**
     * The label's topic words: everything significant except the action it shares with its text.
     *
     * <p>Which word is the action cannot be assumed from position — German writes both "Analysieren
     * von Singularitäten" and "Potenzreihen analysieren" — but the wording invariant already
     * requires the label to reuse the text's action, and the text always opens with it. So the
     * action is identified from the text and removed from the label, whatever order it appears in.
     */
    static Set<String> objectTokens(String shortLabel, String text) {
        String action = significantPrefix(text.strip().split("\\s+", 2)[0]);
        Set<String> tokens = new LinkedHashSet<>(significantTokens(shortLabel));
        tokens.removeIf(token -> action != null && sharesStem(token, action));
        return tokens;
    }

    /** Words of at least {@value #SIGNIFICANT_TOKEN_LENGTH} letters, lowercased. */
    static Set<String> significantTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null) {
            return tokens;
        }
        for (String raw : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= SIGNIFICANT_TOKEN_LENGTH) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private static String significantPrefix(String word) {
        String cleaned = word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return cleaned.length() >= SIGNIFICANT_TOKEN_LENGTH ? cleaned : null;
    }

    /**
     * Anchoring compares stems, not words. German inflects and compounds freely — the outcomes say
     * "Integralen" where a good label says "Integralmethoden", and "Abbildungen" where it says
     * "Abbildung" — so demanding the label's words appear verbatim would reject exactly the
     * abstraction the name is supposed to make. A shared prefix of {@value #SIGNIFICANT_TOKEN_LENGTH}
     * is the loosest rule that still separates a term drawn from the material from one supplied
     * elsewhere. It deliberately does NOT try to catch a coined near-word: "Konstruktieren" shares
     * its stem with "Konstruieren", and no cheap lexical rule can tell a misspelling from a
     * legitimate compound.
     */
    static boolean anchored(String token, Set<String> vocabulary) {
        return vocabulary.stream().anyMatch(known -> sharesStem(token, known));
    }

    private static boolean sharesStem(String left, String right) {
        int shared = 0;
        int limit = Math.min(left.length(), right.length());
        while (shared < limit && left.charAt(shared) == right.charAt(shared)) {
            shared++;
        }
        return shared >= SIGNIFICANT_TOKEN_LENGTH;
    }

    /**
     * Reads one node's member indices, rejecting anything that would break the partition: a blank
     * group, an out-of-range index, or an index some earlier node already claimed.
     */
    private static List<Integer> exactPartitionMembers(List<Integer> members, int memberCount,
                                                       Set<Integer> covered, String subject) {
        return exactPartitionMembers(members, memberCount, covered, subject, false);
    }

    private static List<Integer> exactPartitionMembers(List<Integer> members, int memberCount,
                                                       Set<Integer> covered, String subject,
                                                       boolean mayBeEmpty) {
        if (members.isEmpty() && !mayBeEmpty) {
            throw new IllegalArgumentException("Every node needs at least one " + subject);
        }
        List<Integer> result = new ArrayList<>(members.size());
        for (Integer index : members) {
            if (index == null || index < 0 || index >= memberCount || !covered.add(index)) {
                throw new IllegalArgumentException(
                        "The " + subject + " indices must form an exact partition; [" + index + "] does not");
            }
            result.add(index);
        }
        result.sort(Integer::compareTo);
        return List.copyOf(result);
    }

    static int minSubSkills(int candidateCount) {
        return Math.max(1, Math.min(MIN_FULL_COURSE_SUB_SKILLS,
                ceilDiv(candidateCount, OUTCOMES_PER_SUB_SKILL)));
    }

    /**
     * The count actually enforced, which is deliberately looser than what the prompt requests. The
     * requested range is an editorial preference; rejecting a coherent twelve-sub-skill tree for a
     * course that genuinely has twelve capabilities would just burn a retry and then fail the stage.
     * What must hold is that the tree is not degenerate and still fits under the terminal width cap.
     */
    static int minAcceptedSubSkills(int candidateCount) {
        return Math.max(1, Math.min(MIN_SUB_SKILLS, candidateCount));
    }

    /** Bounded by {@link #MAX_SUB_SKILLS} so stage two can always fit them within the width cap. */
    static int maxSubSkills(int candidateCount) {
        return Math.max(minSubSkills(candidateCount), Math.min(MAX_SUB_SKILLS, candidateCount));
    }

    /** The fewest terminals the sub-skills fit into at the width cap. */
    static int minTerminals(int subSkillCount) {
        return Math.max(1, ceilDiv(subSkillCount, MAX_SUB_SKILLS_PER_SKILL));
    }

    /**
     * The most terminals that still leave every one of them at least
     * {@value #MIN_SUB_SKILLS_PER_SKILL} sub-skills. How many the course actually has is the model's
     * call inside this range, not a number configured here.
     */
    static int maxTerminals(int subSkillCount) {
        return Math.max(minTerminals(subSkillCount),
                Math.min(MAX_SKILLS, subSkillCount / MIN_SUB_SKILLS_PER_SKILL));
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private <T> T call(String prompt, String languageName, String model, double temperature,
                       ParameterizedTypeReference<T> type) {
        ChatOptions.Builder options = ChatOptions.builder().temperature(temperature);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        return chatClient.prompt()
                .system(LanguagePrompt.systemInstruction(languageName))
                .options(options.build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(type));
    }

    private static String effectiveModel(String override, String configured) {
        return override == null || override.isBlank() ? configured : override;
    }

    private static String numbered(List<Candidate> candidates) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            result.append('[').append(index).append("] (")
                    .append(candidate.bloomLevel() == null ? "?" : candidate.bloomLevel())
                    .append(") {").append(candidate.session() == null ? "?" : candidate.session())
                    .append("} ").append(candidate.text()).append('\n');
        }
        return result.toString();
    }

    /** Stage 2b sees each group whole: every sub-skill it holds, so the name can come from them. */
    private static String renderGroups(List<List<Integer>> groups, List<SubSkillGroup> subSkills,
                                       List<Candidate> candidates) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < groups.size(); index++) {
            result.append("Group ").append(index).append(":\n");
            for (Integer member : groups.get(index)) {
                result.append("  - ")
                        .append(candidates.get(subSkills.get(member).representative()).text())
                        .append('\n');
            }
        }
        return result.toString();
    }

    /** Stage 2a sees each group through its elected outcome, which is the node's visible name. */
    private static String renderSubSkills(List<SubSkillGroup> subSkills, List<Candidate> candidates) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < subSkills.size(); index++) {
            SubSkillGroup group = subSkills.get(index);
            result.append('[').append(index).append("] ")
                    .append(candidates.get(group.representative()).text())
                    .append(" (").append(group.supporting().size()).append(" source outcomes)\n");
        }
        return result.toString();
    }
}
