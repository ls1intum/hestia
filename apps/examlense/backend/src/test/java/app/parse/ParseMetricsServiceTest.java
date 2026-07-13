package app.parse;

import app.parse.ParseMetricsService.Metrics;
import app.parse.ParseMetricsService.ModelStat;
import app.persistence.entity.ParseMetric;
import app.persistence.repository.ParseMetricRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParseMetricsServiceTest {

    private static ParseMetric row(String model, String pdfMode, boolean success,
                                   Integer pageCount, Integer durationMs, Integer totalTokens) {
        ParseMetric m = new ParseMetric();
        m.setParserModel(model);
        m.setPdfMode(pdfMode);
        m.setSuccess(success);
        m.setPageCount(pageCount);
        m.setDurationMs(durationMs);
        m.setTotalTokens(totalTokens);
        return m;
    }

    private static Metrics aggregate(List<ParseMetric> rows) {
        ParseMetricRepository repo = mock(ParseMetricRepository.class);
        when(repo.findAll()).thenReturn(rows);
        return new ParseMetricsService(repo).aggregate();
    }

    private static ModelStat find(Metrics m, String modelId, String pdfMode) {
        return m.byModel().stream()
            .filter(s -> s.modelId().equals(modelId) && s.pdfMode().equals(pdfMode))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + modelId + " / " + pdfMode));
    }

    @Test
    void sameModelSplitsIntoSeparateRowsByPdfMode() {
        Metrics m = aggregate(List.of(
            row("gpt-5.5", "PDF_DIRECT", true, 10, 5_000, 2_000),
            row("gpt-5.5", "TEXT_ONLY", true, 10, 1_000, 800)
        ));

        assertThat(m.byModel()).hasSize(2);
        ModelStat direct = find(m, "gpt-5.5", "PDF_DIRECT");
        ModelStat fast = find(m, "gpt-5.5", "TEXT_ONLY");
        assertThat(direct.total()).isEqualTo(1);
        assertThat(direct.avgMsPerPage()).isEqualTo(500);
        assertThat(fast.total()).isEqualTo(1);
        assertThat(fast.avgMsPerPage()).isEqualTo(100);
    }

    @Test
    void legacyMistralTextRowsAggregateAsBaseMistralTextOnly() {
        Metrics m = aggregate(List.of(
            row("mistral-large-3-text", null, true, 4, 2_000, 400)
        ));

        assertThat(m.byModel()).hasSize(1);
        ModelStat stat = m.byModel().get(0);
        assertThat(stat.modelId()).isEqualTo("mistral-large-3-675b-instruct-2512");
        assertThat(stat.pdfMode()).isEqualTo("TEXT_ONLY");
        assertThat(stat.total()).isEqualTo(1);
        assertThat(stat.avgMsPerPage()).isEqualTo(500);
    }

    @Test
    void legacyAndNewMistralFastModeRowsCombineIntoOneGroup() {
        Metrics m = aggregate(List.of(
            row("mistral-large-3-text", null, true, 5, 5_000, 500),
            row("mistral-large-3-675b-instruct-2512", "TEXT_ONLY", true, 5, 5_000, 500)
        ));

        ModelStat stat = find(m, "mistral-large-3-675b-instruct-2512", "TEXT_ONLY");
        assertThat(m.byModel())
            .filteredOn(s -> s.pdfMode().equals("TEXT_ONLY"))
            .hasSize(1);
        assertThat(stat.total()).isEqualTo(2);
        assertThat(stat.succeeded()).isEqualTo(2);
        // Pooled: (5000 + 5000) / (5 + 5) = 1000 ms/page.
        assertThat(stat.avgMsPerPage()).isEqualTo(1_000);
    }

    @Test
    void failedRowCountsInTotalAndSuccessRateButNotTiming() {
        Metrics m = aggregate(List.of(
            row("gpt-5.5", "PDF_DIRECT", true, 10, 4_000, 2_000),
            row("gpt-5.5", "PDF_DIRECT", false, 10, 9_999, 9_999)
        ));

        ModelStat stat = find(m, "gpt-5.5", "PDF_DIRECT");
        assertThat(stat.total()).isEqualTo(2);
        assertThat(stat.succeeded()).isEqualTo(1);
        assertThat(stat.failed()).isEqualTo(1);
        // Only the successful parse feeds the per-page pools.
        assertThat(stat.avgMsPerPage()).isEqualTo(400);
        assertThat(stat.avgTokensPerPage()).isEqualTo(200);
        // Overall duration percentiles/avg also exclude the failed row.
        assertThat(m.avgDurationMs()).isEqualTo(4_000);
    }
}
