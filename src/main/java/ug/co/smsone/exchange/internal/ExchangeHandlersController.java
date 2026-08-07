package ug.co.smsone.exchange.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The handler catalog — which datasets can move through the platform, what each expects, and which
 * permissions gate it. Global and read-only, like the permission catalog: available to any
 * authenticated user (submitting still requires the listed permission in the target org).
 */
@RestController
@RequestMapping("/api/v1/exchange")
class ExchangeHandlersController {

    static final String RESOURCE_TYPE = "exchange-handler";

    private final HandlerRegistry handlers;
    private final List<FormatCodec> codecs;
    private final ExchangeService exchange;

    ExchangeHandlersController(HandlerRegistry handlers, List<FormatCodec> codecs,
            ExchangeService exchange) {
        this.handlers = handlers;
        this.codecs = codecs;
        this.exchange = exchange;
    }

    record HandlerAttributes(String importPermission, String exportPermission, List<String> header,
            int templateVersion, List<String> formats) {
    }

    @GetMapping("/handlers")
    @Operation(summary = "List the available exchange handlers",
            description = """
                    Each handler names the dataset it moves, the permissions its imports and \
                    exports require, `header` — the exact column order its files use — and the \
                    current `templateVersion`. Download a ready-to-fill file from \
                    `/handlers/{id}/template`.""")
    List<ResourceObject> list() {
        List<String> formats = codecs.stream().map(FormatCodec::id).sorted().toList();
        return handlers.all().stream()
                .sorted(Comparator.comparing(ExchangeHandler::id))
                .map(handler -> new ResourceObject(handler.id(), RESOURCE_TYPE,
                        new HandlerAttributes(handler.importPermission(), handler.exportPermission(),
                                handler.header(), handler.templateVersion(), formats)))
                .toList();
    }

    @GetMapping("/handlers/{id}/template")
    @Operation(summary = "Download a handler's import template",
            description = """
                    An empty, correctly-headed file in the requested `format` (default CSV) — fill \
                    it and submit it back. The filename carries the template version, so a header \
                    mismatch on import names exactly which file to re-download.""")
    org.springframework.http.ResponseEntity<byte[]> template(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(name = "format", defaultValue = "CSV")
            String format) {
        ExchangeHandler handler = exchange.requireHandler(id);
        String normalized = exchange.requireFormat(format);
        FormatCodec codec = codecs.stream().filter(c -> c.id().equals(normalized)).findFirst().orElseThrow();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            // Zero records: the template IS an empty export — one code path for every format. Open and
            // close is the whole operation; close() is what flushes the header the codec buffered.
            // Written as a statement rather than try-with-resources because a resource binding nothing
            // ever reads is a variable javac (and every reader) has to wonder about.
            codec.writer(out, handler.header()).close();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Template generation failed", ex);
        }
        String fileName = handler.id() + "-template-v" + handler.templateVersion()
                + "." + codec.fileExtension();
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(codec.contentType()))
                .body(out.toByteArray());
    }
}
