-- An approved fix ends in one of two states once dengjen's lexicon has been compared with it (#110): `landed` when its
-- row is in the file on dengjen's main branch, `superseded` when a later approved fix for the same written form
-- replaced it before it landed. Without them, the lexicon pull request would keep re-proposing rows already merged.
ALTER TYPE pronunciation_fix_status ADD VALUE 'landed';
ALTER TYPE pronunciation_fix_status ADD VALUE 'superseded';
