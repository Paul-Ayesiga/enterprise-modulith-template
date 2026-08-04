<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm') displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("updatePasswordTitle")}</h1>
    <p class="smsone-subtitle">Please set a new password for your account.</p>

    <form id="kc-passwd-update-form" action="${url.loginAction}" method="post">
      <div class="smsone-form-group">
        <label for="password-new" class="smsone-label">${msg("passwordNew")} <span class="smsone-required">*</span></label>
        <div class="smsone-input-wrapper">
          <input type="password" id="password-new" name="password-new" class="smsone-input" autofocus autocomplete="new-password" aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true<#else>false</#if>" placeholder="Enter new password" />
          <button type="button" class="smsone-password-toggle" onclick="toggleUpdatePassword('password-new')" aria-label="Toggle password visibility">
            <svg id="password-new-eye-open" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
              <circle cx="12" cy="12" r="3"></circle>
            </svg>
            <svg id="password-new-eye-closed" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: none;">
              <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path>
              <line x1="1" y1="1" x2="23" y2="23"></line>
            </svg>
          </button>
        </div>
      </div>

      <div class="smsone-form-group">
        <label for="password-confirm" class="smsone-label">${msg("passwordConfirm")} <span class="smsone-required">*</span></label>
        <div class="smsone-input-wrapper">
          <input type="password" id="password-confirm" name="password-confirm" class="smsone-input" autocomplete="new-password" aria-invalid="<#if messagesPerField.existsError('password-confirm')>true<#else>false</#if>" placeholder="Confirm new password" />
          <button type="button" class="smsone-password-toggle" onclick="toggleUpdatePassword('password-confirm')" aria-label="Toggle password visibility">
            <svg id="password-confirm-eye-open" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
              <circle cx="12" cy="12" r="3"></circle>
            </svg>
            <svg id="password-confirm-eye-closed" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: none;">
              <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path>
              <line x1="1" y1="1" x2="23" y2="23"></line>
            </svg>
          </button>
        </div>
      </div>

      <#if messagesPerField.existsError('password','password-confirm')>
          <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: -0.75rem; margin-bottom: 1rem;">
            ${kcSanitize(messagesPerField.getFirstError('password','password-confirm'))?no_esc}
          </div>
      </#if>

      <div class="smsone-form-group" style="margin-top: 2rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doSubmit')}"/>
      </div>
      
      <#if isAppInitiatedAction??>
        <div style="text-align: center; margin-top: 1rem;">
            <button type="submit" name="cancel-aia" value="true" class="smsone-link" style="background:none; border:none; cursor:pointer; font-weight: inherit; font-size: inherit;">${msg("doCancel")}</button>
        </div>
      </#if>
    </form>

    <script>
      // Show/hide toggle for the new + confirm password fields, so first-time
      // users can verify the password they type (#192). Mirrors register.ftl.
      function toggleUpdatePassword(inputId) {
        var passwordInput = document.getElementById(inputId);
        var eyeOpen = document.getElementById(inputId + "-eye-open");
        var eyeClosed = document.getElementById(inputId + "-eye-closed");

        if (passwordInput.type === "password") {
          passwordInput.type = "text";
          eyeOpen.style.display = "none";
          eyeClosed.style.display = "block";
        } else {
          passwordInput.type = "password";
          eyeOpen.style.display = "block";
          eyeClosed.style.display = "none";
        }
      }
    </script>
  </#if>
</@layout.registrationLayout>
