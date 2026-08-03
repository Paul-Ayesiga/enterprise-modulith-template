<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" lang="en">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Password Changed - ${realmName!'SMSOne'}</title>
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
                Password Changed
              </h1>
              
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#334155;text-align:center;">
                Hello ${(user.firstName!user.username!'there')},
              </p>
              
              <p style="margin:0 0 32px;font-size:16px;line-height:1.6;color:#334155;text-align:center;">
                This is a confirmation that the password for your ${realmName!'SMSOne'} account was successfully updated.
              </p>
              
              <div style="margin:0 0 32px;padding:24px;background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:4px;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                  <tr>
                    <td style="font-size:14px;color:#64748b;line-height:1.6;">
                      <strong>Time:</strong> ${event.date}<br />
                      <strong>IP Address:</strong> ${event.ipAddress}
                    </td>
                  </tr>
                </table>
              </div>

              <p style="margin:0;font-size:14px;line-height:1.5;color:#64748b;text-align:center;">
                If you did not perform this action, please contact your system administrator or secure your account immediately.
              </p>
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
