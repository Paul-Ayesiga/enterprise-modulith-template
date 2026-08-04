Hello ${(user.firstName!user.username!'there')},

A login attempt to your ${realmName!'SMSONE'} account was rejected due to an incorrect password or other login failure.

Time: ${event.date?datetime?string('dd MMM yyyy, HH:mm:ss')}
IP Address: ${event.ipAddress}
<#if event.details?? && event.details?has_content>
<#if event.details?is_hash_ex>
<#list event.details as key, value>
${key}: ${value}
</#list>
<#elseif event.details?is_sequence>
<#list event.details as item>
Details: ${item}
</#list>
<#else>
Details: ${event.details}
</#if>
</#if>

If this was you, you can safely ignore this email. However, if you do not recognize this attempt, we recommend that you reset your password immediately.

Reset your password: ${url.loginUrl}

${realmName!'SMSONE'}
Copyright © 2026 ${realmName!'SMSONE'}. All Rights Reserved.
One platform for your organizations.
