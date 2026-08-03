<#import "template.ftl" as layout>

<@layout.registrationLayout displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">Passkey registration failed</h1>
    <p class="smsone-subtitle">We could not complete the passkey flow. Try again or cancel.</p>

    <script type="text/javascript">
      <#outputformat "JavaScript">
      refreshPage = () => {
          document.getElementById('isSetRetry').value = 'retry';
          document.getElementById('executionValue').value = ${execution?c};
          document.getElementById('kc-error-credential-form').requestSubmit();
      }
      </#outputformat>
    </script>

    <form id="kc-error-credential-form" action="${url.loginAction}" method="post" hidden="hidden">
      <input type="hidden" id="executionValue" name="authenticationExecution"/>
      <input type="hidden" id="isSetRetry" name="isSetRetry"/>
    </form>

    <div class="smsone-form-group">
      <button
        id="kc-try-again"
        type="button"
        class="smsone-button-primary"
        onclick="refreshPage()"
      >
        ${msg("doTryAgain")}
      </button>
    </div>

    <#if isAppInitiatedAction??>
      <form action="${url.loginAction}" class="smsone-form-group" style="margin-top: 1rem;" id="kc-webauthn-settings-form" method="post">
        <button
          id="cancelWebAuthnAIA"
          name="cancel-aia"
          type="submit"
          class="smsone-button-primary"
          style="background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);"
        >
          ${msg("doCancel")}
        </button>
      </form>
    </#if>
  </#if>
</@layout.registrationLayout>
