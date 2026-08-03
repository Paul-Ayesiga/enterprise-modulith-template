<#macro registrationLayout displayInfo=false displayMessage=true displayRequiredFields=false wide=false>
<!DOCTYPE html>
<html lang="${properties.kcHtmlLanguage!'en'}">
<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
    <meta name="robots" content="noindex, nofollow">

    <title>${msg("loginTitle",(realm.displayName!'SMSONE'))}</title>

    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.svg" type="image/svg+xml" />
</head>

<body>
    <#-- Two-column shell: brand panel (sticky, collapses to a compact bar on small
         screens) beside the form panel. The product name everywhere is the realm's
         displayName — set from the BRAND_NAME env var at realm import — with a
         static SMSONE fallback so the theme renders even without one. -->
    <div class="smsone-layout">

        <aside class="smsone-brand-panel">
            <div class="smsone-brand-top">
                <#if properties.kcLogoLink??>
                    <a href="${properties.kcLogoLink}" aria-label="${realm.displayName!'SMSONE'}" class="smsone-logo-link">
                        <span class="smsone-brand-tile"><img src="${url.resourcesPath}/img/smsone-mark.svg" alt="" class="smsone-brand-mark" /></span>
                        <span class="smsone-wordmark">${realm.displayName!'SMSONE'}</span>
                    </a>
                <#else>
                    <span class="smsone-logo-link">
                        <span class="smsone-brand-tile"><img src="${url.resourcesPath}/img/smsone-mark.svg" alt="" class="smsone-brand-mark" /></span>
                        <span class="smsone-wordmark">${realm.displayName!'SMSONE'}</span>
                    </span>
                </#if>
            </div>

            <div class="smsone-brand-copy">
                <h2 class="smsone-brand-headline">${msg("brandHeadline")}</h2>
                <p class="smsone-brand-subline">${msg("brandSubline")}</p>
            </div>

            <div class="smsone-brand-foot">&copy; ${.now?string('yyyy')} ${realm.displayName!'SMSONE'}</div>
        </aside>

        <main class="smsone-form-panel">
            <div class="smsone-form-area">
                <div class="smsone-card <#if wide>smsone-card--wide</#if>">
                    <#nested "header">

                    <#if displayRequiredFields>
                        <p class="smsone-required-note">${msg("requiredFields")}</p>
                    </#if>

                    <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                        <div class="smsone-alert smsone-alert-${message.type}">
                            ${kcSanitize(message.summary)?no_esc}
                        </div>
                    </#if>

                    <#nested "form">

                    <#if displayInfo>
                        <div class="smsone-page-info">
                            <#nested "info">
                        </div>
                    </#if>
                </div>
            </div>

            <footer class="smsone-footer">
                <div class="smsone-copyright">&copy; ${.now?string('yyyy')} ${realm.displayName!'SMSONE'}</div>
                <div class="smsone-footer-links">
                    <a href="#">${msg("brandTermsLink")}</a>
                    <a href="#">${msg("brandPrivacyLink")}</a>
                </div>
            </footer>
        </main>

    </div>

    <script src="${url.resourcesPath}/js/smsone-enhance.js?v=20260803b" defer></script>
</body>
</html>
</#macro>
