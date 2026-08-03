Hello ${user.firstName!user.username!'there'},

This is a security alert to inform you that a security credential (Passkey or Security Key) was updated for your account on ${realmName} on ${event.date?datetime}.

Event Details:
- Date/Time: ${event.date?datetime}
- IP Address: ${event.ipAddress}

If you did not authorize this change, please contact your system administrator immediately as your account security may be compromised.

View account security: ${(url.accountUrl)!'https://smsone.co.ug'}

---
${realmName!'SMSOne'}
${realmName!'SMSOne'}
