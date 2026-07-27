/**
 * Shared kernel: web envelope, error handling, security, persistence base, configuration.
 * OPEN so business modules can use this infrastructure without boundary violations — business
 * modules must never be OPEN themselves.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Shared Kernel",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package ug.co.smsone.shared;
