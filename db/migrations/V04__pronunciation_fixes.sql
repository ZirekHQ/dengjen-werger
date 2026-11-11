-- Pronunciation fixes from native speakers (#110). An approved fix becomes a row of dengjen's lexicon file
-- (crates/text/kurmanji-phonemizer/lexicon/kmr.tsv); `say` is stored without the slashes the file puts around phonemes.
CREATE TYPE pronunciation_say_kind AS ENUM ('respelling', 'phonemes');
CREATE TYPE pronunciation_fix_status AS ENUM ('pending_review', 'approved', 'rejected');

CREATE TABLE pronunciation_fixes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  language_code TEXT NOT NULL REFERENCES languages(code),
  written TEXT NOT NULL,
  say_kind pronunciation_say_kind NOT NULL,
  say TEXT NOT NULL,
  note TEXT,
  submitter_id UUID NOT NULL REFERENCES users(id),
  status pronunciation_fix_status NOT NULL DEFAULT 'pending_review',
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX pronunciation_fixes_pending ON pronunciation_fixes (submitted_at, id) WHERE status = 'pending_review';

CREATE TABLE pronunciation_fix_reviews (
  fix_id UUID PRIMARY KEY REFERENCES pronunciation_fixes(id),
  reviewer_id UUID NOT NULL REFERENCES users(id),
  verdict review_verdict NOT NULL,
  comment TEXT,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The same backstop as reviews_reviewer_not_submitter: nobody reviews their own suggestion.
CREATE FUNCTION check_fix_reviewer_not_submitter() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.reviewer_id = (SELECT submitter_id FROM pronunciation_fixes WHERE id = NEW.fix_id) THEN
    RAISE EXCEPTION 'reviewer cannot review their own pronunciation fix';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pronunciation_fix_reviews_reviewer_not_submitter
  BEFORE INSERT ON pronunciation_fix_reviews
  FOR EACH ROW EXECUTE FUNCTION check_fix_reviewer_not_submitter();
