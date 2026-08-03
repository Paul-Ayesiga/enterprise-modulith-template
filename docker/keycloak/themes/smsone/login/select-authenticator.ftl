<#import "template.ftl" as layout>

<@layout.registrationLayout displayInfo=true; section>
    <#if section = "form">
        <h1 class="smsone-title">${msg("loginChooserTitle")}</h1>
        <p class="smsone-subtitle">Choose how you want to authenticate.</p>

        <form id="kc-select-credential-form" action="${url.loginAction}" method="post">
            <ul style="list-style: none; padding: 0; margin-bottom: 2rem;">
                <#list auth.authenticationSelections as selection>
                    <li style="margin-bottom: 0.75rem;">
                        <button type="submit" name="authenticationExecution" value="${selection.authExecId}" 
                                style="width: 100%; display: flex; align-items: center; border: 1px solid var(--smsone-border); border-radius: 6px; padding: 1.25rem; background: #fff; cursor: pointer; transition: all 0.2s; text-align: left;">
                            <div style="margin-right: 1.25rem; color: var(--smsone-primary); display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; background: rgba(69, 139, 158, 0.1); border-radius: 50%;">
                                <#if selection.iconCssClass?? && selection.iconCssClass?contains("webauthn")>
                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>
                                <#else>
                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
                                </#if>
                            </div>
                            <div style="flex: 1;">
                                <div style="font-weight: 600; font-size: 0.9375rem; color: var(--smsone-text-main);">${msg(selection.helpText)}</div>
                                <div style="font-size: 0.8125rem; color: var(--smsone-text-muted);">${msg(selection.displayName)}</div>
                            </div>
                            <div style="color: var(--smsone-border);">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                            </div>
                        </button>
                    </li>
                </#list>
            </ul>
        </form>
    </#if>
</@layout.registrationLayout>
