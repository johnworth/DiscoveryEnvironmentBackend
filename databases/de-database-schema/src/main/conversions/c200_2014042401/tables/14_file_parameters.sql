SET search_path = public, pg_catalog;

--
-- Renames the existing dataobjects table to file_parameters and adds updated columns.
-- cols to drop: hid, info_type_v187, data_format_v187, multiplicity_v187, data_source_id_v187
-- rename orderd?
--
ALTER TABLE dataobjects RENAME TO file_parameters;
ALTER TABLE ONLY file_parameters RENAME COLUMN id TO id_v187;
ALTER TABLE ONLY file_parameters ADD COLUMN id UUID DEFAULT (uuid_generate_v4());
ALTER TABLE ONLY file_parameters RENAME COLUMN info_type TO info_type_v187;
ALTER TABLE ONLY file_parameters ADD COLUMN info_type UUID;
ALTER TABLE ONLY file_parameters RENAME COLUMN data_format TO data_format_v187;
ALTER TABLE ONLY file_parameters ADD COLUMN data_format UUID;
ALTER TABLE ONLY file_parameters RENAME COLUMN multiplicity TO multiplicity_v187;
ALTER TABLE ONLY file_parameters ADD COLUMN multiplicity UUID;
ALTER TABLE ONLY file_parameters RENAME COLUMN data_source_id TO data_source_id_v187;
ALTER TABLE ONLY file_parameters ADD COLUMN data_source_id  UUID;
