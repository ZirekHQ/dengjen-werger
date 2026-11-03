package werger.service;

import java.time.Clock;
import werger.adapters.db.WorkItemRepo;

/** The scheduled housekeeping the platform runs between user actions. */
public class JobsService {
    private final WorkItemRepo workItems;
    private final Clock clock;

    public JobsService(WorkItemRepo workItems, Clock clock) {
        this.workItems = workItems;
        this.clock = clock;
    }

    /** Returns rejected items to the pool once their submitter's revision window has closed, and counts them. */
    public int sweepExpiredRevisions() {
        return workItems.reopenExpiredRevisions(clock.instant());
    }
}
