<#--
  Email-verification link.

  Sent on registration (or whenever VERIFY_EMAIL is queued as a
  required action) so Keycloak can prove the user owns the address.

  Vars supplied by Keycloak:
    - user.firstName · user.username
    - link
    - linkExpiration (raw minutes int, sometimes thousands-separated)
    - url.resourcesUrl
-->
<#assign totalMinutes = linkExpiration?replace(",", "")?number>
<#assign expHours = (totalMinutes / 60)?floor>
<#assign expMinutes = (totalMinutes - (expHours * 60))?floor>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" lang="en">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Verify your email — ${realmName!'SMSOne'}</title>
</head>
<body style="margin:0;padding:0;background-color:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;color:#0f172a;-webkit-font-smoothing:antialiased;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#f5f5f4;padding:24px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:560px;background-color:#ffffff;border-radius:8px;border:1px solid #e5e7eb;overflow:hidden;">
          <tr>
            <td style="padding:32px 32px 0 32px;">
              <div style="font-family:'Century Gothic','Avant Garde',Helvetica,Arial,sans-serif;font-size:26px;font-weight:700;color:#10218B;text-align:center;letter-spacing:-0.01em;">${realmName!'SMSOne'}</div>
              <div style="margin-top:18px;font-size:11px;letter-spacing:0.06em;text-transform:uppercase;color:#737373;font-weight:500;">${realmName!'SMSOne'} · Email verification</div>
              <h1 style="margin:6px 0 0 0;font-size:22px;font-weight:600;line-height:1.25;color:#0f172a;">
                Confirm your email address
              </h1>
            </td>
          </tr>

          <tr>
            <td style="padding:20px 32px 0 32px;">
              <p style="margin:0;font-size:14px;line-height:1.6;color:#404040;">
                Hi ${(user.firstName!user.username!'there')}, thanks for signing up to ${realmName!'SMSOne'}.
                Confirm your email address to finish setting up your account.
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding:28px 32px 0 32px;">
              <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="background:#0f172a;border-radius:6px;">
                <a href="${link}" style="display:inline-block;padding:11px 22px;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;letter-spacing:-0.1px;">Confirm email →</a>
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
                If you didn&rsquo;t sign up for ${realmName!'SMSOne'}, you can safely ignore this email.
              </div>
            </td>
          </tr>

          <tr>
            <td style="padding:32px 32px 24px 32px;">
              <div style="border-top:1px solid #e5e7eb;padding-top:16px;font-size:11px;line-height:1.55;color:#a3a3a3;">
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
