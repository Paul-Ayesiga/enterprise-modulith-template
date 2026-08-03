<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "form">
        <div class="smsone-page-badge">Welcome back</div>
        <h1 class="smsone-title">Sign in to the portal</h1>
        <p class="smsone-subtitle">Use your account credentials to continue to the ${realm.displayName!'SMSOne'} portal.</p>

        <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
            
            <#if !usernameHidden??>
                <#-- Direct sign-in (no identity-first step yet): editable email. -->
                <div class="smsone-form-group">
                    <label for="username" class="smsone-label">
                        <#if !realm.loginWithEmailAllowed>
                            ${msg("username")}
                        <#elseif !realm.registrationEmailAsUsername>
                            ${msg("usernameOrEmail")}
                        <#else>
                            ${msg("email")}
                        </#if>
                        <span class="smsone-required">*</span>
                    </label>
                    <div class="smsone-input-wrapper">
                        <input tabindex="1" id="username" class="smsone-input" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username" <#if messagesPerField.existsError('username','password')>aria-invalid="true"</#if> placeholder="Enter your email" />
                    </div>
                    <#if messagesPerField.existsError('username','password')>
                        <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </div>
                    </#if>
                </div>
            <#else>
                <#-- Identity-first already captured the email (organization
                     discovery). Show it locked — Keycloak carries the username
                     in the auth session, so we render a read-only display and a
                     restart link, and only the password is editable here. -->
                <div class="smsone-form-group">
                    <label class="smsone-label">${msg("email")}</label>
                    <div class="smsone-input-wrapper">
                        <input id="username-locked" class="smsone-input" value="${(auth.attemptedUsername!'')}" type="email" readonly aria-readonly="true" tabindex="-1" style="opacity:0.7; cursor:not-allowed; background:var(--smsone-surface-muted, #f3f4f6);" />
                    </div>
                    <a href="${url.loginRestartFlowUrl}" class="smsone-link" style="font-size:0.75rem; margin-top:0.35rem; display:inline-block;">Not you? Start over</a>
                </div>
            </#if>

            <div class="smsone-form-group">
                <div class="smsone-flex-between">
                    <label for="password" class="smsone-label" style="margin-bottom:0;">${msg("password")} <span class="smsone-required">*</span></label>
                    <#if realm.resetPasswordAllowed>
                        <a tabindex="5" href="${url.loginResetCredentialsUrl}" class="smsone-link">Forgot Password?</a>
                    </#if>
                </div>
                <div class="smsone-input-wrapper">
                    <input tabindex="2" id="password" class="smsone-input" name="password" type="password" autocomplete="current-password" <#if usernameHidden??>autofocus</#if> <#if messagesPerField.existsError('username','password')>aria-invalid="true"</#if> placeholder="Enter your password" />
                    <button type="button" class="smsone-password-toggle" onclick="togglePassword()" aria-label="Toggle password visibility">
                         <svg id="eye-open" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                            <circle cx="12" cy="12" r="3"></circle>
                         </svg>
                         <svg id="eye-closed" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: none;">
                            <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path>
                            <line x1="1" y1="1" x2="23" y2="23"></line>
                         </svg>
                    </button>
                </div>
            </div>

            <#if realm.rememberMe && !usernameHidden??>
                <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem; margin-top: -0.5rem;">
                    <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" class="smsone-checkbox" <#if login.rememberMe??>checked</#if>>
                    <label for="rememberMe" style="font-size: 0.875rem; color: var(--smsone-text-muted); cursor: pointer; user-select: none;">
                        ${msg("rememberMe")}
                    </label>
                </div>
            </#if>

            <div class="smsone-form-group" style="margin-top: 1.5rem;">
                <input tabindex="4" class="smsone-button-primary" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>

                <#if auth.showTryAnotherWayLink()>
                    <form action="${url.loginAction}" method="post">
                        <input type="hidden" name="tryAnotherWay" value="yes"/>
                        <button type="submit" class="smsone-button-ghost">
                            ${msg("doTryAnotherWay")}
                        </button>
                    </form>
                </#if>
            </div>
            
        </form>

        <script>
            function togglePassword() {
                var passwordInput = document.getElementById("password");
                var eyeOpen = document.getElementById("eye-open");
                var eyeClosed = document.getElementById("eye-closed");
                if (passwordInput.type === "password") {
                    passwordInput.type = "text";
                    eyeOpen.style.display = "none";
                    eyeClosed.style.display = "block";
                } else {
                    passwordInput.type = "password";
                    eyeOpen.style.display = "block";
                    eyeClosed.style.display = "none";
                }
            }
        </script>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div class="smsone-inline-note">
                ${msg("noAccount")} <a href="${url.registrationUrl}" class="smsone-link">${msg("doRegister")}</a>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
