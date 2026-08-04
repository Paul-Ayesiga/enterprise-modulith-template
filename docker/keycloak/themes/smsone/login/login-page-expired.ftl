<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=false displayInfo=true; section>
  <#if section = "form">
    <h1 class="smsone-title">${msg("pageExpiredTitle")}</h1>
    <p class="smsone-subtitle">${msg("pageExpiredMsg1")} <a id="loginContinueLink" class="smsone-link" href="${url.loginAction}">${msg("doClickHere")}</a>.</p>
    <p class="smsone-subtitle">${msg("pageExpiredMsg2")} <a id="loginRestartLink" class="smsone-link" href="${url.loginRestartFlowUrl}">${msg("doClickHere")}</a>.</p>
  </#if>
</@layout.registrationLayout>
