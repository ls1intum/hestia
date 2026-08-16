-- Persist the source document's page count on the exam so the frontend can show
-- a real, page-count-based parsing time estimate (a smooth progress bar +
-- remaining-time countdown) while parsing runs. Computed cheaply at upload via
-- PDFBox (also for .docx, after server-side conversion to PDF).
--
-- Treated as document metadata alongside source_file_url: null for manual
-- from-scratch exams, or when the page count couldn't be determined.
alter table public.exams add column page_count int;
