package werger.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.UserRepo;
import werger.adapters.db.WorkItemRepo;
import werger.ports.Result;

/**
 * Purges a user's personal details and returns their unfinished work to the queue, leaving approved contributions and
 * their points attributed to the retained account row.
 */
public class AccountDeletionService {
    private final UserRepo users;
    private final WorkItemRepo workItems;
    private final TransactionTemplate transactions;

    public AccountDeletionService(UserRepo users, WorkItemRepo workItems, TransactionTemplate transactions) {
        this.users = users;
        this.workItems = workItems;
        this.transactions = transactions;
    }

    public Result<DeletionError, Void> delete(UUID userId) {
        return Objects.requireNonNull(transactions.execute(status -> purge(status, userId)));
    }

    private Result<DeletionError, Void> purge(TransactionStatus status, UUID userId) {
        if (!users.purge(userId)) {
            status.setRollbackOnly();
            return Result.err(new DeletionError.UnknownUser());
        }
        workItems.releaseUnfinishedWorkOf(userId);
        return Result.ok(null);
    }
}
