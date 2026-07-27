package ug.co.smsone.shared.web;

/** Where an error originated: a JSON pointer into the body, a query parameter, or a header. */
public record ApiSource(String pointer, String parameter, String header) {

    public static ApiSource pointer(String pointer) {
        return new ApiSource(pointer, null, null);
    }

    public static ApiSource parameter(String parameter) {
        return new ApiSource(null, parameter, null);
    }

    public static ApiSource header(String header) {
        return new ApiSource(null, null, header);
    }
}
