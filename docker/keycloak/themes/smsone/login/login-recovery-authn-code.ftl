<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('recoveryCode') displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">Recovery code required</h1>
    <p class="smsone-subtitle">Enter one of your saved recovery codes to continue.</p>

    <form id="kc-recovery-authn-code-form" action="${url.loginAction}" method="post">
      <div class="smsone-form-group">
        <label for="recoveryCode" class="smsone-label">${msg("recoveryCode")} <span class="smsone-required">*</span></label>
        <div class="smsone-input-wrapper">
          <input tabindex="1" id="recoveryCode" name="recoveryCode" type="text" class="smsone-input" autocomplete="one-time-code" autofocus aria-invalid="<#if messagesPerField.existsError('recoveryCode')>true<#else>false</#if>" placeholder="Enter recovery code" />
        </div>
        <#if messagesPerField.existsError('recoveryCode')>
          <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
            ${kcSanitize(messagesPerField.get('recoveryCode'))?no_esc}
          </div>
        </#if>
      </div>

      <div class="smsone-form-group" style="margin-top: 2rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doLogIn')}"/>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
