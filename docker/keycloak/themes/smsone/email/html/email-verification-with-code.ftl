<#--
  Email-verification one-time code (no link, code-only).

  Sent when Keycloak's REQUIRED_ACTION emits the code-based flow
  (mobile-friendly path used when device sign-in doesn't have a
  reliable redirect target).

  Vars supplied by Keycloak:
    - user.firstName · user.username
    - code (6-digit string)
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
  <title>Your ${realmName!'SMSONE'} verification code</title>
</head>
<body style="margin:0;padding:0;background-color:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;color:#0f172a;-webkit-font-smoothing:antialiased;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#f5f5f4;padding:24px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:560px;background-color:#ffffff;border-radius:8px;border:1px solid #e5e7eb;overflow:hidden;">
          <tr>
            <td style="padding:32px 32px 0 32px;">
              <div style="font-family:'Century Gothic','Avant Garde',Helvetica,Arial,sans-serif;font-size:26px;font-weight:700;color:#10218B;text-align:center;letter-spacing:-0.01em;">${realmName!'SMSONE'}</div>
              <div style="margin-top:18px;font-size:11px;letter-spacing:0.06em;text-transform:uppercase;color:#737373;font-weight:500;">${realmName!'SMSONE'} · Verification code</div>
              <h1 style="margin:6px 0 0 0;font-size:22px;font-weight:600;line-height:1.25;color:#0f172a;">
                Confirm your email
              </h1>
            </td>
          </tr>

          <tr>
            <td style="padding:20px 32px 0 32px;">
              <p style="margin:0;font-size:14px;line-height:1.6;color:#404040;">
                Hi ${(user.firstName!user.username!'there')}, enter the code below in the verification screen to finish signing in.
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding:24px 32px 0 32px;">
              <table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;border-collapse:collapse;border:1px solid #e5e7eb;border-radius:6px;background:#fafaf9;">
                <tr><td style="padding:22px 24px;text-align:center;">
                  <div style="font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#737373;font-weight:500;">Verification code</div>
                  <div style="margin-top:8px;font-family:'SF Mono',ui-monospace,Menlo,Consolas,monospace;font-size:30px;font-weight:600;letter-spacing:6px;color:#0f172a;">${code}</div>
                </td></tr>
              </table>
              <p style="margin:14px 0 0 0;font-size:12px;color:#737373;">
                The code expires in
                <#if expHours gt 0>
                  <strong style="color:#0f172a;">${expHours} hour<#if expHours gt 1>s</#if></strong><#if expMinutes gt 0> and <strong style="color:#0f172a;">${expMinutes} minute<#if expMinutes gt 1>s</#if></strong></#if>
                <#else>
                  <strong style="color:#0f172a;">${expMinutes} minute<#if expMinutes gt 1>s</#if></strong>
                </#if>
                and can only be used once. If you didn&rsquo;t ask for it, ignore this email.
              </p>
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
