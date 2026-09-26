package werger.ports;

import java.util.List;
import java.util.Map;
import werger.domain.Language;

/**
 * Adapts one hosted machine-translation provider. Never creates a {@code Submission} or moves a {@code WorkItemStatus}
 * — callers decide what to do with a translated batch.
 */
public interface MachineTranslationSource {
    Result<MtError, Map<String, String>> translateBatch(Language language, List<String> sourceTexts);
}
