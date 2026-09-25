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
    List<WorkItem> fetchWorkItems(Language language);

    List<TmSegment> fetchMemory(Language language);

    Result<SourceError, Void> submit(WorkItem item, String translation);
}
