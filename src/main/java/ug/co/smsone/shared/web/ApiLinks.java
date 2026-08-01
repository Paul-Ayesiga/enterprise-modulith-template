package ug.co.smsone.shared.web;

/**
 * The envelope's {@code links} object.
 *
 * <p>{@code first}/{@code prev}/{@code last} are deliberately absent, not merely unpopulated: pagination
 * here is cursor-only (ADR 0002), so {@code first} and {@code last} would need a total the API refuses to
 * compute and {@code prev} would need a backwards cursor {@code Cursors} does not mint. Declaring them
 * would advertise navigation that can never arrive.
 */
public record ApiLinks(String self, String next) {

    public static ApiLinks self(String self) {
        return new ApiLinks(self, null);
    }
}
