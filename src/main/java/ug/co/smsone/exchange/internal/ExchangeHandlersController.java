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

    ExchangeHandlersController(HandlerRegistry handlers, List<FormatCodec> codecs) {
        this.handlers = handlers;
        this.codecs = codecs;
    }

    record HandlerAttributes(String importPermission, String exportPermission, List<String> header,
            List<String> formats) {
    }

    @GetMapping("/handlers")
    @Operation(summary = "List the available exchange handlers",
            description = """
                    Each handler names the dataset it moves, the permissions its imports and \
                    exports require, and `header` — the exact CSV column order (or JSONL key set) \
                    its files use. `header` doubles as the import template.""")
    List<ResourceObject> list() {
        List<String> formats = codecs.stream().map(FormatCodec::id).sorted().toList();
        return handlers.all().stream()
                .sorted(Comparator.comparing(ExchangeHandler::id))
                .map(handler -> new ResourceObject(handler.id(), RESOURCE_TYPE,
                        new HandlerAttributes(handler.importPermission(), handler.exportPermission(),
                                handler.header(), formats)))
                .toList();
    }
}
