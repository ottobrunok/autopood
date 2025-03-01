package com.example.autopood.controllers;

import com.example.autopood.components.MessengerSendComponent;
import com.example.autopood.models.User;
import com.example.autopood.repositorities.ParameterRepository;
import com.example.autopood.repositorities.UserRepository;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.github.messenger4j.receive.events.AccountLinkingEvent;
import com.github.messenger4j.receive.handlers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/callback")
public class CallBackHandler
{

    private static final Logger logger = LoggerFactory.getLogger(CallBackHandler.class);

    public static final String OPTION_PROFILE = "profile";
    public static final String OPTION_CREATE_PARAMETER = "create parameter";
    public static final String OPTION_SEARCH = "search";
    private static final String baseUrl = "https://carwatchcom.herokuapp.com/";
    private final MessengerReceiveClient receiveClient;
    private final MessengerSendComponent sendClient;
    private final UserRepository userRepository;
    private final ParameterRepository kuulutusParametersRepository;


    @Autowired
    public CallBackHandler(@Value("${messenger4j.appSecret}") final String appSecret,
                           @Value("${messenger4j.verifyToken}") final String verifyToken,
                           @Autowired final MessengerSendComponent sendClient, UserRepository userRepository, ParameterRepository kuulutusParametersRepository)
    {

        logger.debug("Initializing MessengerReceiveClient - appSecret: {} | verifyToken: {}", appSecret, verifyToken);
        this.receiveClient = MessengerPlatform.newReceiveClientBuilder(appSecret, verifyToken)
                .onTextMessageEvent(newTextMessageEventHandler())
                .onQuickReplyMessageEvent(newQuickReplyMessageEventHandler())
                .onPostbackEvent(newPostbackEventHandler())
                .onAccountLinkingEvent(newAccountLinkingEventHandler())
                .onOptInEvent(newOptInEventHandler())
                .onEchoMessageEvent(newEchoMessageEventHandler())
                .onMessageDeliveredEvent(newMessageDeliveredEventHandler())
                .onMessageReadEvent(newMessageReadEventHandler())
                .fallbackEventHandler(newFallbackEventHandler())
                .build();
        this.sendClient = sendClient;
        this.userRepository = userRepository;
        this.kuulutusParametersRepository = kuulutusParametersRepository;
    }

    //For messenger verification
    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> verifyWebhook(@RequestParam("hub.mode") final String mode,
                                                @RequestParam("hub.verify_token") final String verifyToken,
                                                @RequestParam("hub.challenge") final String challenge)
    {

        logger.debug("Received Webhook verification request - mode: {} | verifyToken: {} | challenge: {}", mode,
                verifyToken, challenge);
        try
        {
            return ResponseEntity.ok(this.receiveClient.verifyWebhook(mode, verifyToken, challenge));
        } catch (MessengerVerificationException e)
        {
            logger.warn("Webhook verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    //For incoming messages
    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<Void> handleCallback(@RequestBody final String payload,
                                               @RequestHeader("X-Hub-Signature") final String signature)
    {
        logger.debug("Received Messenger Platform callback - payload: {} | signature: {}", payload, signature);
        try
        {
            this.receiveClient.processCallbackPayload(payload, signature);
            logger.debug("Processed callback payload successfully");
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (MessengerVerificationException e)
        {
            logger.warn("Processing of callback payload failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    private TextMessageEventHandler newTextMessageEventHandler()
    {
        return event ->
        {
            logger.debug("Received TextMessageEvent: {}", event);

            final String messageId = event.getMid();
            final String messageText = event.getText();
            final Long senderId = Long.parseLong(event.getSender().getId());
            final Date timestamp = event.getTimestamp();

            logger.info("Received message '{}' with text '{}' from user '{}' at '{}'",
                    messageId, messageText, senderId, timestamp);

            boolean userExists = userRepository.existsById(senderId);
            if (!userExists)
            {
                User user = new User();
                user.setId(senderId);
                userRepository.save(user);
                sendClient.sendTextMessage(senderId, "Teretulemast!");
                System.out.println("Alustuseks loo uus parameeter, et oskaksime autosi teile soovitada");
            }

            sendClient.sendOptions(senderId);

        };
    }

    private QuickReplyMessageEventHandler newQuickReplyMessageEventHandler()
    {
        return event ->
        {
            logger.debug("Received QuickReplyMessageEvent: {}", event);

            final var senderId = Long.parseLong(event.getSender().getId());
            final String messageId = event.getMid();
            final String quickReplyPayload = event.getQuickReply().getPayload();
            logger.info("Received quick reply for message '{}' with payload '{}'", messageId, quickReplyPayload);

            if (userRepository.existsById(senderId))
            {
                User user = userRepository.findById(senderId).get();
                if (quickReplyPayload.equals(OPTION_PROFILE))
                {
                    sendClient.sendTextMessage(senderId, "You can change your profile settings here:\n " + baseUrl + "main?userId=" + senderId);
                } else if (quickReplyPayload.equals(OPTION_CREATE_PARAMETER))
                {
                    sendClient.sendTextMessage(senderId, "Create new parameter here: \n " + baseUrl + "main?userId=" + senderId);
                } else if (quickReplyPayload.equals(OPTION_SEARCH))
                {
                    sendClient.sendTextMessage(senderId, "Search: \n " + baseUrl + "main");
                } else
                {
                    var paraId = Long.parseLong(quickReplyPayload);
                    if (kuulutusParametersRepository.existsById(paraId))
                    {
                        var parameter = kuulutusParametersRepository.findById(paraId).get();
                        sendClient.sendTextMessage(senderId, parameter.toString());
                        sendClient.sendTextMessage(senderId, "Muuda või vaata kuulutusi: \n " + baseUrl + "main?userId=" + senderId + "&paraId=" + paraId);
                    } else
                    {
                        sendClient.sendTextMessage(senderId, "Error");
                    }
                }
            }
            sendClient.sendOptions(senderId);
        };
    }

    private void handleSendException(Exception e)
    {
        logger.error("Message could not be sent. An unexpected error occurred.", e);
    }

    private PostbackEventHandler newPostbackEventHandler()
    {
        return event ->
        {
            logger.debug("Received PostbackEvent: {}", event);

            final var senderId = Long.parseLong(event.getSender().getId());
            final String recipientId = event.getRecipient().getId();
            final String payload = event.getPayload();
            final Date timestamp = event.getTimestamp();

            logger.info("Received postback for user '{}' and page '{}' with payload '{}' at '{}'",
                    senderId, recipientId, payload, timestamp);

            boolean userExists = userRepository.existsById(senderId);
            if (!userExists)
            {
                User user = new User();
                user.setId(senderId);
                userRepository.save(user);
                sendClient.sendTextMessage(senderId, "Tere tulemast!");
                sendClient.sendOptions(senderId);
            }
        };
    }


    //Not needed right now but might be in future

    private AccountLinkingEventHandler newAccountLinkingEventHandler()
    {
        return event ->
        {
            logger.debug("Received AccountLinkingEvent: {}", event);

            final String senderId = event.getSender().getId();
            final AccountLinkingEvent.AccountLinkingStatus accountLinkingStatus = event.getStatus();
            final String authorizationCode = event.getAuthorizationCode();

            logger.info("Received account linking event for user '{}' with status '{}' and auth code '{}'",
                    senderId, accountLinkingStatus, authorizationCode);
        };
    }

    private OptInEventHandler newOptInEventHandler()
    {
        return event ->
        {
            logger.debug("Received OptInEvent: {}", event);

            final var senderId = Long.parseLong(event.getSender().getId());
            final String recipientId = event.getRecipient().getId();
            final String passThroughParam = event.getRef();
            final Date timestamp = event.getTimestamp();

            logger.info("Received authentication for user '{}' and page '{}' with pass through param '{}' at '{}'",
                    senderId, recipientId, passThroughParam, timestamp);

            sendClient.sendTextMessage(senderId, "Authentication successful");
        };
    }

    private EchoMessageEventHandler newEchoMessageEventHandler()
    {
        return event ->
        {
            logger.debug("Received EchoMessageEvent: {}", event);

            final String messageId = event.getMid();
            final String recipientId = event.getRecipient().getId();
            final String senderId = event.getSender().getId();
            final Date timestamp = event.getTimestamp();

            logger.info("Received echo for message '{}' that has been sent to recipient '{}' by sender '{}' at '{}'",
                    messageId, recipientId, senderId, timestamp);
        };
    }

    private MessageDeliveredEventHandler newMessageDeliveredEventHandler()
    {
        return event ->
        {
            logger.debug("Received MessageDeliveredEvent: {}", event);

            final List<String> messageIds = event.getMids();
            final Date watermark = event.getWatermark();
            final String senderId = event.getSender().getId();

            if (messageIds != null)
            {
                messageIds.forEach(messageId ->
                {
                    logger.info("Received delivery confirmation for message '{}'", messageId);
                });
            }

            logger.info("All messages before '{}' were delivered to user '{}'", watermark, senderId);
        };
    }

    private MessageReadEventHandler newMessageReadEventHandler()
    {
        return event ->
        {
            logger.debug("Received MessageReadEvent: {}", event);

            final Date watermark = event.getWatermark();
            final String senderId = event.getSender().getId();

            logger.info("All messages before '{}' were read by user '{}'", watermark, senderId);
        };
    }

    private FallbackEventHandler newFallbackEventHandler()
    {
        return event ->
        {
            logger.debug("Received FallbackEvent: {}", event);

            final String senderId = event.getSender().getId();
            logger.info("Received unsupported message from user '{}'", senderId);
        };
    }


}