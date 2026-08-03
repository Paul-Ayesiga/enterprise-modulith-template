<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=true; section>
  <#if section = "form">
    <div class="smsone-page-badge">Verification required</div>
    <h1 class="smsone-title">${msg("emailVerifyTitle")}</h1>
    <p class="smsone-subtitle">${msg("emailVerifyInstruction1", user.email)}</p>
    
    <div class="smsone-form-group smsone-status-panel" style="margin-top: 2rem; text-align: center;">
      <p class="smsone-inline-note" style="margin-bottom: 0;">
        ${msg("emailVerifyInstruction2")}
        <a href="${url.loginAction}" class="smsone-link">${msg("doClickHere")}</a>
        ${msg("emailVerifyInstruction3")}
      </p>
    </div>
  <#elseif section = "info">
    <div class="smsone-inline-note">
      If you did not receive an email, check your spam folder or retry from the same browser session.
    </div>
  </#if>
</@layout.registrationLayout>
