<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp','userLabel') displayInfo=true wide=true; section>
  <#if section = "form">
    <h1 class="smsone-title">Secure your account</h1>
    <p class="smsone-subtitle">Set up your authenticator app to continue.</p>

    <div style="display: flex; gap: 2.5rem; margin-bottom: 2rem; flex-wrap: wrap;">
        <!-- Left Column: Instructions -->
        <div style="flex: 1; min-width: 280px;">
            <div style="margin-bottom: 1.5rem;">
                <p style="font-weight: 600; font-size: 0.875rem; margin-bottom: 0.5rem;">1. ${msg("loginTotpStep1")}</p>
                <ul style="font-size: 0.8125rem; color: var(--smsone-text-muted); list-style: disc; padding-left: 1.5rem; line-height: 1.6;">
                    <#list totp.supportedApplications as app>
                        <li>${msg(app)}</li>
                    </#list>
                </ul>
            </div>

            <div style="margin-bottom: 1.5rem;">
                <#if mode?? && mode = "manual">
                    <p style="font-weight: 600; font-size: 0.875rem; margin-bottom: 0.5rem;">2. ${msg("loginTotpManualStep2")}</p>
                    <p style="font-size: 0.8125rem; color: var(--smsone-text-muted); font-family: monospace; background: #f1f5f9; padding: 0.5rem; border-radius: 4px; word-break: break-all;">${totp.totpSecretEncoded}</p>
                    <p style="font-size: 0.75rem; margin-top: 0.5rem;"><a href="${totp.qrUrl}" class="smsone-link">${msg("loginTotpScanBarcode")}</a></p>
                <#else>
                    <p style="font-weight: 600; font-size: 0.875rem; margin-bottom: 0.5rem;">2. ${msg("loginTotpStep2")}</p>
                    <p style="font-size: 0.8125rem; color: var(--smsone-text-muted);">${msg("loginTotpStep2")}</p>
                    <p style="font-size: 0.75rem; margin-top: 0.5rem;"><a href="${totp.manualUrl}" class="smsone-link">${msg("loginTotpUnableToScan")}</a></p>
                </#if>
            </div>

            <div>
                <p style="font-weight: 600; font-size: 0.875rem; margin-bottom: 0.5rem;">3. ${msg("loginTotpStep3")}</p>
                <p style="font-size: 0.8125rem; color: var(--smsone-text-muted);">${msg("loginTotpStep3DeviceName")}</p>
            </div>
        </div>

        <!-- Right Column: QR Code & Inputs -->
        <div style="flex: 1; min-width: 280px;">
            <#if !(mode?? && mode = "manual")>
                <div style="text-align: center; background: #fff; padding: 1rem; border: 1px solid var(--smsone-border); border-radius: 4px; margin-bottom: 2rem;">
                    <img id="kc-totp-secret-qr-code" src="data:image/png;base64, ${totp.totpSecretQrCode}" alt="Authenticator QR code" style="width: 180px; height: 180px;" />
                </div>
            <#else>
                <div style="background: #f8fafc; padding: 1rem; border: 1px solid var(--smsone-border); border-radius: 4px; margin-bottom: 2rem; font-size: 0.75rem;">
                    <ul style="list-style: none; padding: 0;">
                        <li><strong>${msg("loginTotpType")}:</strong> ${msg("loginTotp." + totp.policy.type)}</li>
                        <li><strong>${msg("loginTotpAlgorithm")}:</strong> ${totp.policy.getAlgorithmKey()}</li>
                        <li><strong>${msg("loginTotpDigits")}:</strong> ${totp.policy.digits}</li>
                        <#if totp.policy.type = "totp">
                            <li><strong>${msg("loginTotpInterval")}:</strong> ${totp.policy.period}</li>
                        <#elseif totp.policy.type = "hotp">
                            <li><strong>${msg("loginTotpCounter")}:</strong> ${totp.policy.initialCounter}</li>
                        </#if>
                    </ul>
                </div>
            </#if>

            <form action="${url.loginAction}" id="kc-totp-settings-form" method="post">
                <div class="smsone-form-group">
                    <label for="totp" class="smsone-label">${msg("authenticatorCode")} <span class="smsone-required">*</span></label>
                    <div class="smsone-input-wrapper">
                        <input type="text" id="totp" name="totp" autocomplete="one-time-code" class="smsone-input" aria-invalid="<#if messagesPerField.existsError('totp')>true<#else>false</#if>" inputmode="numeric" autofocus />
                    </div>
                    <#if messagesPerField.existsError('totp')>
                        <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
                            ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                        </div>
                    </#if>
                </div>

                <div class="smsone-form-group">
                    <label for="userLabel" class="smsone-label">${msg("loginTotpDeviceName")}</label>
                    <div class="smsone-input-wrapper">
                        <input type="text" class="smsone-input" id="userLabel" name="userLabel" autocomplete="off" aria-invalid="<#if messagesPerField.existsError('userLabel')>true<#else>false</#if>" placeholder="e.g. My Phone" />
                    </div>
                    <#if messagesPerField.existsError('userLabel')>
                        <div style="color: var(--smsone-error); font-size: 0.75rem; margin-top: 0.25rem;">
                            ${kcSanitize(messagesPerField.get('userLabel'))?no_esc}
                        </div>
                    </#if>
                </div>

                <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}" />
                <#if mode??><input type="hidden" id="mode" name="mode" value="${mode}" /></#if>

                <div class="smsone-form-group" style="display: flex; align-items: center; gap: 0.5rem; margin-top: 1rem;">
                    <input type="checkbox" class="smsone-checkbox" id="logout-sessions" name="logout-sessions" value="on">
                    <label for="logout-sessions" style="font-size: 0.8125rem; color: var(--smsone-text-muted); cursor: pointer;">${msg("logoutOtherSessions")}</label>
                </div>

                <div class="smsone-form-group" style="margin-top: 2rem;">
                    <input class="smsone-button-primary" type="submit" value="${msg('doSubmit')}"/>
                </div>

                <#if isAppInitiatedAction??>
                    <div style="text-align: center; margin-top: 1rem;">
                        <button type="submit" name="cancel-aia" value="true" class="smsone-link" style="background:none; border:none; cursor:pointer; font-weight: inherit; font-size: inherit;">${msg("doCancel")}</button>
                    </div>
                </#if>
            </form>
        </div>
    </div>
  </#if>
</@layout.registrationLayout>
