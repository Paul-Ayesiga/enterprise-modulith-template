package ug.co.smsone.shared.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Auto-wraps controller return values in the {@link ApiResponse} envelope. Scoped to
 * {@code ug.co.smsone} controllers only, so springdoc and actuator endpoints stay untouched.
 */
@RestControllerAdvice(basePackages = "ug.co.smsone")
public class EnvelopeResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ApiMetaFactory metaFactory;

    public EnvelopeResponseBodyAdvice(ApiMetaFactory metaFactory) {
        this.metaFactory = metaFactory;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Only JSON bodies rendered by Jackson are wrapped; String/byte[]/Resource converters pass through.
        return converterType.getSimpleName().contains("Jackson");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> || body instanceof ProblemDetail) {
            return body;
        }
        if (!selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        String selfPath = request.getURI().getPath();
        if (body instanceof WindowedResult<?> windowed) {
            PageMeta page = windowed.page();
            String next = page.nextCursor() == null ? null
                    : selfPath + "?page[size]=" + page.size() + "&page[after]=" + page.nextCursor();
            return ApiResponse.of(windowed.items(), metaFactory.create().withPage(page),
                    new ApiLinks(selfPath, next));
        }
        return ApiResponse.of(body, metaFactory.create(), ApiLinks.self(selfPath));
    }
}
