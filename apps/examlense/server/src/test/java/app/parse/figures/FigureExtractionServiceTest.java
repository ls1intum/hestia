package app.parse.figures;

import app.exam.Exam;
import app.exam.ExamRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.sse.SseHub;
import app.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end over a real PDF, with only storage and the repositories mocked: the
 * detector, matcher and cropper all run for real, so these tests fail if the
 * pipeline stops producing an actual image.
 *
 * <p>The negative cases matter as much as the happy path. Every one of them must
 * end with no row written, because the empty block the user then sees is exactly
 * the state this feature is replacing — and a wrong image would be worse than it.
 */
class FigureExtractionServiceTest {

    private static final UUID EXAM = UUID.randomUUID();
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String PATH = USER + "/" + EXAM + ".pdf";
    private static final OffsetDateTime PARSED_AT = OffsetDateTime.now();

    private ExamRepository examRepository;
    private SectionFigureRepository figureRepository;
    private StorageService storage;
    private SseHub sse;
    private FigureExtractionService service;

    @BeforeEach
    void setUp() {
        examRepository = mock(ExamRepository.class);
        figureRepository = mock(SectionFigureRepository.class);
        storage = mock(StorageService.class);
        sse = mock(SseHub.class);
        service = newService(true);
        Exam exam = new Exam();
        exam.setParsedAt(PARSED_AT);
        when(examRepository.findById(EXAM)).thenReturn(Optional.of(exam));
    }

    private FigureExtractionService newService(boolean enabled) {
        return new FigureExtractionService(examRepository, figureRepository, storage,
            new FigureRegionDetector(), new FigureMatcher(), new PdfFigureCropper(), sse, enabled);
    }

    /** A page carrying one pasted screenshot — the Word-export shape. */
    private static byte[] pdfWithOneImage() {
        return TestPdfs.page((doc, cs) -> {
            TestPdfs.text(cs, 72, 780, "Aufgabe 1: Betrachten Sie die folgende Abbildung.");
            cs.drawImage(TestPdfs.image(doc, 500, 380), 120, 420, 260, 200);
        });
    }

    private static FigurePlacement placement(Integer page) {
        return new FigurePlacement(UUID.randomUUID(), page, "Abbildung 1", 0);
    }

    @Test
    void storesACropAndRowForAnEmbeddedImage() throws Exception {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());
        FigurePlacement block = placement(1);

        service.extract(EXAM, USER, PATH, List.of(block));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(eq("exam-figures"), contains("/auto/"), bytes.capture());
        assertThat(ImageIO.read(new ByteArrayInputStream(bytes.getValue()))).isNotNull();

        ArgumentCaptor<SectionFigure> row = ArgumentCaptor.forClass(SectionFigure.class);
        verify(figureRepository).save(row.capture());
        assertThat(row.getValue().getSource()).isEqualTo("pdf");
        assertThat(row.getValue().getBlockId()).isEqualTo(block.blockId());
        assertThat(row.getValue().getStoragePath())
            .startsWith(USER + "/" + EXAM + "/auto/")
            .endsWith(".png");
        verify(sse).examUpdated(EXAM);
    }

    @Test
    void keepsExtractedImagesUnderAnAutoPrefixSoManualUploadsStaySeparable() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        ArgumentCaptor<SectionFigure> row = ArgumentCaptor.forClass(SectionFigure.class);
        verify(figureRepository).save(row.capture());
        // A manual upload writes {user}/{exam}/{id}.ext — this must not collide.
        assertThat(row.getValue().getStoragePath()).contains("/auto/");
    }

    @Test
    void extractsAnIncludegraphicsStyleFigureExactlyOnce() {
        // A form wrapping an image yields two overlapping regions; if containment
        // dedup missed, one figure block would compete with itself for two crops.
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            TestPdfs.text(cs, 72, 780, "Aufgabe 1: Betrachten Sie die Abbildung.");
            cs.drawForm(TestPdfs.formWithImage(
                doc, new org.apache.pdfbox.pdmodel.common.PDRectangle(120, 430, 250, 190), 400, 300));
        });
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdf);

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(storage).store(eq("exam-figures"), contains("/auto/"), any());
        verify(figureRepository).save(any());
    }

    @Test
    void writesNothingWhenTheFigureHasNoPageNumber() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());

        service.extract(EXAM, USER, PATH, List.of(placement(null)));

        verify(storage, never()).store(anyString(), anyString(), any());
        verify(figureRepository, never()).save(any());
    }

    @Test
    void writesNothingForAVectorOnlyPage() {
        // Inline TikZ/pgfplots is out of scope by design: thousands of raw path
        // ops with no grouping. It must degrade to the empty placeholder, never
        // to a guessed crop.
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            cs.setStrokingColor(Color.BLACK);
            for (int i = 0; i < 40; i++) {
                cs.moveTo(120 + i * 3, 420);
                cs.lineTo(130 + i * 3, 600);
            }
            cs.stroke();
        });
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdf);

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void writesNothingWhenThePageHasNoArtworkAtAll() {
        when(storage.download(eq("exam-pdfs"), eq(PATH)))
            .thenReturn(TestPdfs.page((doc, cs) -> TestPdfs.text(cs, 72, 700, "Nur Text hier.")));

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
        verify(sse, never()).examUpdated(any());
    }

    @Test
    void survivesAMissingSourcePdf() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(null);

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void survivesUnreadablePdfBytes() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn("not a pdf".getBytes());

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void ignoresAPageNumberBeyondTheEndOfTheDocument() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());

        service.extract(EXAM, USER, PATH, List.of(placement(9)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void doesNotInsertARowWhenStoringTheBytesFails() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());
        doThrow(new RuntimeException("disk full"))
            .when(storage).store(eq("exam-figures"), anyString(), any());

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void cleansUpTheStoredObjectWhenTheRowInsertFails() {
        // block_id is an FK: a re-parse that cascaded the block away lands here.
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());
        when(figureRepository.save(any())).thenThrow(new RuntimeException("FK violation"));

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(storage).delete(eq("exam-figures"), contains("/auto/"));
        verify(sse, never()).examUpdated(any());
    }

    @Test
    void stopsWhenTheExamHasBeenReParsedUnderneathIt() {
        when(storage.download(eq("exam-pdfs"), eq(PATH))).thenReturn(pdfWithOneImage());
        Exam reParsed = new Exam();
        reParsed.setParsedAt(PARSED_AT.plusMinutes(1));
        when(examRepository.findById(EXAM))
            .thenReturn(Optional.of(examAt(PARSED_AT)))   // snapshot at start
            .thenReturn(Optional.of(reParsed));           // staleness re-check
        service = newService(true);

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(figureRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenTheFeatureIsDisabled() {
        service = newService(false);

        service.extract(EXAM, USER, PATH, List.of(placement(1)));

        verify(storage, never()).download(anyString(), anyString());
    }

    private static Exam examAt(OffsetDateTime parsedAt) {
        Exam e = new Exam();
        e.setParsedAt(parsedAt);
        return e;
    }
}
