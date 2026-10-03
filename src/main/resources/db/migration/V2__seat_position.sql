-- Preserve the admin's seat order (A1, A2, ... A10) instead of lexical label order (A1, A10, A2).
ALTER TABLE seats ADD COLUMN position INT NOT NULL DEFAULT 0;
