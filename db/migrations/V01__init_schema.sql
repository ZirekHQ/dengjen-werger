CREATE TYPE role AS ENUM ('contributor', 'reviewer', 'maintainer');
CREATE TYPE user_status AS ENUM ('active', 'suspended');
CREATE TYPE commitment_tier AS ENUM ('light', 'medium', 'heavy');
CREATE TYPE work_item_status AS ENUM
  ('available', 'in_progress', 'pending_review', 'revision_pending',
   'approved', 'upstream_approval_pending', 'synced', 'retired');
CREATE TYPE review_verdict AS ENUM ('approved', 'rejected');
CREATE TYPE points_reason AS ENUM ('submission_approved', 'review_completed');

CREATE TABLE languages (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_provider_ref TEXT UNIQUE,
  role role NOT NULL DEFAULT 'contributor',
  status user_status NOT NULL DEFAULT 'active',
  timezone TEXT NOT NULL,
  commitment_tier commitment_tier,
  commitment_started_at TIMESTAMPTZ,
  email TEXT,
  email_opt_in BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE work_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_adapter TEXT NOT NULL,
  external_id TEXT NOT NULL,
  language_code TEXT NOT NULL REFERENCES languages(code),
  source_text TEXT NOT NULL,
  target_text_draft TEXT,
  status work_item_status NOT NULL DEFAULT 'available',
  leased_by UUID REFERENCES users(id),
  lease_expires_at TIMESTAMPTZ,
  pending_submission_id UUID,
  revision_submitter UUID REFERENCES users(id),
  revision_comment TEXT,
  revision_expires_at TIMESTAMPTZ,
  UNIQUE (source_adapter, external_id, language_code)
);

CREATE TABLE submissions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_item_id UUID NOT NULL REFERENCES work_items(id),
  submitter_id UUID NOT NULL REFERENCES users(id),
  proposed_translation TEXT NOT NULL,
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE work_items
  ADD CONSTRAINT work_items_pending_submission_fk
  FOREIGN KEY (pending_submission_id) REFERENCES submissions(id);

CREATE TABLE reviews (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id),
  reviewer_id UUID NOT NULL REFERENCES users(id),
  verdict review_verdict NOT NULL,
  comment TEXT,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION check_reviewer_not_submitter() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.reviewer_id = (SELECT submitter_id FROM submissions WHERE id = NEW.submission_id) THEN
    RAISE EXCEPTION 'reviewer cannot review their own submission';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reviews_reviewer_not_submitter
  BEFORE INSERT ON reviews
  FOR EACH ROW EXECUTE FUNCTION check_reviewer_not_submitter();

CREATE TABLE points_ledger (
  user_id UUID NOT NULL REFERENCES users(id),
  submission_id UUID NOT NULL REFERENCES submissions(id),
  reason points_reason NOT NULL,
  amount INT NOT NULL,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, submission_id, reason)
);

CREATE TABLE streak_state (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  current INT NOT NULL DEFAULT 0,
  longest INT NOT NULL DEFAULT 0,
  last_met_period DATE
);

CREATE TABLE tm_segments (
  language_code TEXT NOT NULL REFERENCES languages(code),
  source_text TEXT NOT NULL,
  target_text TEXT NOT NULL,
  source_adapter TEXT NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (language_code, source_adapter, source_text)
);

INSERT INTO languages (code, name) VALUES ('kmr', 'Kurmanji Kurdish');
