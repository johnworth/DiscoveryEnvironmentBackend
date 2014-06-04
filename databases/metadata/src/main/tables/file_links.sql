SET search_path = public, pg_catalog;

--
-- file_links table
--
CREATE TABLE file_links (
  file_id UUID NOT NULL,
  target_id UUID NOT NULL,
  owner_id UUID NOT NULL,
  created_on timestamp DEFAULT now() NOT NULL
);

CREATE INDEX file_links_target_id_idx ON file_links(target_id);

