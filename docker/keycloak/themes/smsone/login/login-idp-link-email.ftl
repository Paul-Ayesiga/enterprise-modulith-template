<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=false; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("emailLinkIdpTitle", idpDisplayName)}</h1>
    <p class="smsone-subtitle">${msg("emailLinkIdp1", idpDisplayName, brokerContext.username, realm.displayName)}</p>

    <div class="smsone-form-group" style="margin-top: 2rem;">
      <p style="font-size: 0.875rem; color: var(--smsone-text-muted); margin-bottom: 1rem;">
        ${msg("emailLinkIdp2")}
        <a class="smsone-link" href="${url.loginAction}">${msg("doClickHere")}</a>
        ${msg("emailLinkIdp3")}
      </p>

      <p style="font-size: 0.875rem; color: var(--smsone-text-muted);">
        ${msg("emailLinkIdp4")}
        <a class="smsone-link" href="${url.loginAction}">${msg("doClickHere")}</a>
        ${msg("emailLinkIdp5")}
      </p>
    </div>
  </#if>
</@layout.registrationLayout>
