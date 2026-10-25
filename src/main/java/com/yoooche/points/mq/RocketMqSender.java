package com.yoooche.points.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RocketMqSender {

    private final RocketMQTemplate rocketMQTemplate;

    public void sendBatch(String topic, String tag, List<MqMessage> messages) {
        List<Message<String>> batch = messages.stream()
                .map(message -> MessageBuilder.withPayload(message.payload())
                        .setHeader(RocketMQHeaders.KEYS, message.keys())
                        .build())
                .toList();
        SendResult result;
        try {
            result = rocketMQTemplate.syncSend(topic + ":" + tag, batch);
        } catch (MessagingException ex) {
            Throwable illegal = findMessageIllegal(ex);
            if (illegal != null) {
                throw new InvalidMessageException(illegal.getMessage(), ex);
            }
            throw ex;
        }
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Broker returned " + result.getSendStatus());
        }
    }

    private static Throwable findMessageIllegal(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            int code = switch (cause) {
                case MQClientException client -> client.getResponseCode();
                case MQBrokerException broker -> broker.getResponseCode();
                default -> -1;
            };
            if (code == ResponseCode.MESSAGE_ILLEGAL) {
                return cause;
            }
        }
        return null;
    }
}
