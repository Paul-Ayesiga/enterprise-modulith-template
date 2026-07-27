package ug.co.smsone.shared.web;

public record ApiLinks(String self, String first, String prev, String next, String last) {

    public static ApiLinks self(String self) {
        return new ApiLinks(self, null, null, null, null);
    }
}
