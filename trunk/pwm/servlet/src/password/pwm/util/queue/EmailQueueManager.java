/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2012 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.util.queue;

import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.error.PwmException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.health.HealthMessage;
import password.pwm.health.HealthRecord;
import password.pwm.health.HealthStatus;
import password.pwm.health.HealthTopic;
import password.pwm.util.Helper;
import password.pwm.util.MacroMachine;
import password.pwm.util.PwmLogger;
import password.pwm.util.TimeDuration;
import password.pwm.util.localdb.LocalDB;
import password.pwm.ldap.UserDataReader;
import password.pwm.util.stats.Statistic;
import password.pwm.util.stats.StatisticsManager;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * @author Jason D. Rivard
 */
public class EmailQueueManager extends AbstractQueueManager {
// ------------------------------ FIELDS ------------------------------


    private static final PwmLogger LOGGER = PwmLogger.getLogger(EmailQueueManager.class);

    private Properties javaMailProps = new Properties();

// --------------------------- CONSTRUCTORS ---------------------------

    public EmailQueueManager() {
    }

// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface PwmService ---------------------

    public void init(final PwmApplication pwmApplication)
            throws PwmException
    {
        javaMailProps = makeJavaMailProps(pwmApplication.getConfig());
        final Settings settings = new Settings(
                new TimeDuration(Long.parseLong(pwmApplication.getConfig().readAppProperty(AppProperty.QUEUE_EMAIL_MAX_AGE_MS))),
                new TimeDuration(Long.parseLong(pwmApplication.getConfig().readAppProperty(AppProperty.QUEUE_EMAIL_RETRY_TIMEOUT_MS))),
                Integer.parseInt(pwmApplication.getConfig().readAppProperty(AppProperty.QUEUE_EMAIL_MAX_COUNT)),
                EmailQueueManager.class.getSimpleName()
        );
        super.init(pwmApplication, LocalDB.DB.EMAIL_QUEUE, settings);
    }

// -------------------------- OTHER METHODS --------------------------

    protected boolean determineIfItemCanBeDelivered(final EmailItemBean emailItem) {
        final String serverAddress = pwmApplication.getConfig().readSettingAsString(PwmSetting.EMAIL_SERVER_ADDRESS);

        if (serverAddress == null || serverAddress.length() < 1) {
            LOGGER.debug("discarding email send event (no SMTP server address configured) " + emailItem.toString());
            return false;
        }

        if (emailItem.getFrom() == null || emailItem.getFrom().length() < 1) {
            LOGGER.error("discarding email event (no from address): " + emailItem.toString());
            return false;
        }

        if (emailItem.getTo() == null || emailItem.getTo().length() < 1) {
            LOGGER.error("discarding email event (no to address): " + emailItem.toString());
            return false;
        }

        if (emailItem.getSubject() == null || emailItem.getSubject().length() < 1) {
            LOGGER.error("discarding email event (no subject): " + emailItem.toString());
            return false;
        }

        if ((emailItem.getBodyPlain() == null || emailItem.getBodyPlain().length() < 1) && (emailItem.getBodyHtml() == null || emailItem.getBodyHtml().length() < 1)) {
            LOGGER.error("discarding email event (no body): " + emailItem.toString());
            return false;
        }

        return true;
    }

    public void submit(
            final EmailItemBean emailItem,
            final UserInfoBean uiBean,
            final UserDataReader userDataReader
    )
    {
        if (emailItem == null) {
            return;
        }

        String toAddress = emailItem.getTo();
        if (toAddress == null || toAddress.length() < 1 && uiBean != null) {
            toAddress = uiBean.getUserEmailAddress();
        }

        final EmailItemBean expandedEmailItem = new EmailItemBean(
                MacroMachine.expandMacros(toAddress, pwmApplication, uiBean, userDataReader),
                MacroMachine.expandMacros(emailItem.getFrom(), pwmApplication, uiBean, userDataReader),
                MacroMachine.expandMacros(emailItem.getSubject(), pwmApplication, uiBean, userDataReader),
                MacroMachine.expandMacros(emailItem.getBodyPlain(), pwmApplication, uiBean, userDataReader),
                MacroMachine.expandMacros(emailItem.getBodyHtml(), pwmApplication, uiBean, userDataReader)
        );

        if (expandedEmailItem.getTo() == null || expandedEmailItem.getTo().length() < 1) {
            LOGGER.error("no destination address available for email, skipping; email: " + emailItem.toString());
        }

        if (!determineIfItemCanBeDelivered(emailItem)) {
            return;
        }

        try {
            add(new QueueEvent(Helper.getGson().toJson(expandedEmailItem),new Date()));
        } catch (PwmUnrecoverableException e) {
            LOGGER.warn("unable to add email to queue: " + e.getMessage());
        }
    }

    boolean sendItem(final String item) {
        final EmailItemBean emailItemBean = Helper.getGson().fromJson(item, EmailItemBean.class);
        final StatisticsManager statsMgr = pwmApplication.getStatisticsManager();

        // create a new MimeMessage object (using the Session created above)
        try {
            final Message message = convertEmailItemToMessage(emailItemBean, this.pwmApplication.getConfig());
            final String mailuser = this.pwmApplication.getConfig().readSettingAsString(PwmSetting.EMAIL_USERNAME);
            final String mailpassword = this.pwmApplication.getConfig().readSettingAsString(PwmSetting.EMAIL_PASSWORD);

            // Login to SMTP server first if both username and password is given
            final String logText;
            if (mailuser == null || mailuser.length() < 1 || mailpassword == null || mailpassword.length() < 1) {

                logText = "plaintext";
                Transport.send(message);
            } else {
                // createSharedHistoryManager a new Session object for the message
                final javax.mail.Session session = javax.mail.Session.getInstance(javaMailProps, null);

                final String mailhost = this.pwmApplication.getConfig().readSettingAsString(PwmSetting.EMAIL_SERVER_ADDRESS);
                final int mailport = (int)this.pwmApplication.getConfig().readSettingAsLong(PwmSetting.EMAIL_SERVER_PORT);

                final Transport tr = session.getTransport("smtp");
                tr.connect(mailhost, mailport, mailuser, mailpassword);
                message.saveChanges();
                tr.sendMessage(message, message.getAllRecipients());
                tr.close();
                logText = "authenticated ";
            }

            LOGGER.debug("successfully sent " + logText + "email: " + emailItemBean.toString());
            if (statsMgr != null) {
                statsMgr.incrementValue(Statistic.EMAIL_SEND_SUCCESSES);
            }

            return true;
        } catch (MessagingException e) {
            if (statsMgr != null) {
                statsMgr.incrementValue(Statistic.EMAIL_SEND_FAILURES);
            }

            lastSendFailure = new HealthRecord(HealthStatus.WARN, HealthTopic.Email, HealthMessage.Email_SendFailure, e.getMessage());
            LOGGER.error("error during email send attempt: " + e);

            if (sendIsRetryable(e)) {
                LOGGER.error("error sending email (" + e.getMessage() + ") " + emailItemBean.toString() + ", will retry");
                return false;
            } else {
                LOGGER.error("error sending email (" + e.getMessage() + ") " + emailItemBean.toString() + ", permanent failure, discarding message");
                return true;
            }
        }
    }

    private Message convertEmailItemToMessage(final EmailItemBean emailItemBean, final Configuration config)
            throws MessagingException {
        final boolean hasPlainText = emailItemBean.getBodyPlain() != null && emailItemBean.getBodyPlain().length() > 0;
        final boolean hasHtml = emailItemBean.getBodyHtml() != null && emailItemBean.getBodyHtml().length() > 0;


        // createSharedHistoryManager a new Session object for the message
        final javax.mail.Session session = javax.mail.Session.getInstance(javaMailProps, null);

        final Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(emailItemBean.getFrom()));
        message.setRecipients(Message.RecipientType.TO, new InternetAddress[]{new InternetAddress(emailItemBean.getTo())});
        message.setSubject(emailItemBean.getSubject());
        message.setSentDate(new Date());

        if (hasPlainText && hasHtml) {
            final MimeMultipart content = new MimeMultipart("alternative");
            final MimeBodyPart text = new MimeBodyPart();
            final MimeBodyPart html = new MimeBodyPart();
            text.setContent(emailItemBean.getBodyPlain(), "text/plain; charset=\"utf-8\"");
            html.setContent(emailItemBean.getBodyHtml(), "text/html; charset=\"utf-8\"");
            content.addBodyPart(text);
            content.addBodyPart(html);
            message.setContent(content);
        } else if (hasPlainText) {
            message.setContent(emailItemBean.getBodyPlain(), "text/plain; charset=\"utf-8\"");
        } else if (hasHtml) {
            message.setContent(emailItemBean.getBodyHtml(), "text/html; charset=\"utf-8\"");
        }

        return message;
    }

    protected static Properties makeJavaMailProps(final Configuration config) {
        //Create a properties item to start setting up the mail
        final Properties props = new Properties();

        //Specify the desired SMTP server
        props.put("mail.smtp.host", config.readSettingAsString(PwmSetting.EMAIL_SERVER_ADDRESS));

        //Specify SMTP server port
        props.put("mail.smtp.port",(int)config.readSettingAsLong(PwmSetting.EMAIL_SERVER_PORT));

        //Specify configured advanced settings.
        final Map<String, String> advancedSettingValues = Configuration.convertStringListToNameValuePair(config.readSettingAsStringArray(PwmSetting.EMAIL_ADVANCED_SETTINGS), "=");
        for (final String key : advancedSettingValues.keySet()) {
            props.put(key, advancedSettingValues.get(key));
        }

        return props;
    }

}
