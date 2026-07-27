package ug.co.smsone.notification.internal;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/** Email delivery over SMTP (Mailpit in dev, any SMTP server elsewhere — externalized config). */
@Component
class EmailChannelSender implements NotificationChannelSender {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    EmailChannelSender(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(NotificationMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(message.address());
        mail.setSubject(message.subject());
        mail.setText(message.body() == null ? "" : message.body());
        mailSender.send(mail); // MailException (RuntimeException) is caught + recorded by the dispatcher
    }
}
