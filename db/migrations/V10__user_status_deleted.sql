-- A deleted account keeps its row so approved contributions stay attributed; the status keeps it out of every
-- queue that already filters on active users.
ALTER TYPE user_status ADD VALUE 'deleted';
