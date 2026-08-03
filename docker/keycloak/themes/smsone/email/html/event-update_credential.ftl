<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" lang="en">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Security Alert: Passkey Updated - ${realmName!'SMSOne'}</title>
</head>
<body style="margin:0;padding:0;background-color:#FAFAF7;font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#FAFAF7;padding:40px 20px;">
    <tr>
      <td align="center">
        <!-- Main Card Wrapper -->
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:540px;background-color:#ffffff;border-radius:4px;overflow:hidden;box-shadow: 0 4px 15px rgba(0, 0, 0, 0.05);">
          <!-- Header -->
          <tr>
            <td style="padding:40px 40px 0;text-align:center;">
                <div style="font-family:'Century Gothic','Avant Garde',Helvetica,Arial,sans-serif;font-size:26px;font-weight:700;color:#10218B;text-align:center;letter-spacing:-0.01em;">${realmName!'SMSOne'}</div>
            </td>
          </tr>
          
          <!-- Content Body -->
          <tr>
            <td style="padding:40px;">
              <h1 style="margin:0 0 16px;font-size:24px;font-weight:700;line-height:1.2;color:#1e293b;text-align:center;">
                Security Alert: Passkey Updated
              </h1>
              
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#334155;text-align:center;">
                Hello ${(user.firstName!user.username!'there')},
              </p>
              
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#334155;text-align:center;">
                A security credential (such as a <strong>Passkey</strong> or <strong>Security Key</strong>) was recently updated or added to your account on the <strong>${realmName}</strong> portal.
              </p>

              <!-- Event Details -->
              <div style="margin:0 0 32px;padding:24px;border-radius:4px;background-color:#f8fafc;border:1px solid #e2e8f0;text-align:left;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                  <tr>
                    <td style="padding:4px 0;font-size:14px;color:#64748b;width:100px;">Time:</td>
                    <td style="padding:4px 0;font-size:14px;font-weight:600;color:#1e293b;">${event.date?datetime}</td>
                  </tr>
                  <tr>
                    <td style="padding:4px 0;font-size:14px;color:#64748b;">IP Address:</td>
                    <td style="padding:4px 0;font-size:14px;font-weight:600;color:#1e293b;">${event.ipAddress}</td>
                  </tr>
                </table>
              </div>

              <p style="margin:0 0 24px;font-size:14px;line-height:1.6;color:#dc2626;text-align:center;font-weight:500;">
                If you did not authorized this change, please contact your system administrator immediately to secure your account.
              </p>
              
              <!-- Action Button (Always visible with fallback) -->
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                <tr>
                  <td align="center">
                    <a href="${(url.accountUrl)!'https://smsone.co.ug'}" style="display:inline-block;padding:12px 32px;font-size:14px;font-weight:600;line-height:1;text-decoration:none;color:#ffffff;background-color:#3C5DAA;border-radius:4px;">
                      View Account Security
                    </a>
                  </td>
                </tr>
              </table>
            </td>
          </tr>
          
          <!-- Footer -->
          <tr>
            <td style="padding:32px 40px;background-color:#ffffff;border-top:1px solid #f1f5f9;text-align:center;">
              <p style="margin:0 0 8px;font-size:12px;font-weight:700;color:#1e293b;text-transform:uppercase;letter-spacing:0.05em;">
                ${realmName!'SMSOne'}
              </p>
              <p style="margin:0;font-size:12px;color:#94a3b8;line-height:1.6;">
                Copyright &copy; 2026 ${realmName!'SMSOne'}. All Rights Reserved.<br />
                Connecting communities, simplifying living.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
