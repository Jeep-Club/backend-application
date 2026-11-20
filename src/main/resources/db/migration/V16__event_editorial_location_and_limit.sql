ALTER TABLE publication_events ADD COLUMN location VARCHAR(500) NULL, ADD COLUMN participant_limit INT NULL;
ALTER TABLE publication_event_history ADD COLUMN location VARCHAR(500) NULL, ADD COLUMN participant_limit INT NULL;
