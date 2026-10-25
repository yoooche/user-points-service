package com.yoooche.points.mq;

import org.apache.rocketmq.client.common.ClientErrorCode;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RocketMqSenderTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @InjectMocks
    private RocketMqSender sender;

    @Test
    @SuppressWarnings("unchecked")
    void sendsTheWholeListToTopicAndTagInOneCall() {
        when(rocketMQTemplate.syncSend(anyString(), anyCollection())).thenReturn(result(SendStatus.SEND_OK));

        sender.sendBatch("user-points-topic", "ADDED",
                List.of(new MqMessage("user_1 req-1", "{\"a\":1}"), new MqMessage("user_2", "{\"a\":2}")));

        ArgumentCaptor<Collection<Message<?>>> batch = ArgumentCaptor.captor();
        verify(rocketMQTemplate).syncSend(eq("user-points-topic:ADDED"), batch.capture());
        assertThat(batch.getValue()).extracting("payload").containsExactly("{\"a\":1}", "{\"a\":2}");
        assertThat(batch.getValue()).extracting(message -> message.getHeaders().get(RocketMQHeaders.KEYS))
                .containsExactly("user_1 req-1", "user_2");
    }

    @Test
    void sendStatusOtherThanOkIsAFailure() {
        when(rocketMQTemplate.syncSend(anyString(), anyCollection())).thenReturn(result(SendStatus.FLUSH_DISK_TIMEOUT));

        assertThatThrownBy(() -> sender.sendBatch("user-points-topic", "ADDED", List.of(new MqMessage("user_1", "{}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void messageIllegalDownTheCauseChainIsAnInvalidMessage() {
        var illegal = new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "the message body size over max value");
        when(rocketMQTemplate.syncSend(anyString(), anyCollection()))
                .thenThrow(new MessagingException("send failed", new MQClientException("Failed to initiate the MessageBatch", illegal)));

        assertThatThrownBy(() -> sender.sendBatch("user-points-topic", "ADDED", List.of(new MqMessage("user_1", "{}"))))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("the message body size over max value");
    }

    @Test
    void brokerRefusingTheMessageIsAnInvalidMessage() {
        when(rocketMQTemplate.syncSend(anyString(), anyCollection()))
                .thenThrow(new MessagingException("send failed", new MQBrokerException(ResponseCode.MESSAGE_ILLEGAL, "too large")));

        assertThatThrownBy(() -> sender.sendBatch("user-points-topic", "ADDED", List.of(new MqMessage("user_1", "{}"))))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void otherFailuresAreRethrownForRetry() {
        var noRoute = new MQClientException(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION, "No route info of this topic");
        when(rocketMQTemplate.syncSend(anyString(), anyCollection()))
                .thenThrow(new MessagingException("send failed", noRoute));

        assertThatThrownBy(() -> sender.sendBatch("user-points-topic", "ADDED", List.of(new MqMessage("user_1", "{}"))))
                .isInstanceOf(MessagingException.class)
                .isNotInstanceOf(InvalidMessageException.class);
    }

    private static SendResult result(SendStatus status) {
        var result = new SendResult();
        result.setSendStatus(status);
        return result;
    }
}
