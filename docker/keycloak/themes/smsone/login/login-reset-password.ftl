<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username') displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("emailForgotTitle")}</h1>
    <p class="smsone-subtitle">
      <#if realm.duplicateEmailsAllowed>
        ${msg("emailInstructionUsername")}
      <#else>
        ${msg("emailInstruction")}
      </#if>
    </p>

    <form id="kc-reset-password-form" action="${url.loginAction}" method="post">
      <div class="smsone-form-group">
        <label for="username" class="smsone-label">
          <#if !realm.loginWithEmailAllowed>
            ${msg("username")}
          <#elseif !realm.registrationEmailAsUsername>
            ${msg("usernameOrEmail")}
          <#else>
            ${msg("email")}
          </#if>
        </label>
        
        <div class="smsone-input-wrapper">
          <input type="text" id="username" name="username" class="smsone-input" autofocus value="${(auth.attemptedUsername!'')}" aria-invalid="<#if messagesPerField.existsError('username')>true<#else>false</#if>" autocomplete="username" placeholder="Enter your email or username" />
        </div>
        
        <#if messagesPerField.existsError('username')>
          <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
            ${kcSanitize(messagesPerField.get('username'))?no_esc}
          </div>
        </#if>
      </div>

      <div class="smsone-form-group" style="margin-top: 1.5rem;">
        <input class="smsone-button-primary" type="submit" value="${msg('doSubmit')}"/>
      </div>

      <div style="text-align: center; margin-top: 1rem;">
          <a class="smsone-link" href="${url.loginUrl}">${msg("backToLogin")}</a>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
