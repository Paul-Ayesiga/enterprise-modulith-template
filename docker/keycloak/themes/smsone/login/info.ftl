<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=false displayInfo=false; section>
  <#if section = "form">
    <div id="kc-info-message">
      <h1 class="smsone-title">
        <#if messageHeader??>
          ${kcSanitize(msg(messageHeader))?no_esc}
        <#else>
          ${kcSanitize(message.summary)?no_esc}
        </#if>
      </h1>
      
      <p class="smsone-subtitle">
        ${kcSanitize(message.summary)?no_esc}
        <#if requiredActions??>
          <#list requiredActions>
            :
            <b>
              <#items as reqActionItem>
                ${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}<#sep>, </#items>
            </b>
          </#list>
        </#if>
      </p>

      <#if !skipLink??>
        <div class="smsone-form-group" style="margin-top: 2rem;">
          <#if pageRedirectUri?has_content>
            <a class="smsone-button-primary" style="display: block; text-align: center; text-decoration: none;" href="${pageRedirectUri}">
              ${msg("backToApplication")}
            </a>
          <#elseif actionUri?has_content>
            <a class="smsone-button-primary" style="display: block; text-align: center; text-decoration: none;" href="${actionUri}">
              ${msg("proceedWithAction")}
            </a>
          <#elseif (client.baseUrl)?has_content>
            <a class="smsone-button-primary" style="display: block; text-align: center; text-decoration: none;" href="${client.baseUrl}">
              ${msg("backToApplication")}
            </a>
          </#if>
        </div>
      </#if>
    </div>
  </#if>
</@layout.registrationLayout>
