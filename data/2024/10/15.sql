-- 2024-10-15
INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (8144, 'reagent', 'reagent');
INSERT OR IGNORE INTO versions (id, version) VALUES (32, '1.1.0');
INSERT OR IGNORE INTO versions (id, version) VALUES (42, '1.2.0');
INSERT OR REPLACE INTO downloads (date, artifact_id, version_id, downloads) VALUES ('20241015', 8144, 42, 100);
INSERT OR REPLACE INTO downloads (date, artifact_id, version_id, downloads) VALUES ('20241015', 8144, 32, 50);
