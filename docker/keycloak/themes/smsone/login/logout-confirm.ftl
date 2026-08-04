<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <div class="smsone-title">${msg("sessionExpiredTitle")!"Session Timeout"}</div>
        <div class="smsone-subtitle">${msg("sessionExpiredSubtitle")!"Your session has expired due to inactivity. Please sign in again to continue."}</div>
    <#elseif section = "form">
        <div id="kc-logout-confirm">
            <form action="${url.logoutConfirmAction}" method="post">
                <input type="hidden" name="session_code" value="${logoutConfirm.code}"/>
                
                <div class="smsone-form-group">
                    <button class="smsone-button-primary" name="confirmLogout" id="kc-logout" type="submit" value="true">
                        ${msg("doLogout")}
                    </button>
                </div>

                <div class="smsone-form-group">
                    <a href="${(logoutConfirm.backToApplicationLink)!'https://smsone.co.ug'}" class="smsone-button-ghost">
                        ${kcSanitize(msg("backToApplication"))?no_esc}
                    </a>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>
