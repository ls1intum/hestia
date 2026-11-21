-- A slide template belongs to its session or course (its id is theirs), not to the user who
-- uploaded it. Deleting that user (e.g. after a year without login) must not take the template of
-- a shared course with it: the uploader becomes unknown instead.
alter table teaching.slide_templates drop constraint slide_templates_owner_id_fkey;
alter table teaching.slide_templates alter column owner_id drop not null;
alter table teaching.slide_templates
    add constraint slide_templates_owner_id_fkey
        foreign key (owner_id) references core.app_user (id) on delete set null;
