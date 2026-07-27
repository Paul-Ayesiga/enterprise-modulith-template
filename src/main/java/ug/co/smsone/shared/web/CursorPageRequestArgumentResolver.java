package ug.co.smsone.shared.web;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ug.co.smsone.shared.error.ValidationException;

/** Resolves {@code page[size]} / {@code page[after]} into a {@link CursorPageRequest}. */
@Component
public class CursorPageRequestArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String SIZE_PARAM = "page[size]";
    private static final String AFTER_PARAM = "page[after]";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CursorPageRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        int size = parseSize(webRequest.getParameter(SIZE_PARAM));
        String after = webRequest.getParameter(AFTER_PARAM);
        return new CursorPageRequest(size, (after == null || after.isBlank()) ? null : after);
    }

    private int parseSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return CursorPageRequest.DEFAULT_SIZE;
        }
        try {
            int size = Integer.parseInt(raw);
            if (size < 1 || size > CursorPageRequest.MAX_SIZE) {
                throw new ValidationException(
                        "page[size] must be between 1 and " + CursorPageRequest.MAX_SIZE + ".",
                        ApiSource.parameter(SIZE_PARAM));
            }
            return size;
        } catch (NumberFormatException e) {
            throw new ValidationException("page[size] must be an integer.", ApiSource.parameter(SIZE_PARAM));
        }
    }
}
