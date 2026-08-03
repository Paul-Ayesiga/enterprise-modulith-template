<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('otp') displayInfo=true; section>
  <#if section = "form">
    <div class="smsone-page-badge">Step 2 of 2</div>
    <h1 class="smsone-title">Two-factor verification</h1>
    <p class="smsone-subtitle">Enter the one-time code from your authenticator app to complete sign-in.</p>

    <form id="kc-otp-login-form" action="${url.loginAction}" method="post">
      <div class="smsone-form-group">
        <label for="otp" class="smsone-label">${msg("authenticatorCode")} <span class="smsone-required">*</span></label>
        <div class="smsone-input-wrapper">
          <input tabindex="1" id="otp" name="otp" type="text" class="smsone-input" autocomplete="one-time-code" inputmode="numeric" autofocus aria-invalid="<#if messagesPerField.existsError('otp')>true<#else>false</#if>" placeholder="Enter 6-digit code" />
        </div>
        <#if messagesPerField.existsError('otp')>
          <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
            ${kcSanitize(messagesPerField.get('otp'))?no_esc}
          </div>
        </#if>
      </div>

      <div class="smsone-form-group" style="margin-top: 2rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doLogIn')}"/>
      </div>
    </form>
  <#elseif section = "info">
    <div class="smsone-inline-note">
      Need help? Contact your ${realm.displayName!'SMSONE'} administrator for support.
    </div>
  </#if>
</@layout.registrationLayout>
