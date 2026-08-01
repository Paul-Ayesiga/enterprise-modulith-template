package ug.co.smsone.localization.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The translation catalog. Reads are open to any authenticated user (these are the UI's own
 * strings); writes change what every user sees, so {@code platform-admin} is the floor.
 */
@RestController
@RequestMapping("/api/v1/translations")
class TranslationController {

    private static final String RESOURCE_TYPE = "translation";

    private final TranslationService translations;

    TranslationController(TranslationService translations) {
        this.translations = translations;
    }

    record TranslationAttributes(String locale, String key, String value) {
    }

    record UpsertTranslationRequest(@NotBlank String value) {
    }

    @GetMapping
    @Operation(summary = "List translations, optionally for one locale")
    WindowedResult<ResourceObject> list(@RequestParam(required = false) String locale,
            CursorPageRequest page) {
        return WindowedResult.of(translations.list(locale, page), page, TranslationController::toResource);
    }

    @GetMapping("/{locale}/{key}")
    @Operation(summary = "Get one translation")
    ResourceObject get(@PathVariable String locale, @PathVariable String key) {
        return toResource(translations.require(locale, key));
    }

    @PutMapping("/{locale}/{key}")
    @Operation(summary = "Create or replace a translation",
            description = """
                    Upsert — a (locale, key) that does not exist yet is created. Locales are BCP-47 \
                    tags, stored lowercased; an unparseable tag is a 422. The locale's cached bundle \
                    is evicted cluster-wide, so resolution reflects the change promptly.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject put(@PathVariable String locale, @PathVariable String key,
            @Valid @RequestBody UpsertTranslationRequest request) {
        return toResource(translations.put(locale, key, request.value()));
    }

    @DeleteMapping("/{locale}/{key}")
    @Operation(summary = "Delete a translation",
            description = "Resolution falls back down the chain (language → default → the key itself).")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String locale, @PathVariable String key) {
        translations.delete(locale, key);
    }

    private static ResourceObject toResource(Translation translation) {
        return new ResourceObject(translation.getId().toString(), RESOURCE_TYPE,
                new TranslationAttributes(translation.getLocale(), translation.getMsgKey(),
                        translation.getMsgValue()));
    }
}
