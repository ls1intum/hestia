package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentOrder;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService.FigureDescription;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.ResolvedSource;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SessionExtraction;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SessionUnit;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.UnitExtraction;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.UnitGoal;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.Window;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalCreationProvenance;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalStatus;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Finds a topic in the course's slides when an instructor adds it by name.
 *
 * <p>Three steps, each its own request. {@link #planPages} searches every page's text and figure
 * description for a few model-written terms and proposes runs of pages. {@link #search} reads the
 * pages the instructor settled on with the ordinary session extraction, marks outcomes the course
 * already has on those pages, and groups the rest into skills. Nothing is written: the proposal is
 * held in memory for {@link #PROPOSAL_TTL}. {@link #accept} then persists the topic with the
 * sub-skills the instructor kept, each with its quote and knowledge, exactly like extracted goals.
 */
@Service
public class TopicSearchService {

    private static final Logger log = LoggerFactory.getLogger(TopicSearchService.class);

    static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);

    /**
     * Below this many sub-skills the structure call is skipped. Two or three outcomes from a short
     * page range come back as one skill holding all of them, which structuring dissolves anyway.
     */
    static final int MIN_SUB_SKILLS_TO_STRUCTURE = 4;

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentSectionRepository documentSectionRepository;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final UnitExtractor unitExtractor;
    private final TopicSearchSynthesizer topicSearchSynthesizer;
    private final TopicTreeSynthesizer topicTreeSynthesizer;
    private final TaxonomyService taxonomyService;
    private final int parallelism;
    private final int maxPages;
    private final Clock clock;
    private final Map<UUID, Proposal> proposals = new ConcurrentHashMap<>();

    @Autowired
    public TopicSearchService(CourseRepository courseRepository,
                              DocumentRepository documentRepository,
                              DocumentSectionRepository documentSectionRepository,
                              PageDescriptionRepository pageDescriptionRepository,
                              LearningGoalRepository goalRepository,
                              GoalSourceRepository goalSourceRepository,
                              GoalRelationshipRepository goalRelationshipRepository,
                              HierarchyNodeRepository hierarchyNodeRepository,
                              UnitExtractor unitExtractor,
                              TopicSearchSynthesizer topicSearchSynthesizer,
                              TopicTreeSynthesizer topicTreeSynthesizer,
                              TaxonomyService taxonomyService,
                              @Value("${hestia.extraction.parallelism:8}") int parallelism,
                              @Value("${hestia.topic-search.max-pages:80}") int maxPages) {
        this(courseRepository, documentRepository, documentSectionRepository, pageDescriptionRepository,
                goalRepository, goalSourceRepository, goalRelationshipRepository, hierarchyNodeRepository,
                unitExtractor, topicSearchSynthesizer, topicTreeSynthesizer, taxonomyService, parallelism,
                maxPages, Clock.systemUTC());
    }

    TopicSearchService(CourseRepository courseRepository,
                       DocumentRepository documentRepository,
                       DocumentSectionRepository documentSectionRepository,
                       PageDescriptionRepository pageDescriptionRepository,
                       LearningGoalRepository goalRepository,
                       GoalSourceRepository goalSourceRepository,
                       GoalRelationshipRepository goalRelationshipRepository,
                       HierarchyNodeRepository hierarchyNodeRepository,
                       UnitExtractor unitExtractor,
                       TopicSearchSynthesizer topicSearchSynthesizer,
                       TopicTreeSynthesizer topicTreeSynthesizer,
                       TaxonomyService taxonomyService,
                       int parallelism,
                       int maxPages,
                       Clock clock) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.unitExtractor = unitExtractor;
        this.topicSearchSynthesizer = topicSearchSynthesizer;
        this.topicTreeSynthesizer = topicTreeSynthesizer;
        this.taxonomyService = taxonomyService;
        this.parallelism = parallelism;
        this.maxPages = maxPages;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------
    // Page picker
    // ---------------------------------------------------------------------------------------------

    /** Proposes the page runs that mention the topic, labelled by bookmark or file name. */
    @Transactional(readOnly = true)
    public PagePlan planPages(Long courseId, String topicText, String modelOverride) {
        Course course = course(courseId);
        String topic = requireTopic(topicText);
        List<Document> documents = documentRepository.findByCourseId(courseId).stream()
                .sorted(DocumentOrder.comparator())
                .toList();

        // The topic itself is always searched, so a failed terms call still finds its pages.
        Set<String> terms = new LinkedHashSet<>();
        terms.add(topic);
        try {
            terms.addAll(topicSearchSynthesizer.searchTerms(topic, languageName(course, documents), modelOverride));
        } catch (RuntimeException ex) {
            log.warn("Search terms for topic '{}' failed, searching for the topic alone: {}", topic, ex.getMessage());
        }
        List<Pattern> needles = terms.stream()
                .map(TopicSearchService::normalize)
                .filter(term -> !term.isEmpty())
                .distinct()
                .map(TopicSearchService::termPattern)
                .toList();

        Map<Long, Map<Integer, String>> descriptions = teachingDescriptions(documents);
        List<PageRun> runs = new ArrayList<>();
        List<PageRun> singlePages = new ArrayList<>();
        for (Document document : documents) {
            int[] offsets = document.getPageOffsets();
            String text = document.getRawText();
            if (offsets == null || offsets.length < 2 || text == null) {
                continue;
            }
            Map<Integer, String> pageDescriptions = descriptions.getOrDefault(document.getId(), Map.of());
            List<Integer> hits = new ArrayList<>();
            for (int page = 1; page < offsets.length; page++) {
                String pageText = normalize(pageText(text, offsets, page) + " "
                        + pageDescriptions.getOrDefault(page, ""));
                if (needles.stream().anyMatch(needle -> needle.matcher(pageText).find())) {
                    hits.add(page);
                }
            }
            if (hits.isEmpty()) {
                continue;
            }
            List<DocumentSection> sections = documentSectionRepository.findByDocumentIdOrderByOrdinal(document.getId());
            for (int[] run : mergeRuns(hits)) {
                runs.add(new PageRun(document.getId(), rangeLabel(document, sections, run[0], run[1]), run[0], run[1]));
            }
            for (int page : hits) {
                singlePages.add(new PageRun(document.getId(), rangeLabel(document, sections, page, page), page, page));
            }
        }
        // A topic taught on scattered single slides forms no run. Offering its pages beats offering nothing.
        if (runs.isEmpty()) {
            runs = singlePages;
        }
        int totalPages = runs.stream().mapToInt(run -> run.endPage() - run.startPage() + 1).sum();
        return new PagePlan(List.copyOf(terms), runs, totalPages, maxPages);
    }

    /**
     * Merges ascending page hits into runs, bridging a gap of at most one page. A run of a single
     * page is dropped: a stray mention is rarely where a topic is taught.
     */
    static List<int[]> mergeRuns(List<Integer> hits) {
        List<int[]> runs = new ArrayList<>();
        int[] current = null;
        for (int page : hits) {
            if (current != null && page <= current[1] + 2) {
                current[1] = page;
                continue;
            }
            if (current != null && current[1] > current[0]) {
                runs.add(current);
            }
            current = new int[]{page, page};
        }
        if (current != null && current[1] > current[0]) {
            runs.add(current);
        }
        return runs;
    }

    // ---------------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------------

    /** Reads the chosen pages and holds the resulting proposal; nothing is persisted. */
    @Transactional(readOnly = true)
    public ProposalResponse search(Long courseId, String topicText, List<PageRange> ranges, String modelOverride) {
        Course course = course(courseId);
        String topic = requireTopic(topicText);
        List<Document> courseDocuments = documentRepository.findByCourseId(courseId);
        Map<Long, Document> documentsById = courseDocuments.stream()
                .collect(Collectors.toMap(Document::getId, document -> document));
        int pagesRead = validateRanges(ranges, documentsById);
        String dominantLanguage = ExtractionRunner.dominantLanguage(courseDocuments);
        String languageName = languageName(course, courseDocuments);

        List<SessionUnit<PageRange>> units = new ArrayList<>();
        Map<Long, List<FigureDescription>> figuresByDocument = new HashMap<>();
        for (PageRange range : ranges) {
            Document document = documentsById.get(range.documentId());
            String text = document.getRawText();
            int[] offsets = document.getPageOffsets();
            int start = Math.min(offsets[range.startPage() - 1], text.length());
            int end = Math.max(start, Math.min(offsets[range.endPage()], text.length()));
            String label = rangeLabel(document, documentSectionRepository.findByDocumentIdOrderByOrdinal(
                    document.getId()), range.startPage(), range.endPage());
            List<FigureDescription> figures = course.isFiguresEnabled()
                    ? figuresByDocument.computeIfAbsent(document.getId(), id -> figureDescriptions(document))
                    : List.of();
            for (Window window : unitExtractor.windows(label, document, text, start, end,
                    range.startPage(), range.endPage())) {
                String unitText = text.substring(window.start(), Math.min(window.end(), text.length()));
                if (!unitText.isBlank()) {
                    units.add(new SessionUnit<>(range, document, window, label, unitText,
                            UnitExtractor.figureDescriptionsFor(document, window, figures)));
                }
            }
        }
        if (units.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The chosen pages contain no text.");
        }

        List<Candidate> candidates = candidates(unitExtractor.assemble(
                extractAll(course, units, dominantLanguage, modelOverride)));
        Map<Integer, DuplicateOf> duplicates = duplicates(courseId, candidates, ranges, modelOverride);
        List<Integer> fresh = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            if (!duplicates.containsKey(index)) {
                fresh.add(index);
            }
        }
        Structure structure = structure(topic, candidates, fresh, languageName, modelOverride);

        Instant expiresAt = clock.instant().plus(PROPOSAL_TTL);
        Proposal proposal = new Proposal(UUID.randomUUID(), courseId, topic, expiresAt, candidates,
                structure.skills(), structure.direct(), duplicates.keySet());
        sweep();
        proposals.put(proposal.id(), proposal);
        log.info("Topic search for '{}' in course {} read {} pages: {} sub-skills, {} already in the course, {} skills",
                topic, courseId, pagesRead, candidates.size(), duplicates.size(), structure.skills().size());
        return response(proposal, pagesRead, documentsById, duplicates);
    }

    private int validateRanges(List<PageRange> ranges, Map<Long, Document> documentsById) {
        if (ranges == null || ranges.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one page range.");
        }
        int pages = 0;
        for (PageRange range : ranges) {
            Document document = range == null ? null : documentsById.get(range.documentId());
            if (document == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A page range names a document outside this course.");
            }
            int[] offsets = document.getPageOffsets();
            int pageCount = offsets == null || document.getRawText() == null ? 0 : offsets.length - 1;
            if (range.startPage() < 1 || range.endPage() < range.startPage() || range.endPage() > pageCount) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Pages " + range.startPage() + "–" + range.endPage() + " are outside "
                                + displayName(document) + " (" + pageCount + " pages).");
            }
            pages += range.endPage() - range.startPage() + 1;
        }
        if (pages > maxPages) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The chosen ranges cover " + pages + " pages; at most " + maxPages + " can be read at once.");
        }
        return pages;
    }

    private List<SessionExtraction<PageRange>> extractAll(Course course, List<SessionUnit<PageRange>> units,
                                                          String dominantLanguage, String modelOverride) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, units.size())));
        try {
            List<CompletableFuture<SessionExtraction<PageRange>>> futures = units.stream()
                    .map(unit -> CompletableFuture.supplyAsync(
                            () -> unitExtractor.extract(course, unit, dominantLanguage, modelOverride), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("Topic search extraction failed", cause);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The chosen pages could not be read: " + cause.getMessage(), cause);
        } finally {
            executor.shutdown();
        }
    }

    /** Every extracted skill becomes a sub-skill candidate carrying its resolved quote and knowledge. */
    private List<Candidate> candidates(List<UnitExtraction<PageRange>> extractions) {
        List<Candidate> candidates = new ArrayList<>();
        Map<ExtractedGoal, Candidate> bySkill = new IdentityHashMap<>();
        for (UnitExtraction<PageRange> extraction : extractions) {
            SessionUnit<PageRange> unit = extraction.session();
            Document document = unit.document();
            for (UnitGoal goal : extraction.goals()) {
                ResolvedSource source = unitExtractor.resolve(document, unit.window(), goal.sourceLineSelection(),
                        unit.figures(), goal.extracted().sourceSnippet());
                String snippet = blankToNull(UnitExtractor.snippetOf(document, source));
                Integer page = source.resolution().page() != null
                        ? source.resolution().page() : unit.window().startPage();
                if (goal.role() == GoalRole.KNOWLEDGE) {
                    Candidate parent = bySkill.get(goal.parentSkill());
                    if (parent != null) {
                        parent.knowledge().add(new KnowledgeCandidate(goal, source));
                    }
                    continue;
                }
                Candidate candidate = new Candidate(document.getId(), goal, source, snippet, page, new ArrayList<>());
                bySkill.put(goal.extracted(), candidate);
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * Marks candidates the course already has: extracted sub-skills whose quotes lie on the chosen
     * pages are compared in one call. A failed call marks nothing, so the instructor still decides.
     */
    private Map<Integer, DuplicateOf> duplicates(Long courseId, List<Candidate> candidates, List<PageRange> ranges,
                                                 String modelOverride) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<LearningGoal> skills = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.EXTRACTED))
                .stream()
                .filter(goal -> goal.getRole() == GoalRole.SKILL)
                .toList();
        if (skills.isEmpty()) {
            return Map.of();
        }
        Set<Long> onPages = goalSourceRepository.findByGoalIdIn(skills.stream().map(LearningGoal::getId).toList())
                .stream()
                .filter(source -> source.getPage() != null && ranges.stream().anyMatch(range ->
                        range.documentId().equals(source.getDocument().getId())
                                && source.getPage() >= range.startPage() && source.getPage() <= range.endPage()))
                .map(source -> source.getGoal().getId())
                .collect(Collectors.toSet());
        List<LearningGoal> existing = skills.stream()
                .filter(goal -> onPages.contains(goal.getId()))
                .sorted(Comparator.comparing(LearningGoal::getId))
                .toList();
        if (existing.isEmpty()) {
            return Map.of();
        }
        List<Integer> answer;
        try {
            answer = topicSearchSynthesizer.duplicates(
                    candidates.stream().map(candidate -> candidate.goal().extracted().text()).toList(),
                    existing.stream().map(LearningGoal::getText).toList(), modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Duplicate check against {} existing sub-skills failed, marking none: {}",
                    existing.size(), ex.getMessage());
            return Map.of();
        }
        Map<Integer, DuplicateOf> result = new LinkedHashMap<>();
        for (int index = 0; index < answer.size(); index++) {
            if (answer.get(index) >= 0) {
                LearningGoal match = existing.get(answer.get(index));
                result.put(index, new DuplicateOf(match.getId(), match.getText(), topicTextOf(match)));
            }
        }
        return result;
    }

    /** The text of the nearest topic above a goal in the competency tree, or null outside the tree. */
    private String topicTextOf(LearningGoal goal) {
        Deque<LearningGoal> pending = new ArrayDeque<>(List.of(goal));
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            LearningGoal current = pending.removeFirst();
            if (!visited.add(current.getId())) {
                continue;
            }
            if (current.getOrigin() == GoalOrigin.TERMINAL) {
                return current.getText();
            }
            for (GoalRelationship relationship : goalRelationshipRepository.findBySourceId(current.getId())) {
                if (relationship.getType() == RelationshipType.CONTRIBUTES_TO) {
                    pending.addLast(relationship.getTarget());
                }
            }
        }
        return null;
    }

    /** Groups the new sub-skills into named, classified skills, or hangs them all under the topic. */
    private Structure structure(String topic, List<Candidate> candidates, List<Integer> fresh, String languageName,
                                String modelOverride) {
        if (fresh.size() < MIN_SUB_SKILLS_TO_STRUCTURE) {
            return new Structure(List.of(), fresh);
        }
        TopicTreeSynthesizer.PlannedTopic planned;
        try {
            planned = topicTreeSynthesizer.structure(topic,
                    fresh.stream().map(index -> candidates.get(index).goal().extracted().text()).toList(),
                    languageName, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Structuring topic '{}' failed, hanging its sub-skills directly beneath it: {}",
                    topic, ex.getMessage());
            return new Structure(List.of(), fresh);
        }
        List<String> names = planned.capabilities().stream().map(TopicTreeSynthesizer.PlannedCapability::name).toList();
        List<String> shortLabels = topicTreeSynthesizer.shortLabels(names, languageName, modelOverride);
        List<TaxonomyClassification> classifications = classify(names, modelOverride);
        List<SkillPlan> skills = new ArrayList<>();
        for (int index = 0; index < planned.capabilities().size(); index++) {
            List<Integer> members = planned.capabilities().get(index).outcomes().stream().map(fresh::get).toList();
            skills.add(new SkillPlan(names.get(index), shortLabels.get(index), classifications.get(index), members));
        }
        return new Structure(skills, planned.direct().stream().map(fresh::get).toList());
    }

    private List<TaxonomyClassification> classify(List<String> names, String modelOverride) {
        if (names.isEmpty()) {
            return List.of();
        }
        try {
            List<TaxonomyClassification> result = taxonomyService.classifyBatch(names, modelOverride);
            if (result != null && result.size() == names.size()) {
                return result;
            }
        } catch (RuntimeException ex) {
            log.warn("Classifying topic search skills failed, flooring them at their members: {}", ex.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(names.size(), (TaxonomyClassification) null));
    }

    private ProposalResponse response(Proposal proposal, int pagesRead, Map<Long, Document> documentsById,
                                      Map<Integer, DuplicateOf> duplicates) {
        List<Candidate> candidates = proposal.candidates();
        List<ProposedSkill> skills = new ArrayList<>();
        for (int index = 0; index < proposal.skills().size(); index++) {
            SkillPlan skill = proposal.skills().get(index);
            TaxonomyClassification levels = floorAtMembers(skill.classification(),
                    skill.members().stream().map(candidates::get).toList());
            skills.add(new ProposedSkill("k" + index, skill.name(), skill.shortLabel(),
                    levels == null ? null : levels.bloom(), levels == null ? null : levels.solo(),
                    skill.members().stream().map(member -> proposed(member, candidates, documentsById, null)).toList()));
        }
        List<ProposedSubSkill> direct = proposal.direct().stream()
                .map(member -> proposed(member, candidates, documentsById, null))
                .toList();
        List<ProposedSubSkill> duplicated = duplicates.entrySet().stream()
                .map(entry -> proposed(entry.getKey(), candidates, documentsById, entry.getValue()))
                .toList();
        return new ProposalResponse(proposal.id(), OffsetDateTime.ofInstant(proposal.expiresAt(), ZoneOffset.UTC),
                pagesRead, skills, direct, duplicated);
    }

    private static ProposedSubSkill proposed(int index, List<Candidate> candidates, Map<Long, Document> documentsById,
                                             DuplicateOf duplicateOf) {
        Candidate candidate = candidates.get(index);
        ExtractedGoal extracted = candidate.goal().extracted();
        TaxonomyClassification levels = candidate.goal().classification();
        Document document = documentsById.get(candidate.documentId());
        return new ProposedSubSkill("s" + index, extracted.text(), extracted.shortLabel(),
                levels == null ? null : levels.bloom(), levels == null ? null : levels.solo(),
                new ProposedSource(candidate.documentId(), displayName(document), candidate.page(),
                        candidate.snippet(), candidate.source().evidenceKind()),
                candidate.knowledge().stream()
                        .map(knowledge -> new ProposedKnowledge(knowledge.goal().extracted().text(),
                                knowledge.goal().extracted().shortLabel()))
                        .toList(),
                duplicateOf);
    }

    // ---------------------------------------------------------------------------------------------
    // Accept
    // ---------------------------------------------------------------------------------------------

    /**
     * Persists the topic with the ticked sub-skills. A skill left with fewer than two ticked members
     * is not created, and its remaining member hangs directly under the topic, as tree synthesis
     * does with a one-member group.
     *
     * @return the created topic.
     */
    @Transactional
    public LearningGoal accept(Long courseId, UUID proposalId, List<String> subSkillKeys) {
        sweep();
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null || !proposal.courseId().equals(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This search has expired or does not exist. Search the slides again.");
        }
        Course course = course(courseId);
        Set<Integer> ticked = new HashSet<>();
        for (String key : subSkillKeys == null ? List.<String>of() : subSkillKeys) {
            Integer index = subSkillIndex(key, proposal.candidates().size());
            if (index != null && !proposal.duplicates().contains(index)) {
                ticked.add(index);
            }
        }
        if (ticked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep at least one sub-skill.");
        }
        boolean duplicateTopic = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL))
                .stream()
                .anyMatch(goal -> goal.getText() != null && goal.getText().strip().equalsIgnoreCase(proposal.topic()));
        if (duplicateTopic) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A terminal skill with this text already exists");
        }

        LearningGoal topic = new LearningGoal(course, proposal.topic(), GoalKind.IMPLICIT);
        topic.setOrigin(GoalOrigin.TERMINAL);
        topic.setStatus(GoalStatus.PENDING);
        topic.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        topic.setHierarchyNode(competencyRoot(course));
        goalRepository.saveAndFlush(topic);

        Map<Integer, LearningGoal> subSkills = new HashMap<>();
        Map<String, HierarchyNode> nodes = new HashMap<>();
        Map<Long, Integer> orders = new HashMap<>();
        try (UnitExtractor.PdfCache pdfCache = unitExtractor.pdfCache()) {
            for (int index : ticked.stream().sorted().toList()) {
                Candidate candidate = proposal.candidates().get(index);
                Document document = documentRepository.findById(candidate.documentId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "A document this search read was deleted. Search the slides again."));
                HierarchyNode node = sessionNode(course, document, candidate.page(), nodes);
                int order = orders.computeIfAbsent(node.getId(), id -> lectureOrderFor(course, node));
                LearningGoal subSkill = extractedGoal(course, candidate.goal(), node, order);
                unitExtractor.persistSource(subSkill, document, candidate.source(), pdfCache);
                subSkills.put(index, subSkill);
                for (KnowledgeCandidate knowledge : candidate.knowledge()) {
                    LearningGoal knowledgeGoal = extractedGoal(course, knowledge.goal(), node, order);
                    unitExtractor.persistSource(knowledgeGoal, document, knowledge.source(), pdfCache);
                    link(knowledgeGoal, subSkill, RelationshipOrigin.HIERARCHY);
                }
            }
        }

        List<LearningGoal> direct = new ArrayList<>();
        for (SkillPlan plan : proposal.skills()) {
            List<LearningGoal> members = plan.members().stream()
                    .filter(ticked::contains)
                    .map(subSkills::get)
                    .toList();
            if (members.size() < 2) {
                direct.addAll(members);
                continue;
            }
            LearningGoal skill = new LearningGoal(course, plan.name(), GoalKind.IMPLICIT);
            skill.setOrigin(GoalOrigin.SYNTHESIZED);
            skill.setRole(GoalRole.SKILL);
            skill.setShortLabel(plan.shortLabel());
            skill.setLectureOrder(median(members));
            TaxonomyClassification levels = ExtractionRunner.atLeastChildLevels(plan.classification(),
                    members.stream().map(LearningGoal::getBloomLevel).filter(Objects::nonNull).toList(),
                    members.stream().map(LearningGoal::getSoloLevel).filter(Objects::nonNull).toList());
            if (levels != null) {
                skill.setBloomLevel(levels.bloom());
                skill.setSoloLevel(levels.solo());
            }
            goalRepository.saveAndFlush(skill);
            link(skill, topic, RelationshipOrigin.SYNTHESIS);
            members.forEach(member -> link(member, skill, RelationshipOrigin.SYNTHESIS));
        }
        proposal.direct().stream().filter(ticked::contains).map(subSkills::get).forEach(direct::add);
        direct.forEach(member -> link(member, topic, RelationshipOrigin.SYNTHESIS));

        topic.setLectureOrder(median(new ArrayList<>(subSkills.values())));
        goalRepository.save(topic);
        proposals.remove(proposalId);
        log.info("Created topic '{}' in course {} from a slide search with {} sub-skills",
                proposal.topic(), courseId, subSkills.size());
        return topic;
    }

    private static Integer subSkillIndex(String key, int count) {
        if (key == null || !key.startsWith("s")) {
            return null;
        }
        try {
            int index = Integer.parseInt(key.substring(1));
            return index >= 0 && index < count ? index : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LearningGoal extractedGoal(Course course, UnitGoal unitGoal, HierarchyNode node, int lectureOrder) {
        ExtractedGoal extracted = unitGoal.extracted();
        LearningGoal goal = new LearningGoal(course, extracted.text(), extracted.kind());
        goal.setShortLabel(extracted.shortLabel());
        goal.setRole(unitGoal.role());
        goal.setHierarchyNode(node);
        goal.setLectureOrder(lectureOrder);
        if (unitGoal.classification() != null) {
            goal.setBloomLevel(unitGoal.classification().bloom());
            goal.setSoloLevel(unitGoal.classification().solo());
        }
        return goalRepository.saveAndFlush(goal);
    }

    private void link(LearningGoal source, LearningGoal target, RelationshipOrigin origin) {
        goalRelationshipRepository.save(new GoalRelationship(source, target, RelationshipType.CONTRIBUTES_TO, 1.0, origin));
    }

    /**
     * The session node the goals on a page belong to: the one the extraction created for the bookmark
     * section holding that page, or for the whole file when it has no bookmarks. Created under the
     * module root, as the extraction would, when it does not exist yet.
     */
    private HierarchyNode sessionNode(Course course, Document document, Integer page, Map<String, HierarchyNode> cache) {
        String label = documentSectionRepository.findByDocumentIdOrderByOrdinal(document.getId()).stream()
                .filter(section -> page != null && covers(section, page))
                .map(DocumentSection::getTitle)
                .findFirst()
                .orElse(document.getFilename());
        return cache.computeIfAbsent(document.getId() + "\n" + label, key -> hierarchyNodeRepository
                .findByCourseId(course.getId()).stream()
                .filter(node -> node.getLevel() == HierarchyLevel.SESSION || node.getLevel() == HierarchyLevel.EXERCISE)
                .filter(node -> node.getDocument() != null && document.getId().equals(node.getDocument().getId()))
                .filter(node -> Objects.equals(label, node.getLabel()))
                .findFirst()
                .orElseGet(() -> hierarchyNodeRepository.save(new HierarchyNode(course, moduleRoot(course),
                        ExtractionRunner.levelFor(document, label), label, document))));
    }

    private HierarchyNode moduleRoot(Course course) {
        return hierarchyNodeRepository.findFirstByCourseIdAndLevelOrderByIdAsc(course.getId(), HierarchyLevel.MODULE)
                .orElseGet(() -> hierarchyNodeRepository.save(
                        new HierarchyNode(course, null, HierarchyLevel.MODULE, course.getName())));
    }

    private HierarchyNode competencyRoot(Course course) {
        return hierarchyNodeRepository.findFirstByCourseIdAndLevelOrderByIdAsc(course.getId(), HierarchyLevel.COMPETENCY)
                .orElseGet(() -> hierarchyNodeRepository.save(
                        new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies")));
    }

    /** Places new goals among the goals already on their session, or after every goal of the course. */
    private int lectureOrderFor(Course course, HierarchyNode node) {
        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        return goals.stream()
                .filter(goal -> goal.getHierarchyNode() != null && node.getId().equals(goal.getHierarchyNode().getId()))
                .map(LearningGoal::getLectureOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> goals.stream()
                        .map(LearningGoal::getLectureOrder)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(-1) + 1);
    }

    private static Integer median(List<LearningGoal> goals) {
        List<Integer> orders = goals.stream().map(LearningGoal::getLectureOrder).filter(Objects::nonNull).sorted().toList();
        return orders.isEmpty() ? null : orders.get(orders.size() / 2);
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    private void sweep() {
        Instant now = clock.instant();
        proposals.values().removeIf(proposal -> !proposal.expiresAt().isAfter(now));
    }

    private Course course(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));
    }

    private static String requireTopic(String text) {
        String topic = text == null ? "" : text.strip();
        if (topic.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic text must not be blank");
        }
        return topic;
    }

    private static String languageName(Course course, List<Document> documents) {
        return LanguageUtils.englishName(
                ExtractionRunner.resolveLanguage(course, null, ExtractionRunner.dominantLanguage(documents)));
    }

    /**
     * Matches a normalised term where a word starts. Without the boundary a short term hits inside
     * unrelated words ("rf" in "performance"); the end stays open so plurals and compounds still match.
     */
    static Pattern termPattern(String normalizedTerm) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedTerm));
    }

    /** Lower-cased with every run of whitespace, including line breaks, collapsed to one space. */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String pageText(String text, int[] offsets, int page) {
        int start = Math.min(offsets[page - 1], text.length());
        int end = Math.max(start, Math.min(offsets[page], text.length()));
        return text.substring(start, end);
    }

    /** Descriptions of the pages that teach content, by document and page. */
    private Map<Long, Map<Integer, String>> teachingDescriptions(List<Document> documents) {
        if (documents.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Integer, String>> result = new HashMap<>();
        for (PageDescription description : pageDescriptionRepository.findByDocumentIdIn(
                documents.stream().map(Document::getId).toList())) {
            if (description.isTeachesContent() && description.getDescription() != null) {
                result.computeIfAbsent(description.getDocument().getId(), id -> new HashMap<>())
                        .put(description.getPage(), description.getDescription());
            }
        }
        return result;
    }

    private List<FigureDescription> figureDescriptions(Document document) {
        return pageDescriptionRepository.findByDocumentId(document.getId()).stream()
                .filter(PageDescription::isTeachesContent)
                .sorted(Comparator.comparingInt(PageDescription::getPage))
                .map(description -> new FigureDescription(description.getPage(), description.getDescription()))
                .toList();
    }

    /**
     * The bookmark title of the section covering most of the pages, else the document's name. A run
     * often starts a page or two before its section, on the previous section's closing slides.
     */
    static String rangeLabel(Document document, List<DocumentSection> sections, int startPage, int endPage) {
        DocumentSection best = null;
        int bestOverlap = 0;
        for (DocumentSection section : sections) {
            if (section.getStartPage() == null || section.getEndPage() == null) {
                continue;
            }
            int overlap = Math.min(endPage, section.getEndPage()) - Math.max(startPage, section.getStartPage()) + 1;
            if (overlap > bestOverlap) {
                best = section;
                bestOverlap = overlap;
            }
        }
        return best != null ? best.getTitle() : displayName(document);
    }

    private static boolean covers(DocumentSection section, int page) {
        return section.getStartPage() != null && section.getEndPage() != null
                && section.getStartPage() <= page && page <= section.getEndPage();
    }

    private static String displayName(Document document) {
        if (document == null) {
            return null;
        }
        return document.getDisplayName() != null && !document.getDisplayName().isBlank()
                ? document.getDisplayName() : document.getFilename();
    }

    private static TaxonomyClassification floorAtMembers(TaxonomyClassification classified, List<Candidate> members) {
        List<BloomLevel> blooms = new ArrayList<>();
        List<SoloLevel> solos = new ArrayList<>();
        for (Candidate member : members) {
            TaxonomyClassification levels = member.goal().classification();
            if (levels != null && levels.bloom() != null) {
                blooms.add(levels.bloom());
            }
            if (levels != null && levels.solo() != null) {
                solos.add(levels.solo());
            }
        }
        return ExtractionRunner.atLeastChildLevels(classified, blooms, solos);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ---------------------------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------------------------

    /** A page range of one document, both ends inclusive and 1-based. */
    public record PageRange(Long documentId, int startPage, int endPage) {
    }

    /** A proposed run of pages mentioning the topic. */
    public record PageRun(Long documentId, String label, int startPage, int endPage) {
    }

    /**
     * @param terms      what was searched for, the topic first.
     * @param totalPages how many pages the proposed runs cover together.
     * @param maxPages   how many pages one search may read.
     */
    public record PagePlan(List<String> terms, List<PageRun> runs, int totalPages, int maxPages) {
    }

    /**
     * @param skills     skills grouping several new sub-skills.
     * @param direct     new sub-skills directly under the topic.
     * @param duplicates sub-skills the course already has on these pages; shown, never created.
     */
    public record ProposalResponse(UUID proposalId, OffsetDateTime expiresAt, int pagesRead,
                                   List<ProposedSkill> skills, List<ProposedSubSkill> direct,
                                   List<ProposedSubSkill> duplicates) {
    }

    public record ProposedSkill(String key, String text, String shortLabel, BloomLevel bloom, SoloLevel solo,
                                List<ProposedSubSkill> subSkills) {
    }

    public record ProposedSubSkill(String key, String text, String shortLabel, BloomLevel bloom, SoloLevel solo,
                                   ProposedSource source, List<ProposedKnowledge> knowledge,
                                   DuplicateOf duplicateOf) {
    }

    public record ProposedSource(Long documentId, String displayName, Integer page, String snippet,
                                 EvidenceKind evidenceKind) {
    }

    public record ProposedKnowledge(String text, String shortLabel) {
    }

    /** The existing sub-skill a proposed one repeats, and the topic it sits under, if any. */
    public record DuplicateOf(Long goalId, String text, String topicText) {
    }

    private record Proposal(UUID id, Long courseId, String topic, Instant expiresAt, List<Candidate> candidates,
                            List<SkillPlan> skills, List<Integer> direct, Set<Integer> duplicates) {
    }

    private record Candidate(Long documentId, UnitGoal goal, ResolvedSource source, String snippet, Integer page,
                             List<KnowledgeCandidate> knowledge) {
    }

    private record KnowledgeCandidate(UnitGoal goal, ResolvedSource source) {
    }

    private record SkillPlan(String name, String shortLabel, TaxonomyClassification classification,
                             List<Integer> members) {
    }

    private record Structure(List<SkillPlan> skills, List<Integer> direct) {
    }
}
