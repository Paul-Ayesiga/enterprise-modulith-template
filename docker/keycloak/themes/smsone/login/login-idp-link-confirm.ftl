<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=false; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("confirmLinkIdpTitle")}</h1>
    <p class="smsone-subtitle">${msg("confirmLinkIdpContinue", idpDisplayName)}</p>

    <form id="kc-register-form" action="${url.loginAction}" method="post">
      <div class="smsone-form-group" style="display: flex; flex-direction: column; gap: 1rem; margin-top: 2rem;">
        <button
          type="submit"
          class="smsone-button-primary"
          name="submitAction"
          id="linkAccount"
          value="linkAccount"
        >
          ${msg("confirmLinkIdpContinue", idpDisplayName)}
        </button>

        <#if !hideReviewButton?has_content>
          <button
            type="submit"
            class="smsone-button-primary"
            name="submitAction"
            id="updateProfile"
            value="updateProfile"
            style="background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);"
          >
            ${msg("confirmLinkIdpReviewProfile")}
          </button>
        </#if>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
