package werger.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs transactional callbacks with no database, for service tests whose repos are stubs. */
final class NoTransactions implements PlatformTransactionManager {
    static TransactionTemplate template() {
        return new TransactionTemplate(new NoTransactions());
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        // Nothing to commit: no transaction was opened.
    }

    @Override
    public void rollback(TransactionStatus status) {
        // Nothing to roll back: no transaction was opened.
    }
}
