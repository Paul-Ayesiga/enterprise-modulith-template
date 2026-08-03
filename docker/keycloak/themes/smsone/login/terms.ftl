<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=false displayInfo=false wide=true; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("termsTitle")}</h1>
    <p class="smsone-subtitle">Please review the terms and conditions to continue securely.</p>

    <div id="kc-terms-text" style="height: 300px; overflow-y: scroll; border: 1px solid var(--smsone-border); padding: 1.5rem; border-radius: 4px; background: #fdfdfd; font-size: 0.875rem; line-height: 1.6; color: var(--smsone-text-main); margin-bottom: 2rem;">
        ${kcSanitize(msg("termsText"))?no_esc}
    </div>

    <form class="smsone-form-group" action="${url.loginAction}" method="post" style="display: flex; gap: 1rem;">
        <input
            class="smsone-button-primary"
            name="accept"
            id="kc-accept"
            type="submit"
            value="${msg("doAccept")}"
        />
        <input
            class="smsone-button-primary"
            name="cancel"
            id="kc-decline"
            type="submit"
            value="${msg("doDecline")}"
            style="background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);"
        />
    </form>
  </#if>
</@layout.registrationLayout>
