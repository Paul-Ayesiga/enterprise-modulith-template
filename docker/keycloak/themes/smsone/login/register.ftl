<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm') displayInfo=true; section>
  <#if section = "form">
    <div class="smsone-page-badge">Create account</div>
    <h1 class="smsone-title">Register for ${realm.displayName!'SMSONE'}</h1>
    <p class="smsone-subtitle">Complete your profile to access your patient mobile experience.</p>

    <form id="kc-register-form" action="${url.registrationAction}" method="post">
      <div class="smsone-form-row">
        <div class="smsone-form-group">
          <label for="firstName" class="smsone-label">${msg("firstName")} <span class="smsone-required">*</span></label>
          <input type="text" id="firstName" class="smsone-input" name="firstName" value="${(register.formData.firstName!'')}" autocomplete="given-name" />
        </div>
        <div class="smsone-form-group">
          <label for="lastName" class="smsone-label">${msg("lastName")} <span class="smsone-required">*</span></label>
          <input type="text" id="lastName" class="smsone-input" name="lastName" value="${(register.formData.lastName!'')}" autocomplete="family-name" />
        </div>
      </div>

      <div class="smsone-form-group">
        <label for="email" class="smsone-label">${msg("email")} <span class="smsone-required">*</span></label>
        <input type="email" id="email" class="smsone-input" name="email" value="${(register.formData.email!'')}" autocomplete="email" <#if messagesPerField.existsError('email')>aria-invalid="true"</#if> />
        <#if messagesPerField.existsError('email')>
          <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
            ${kcSanitize(messagesPerField.get('email'))?no_esc}
          </div>
        </#if>
      </div>

      <#if !realm.registrationEmailAsUsername>
        <div class="smsone-form-group">
          <label for="username" class="smsone-label">${msg("username")} <span class="smsone-required">*</span></label>
          <input type="text" id="username" class="smsone-input" name="username" value="${(register.formData.username!'')}" autocomplete="username" <#if messagesPerField.existsError('username')>aria-invalid="true"</#if> />
          <#if messagesPerField.existsError('username')>
            <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
              ${kcSanitize(messagesPerField.get('username'))?no_esc}
            </div>
          </#if>
        </div>
      </#if>

      <div class="smsone-form-row">
        <div class="smsone-form-group">
          <label for="password" class="smsone-label">${msg("password")} <span class="smsone-required">*</span></label>
          <div class="smsone-input-wrapper">
            <input type="password" id="password" class="smsone-input" name="password" autocomplete="new-password" <#if messagesPerField.existsError('password','password-confirm')>aria-invalid="true"</#if> />
            <button type="button" class="smsone-password-toggle" onclick="toggleRegisterPassword('password')" aria-label="Toggle password visibility">
              <svg id="password-eye-open" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                <circle cx="12" cy="12" r="3"></circle>
              </svg>
              <svg id="password-eye-closed" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: none;">
                <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path>
                <line x1="1" y1="1" x2="23" y2="23"></line>
              </svg>
            </button>
          </div>
        </div>
        <div class="smsone-form-group">
          <label for="password-confirm" class="smsone-label">${msg("passwordConfirm")} <span class="smsone-required">*</span></label>
          <div class="smsone-input-wrapper">
            <input type="password" id="password-confirm" class="smsone-input" name="password-confirm" autocomplete="new-password" <#if messagesPerField.existsError('password-confirm')>aria-invalid="true"</#if> />
            <button type="button" class="smsone-password-toggle" onclick="toggleRegisterPassword('password-confirm')" aria-label="Toggle password visibility">
              <svg id="password-confirm-eye-open" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                <circle cx="12" cy="12" r="3"></circle>
              </svg>
              <svg id="password-confirm-eye-closed" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: none;">
                <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path>
                <line x1="1" y1="1" x2="23" y2="23"></line>
              </svg>
            </button>
          </div>
        </div>
      </div>

      <#if messagesPerField.existsError('password','password-confirm')>
        <div class="smsone-form-group" style="margin-top: -0.75rem;">
          <div style="color: var(--smsone-error); font-size: 0.75rem;">
            ${kcSanitize(messagesPerField.getFirstError('password','password-confirm'))?no_esc}
          </div>
        </div>
      </#if>

      <div class="smsone-form-group" style="margin-top: 2rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doRegister')}"/>
      </div>
    </form>

    <script>
      function toggleRegisterPassword(inputId) {
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
  <#elseif section = "info">
    <div class="smsone-inline-note">
      ${msg("backToLogin")} <a class="smsone-link" href="${url.loginUrl}">${msg("doLogIn")}</a>
    </div>
  </#if>
</@layout.registrationLayout>
