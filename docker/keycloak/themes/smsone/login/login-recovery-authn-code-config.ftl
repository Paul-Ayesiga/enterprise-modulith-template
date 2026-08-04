<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=true wide=true; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("recovery-code-config-header")}</h1>
    <p class="smsone-subtitle">Save your recovery codes in a secure place before continuing. These can be used to access your account if you lose your authenticator device.</p>

    <div style="background-color: #fff7ed; border-left: 4px solid #f97316; padding: 1rem; border-radius: 4px; margin-bottom: 2rem;">
        <p style="font-size: 0.8125rem; color: #9a3412; font-weight: 500;">
            ${msg("recovery-code-config-warning-title")} — ${msg("recovery-code-config-warning-message")}
        </p>
    </div>

    <ul id="kc-recovery-codes-list" style="list-style: none; padding: 0; margin-bottom: 2rem; display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 1rem;">
        <#list recoveryAuthnCodesConfigBean.generatedRecoveryAuthnCodesList as code>
          <li style="border: 1px solid var(--smsone-border); border-radius: 4px; padding: 0.75rem; background: #f8fafc; font-family: monospace; font-size: 1rem; color: var(--smsone-text-main); display: flex; align-items: center;">
            <span style="color: var(--smsone-text-muted); font-size: 0.75rem; margin-right: 0.75rem;">${code?counter}.</span>
            <span style="letter-spacing: 0.05em;">${code[0..3]}-${code[4..7]}-${code[8..]}</span>
          </li>
        </#list>
    </ul>

    <div style="display: flex; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 2.5rem;">
        <button id="printRecoveryCodes" class="smsone-button-primary" type="button" style="flex: 1; min-width: 140px; background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);">
          ${msg("recovery-codes-print")}
        </button>
        <button id="downloadRecoveryCodes" class="smsone-button-primary" type="button" style="flex: 1; min-width: 140px; background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);">
          ${msg("recovery-codes-download")}
        </button>
        <button id="copyRecoveryCodes" class="smsone-button-primary" type="button" style="flex: 1; min-width: 140px; background-color: transparent; border: 1px solid var(--smsone-border); color: var(--smsone-text-main);">
          ${msg("recovery-codes-copy")}
        </button>
    </div>

    <form action="${url.loginAction}" id="kc-recovery-codes-settings-form" method="post">
        <input type="hidden" name="generatedRecoveryAuthnCodes" value="${recoveryAuthnCodesConfigBean.generatedRecoveryAuthnCodesAsString}" />
        <input type="hidden" name="generatedAt" value="${recoveryAuthnCodesConfigBean.generatedAt?c}" />
        <input type="hidden" id="userLabel" name="userLabel" value="${msg("recovery-codes-label-default")}" />

        <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem; margin-bottom: 1.5rem;">
          <input
            type="checkbox"
            id="kcRecoveryCodesConfirmationCheck"
            name="kcRecoveryCodesConfirmationCheck"
            onchange="document.getElementById('saveRecoveryAuthnCodesBtn').disabled = !this.checked;"
            style="cursor: pointer;"
          />
          <label for="kcRecoveryCodesConfirmationCheck" style="font-size: 0.8125rem; color: var(--smsone-text-muted); cursor: pointer; user-select: none;">
            ${msg("recovery-codes-confirmation-message")}
          </label>
        </div>

        <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem; margin-bottom: 2rem;">
            <input type="checkbox" class="smsone-checkbox" id="logout-sessions" name="logout-sessions" value="on">
            <label for="logout-sessions" style="font-size: 0.8125rem; color: var(--smsone-text-muted); cursor: pointer;">${msg("logoutOtherSessions")}</label>
        </div>

        <div class="smsone-form-group">
            <input
              type="submit"
              class="smsone-button-primary"
              id="saveRecoveryAuthnCodesBtn"
              value="${msg("recovery-codes-action-complete")}"
              disabled
            />
            
            <#if isAppInitiatedAction??>
                <div style="text-align: center; margin-top: 1rem;">
                    <button type="submit" name="cancel-aia" value="true" class="smsone-link" style="background:none; border:none; cursor:pointer; font-weight: inherit; font-size: inherit;">${msg("doCancel")}</button>
                </div>
            </#if>
        </div>
    </form>

    <script>
      (function () {
        function recoveryCodesAsText() {
          var codes = document.querySelectorAll('#kc-recovery-codes-list li');
          var lines = [];
          for (var i = 0; i < codes.length; i++) {
            lines.push(codes[i].innerText.trim());
          }
          return lines.join('\n');
        }

        function formatCurrentDateTime() {
          var dt = new Date();
          return dt.toLocaleString('en-US', {
            month: 'long',
            day: 'numeric',
            year: 'numeric',
            hour: 'numeric',
            minute: 'numeric',
            timeZoneName: 'short'
          });
        }

        function buildDownloadContent() {
          return (
            '${msg("recovery-codes-download-file-header")}\n\n' +
            recoveryCodesAsText() + '\n\n' +
            '${msg("recovery-codes-download-file-description")}\n\n' +
            '${msg("recovery-codes-download-file-date")} ' + formatCurrentDateTime()
          );
        }

        function copyRecoveryCodes() {
          var text = recoveryCodesAsText();
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).catch(function () {});
            return;
          }

          var tmpTextarea = document.createElement('textarea');
          tmpTextarea.value = text;
          document.body.appendChild(tmpTextarea);
          tmpTextarea.select();
          document.execCommand('copy');
          document.body.removeChild(tmpTextarea);
        }

        function downloadRecoveryCodes() {
          var link = document.createElement('a');
          link.setAttribute('href', 'data:text/plain;charset=utf-8,' + encodeURIComponent(buildDownloadContent()));
          link.setAttribute('download', 'kc-download-recovery-codes.txt');
          link.style.display = 'none';
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
        }

        function buildPrintContent() {
          var recoveryCodeListHTML = document.getElementById('kc-recovery-codes-list').innerHTML;
          var styles =
            '@page { size: auto; margin-top: 0; }' +
            'body { width: 480px; font-family: Inter, Arial, sans-serif; color: #18213f; }' +
            'ul { list-style: none; padding: 0; }' +
            'li { margin-bottom: 8px; font-family: monospace; border: 1px solid #e2e8f0; padding: 8px; border-radius: 4px; }' +
            'p:first-of-type { margin-top: 48px; }';

          return (
            '<html><head><title>kc-download-recovery-codes</title><style>' + styles + '</style></head><body>' +
            '<p>${msg("recovery-codes-download-file-header")}</p>' +
            '<ul>' + recoveryCodeListHTML + '</ul>' +
            '<p>${msg("recovery-codes-download-file-description")}</p>' +
            '<p>${msg("recovery-codes-download-file-date")} ' + formatCurrentDateTime() + '</p>' +
            '</body></html>'
          );
        }

        function printRecoveryCodes() {
          var w = window.open('', '_blank');
          if (!w) return;
          w.document.write(buildPrintContent());
          w.document.close();
          w.focus();
          w.print();
          w.close();
        }

        var copyButton = document.getElementById('copyRecoveryCodes');
        var downloadButton = document.getElementById('downloadRecoveryCodes');
        var printButton = document.getElementById('printRecoveryCodes');

        if (copyButton) copyButton.addEventListener('click', copyRecoveryCodes);
        if (downloadButton) downloadButton.addEventListener('click', downloadRecoveryCodes);
        if (printButton) printButton.addEventListener('click', printRecoveryCodes);
      })();
    </script>
  </#if>
</@layout.registrationLayout>
