<#--
  Invitation magic-link email (UPDATE_PASSWORD + VERIFY_EMAIL).
  Sent by KeycloakAdminClient::executeActionsEmail() on:
    - PersonService::create with web_enabled + email, OR
    - PersonController::reinvite (CM clicks "Resend invite")

  Why we don't use Keycloak's stock copy: the default executeActions
  template reads like an ops alert ("An administrator has requested
  that you perform the following account actions"). For a resident
  receiving their first invite that's confusing — this template frames
  it as a welcome.

  linkExpiration arrives as raw minutes. Keycloak's default would
  print "1,440 minutes". We do the math inline so the copy reads
  "24 hours" or "30 minutes" depending on realm config.

  Vars supplied by Keycloak:
    - user.firstName · user.username
    - link
    - linkExpiration (raw minutes int, sometimes thousands-separated)
    - url.resourcesUrl
    - realmName (display name, "SMSOne")
-->
<#-- Keycloak hands linkExpiration in as a thousands-separated string
     (e.g. "1,440"). Strip the commas before ?number or FreeMarker
     throws NonNumericalException. -->
<#assign totalMinutes = linkExpiration?replace(",", "")?number>
<#assign expHours = (totalMinutes / 60)?floor>
<#assign expMinutes = (totalMinutes - (expHours * 60))?floor>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" lang="en">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Welcome to your ${realmName!'SMSOne'} portal</title>
</head>
<body style="margin:0;padding:0;background-color:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;color:#0f172a;-webkit-font-smoothing:antialiased;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#f5f5f4;padding:24px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:560px;background-color:#ffffff;border-radius:8px;border:1px solid #e5e7eb;overflow:hidden;">
          <tr>
            <td style="padding:32px 32px 0 32px;">
              <div style="font-family:'Century Gothic','Avant Garde',Helvetica,Arial,sans-serif;font-size:26px;font-weight:700;color:#10218B;text-align:center;letter-spacing:-0.01em;">${realmName!'SMSOne'}</div>
              <div style="margin-top:18px;font-size:11px;letter-spacing:0.06em;text-transform:uppercase;color:#737373;font-weight:500;">${realmName!'SMSOne'} · Invitation</div>
              <h1 style="margin:6px 0 0 0;font-size:22px;font-weight:600;line-height:1.25;color:#0f172a;">
                Welcome to your portal
              </h1>
            </td>
          </tr>

          <tr>
            <td style="padding:20px 32px 0 32px;">
              <p style="margin:0;font-size:14px;line-height:1.6;color:#404040;">
                Hi ${(user.firstName!user.username!'there')}, your community manager has invited you to ${realmName!'SMSOne'}.
                Click the button below to set your password — that&rsquo;s all it takes.
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding:28px 32px 0 32px;">
              <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="background:#0f172a;border-radius:6px;">
                <a href="${link}" style="display:inline-block;padding:11px 22px;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;letter-spacing:-0.1px;">Set up my account →</a>
              </td></tr></table>
              <p style="margin:14px 0 0 0;font-size:12px;color:#737373;">
                Or paste this link into your browser:<br>
                <a href="${link}" style="color:#404040;word-break:break-all;">${link}</a>
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding:24px 32px 0 32px;">
              <div style="padding:14px 16px;border:1px solid #e5e7eb;border-radius:6px;background:#fafaf9;font-size:12px;line-height:1.55;color:#737373;">
                This link expires in
                <#if expHours gt 0>
                  <strong style="color:#0f172a;">${expHours} hour<#if expHours gt 1>s</#if></strong><#if expMinutes gt 0> and <strong style="color:#0f172a;">${expMinutes} minute<#if expMinutes gt 1>s</#if></strong></#if>.
                <#else>
                  <strong style="color:#0f172a;">${expMinutes} minute<#if expMinutes gt 1>s</#if></strong>.
                </#if>
                If you weren&rsquo;t expecting this email, you can safely ignore it.
              </div>
            </td>
          </tr>

          <tr>
            <td style="padding:32px 32px 24px 32px;">
              <div style="border-top:1px solid #e5e7eb;padding-top:16px;font-size:11px;line-height:1.55;color:#a3a3a3;">
                You&rsquo;re receiving this because a community on ${realmName!'SMSOne'} was set up with your email.
                Questions? Reply to this email or write to <a href="mailto:hello@smsone.co.ug" style="color:#737373;text-decoration:underline;">hello@smsone.co.ug</a>.
              </div>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
