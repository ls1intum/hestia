-- A figure block carries exactly one image. Every reader already took the first
-- row and ignored the rest, so a block that collected a second figure displayed
-- one and hid the other -- which is what happened whenever the editor's upload
-- succeeded but its follow-up delete of the previous row did not.
--
-- Drop the extras (lowest position wins, matching the row that was on screen),
-- then let the database hold the rule. The plain index becomes redundant.
delete from public.section_figures f
 using public.section_figures keep
 where f.block_id = keep.block_id
   and (keep.position, keep.created_at, keep.id) < (f.position, f.created_at, f.id);

create unique index if not exists section_figures_block_id_key
    on public.section_figures (block_id);

drop index if exists public.idx_section_figures_block_id;
