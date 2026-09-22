-- -----------------------------------------------------------------------------
-- Meetings: optimistic-lock version, participant uniqueness, query-shaped indexes
-- -----------------------------------------------------------------------------
-- Rationale:
--   MeetingRepositoryImpl.addParticipants is a read-modify-write. Without a
--   version column two concurrent adds both pass the MAX_PARTICIPANTS check,
--   and without a (meeting_id, user_id) unique key the same user can be
--   inserted twice. The entity now carries @Version and the participant write
--   path locks the meeting with OPTIMISTIC_FORCE_INCREMENT, so the loser fails
--   on commit instead of overfilling.
--
--   The reminder scheduler sweeps meetings(is_canceled, start_at) every minute,
--   `/meetup list` filters meetings(publisher_id, start_at) and
--   meeting_participants(user_id), and standup lookups filter
--   standup_routine(is_active, command_channel). None of these had an index.
--
-- Behaviour:
--   - meetings.version BIGINT NOT NULL DEFAULT 0; existing rows start at 0.
--   - Adding the unique key fails if duplicate (meeting_id, user_id) rows
--     already exist. Check first:
--       SELECT meeting_id, user_id, COUNT(*) FROM meeting_participants
--       GROUP BY meeting_id, user_id HAVING COUNT(*) > 1;
--     and delete the extra rows (keep the lowest id) before applying.
--
-- Apply this script BEFORE rolling out application code that expects the
-- version column. For dev/local with auto-ddl enabled, Hibernate applies it
-- automatically. In prod, execute manually — schema auto-migration is disabled.
-- -----------------------------------------------------------------------------

ALTER TABLE meetings ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS idx_meetings_canceled_start_at ON meetings;
CREATE INDEX idx_meetings_canceled_start_at ON meetings (is_canceled, start_at);

DROP INDEX IF EXISTS idx_meetings_publisher_start_at ON meetings;
CREATE INDEX idx_meetings_publisher_start_at ON meetings (publisher_id, start_at);

DROP INDEX IF EXISTS idx_meeting_participants_user_id ON meeting_participants;
CREATE INDEX idx_meeting_participants_user_id ON meeting_participants (user_id);

ALTER TABLE meeting_participants
    ADD UNIQUE INDEX IF NOT EXISTS uk_meeting_participants_meeting_user (meeting_id, user_id);

DROP INDEX IF EXISTS idx_standup_routine_active_channel ON standup_routine;
CREATE INDEX idx_standup_routine_active_channel ON standup_routine (is_active, command_channel);
