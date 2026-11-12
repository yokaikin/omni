package com.omni.notification.email;

public interface EmailSender {

    EmailSendResult send(EmailSendRequest request);
}
