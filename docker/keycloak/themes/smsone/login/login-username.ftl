<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "form">
        <h1 class="smsone-title">Sign in to the portal</h1>
        <p class="smsone-subtitle">Access the portal using your email</p>

        <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
            
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

            <#if realm.rememberMe && !usernameHidden??>
                <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem;">
                    <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" class="smsone-checkbox" <#if login.rememberMe??>checked</#if>>
                    <label for="rememberMe" style="font-size: 0.8125rem; color: var(--smsone-ink-muted); cursor: pointer; user-select: none;">
                        ${msg("rememberMe")}
                    </label>
                </div>
            </#if>

            <div class="smsone-form-group" style="margin-top: 1.5rem;">
                <input tabindex="4" class="smsone-button-primary" name="login" id="kc-login" type="submit" value="Continue"/>

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
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div style="font-size: 0.875rem; color: var(--smsone-text-muted);">
                ${msg("noAccount")} <a href="${url.registrationUrl}" class="smsone-link">${msg("doRegister")}</a>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
