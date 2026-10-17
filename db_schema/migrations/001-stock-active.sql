-- Apply once to the explicitly approved target DB before deploying the new WAR.
-- Existing stock rows and all related trade data remain unchanged.
ALTER TABLE stock ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1;
