<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=false; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("oauth2DeviceVerificationTitle")}</h1>
    <p class="smsone-subtitle">Enter the verification code shown on your device to continue securely.</p>

    <form id="kc-user-verify-device-user-code-form" action="${url.oauth2DeviceVerificationAction}" method="post">
      <div class="smsone-form-group">
        <label for="device-user-code" class="smsone-label">${msg("verifyOAuth2DeviceUserCode")} <span class="smsone-required">*</span></label>
        <div class="smsone-input-wrapper">
          <input id="device-user-code" name="device_user_code" autocomplete="off" type="text" class="smsone-input" autofocus spellcheck="false" autocapitalize="characters" placeholder="Enter device code" />
        </div>
      </div>

      <div class="smsone-form-group" style="margin-top: 2rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doSubmit')}"/>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
