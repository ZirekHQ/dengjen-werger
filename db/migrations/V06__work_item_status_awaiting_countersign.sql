-- An untrusted reviewer's approval waits here for a trusted countersign, so `approved` only ever means a push is owed.
ALTER TYPE work_item_status ADD VALUE 'awaiting_countersign';
