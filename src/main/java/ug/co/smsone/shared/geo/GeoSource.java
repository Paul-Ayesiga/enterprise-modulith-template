package ug.co.smsone.shared.geo;

/** How a fix was obtained — its trust level descends down the list. */
public enum GeoSource {
    /** Device GNSS/GPS — the most precise, metres-level. */
    DEVICE_GPS,
    /** Wi-Fi / cell-tower network location — coarser than GPS. */
    NETWORK,
    /** A human-entered coordinate (a picked map point, a typed lat/lng). */
    MANUAL,
    /** Derived from the caller's IP at the edge — city-level at best, a last-resort fallback. */
    IP
}
