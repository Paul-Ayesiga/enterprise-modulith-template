<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "form">
        <div style="text-align: center;">
            <div style="color: var(--smsone-error); margin-bottom: 1.5rem;">
                <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="8" x2="12" y2="12"></line>
                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
            </div>
            
            <h1 class="smsone-title">${msg("errorTitle")}</h1>
            <p class="smsone-subtitle" style="margin-bottom: 2rem;">${message.summary?no_esc}</p>

            <#if !skipLink?? || !skipLink>
                <#if client?? && client.baseUrl?has_content>
                    <div class="smsone-form-group" style="margin-top: 2rem;">
                        <a href="${client.baseUrl}" class="smsone-button-primary" style="display: block; text-decoration: none; text-align: center;">
                            ${kcSanitize(msg("backToApplication"))?no_esc}
                        </a>
                    </div>
                </#if>
            </#if>
        </div>
    <#elseif section = "info">
        <#-- Generic contact support or retry info could go here -->
        <div style="font-size: 0.875rem; color: var(--smsone-text-muted);">
            If you believe this is a technical error, please contact system support.
        </div>
    </#if>
</@layout.registrationLayout>
