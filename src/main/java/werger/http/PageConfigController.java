package werger.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the page needs before anyone signs in: where Supabase Auth is and the project's publishable key. Both are
 * public by design; the key only lets a browser ask Supabase to sign someone in.
 */
@RestController
public class PageConfigController {
    private final PageConfig config;

    /** The page's sign-in settings. */
    public record PageConfig(String supabaseUrl, String publishableKey) {}

    /**
     * Serves the configured project.
     *
     * @param supabaseUrl the Supabase project URL
     * @param publishableKey the project's publishable (anon) key
     */
    public PageConfigController(
            @Value("${werger.auth.supabase-url}") String supabaseUrl,
            @Value("${werger.auth.supabase-publishable-key}") String publishableKey) {
        this.config = new PageConfig(SupabaseAuth.projectUrl(supabaseUrl), publishableKey);
    }

    /**
     * The page's sign-in settings.
     *
     * @return the Supabase project URL and publishable key
     */
    @GetMapping("/page-config")
    public PageConfig pageConfig() {
        return config;
    }
}
