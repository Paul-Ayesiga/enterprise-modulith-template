<#--
  Plaintext version of the invitation email — see html/executeActions.ftl
  for the rationale + variable docs.
-->
<#-- linkExpiration comes through as a thousands-separated string
     (e.g. "1,440"); strip commas before ?number. -->
<#assign totalMinutes = linkExpiration?replace(",", "")?number>
<#assign expHours = (totalMinutes / 60)?floor>
<#assign expMinutes = (totalMinutes - (expHours * 60))?floor>
Hello ${(user.firstName!user.username!'there')},

Your community manager has invited you to ${realmName!'SMSOne'}. Set your password to finish setting up your portal access:

${link}

This link expires in <#if expHours gt 0>${expHours} hour<#if expHours gt 1>s</#if><#if expMinutes gt 0> and ${expMinutes} minute<#if expMinutes gt 1>s</#if></#if><#else>${expMinutes} minute<#if expMinutes gt 1>s</#if></#if>. If you weren't expecting this email, you can safely ignore it.

—
${realmName!'SMSOne'}
Connecting communities, simplifying living.
