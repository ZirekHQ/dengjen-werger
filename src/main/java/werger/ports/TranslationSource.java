package werger.ports;

import java.util.List;
import werger.domain.Language;
import werger.domain.TmSegment;
import werger.domain.WorkItem;

/**
 * Adapts one external translation platform for both reading work and durable Translation Memory, and for pushing an
 * approved translation back.
 */
public interface TranslationSource {
    /** Matches {@code work_items.source_adapter} for every item this source owns. */
    String adapterName();

    List<WorkItem> fetchWorkItems(Language language);

    List<TmSegment> fetchMemory(Language language);

    Result<SourceError, Void> submit(WorkItem item, String translation);
}
