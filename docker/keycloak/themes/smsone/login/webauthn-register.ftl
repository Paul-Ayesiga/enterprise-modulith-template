<#import "template.ftl" as layout>

<@layout.registrationLayout displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">Set up a passkey</h1>
    <p class="smsone-subtitle">We will prompt your device to create a passkey for faster and safer sign-in. Use your device biometrics or security key to finish registration.</p>

    <form id="register" class="smsone-form-group" action="${url.loginAction}" method="post">
      <input type="hidden" id="clientDataJSON" name="clientDataJSON"/>
      <input type="hidden" id="attestationObject" name="attestationObject"/>
      <input type="hidden" id="publicKeyCredentialId" name="publicKeyCredentialId"/>
      <input type="hidden" id="authenticatorLabel" name="authenticatorLabel"/>
      <input type="hidden" id="transports" name="transports"/>
      <input type="hidden" id="error" name="error"/>

      <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem; margin-bottom: 2rem;">
          <input type="checkbox" class="smsone-checkbox" id="logout-sessions" name="logout-sessions" value="on">
          <label for="logout-sessions" style="font-size: 0.875rem; color: var(--smsone-text-muted); cursor: pointer; user-select: none;">
            ${msg("logoutOtherSessions")}
          </label>
      </div>

      <div class="smsone-form-group">
        <button
          id="registerWebAuthn"
          type="button"
          class="smsone-button-primary"
        >
          ${msg("doRegisterSecurityKey")}
        </button>
      </div>
    </form>

    <#if !isSetRetry?has_content && isAppInitiatedAction?has_content>
      <form class="smsone-form-group" style="margin-top: 1rem;" action="${url.loginAction}" id="kc-webauthn-settings-form" method="post">
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

    <script type="module">
      <#outputformat "JavaScript">
      import { registerByWebAuthn } from "${url.resourcesPath}/js/webauthnRegister.js";
      const registerButton = document.getElementById('registerWebAuthn');
      registerButton.addEventListener("click", function() {
          if (registerButton.dataset.busy === "true") {
              return;
          }
          registerButton.dataset.busy = "true";
          registerButton.disabled = true;
          const input = {
              challenge : ${challenge?c},
              userid : ${userid?c},
              username : ${username?c},
              signatureAlgorithms : [<#list signatureAlgorithms as sigAlg>${sigAlg?c},</#list>],
              rpEntityName : ${rpEntityName?c},
              rpId : ${rpId?c},
              attestationConveyancePreference : ${attestationConveyancePreference?c},
              authenticatorAttachment : ${authenticatorAttachment?c},
              requireResidentKey : ${requireResidentKey?c},
              userVerificationRequirement : ${userVerificationRequirement?c},
              createTimeout : ${createTimeout?c},
              excludeCredentialIds : ${excludeCredentialIds?c},
              initLabel : ${msg("webauthn-registration-init-label")?c},
              initLabelPrompt : ${msg("webauthn-registration-init-label-prompt")?c},
              errmsg : ${msg("webauthn-unsupported-browser-text")?c}
          };
          registerByWebAuthn(input)
            .finally(() => {
              registerButton.dataset.busy = "false";
              registerButton.disabled = false;
            });
      });
      </#outputformat>
    </script>
  </#if>
</@layout.registrationLayout>
